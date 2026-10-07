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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** Authenticated, acknowledged delivery to the app's recorder, kept up while the app goes away and comes back. */
@Slf4j
public final class MccrRecorderClient implements Recorder
{
	public enum Lifetime
	{
		UNAVAILABLE, CONNECTING, CONNECTED, CLOSED
	}

	static final int MAX_BATCH_BYTES = 256 * 1024;
	// Held while the app is away: about three minutes of rows at the measured 40 KiB/s, over a minute at the busiest.
	static final int MAX_PENDING_BYTES = 8 * 1024 * 1024;
	static final int MAX_BATCH_RECORDS = 256;
	static final long FLUSH_MS = 250;
	// A sealed part waits for the app as long as a live part's rows last at 40 KiB/s.
	static final long FINISH_BUDGET_MS = 205_000;
	static final long LIFETIME_TIMEOUT_MS = 5_000;
	static final long MAX_BACKOFF_MS = 4_000;
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final Pattern CAPTURE_ID = Pattern.compile("^[a-zA-Z0-9_-]{1,160}$");

	private enum Failure
	{
		PERMANENT, RETRY, REOPEN
	}

	private static final class Entry
	{
		/** The row's UTF-8 text and a newline. */
		final byte[] line;
		final int size;

		Entry(byte[] line)
		{
			this.line = line;
			this.size = line.length;
		}
	}

	/** Sent again unchanged until acknowledged, so the app can tell a retry from new rows. */
	private static final class Batch
	{
		final int sequence;
		final byte[] body;
		final long bytes;

		Batch(int sequence, byte[] body, long bytes)
		{
			this.sequence = sequence;
			this.body = body;
			this.bytes = bytes;
		}
	}

	private final OkHttpClient http;
	private final Gson gson;
	private final RowWriter text = new RowWriter();
	private final ScheduledExecutorService executor;
	private final Object lock = new Object();
	private final ArrayDeque<Entry> queue = new ArrayDeque<>();
	private final List<CompletableFuture<Void>> drained = new ArrayList<>();
	private final CompletableFuture<Void> opened = new CompletableFuture<>();
	private final CompletableFuture<Void> finished = new CompletableFuture<>();
	private final int reserve;
	private JsonObject header;
	private byte[] openBody;
	private long pendingBytes;
	private int sequence;
	private Batch batch;
	private boolean busy;
	private boolean reopen = true;
	private boolean rediscover;
	private int failures;
	private long finishBy;
	private ScheduledFuture<?> timer;
	private WebSocket lifetimeSocket;
	private String lifetimeKey;
	private int generation;
	private int lifetimeFailures;
	private ScheduledFuture<?> redial;
	private boolean lifetimeClosed;
	private volatile RecorderEndpoint endpoint;
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
		// Without one, endpoint.json is read when the app appears.
		this.rediscover = endpoint == null;
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

	Gson gson()
	{
		return gson;
	}

	public String captureId()
	{
		return captureId;
	}

	public boolean studioRequested()
	{
		return studioRequested;
	}

