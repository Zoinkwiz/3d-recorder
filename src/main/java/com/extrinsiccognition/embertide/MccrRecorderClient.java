package com.extrinsiccognition.embertide;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** Authenticated, acknowledged delivery to the app's recorder. */
@Slf4j
public final class MccrRecorderClient implements Recorder
{
	public enum Lifetime
	{
		UNAVAILABLE, CONNECTING, CONNECTED, CLOSED
	}

	static final int MAX_BATCH_BYTES = 256 * 1024;
	static final int MAX_PENDING_BYTES = 2 * 1024 * 1024;
	static final int MAX_BATCH_RECORDS = 256;
	static final long FLUSH_MS = 250;
	static final long RETRY_BUDGET_MS = 30_000;
	static final long LIFETIME_TIMEOUT_MS = 5_000;
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final Pattern CAPTURE_ID = Pattern.compile("^[a-zA-Z0-9_-]{1,160}$");

	private static final class Entry
	{
		final String json;
		final int size;

		Entry(String json)
		{
			this.json = json;
			this.size = json.getBytes(StandardCharsets.UTF_8).length + 1;
		}
	}

	private static final class RecorderException extends IOException
	{
		final boolean permanent;

		RecorderException(String message, boolean permanent)
		{
			super(message);
			this.permanent = permanent;
		}
	}

	private final OkHttpClient http;
	private final Gson gson;
	private final ScheduledExecutorService executor;
	private final RecorderEndpoint endpoint;
	private final Object lock = new Object();
	private final ArrayDeque<Entry> queue = new ArrayDeque<>();
	private final int reserve;
	private long pendingBytes;
	private int sequence;
	private ScheduledFuture<?> timer;
	private CompletableFuture<Void> inFlight;
	private volatile CompletableFuture<Void> opening;
	private WebSocket lifetimeSocket;
	private boolean lifetimeClosed;
	private volatile State state = State.IDLE;
	private volatile String error = "";
	private volatile String captureId;
	private volatile String capability;
	private volatile Lifetime lifetime = Lifetime.UNAVAILABLE;
	private volatile boolean studioRequested;

	public MccrRecorderClient(OkHttpClient http, Gson gson, ScheduledExecutorService executor, RecorderEndpoint endpoint)
	{
		this.http = http.newBuilder()
			.connectTimeout(2, TimeUnit.SECONDS)
			.readTimeout(5, TimeUnit.SECONDS)
			.writeTimeout(5, TimeUnit.SECONDS)
			.retryOnConnectionFailure(false)
			.build();
		this.gson = gson;
		this.executor = executor;
		this.endpoint = endpoint;
		this.reserve = Math.min(4096, MAX_PENDING_BYTES / 4);
	}

	@Override
	public String destination()
	{
		return "";
	}

	public State state()
	{
		return state;
	}

	public String error()
	{
		return error;
	}

	public Lifetime lifetime()
	{
		return lifetime;
	}

	public String captureId()
	{
		return captureId;
	}

	public boolean studioRequested()
	{
		return studioRequested;
	}

	public CompletableFuture<Void> open(JsonObject header)
	{
		synchronized (lock)
		{
			if (state != State.IDLE)
			{
				return CompletableFuture.completedFuture(null);
			}
			state = State.OPENING;
		}
		CompletableFuture<Void> opened = new CompletableFuture<>();
		opening = opened;
		executor.execute(() ->
		{
			try
			{
				JsonObject body = new JsonObject();
				body.add("header", header);
				JsonObject result = requestJson("/v1/mccr/open", gson.toJson(body), endpoint.getToken(), true);
				String id = result.has("id") && result.get("id").isJsonPrimitive() ? result.get("id").getAsString() : "";
				String cap = result.has("capability") && result.get("capability").isJsonPrimitive() ? result.get("capability").getAsString() : "";
				int next = result.has("nextSequence") && result.get("nextSequence").isJsonPrimitive() ? result.get("nextSequence").getAsInt() : -1;
				if (!CAPTURE_ID.matcher(id).matches() || cap.isEmpty() || next != 0)
				{
					throw new RecorderException("Invalid local recording acknowledgement.", true);
				}
				captureId = id;
				capability = cap;
				synchronized (lock)
				{
					state = State.READY;
					if (!queue.isEmpty())
					{
						scheduleFlush();
					}
				}
				connectLifetime();
				opened.complete(null);
			}
			catch (Exception e)
			{
				fail(e.getMessage() == null ? "Local writer is unavailable; recording stopped." : e.getMessage());
				opened.completeExceptionally(e);
			}
		});
		return opened;
	}

	/** False means refused, which ends the capture. */
	public boolean enqueue(JsonObject record, boolean control)
	{
		Entry entry = new Entry(gson.toJson(record));
		synchronized (lock)
		{
			if (state != State.OPENING && state != State.READY)
			{
				return false;
			}
			long budget = MAX_PENDING_BYTES - 80 - (control ? 0 : reserve);
			if (entry.size + 80 > MAX_BATCH_BYTES || pendingBytes + entry.size > budget)
			{
				return false;
			}
			queue.add(entry);
			pendingBytes += entry.size;
			if (state == State.READY)
			{
				scheduleFlush();
			}
			return true;
		}
	}

