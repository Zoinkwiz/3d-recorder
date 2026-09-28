package com.extrinsiccognition.embertide;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipException;
import net.runelite.client.util.Filepath;
import net.runelite.client.util.LinkBrowser;

public final class MccrFileRecorder implements Recorder
{
	public static final String STUDIO_URL = "https://embertide.gg/studio/";
	// The desktop's 128 MiB archive also includes ingest checkpoints and links.
	static final int MAX_BYTES = 120 * 1024 * 1024;
	static final int END_RESERVE = 16 * 1024;
	// The desktop importer accepts 120,000 records plus a header.
	static final int MAX_RECORDS = 120_000;
	// Rows reach the partial file about once a second, so a crash leaves a readable prefix.
	static final long FLUSH_NANOS = 1_000_000_000L;
	private final Gson gson;
	private final Filepath directory;
	private final Supplier<String> stem;
	private final Executor worker;
	private final int capacity;
	private final String unique = UUID.randomUUID().toString();
	// Rows not yet in the partial file: about a second's worth.
	private final List<byte[]> pending = new ArrayList<>();
	private int count;
	private Filepath sealed;
	private String name = "";
	private State state = State.IDLE;
	private String error = "";
	private int bytes;
	private boolean full;
	private CompletableFuture<Void> saving;
	private final Object io = new Object();
	private Filepath partial;
	private GZIPOutputStream out;
	private int written;
	private boolean finished;
	private boolean draining;
	private long drainedAt = System.nanoTime() - FLUSH_NANOS;
	// osrs.motion x and y as thousandths of a tile, each actor's first absolute and then its change.
	static final String MOTION_XY = "delta-milli";
	private final Map<String, long[]> lastXY = new HashMap<>();
	private boolean compact;

	public MccrFileRecorder(Gson gson, Filepath directory, Supplier<String> stem, Executor worker)
	{
		this(gson, directory, stem, worker, MAX_BYTES);
	}

	MccrFileRecorder(Gson gson, Filepath directory, Supplier<String> stem, Executor worker, int capacity)
	{
		this.gson = gson;
		this.directory = directory;
		this.stem = stem;
		this.worker = worker;
		this.capacity = capacity;
	}

	@Override
	public synchronized State state() { return state; }

	@Override
	public synchronized String error() { return error; }

	@Override
	public synchronized String captureId() { return name.isEmpty() ? unique : name; }

	public synchronized Filepath path() { return sealed; }

	@Override
	public synchronized String destination()
	{
		return sealed == null ? "" : "Will be saved as " + sealed;
	}

	@Override
	public synchronized boolean full() { return full; }

	synchronized int budgetBytes() { return bytes; }

	synchronized int pendingRows() { return pending.size(); }

	@Override
	public synchronized CompletableFuture<Void> open(JsonObject header)
	{
		if (state != State.IDLE)
		{
			return CompletableFuture.failedFuture(new IllegalStateException("Recording already opened."));
		}
		// The supplier may read the local player's name: this stays on the client thread.
		name = stem.get() + "-" + unique;
		sealed = directory.joinSegment(name + ".embertide");
		state = State.READY;
		if (header.has("osrs") && header.get("osrs").isJsonObject())
		{
			header = header.deepCopy();
			header.getAsJsonObject("osrs").addProperty("motion_xy", MOTION_XY);
			compact = true;
		}
		if (!enqueue(header, false))
		{
			state = State.FAILED;
			error = "The recording header exceeds the size budget.";
			return CompletableFuture.failedFuture(new IOException(error));
		}
		return CompletableFuture.completedFuture(null);
	}