	/** It was live, and the app or the lifetime socket went away; it is being tried again. */
	public boolean reconnecting()
	{
		synchronized (lock)
		{
			boolean live = state == State.READY || state == State.FINISHING;
			return live && (failures > 0 || lifetimeFailures > 0);
		}
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
			this.header = header;
		}
		run(this::pump);
		return opened;
	}

	/** False means refused, which ends the capture. */
	public boolean enqueue(JsonObject record, boolean control)
	{
		return enqueue(text.line(gson, record), control);
	}

	/** A row already written by this client's Gson, with its newline. */
	boolean enqueue(byte[] line, boolean control)
	{
		Entry entry = new Entry(line);
		List<CompletableFuture<Void>> stopped;
		synchronized (lock)
		{
			if (state != State.OPENING && state != State.READY)
			{
				return false;
			}
			long budget = MAX_PENDING_BYTES - 80 - (control ? 0 : reserve);
			if (entry.size + 80 > MAX_BATCH_BYTES)
			{
				stopped = failNow("A row was too large for the live view.");
			}
			else if (pendingBytes + entry.size > budget)
			{
				stopped = failNow(state == State.OPENING ? "Embertide is not open." : failures > 0 ? "Embertide was away too long." : "Embertide fell behind.");
			}
			else
			{
				queue.add(entry);
				pendingBytes += entry.size;
				if (!busy && timer == null)
				{
					timer = schedule(this::pump, FLUSH_MS);
				}
				return true;
			}
		}
		settle(stopped);
		return false;
	}

	/** The rows waiting to be sent, as they will be sent. */
	List<String> queued()
	{
		synchronized (lock)
		{
			List<String> rows = new ArrayList<>();
			for (Entry entry : queue)
			{
				rows.add(new String(entry.line, 0, entry.line.length - 1, StandardCharsets.UTF_8));
			}
			return rows;
		}
	}

	/** {"sequence":n,"records":[row,row]} from rows that each end in a newline. */
	static byte[] body(int sequence, List<byte[]> lines)
	{
		byte[] head = ("{\"sequence\":" + sequence + ",\"records\":[").getBytes(StandardCharsets.UTF_8);
		int length = head.length + 2;
		for (byte[] line : lines)
		{
			length += line.length;
		}
		byte[] body = new byte[Math.max(head.length + 2, length - 1)];
		System.arraycopy(head, 0, body, 0, head.length);
		int at = head.length;
		for (int i = 0; i < lines.size(); i++)
		{
			if (i > 0)
			{
				body[at++] = ',';
			}
			byte[] line = lines.get(i);
			System.arraycopy(line, 0, body, at, line.length - 1);
			at += line.length - 1;
		}
		body[at++] = ']';
		body[at] = '}';
		return body;
	}

	public CompletableFuture<Void> flush()
	{
		CompletableFuture<Void> done = new CompletableFuture<>();
		synchronized (lock)
		{
			if (state == State.FAILED)
			{
				done.completeExceptionally(new IOException(error));
				return done;
			}
			drained.add(done);
			if (failures > 0)
			{
				// The pending retry delivers it.
				return done;
			}
		}
		run(this::pump);
		return done;
	}

	public CompletableFuture<Void> finish()
	{
		synchronized (lock)
		{
			if (state == State.FAILED)
			{
				return failedFuture();
			}
			if (state == State.IDLE)
			{
				state = State.COMPLETE;
				finished.complete(null);
			}
			if (state == State.OPENING || state == State.READY)
			{
				state = State.FINISHING;
				finishBy = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FINISH_BUDGET_MS);
			}
		}
		run(this::pump);
		return finished;
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
		CompletableFuture<Void> done = new CompletableFuture<>();
		http.newCall(post("/v1/mccr/" + captureId + "/studio", "{}".getBytes(StandardCharsets.UTF_8), capability)).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				done.completeExceptionally(new IOException("Studio did not accept this memory. Please try again."));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				JsonObject result = null;
				try (Response r = response)
				{
					result = r.isSuccessful() && r.body() != null ? parse(r.body().string()) : null;
				}
				catch (IOException ignored)
				{
					// reported below
				}
				if (result != null && result.has("ok") && result.get("ok").isJsonPrimitive() && result.get("ok").getAsBoolean())
				{
					done.complete(null);
				}
				else
				{
					done.completeExceptionally(new IOException("Studio did not accept this memory. Please try again."));
				}
			}
		});
		return done;
	}

	private static CompletableFuture<Void> failed(String message)
	{
		CompletableFuture<Void> future = new CompletableFuture<>();
		future.completeExceptionally(new IOException(message));
		return future;
	}

	/** Sends the next open or batch, one call at a time. Runs on the executor and never waits on the network. */
	private void pump()
	{
		boolean find;
		boolean opening;
		synchronized (lock)
		{
			if (timer != null)
			{
				timer.cancel(false);
				timer = null;
			}
			if (busy || state == State.IDLE || state == State.COMPLETE || state == State.FAILED)
			{
				return;
			}
			busy = true;
			opening = reopen;
			find = reopen && rediscover;
		}
		if (find)
		{
			// A restarted app listens on a new port.
			RecorderEndpoint found = RecorderEndpoint.locate();
			if (found != null)
			{
				endpoint = found;
			}
		}
		if (endpoint == null)
		{
			failed(Failure.REOPEN, "Embertide is not open.");
			return;
		}
		Request request;
		Batch sending = null;
		if (opening)
		{
			if (openBody == null)
			{
				JsonObject body = new JsonObject();
				body.add("header", header);
				openBody = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
			}
			request = post("/v1/mccr/open", openBody, endpoint.getToken());
		}
		else
		{
			sending = nextBatch();
			if (sending == null)
			{
				idle();
				return;
			}
			request = post("/v1/mccr/" + captureId + "/append", sending.body, capability);
		}
		Batch sent = sending;
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				run(() -> failed(Failure.REOPEN, "Embertide is not answering."));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				int code;
				String text;
				try (Response r = response)
				{
					code = r.code();
					text = r.body() == null ? "" : r.body().string();
				}
				catch (IOException e)
				{
					onFailure(call, e);
					return;
				}
				run(() -> answered(opening, sent, code, text));
			}
		});
	}

	/** The batch still owed, or the next one from the queue; null when nothing is waiting. */
	private Batch nextBatch()
	{
		List<Entry> entries = new ArrayList<>();
		int batchSequence;
		synchronized (lock)
		{
			if (batch != null)
			{
				return batch;
			}
			if (queue.isEmpty())
			{
				return null;
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
		List<byte[]> lines = new ArrayList<>(entries.size());
		long bytes = 0;
		for (Entry entry : entries)
		{
			lines.add(entry.line);
			bytes += entry.size;
		}
		Batch next = new Batch(batchSequence, body(batchSequence, lines), bytes);
		synchronized (lock)
		{
			batch = next;
		}
		return next;
	}

	/** Nothing to send: release flush waiters, and complete a finishing capture. */
	private void idle()
	{
		List<CompletableFuture<Void>> done;
		boolean sealed = false;
		synchronized (lock)
		{
			busy = false;
			if (!queue.isEmpty() || batch != null)
			{
				// Rows arrived while this pass was deciding.
				run(this::pump);
				return;
			}
			done = new ArrayList<>(drained);
			drained.clear();
			if (state == State.FINISHING)
			{
				state = State.COMPLETE;
				sealed = true;
			}
		}
		for (CompletableFuture<Void> waiter : done)
		{
			waiter.complete(null);
		}
		if (sealed)
		{
			finished.complete(null);
		}
	}

	private void answered(boolean opening, Batch sent, int code, String text)
	{
		synchronized (lock)
		{
			if (state == State.FAILED)
			{
				// Given up while this call was out.
				busy = false;
				return;
			}
		}
		if (code < 200 || code >= 300)
		{
			boolean retry = code == 408 || code == 429 || (code >= 500 && code != 507);
			Failure kind;
			if (code == 403)
			{
				// At the first open this app or account will not take it; later the app restarted or is closing.
				kind = opening && captureId == null ? Failure.PERMANENT : Failure.REOPEN;
			}
			else if (retry)
			{
				kind = opening ? Failure.REOPEN : Failure.RETRY;
			}
			else
			{
				kind = Failure.PERMANENT;
			}
			JsonObject reply = parse(text);
			String said = reply == null ? "" : string(reply, "error");
			failed(kind, said.isEmpty() ? "Embertide refused the live view (HTTP " + code + ")." : said);
			return;
		}
		JsonObject result = parse(text);
		if (result == null)
		{
			failed(Failure.PERMANENT, "Embertide sent an unreadable reply.");
		}
		else if (opening)
		{
			reopened(result);
		}
		else
		{
			acknowledged(sent, result);
		}
	}

	private void reopened(JsonObject result)
	{
		String id = string(result, "id");
		String cap = string(result, "capability");
		int next = result.has("nextSequence") && result.get("nextSequence").isJsonPrimitive() ? result.get("nextSequence").getAsInt() : -1;
		boolean first;
		List<CompletableFuture<Void>> stopped = null;
		synchronized (lock)
		{
			first = captureId == null;
			// Back after a restart, the app holds every acknowledged batch and maybe the one whose reply was lost.
			boolean matches = first ? next == 0 : id.equals(captureId) && (next == sequence || (batch != null && next == sequence + 1));
			if (!CAPTURE_ID.matcher(id).matches() || cap.isEmpty() || !matches)
			{
				stopped = failNow(first ? "Embertide sent an unreadable reply." : "Embertide's copy no longer matches this recording.");
			}
			else
			{
				captureId = id;
				capability = cap;
				reopen = false;
				rediscover = false;
				failures = 0;
				busy = false;
				if (state == State.OPENING)
				{
					state = State.READY;
				}
			}
		}
		if (stopped != null)
		{
			settle(stopped);
			return;
		}
		if (first)
		{
			opened.complete(null);
		}
		connectLifetime();
		pump();
	}

	private void acknowledged(Batch sent, JsonObject result)
	{
		if (!result.has("ack") || !result.get("ack").isJsonPrimitive() || result.get("ack").getAsInt() != sent.sequence)
		{
			failed(Failure.PERMANENT, "Embertide did not acknowledge the recording sequence.");
			return;
		}
		if (result.has("studioRequested") && result.get("studioRequested").isJsonPrimitive()
			&& result.get("studioRequested").getAsBoolean())
		{
			studioRequested = true;
		}
		synchronized (lock)
		{
			pendingBytes -= sent.bytes;
			sequence++;
			batch = null;
			failures = 0;
			busy = false;
		}
		pump();
	}

	private void failed(Failure kind, String message)
	{
		List<CompletableFuture<Void>> stopped;
		synchronized (lock)
		{
			busy = false;
			if (state == State.FAILED || state == State.COMPLETE)
			{
				return;
			}
			if (kind == Failure.PERMANENT)
			{
				stopped = failNow(message);
			}
			else if (state == State.FINISHING && System.nanoTime() >= finishBy)
			{
				stopped = failNow("Embertide did not come back before the recording ended.");
			}
			else
			{
				failures++;
				if (kind == Failure.REOPEN)
				{
					reopen = true;
					rediscover = true;
				}
				timer = schedule(this::pump, backoff(failures));
				log.debug("live link retry {}: {}", failures, message);
				return;
			}
		}
		settle(stopped);
	}

	/** Ends the client. Called holding the lock; the returned waiters are failed outside it. */
	private List<CompletableFuture<Void>> failNow(String message)
	{
		if (timer != null)
		{
			timer.cancel(false);
			timer = null;
		}
		state = State.FAILED;
		error = message;
		queue.clear();
		pendingBytes = 0;
		batch = null;
		List<CompletableFuture<Void>> waiters = new ArrayList<>(drained);
		drained.clear();
		waiters.add(opened);
		waiters.add(finished);
		log.debug("recorder failed: {}", message);
		return waiters;
	}

	private void settle(List<CompletableFuture<Void>> waiters)
	{
		IOException cause = new IOException(error);
		for (CompletableFuture<Void> waiter : waiters)
		{
			waiter.completeExceptionally(cause);
		}
	}

	/** 250 ms, doubling to 4 s. */
	static long backoff(int failures)
	{
		return Math.min(250L << Math.min(Math.max(failures, 1) - 1, 4), MAX_BACKOFF_MS);
	}

	private Request post(String path, byte[] body, String token)
	{
		return new Request.Builder()
			.url(endpoint.getUrl() + path)
			.header("Origin", RecorderEndpoint.ORIGIN)
			.header("Authorization", "Bearer " + token)
			.header("Cache-Control", "no-store")
			.post(RequestBody.create(JSON, body))
			.build();
	}

	private static JsonObject parse(String text)
	{
		try
		{
			JsonElement parsed = new JsonParser().parse(text);
			return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	private static String string(JsonObject object, String key)
	{
		return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
	}

	private void run(Runnable task)
	{
		try
		{
			executor.execute(task);
		}
		catch (RejectedExecutionException e)
		{
			log.debug("live link stopped with its executor");
		}
	}

	private ScheduledFuture<?> schedule(Runnable task, long delayMs)
	{
		try
		{
			return executor.schedule(task, delayMs, TimeUnit.MILLISECONDS);
		}
		catch (RejectedExecutionException e)
		{
			return null;
		}
	}

	/** Opens the lifetime socket for the current endpoint and capability, unless that one is already up. */
	private void connectLifetime()
	{
		WebSocket old;
		Request request;
		int mine;
		String id;
		String cap;
		synchronized (lock)
		{
			String key = endpoint.getUrl() + " " + capability;
			if (lifetimeClosed || state == State.FAILED || (key.equals(lifetimeKey) && lifetimeSocket != null))
			{
				return;
			}
			if (redial != null)
			{
				redial.cancel(false);
				redial = null;
			}
			old = lifetimeSocket;
			lifetimeSocket = null;
			lifetimeKey = key;
			mine = ++generation;
			lifetime = Lifetime.CONNECTING;
			id = captureId;
			cap = capability;
			String url = endpoint.getUrl().replaceFirst("^http:", "ws:") + "/v1/mccr/lifetime";
			request = new Request.Builder().url(url).header("Origin", RecorderEndpoint.ORIGIN).build();
		}
		if (old != null)
		{
			old.cancel();
		}
		WebSocket socket = http.newWebSocket(request, new WebSocketListener()
		{
			@Override
			public void onOpen(WebSocket webSocket, Response response)
			{
				JsonObject auth = new JsonObject();
				auth.addProperty("id", id);
				auth.addProperty("capability", cap);
				if (!webSocket.send(gson.toJson(auth)))
				{
					lost(mine, webSocket);
				}
			}

			@Override
			public void onMessage(WebSocket webSocket, String text)
			{
				JsonObject reply = parse(text);
				if (reply != null && reply.has("ok") && reply.get("ok").isJsonPrimitive() && reply.get("ok").getAsBoolean())
				{
					synchronized (lock)
					{
						if (mine == generation)
						{
							lifetime = Lifetime.CONNECTED;
							lifetimeFailures = 0;
						}
					}
					return;
				}
				lost(mine, webSocket);
			}

			@Override
			public void onClosing(WebSocket webSocket, int code, String reason)
			{
				webSocket.close(1000, null);
				lost(mine, webSocket);
			}

			@Override
			public void onFailure(WebSocket webSocket, Throwable t, Response response)
			{
				lost(mine, webSocket);
			}

			@Override
			public void onClosed(WebSocket webSocket, int code, String reason)
			{
				lost(mine, webSocket);
			}
		});
		synchronized (lock)
		{
			if (mine != generation)
			{
				socket.cancel();
				return;
			}
			lifetimeSocket = socket;
		}
		schedule(() ->
		{
			synchronized (lock)
			{
				if (mine != generation || lifetime != Lifetime.CONNECTING)
				{
					return;
				}
			}
			socket.cancel();
		}, LIFETIME_TIMEOUT_MS);
	}

	/** Redials a dropped lifetime socket, quickly at first: the app waits a second before it calls the capture ended. */
	private void lost(int which, WebSocket socket)
	{
		synchronized (lock)
		{
			if (which != generation || lifetimeClosed)
			{
				return;
			}
			generation++;
			lifetimeSocket = null;
			lifetimeKey = null;
			lifetime = Lifetime.UNAVAILABLE;
			lifetimeFailures++;
			if (redial != null)
			{
				redial.cancel(false);
			}
			redial = schedule(this::connectLifetime, backoff(lifetimeFailures));
		}
		socket.cancel();
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
			lifetimeSocket = null;
			generation++;
			if (redial != null)
			{
				redial.cancel(false);
				redial = null;
			}
		}
		if (socket != null)
		{
			socket.close(1000, "done");
		}
	}

	/** Gives up the live view: closes the socket and stops trying to reach the app. */
	public void stop(String because)
	{
		closeLifetime();
		List<CompletableFuture<Void>> stopped = null;
		synchronized (lock)
		{
			if (state != State.FAILED && state != State.COMPLETE)
			{
				stopped = failNow(because);
			}
		}
		if (stopped != null)
		{
			settle(stopped);
		}
	}
}