	private void scheduleFlush()
	{
		if (timer == null && inFlight == null)
		{
			timer = executor.schedule(() -> flush().exceptionally(ignored -> null), FLUSH_MS, TimeUnit.MILLISECONDS);
		}
	}

	public CompletableFuture<Void> flush()
	{
		synchronized (lock)
		{
			if (timer != null)
			{
				timer.cancel(false);
				timer = null;
			}
			if (inFlight != null)
			{
				return inFlight;
			}
			if (state == State.FAILED)
			{
				CompletableFuture<Void> failed = new CompletableFuture<>();
				failed.completeExceptionally(new IOException(error));
				return failed;
			}
			CompletableFuture<Void> flight = CompletableFuture.runAsync(this::drain, executor);
			inFlight = flight;
			flight.whenComplete((ignored, throwable) ->
			{
				synchronized (lock)
				{
					inFlight = null;
					if (!queue.isEmpty() && state == State.READY && timer == null)
					{
						scheduleFlush();
					}
				}
			});
			return flight;
		}
	}

	private void drain()
	{
		try
		{
			while (true)
			{
				List<Entry> entries = new ArrayList<>();
				int batchSequence;
				synchronized (lock)
				{
					if (queue.isEmpty())
					{
						return;
					}
					int size = 80;
					while (!queue.isEmpty() && entries.size() < MAX_BATCH_RECORDS && size + queue.peek().size <= MAX_BATCH_BYTES)
					{
						Entry entry = queue.poll();
						entries.add(entry);
						size += entry.size;
					}
					if (entries.isEmpty())
					{
						entries.add(queue.poll());
					}
					batchSequence = sequence;
				}
				StringBuilder body = new StringBuilder("{\"sequence\":").append(batchSequence).append(",\"records\":[");
				long bytes = 0;
				for (int i = 0; i < entries.size(); i++)
				{
					if (i > 0)
					{
						body.append(',');
					}
					body.append(entries.get(i).json);
					bytes += entries.get(i).size;
				}
				body.append("]}");
				JsonObject result = requestJson("/v1/mccr/" + captureId + "/append", body.toString(), capability, true);
				if (!result.has("ack") || !result.get("ack").isJsonPrimitive() || result.get("ack").getAsInt() != batchSequence)
				{
					throw new RecorderException("Local writer did not acknowledge the recording sequence.", true);
				}
				if (result.has("studioRequested") && result.get("studioRequested").isJsonPrimitive()
					&& result.get("studioRequested").getAsBoolean())
				{
					studioRequested = true;
				}
				synchronized (lock)
				{
					pendingBytes -= bytes;
					sequence++;
				}
			}
		}
		catch (Exception e)
		{
			fail(e.getMessage() == null ? "Local writer is unavailable; recording stopped." : e.getMessage());
			throw new IllegalStateException(e);
		}
	}

	public CompletableFuture<Void> finish()
	{
		CompletableFuture<Void> pendingOpen;
		synchronized (lock)
		{
			if (state == State.COMPLETE || state == State.FAILED)
			{
				return state == State.COMPLETE ? CompletableFuture.completedFuture(null) : failedFuture();
			}
			pendingOpen = state == State.OPENING ? opening : null;
			if (pendingOpen == null)
			{
				state = State.FINISHING;
			}
		}
		if (pendingOpen != null)
		{
			// Wait for the handshake, then drain.
			return pendingOpen.handle((ignored, throwable) -> throwable == null ? finish() : failedFuture())
				.thenCompose(future -> future);
		}
		return drainUntilEmpty().thenRun(() ->
		{
			synchronized (lock)
			{
				if (state == State.FINISHING)
				{
					state = State.COMPLETE;
				}
			}
		});
	}

	private CompletableFuture<Void> drainUntilEmpty()
	{
		return flush().thenCompose(ignored ->
		{
			boolean empty;
			synchronized (lock)
			{
				empty = queue.isEmpty();
			}
			return empty ? CompletableFuture.completedFuture(null) : drainUntilEmpty();
		});
	}

	private CompletableFuture<Void> failedFuture()
	{
		CompletableFuture<Void> failed = new CompletableFuture<>();
		failed.completeExceptionally(new IOException(error.isEmpty() ? "Recording failed." : error));
		return failed;
	}

	/** Ask the app to open this capture in Studio. */
	public CompletableFuture<Void> openStudio()
	{
		if (captureId == null || capability == null)
		{
			return failed("This memory is not connected to Studio yet.");
		}
		return CompletableFuture.runAsync(() ->
		{
			try
			{
				JsonObject result = requestJson("/v1/mccr/" + captureId + "/studio", "{}", capability, true);
				if (!result.has("ok") || !result.get("ok").isJsonPrimitive() || !result.get("ok").getAsBoolean())
				{
					throw new RecorderException("Studio did not accept this memory. Please try again.", true);
				}
			}
			catch (IOException e)
			{
				throw new IllegalStateException(e.getMessage(), e);
			}
		}, executor);
	}