	@Override
	public synchronized boolean enqueue(JsonObject record, boolean control)
	{
		if (state != State.READY || (full && !control))
		{
			return false;
		}
		byte[] row = (gson.toJson(record) + '\n').getBytes(StandardCharsets.UTF_8);
		// The budget counts rows as readers expand them, so the desktop's limit still holds.
		int plain = row.length;
		Map<String, long[]> moved = compact ? new HashMap<>() : null;
		JsonObject compacted = moved == null ? null : compactMotion(record, moved);
		if (compacted != null)
		{
			row = (gson.toJson(compacted) + '\n').getBytes(StandardCharsets.UTF_8);
		}
		if (bytes + plain > capacity - (control ? 0 : END_RESERVE)
			|| count >= MAX_RECORDS + (control ? 1 : 0))
		{
			full = true;
			return false;
		}
		pending.add(row);
		count++;
		bytes += plain;
		if (compacted != null)
		{
			lastXY.putAll(moved);
		}
		if (!draining && System.nanoTime() - drainedAt >= FLUSH_NANOS)
		{
			draining = true;
			drainedAt = System.nanoTime();
			try
			{
				worker.execute(this::drain);
			}
			catch (RuntimeException e)
			{
				draining = false;
			}
		}
		return true;
	}

	/** A copy of an osrs.motion row with x and y as thousandths, or null for any other row. */
	private JsonObject compactMotion(JsonObject record, Map<String, long[]> moved)
	{
		JsonElement type = record.get("type");
		JsonElement payload = record.get("payload");
		if (type == null || !"osrs.motion".equals(type.getAsString()) || payload == null || !payload.isJsonObject()
			|| !payload.getAsJsonObject().has("samples") || !payload.getAsJsonObject().get("samples").isJsonArray())
		{
			return null;
		}
		JsonObject copy = record.deepCopy();
		for (JsonElement element : copy.getAsJsonObject("payload").getAsJsonArray("samples"))
		{
			JsonArray sample = element.isJsonArray() ? element.getAsJsonArray() : null;
			if (sample == null || sample.size() < 3 || !isString(sample.get(0)) || !isNumber(sample.get(1)) || !isNumber(sample.get(2)))
			{
				continue;
			}
			String id = sample.get(0).getAsString();
			long x = Math.round(sample.get(1).getAsDouble() * 1000.0);
			long y = Math.round(sample.get(2).getAsDouble() * 1000.0);
			long[] seen = moved.containsKey(id) ? moved.get(id) : lastXY.get(id);
			sample.set(1, new JsonPrimitive(seen == null ? x : x - seen[0]));
			sample.set(2, new JsonPrimitive(seen == null ? y : y - seen[1]));
			moved.put(id, new long[]{x, y});
		}
		return copy;
	}

	private static boolean isString(JsonElement e) { return e.isJsonPrimitive() && e.getAsJsonPrimitive().isString(); }

	private static boolean isNumber(JsonElement e) { return e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber(); }

	private void drain()
	{
		try
		{
			synchronized (io)
			{
				if (state() == State.READY)
				{
					writePending();
				}
			}
		}
		catch (IOException | RuntimeException e)
		{
			// The next drain or the save rebuilds the partial file from its readable rows.
			closeStream();
		}
		finally
		{
			synchronized (this)
			{
				draining = false;
			}
		}
	}

	/** Appends the rows not yet on disk and sync-flushes them. Called holding io. */
	private void writePending() throws IOException
	{
		if (out == null)
		{
			Filepath broken = partial;
			int kept = written;
			openPartial();
			if (broken != null)
			{
				try
				{
					copyRows(broken);
				}
				catch (IOException | RuntimeException e)
				{
					closeStream();
					partial.deleteIfExists();
					partial = broken;
					written = kept;
					throw e;
				}
			}
		}
		List<byte[]> rows;
		synchronized (this)
		{
			rows = new ArrayList<>(pending);
		}
		for (byte[] row : rows)
		{
			out.write(row);
		}
		out.flush();
		written += rows.size();
		synchronized (this)
		{
			pending.subList(0, rows.size()).clear();
		}
	}

	private void openPartial() throws IOException
	{
		directory.createDirectories();
		Filepath fresh = directory.joinSegment(name + "-" + UUID.randomUUID() + ".embertide.partial");
		out = new GZIPOutputStream(new BufferedOutputStream(
			fresh.openOutputStream(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), 64 * 1024), 64 * 1024, true);
		partial = fresh;
	}

	/** Copies the rows already flushed to a broken partial file into the new one, then deletes it. */
	private void copyRows(Filepath broken) throws IOException
	{
		int want = written;
		written = 0;
		ByteArrayOutputStream line = new ByteArrayOutputStream();
		byte[] buffer = new byte[64 * 1024];
		try (InputStream in = new GZIPInputStream(broken.openInputStream(), 64 * 1024))
		{
			int read;
			while (written < want && (read = in.read(buffer)) > 0)
			{
				int from = 0;
				for (int i = 0; i < read && written < want; i++)
				{
					if (buffer[i] == '\n')
					{
						line.write(buffer, from, i + 1 - from);
						line.writeTo(out);
						line.reset();
						written++;
						from = i + 1;
					}
				}
				if (written < want)
				{
					line.write(buffer, from, read - from);
				}
			}
		}
		catch (EOFException | ZipException cutOff)
		{
			// Keep every whole row before the damage.
		}
		broken.deleteIfExists();
	}

	/** Closes the stream as a failed write would. */
	void dropStream()
	{
		synchronized (io)
		{
			closeStream();
		}
	}

	private void closeStream()
	{
		if (out != null)
		{
			try { out.close(); }
			catch (IOException ignored) { }
			out = null;
		}
	}

	@Override
	public CompletableFuture<Void> flush() { return CompletableFuture.completedFuture(null); }

	@Override
	public synchronized CompletableFuture<Void> finish()
	{
		if (state == State.COMPLETE || state == State.IDLE)
		{
			return CompletableFuture.completedFuture(null);
		}
		if (state == State.FINISHING)
		{
			return saving;
		}
		state = State.FINISHING;
		error = "";
		saving = new CompletableFuture<>();
		try
		{
			worker.execute(this::write);
		}
		catch (RuntimeException e)
		{
			failed(e);
		}
		return saving;
	}

	private void write()
	{
		synchronized (io)
		{
			try
			{
				if (!finished)
				{
					writePending();
					// The gzip trailer: a finished part is a whole gzip file.
					out.finish();
					out.close();
					out = null;
					finished = true;
				}
				// Same-directory rename without REPLACE_EXISTING. ATOMIC_MOVE can silently
				// replace its destination on some platforms, so do not request that option.
				partial.moveTo(sealed);
				partial = null;
				finished = false;
				written = 0;
				synchronized (this)
				{
					state = State.COMPLETE;
				}
				saving.complete(null);
			}
			catch (IOException | RuntimeException e)
			{
				closeStream();
				failed(e);
			}
		}
	}

	private synchronized void failed(Exception e)
	{
		state = State.FAILED;
		error = "Could not save the recording: " + e.getMessage();
		saving.completeExceptionally(e);
	}

	public void discard()
	{
		synchronized (io)
		{
			synchronized (this)
			{
				if (state != State.FAILED)
				{
					throw new IllegalStateException("Only a failed save can be discarded.");
				}
				pending.clear();
				state = State.COMPLETE;
			}
			closeStream();
			if (partial != null)
			{
				try { partial.deleteIfExists(); }
				catch (IOException | RuntimeException ignored) { }
				partial = null;
			}
			finished = false;
			written = 0;
		}
	}

	@Override
	public CompletableFuture<Void> openStudio()
	{
		if (state() != State.COMPLETE)
		{
			return CompletableFuture.failedFuture(new IOException("Save the recording before opening Studio."));
		}
		javax.swing.SwingUtilities.invokeLater(() -> LinkBrowser.browse(STUDIO_URL));
		return CompletableFuture.completedFuture(null);
	}
}