	private static CompletableFuture<Void> failed(String message)
	{
		CompletableFuture<Void> future = new CompletableFuture<>();
		future.completeExceptionally(new IOException(message));
		return future;
	}

	private void fail(String message)
	{
		synchronized (lock)
		{
			if (timer != null)
			{
				timer.cancel(false);
				timer = null;
			}
			state = State.FAILED;
			error = message;
		}
		log.debug("recorder failed: {}", message);
	}

	private JsonObject requestJson(String path, String body, String token, boolean retry) throws IOException
	{
		long started = System.nanoTime();
		for (int attempt = 0; ; attempt++)
		{
			RecorderException failure;
			try
			{
				Request request = new Request.Builder()
					.url(endpoint.getUrl() + path)
					.header("Origin", RecorderEndpoint.ORIGIN)
					.header("Authorization", "Bearer " + token)
					.header("Cache-Control", "no-store")
					.post(RequestBody.create(JSON, body))
					.build();
				try (Response response = http.newCall(request).execute())
				{
					int code = response.code();
					if (!response.isSuccessful())
					{
						boolean permanent = code == 507 || (code != 408 && code != 429 && code < 500);
						throw new RecorderException("Local writer rejected recording (HTTP " + code + ").", permanent);
					}
					String text = response.body() == null ? "" : response.body().string();
					try
					{
						JsonElement parsed = new JsonParser().parse(text);
						if (!parsed.isJsonObject())
						{
							throw new IllegalStateException();
						}
						return parsed.getAsJsonObject();
					}
					catch (RuntimeException e)
					{
						throw new RecorderException("Local writer returned an invalid acknowledgement.", true);
					}
				}
			}
			catch (RecorderException e)
			{
				failure = e;
			}
			catch (IOException e)
			{
				failure = new RecorderException("Local writer is unavailable; recording stopped.", false);
			}
			long delay = Math.min(500L << Math.min(attempt, 3), 4000L);
			long elapsed = (System.nanoTime() - started) / 1_000_000L;
			if (!retry || failure.permanent || elapsed + delay >= RETRY_BUDGET_MS)
			{
				throw failure;
			}
			try
			{
				Thread.sleep(delay);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				throw failure;
			}
		}
	}

	private void connectLifetime()
	{
		lifetime = Lifetime.CONNECTING;
		String url = endpoint.getUrl().replaceFirst("^http:", "ws:") + "/v1/mccr/lifetime";
		Request request = new Request.Builder().url(url).header("Origin", RecorderEndpoint.ORIGIN).build();
		ScheduledFuture<?> timeout = executor.schedule(() ->
		{
			if (lifetime == Lifetime.CONNECTING)
			{
				unavailable();
			}
		}, LIFETIME_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		WebSocket socket = http.newWebSocket(request, new WebSocketListener()
		{
			@Override
			public void onOpen(WebSocket webSocket, Response response)
			{
				JsonObject auth = new JsonObject();
				auth.addProperty("id", captureId);
				auth.addProperty("capability", capability);
				if (!webSocket.send(gson.toJson(auth)))
				{
					unavailable();
				}
			}

			@Override
			public void onMessage(WebSocket webSocket, String text)
			{
				try
				{
					JsonElement parsed = new JsonParser().parse(text);
					if (parsed.isJsonObject() && parsed.getAsJsonObject().has("ok")
						&& parsed.getAsJsonObject().get("ok").getAsBoolean())
					{
						timeout.cancel(false);
						lifetime = Lifetime.CONNECTED;
						return;
					}
				}
				catch (RuntimeException ignored)
				{
					// fall through to unavailable
				}
				unavailable();
			}

			@Override
			public void onFailure(WebSocket webSocket, Throwable t, Response response)
			{
				timeout.cancel(false);
				unavailable();
			}

			@Override
			public void onClosed(WebSocket webSocket, int code, String reason)
			{
				timeout.cancel(false);
				synchronized (lock)
				{
					lifetime = lifetimeClosed ? Lifetime.CLOSED : Lifetime.UNAVAILABLE;
				}
			}
		});
		synchronized (lock)
		{
			lifetimeSocket = socket;
		}
	}

	private void unavailable()
	{
		WebSocket socket;
		synchronized (lock)
		{
			if (lifetimeClosed)
			{
				return;
			}
			lifetime = Lifetime.UNAVAILABLE;
			socket = lifetimeSocket;
		}
		if (socket != null)
		{
			socket.close(1000, "unavailable");
		}
	}

	/** Deliberate close, so it isn't read as a vanished client. */
	public void closeLifetime()
	{
		WebSocket socket;
		synchronized (lock)
		{
			lifetimeClosed = true;
			lifetime = Lifetime.CLOSED;
			socket = lifetimeSocket;
		}
		if (socket != null)
		{
			socket.close(1000, "done");
		}
	}
}
