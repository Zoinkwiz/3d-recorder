package com.extrinsiccognition.embertide;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.BufferedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;
import net.runelite.client.util.Filepath;
import net.runelite.client.util.LinkBrowser;

public final class MccrFileRecorder implements Recorder
{
	public static final String STUDIO_URL = "https://embertide.gg/studio/";
	// The desktop's 64 MiB archive also includes ingest checkpoints and links.
	static final int MAX_BYTES = 60 * 1024 * 1024;
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
	private final List<byte[]> held = new ArrayList<>();
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
	private boolean draining;
	private long drainedAt = System.nanoTime() - FLUSH_NANOS;

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

	synchronized int retainedBytes() { return bytes; }

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
		if (bytes + row.length > capacity - (control ? 0 : END_RESERVE)
			|| held.size() >= MAX_RECORDS + (control ? 1 : 0))
		{
			full = true;
			return false;
		}
		held.add(row);
		bytes += row.length;
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

	private void drain()
	{
		try
		{
			synchronized (io)
			{
				if (state() == State.READY)
				{
					writeHeld();
				}
			}
		}
		catch (IOException | RuntimeException e)
		{
			// The save at the end starts a fresh file from every held row.
			closeQuietly();
		}
		finally
		{
			synchronized (this)
			{
				draining = false;
			}
		}
	}

	/** Appends the rows not yet in the partial file and sync-flushes them. Called holding io. */
	private void writeHeld() throws IOException
	{
		if (out == null)
		{
			directory.createDirectories();
			partial = directory.joinSegment(name + "-" + UUID.randomUUID() + ".embertide.partial");
			out = new GZIPOutputStream(new BufferedOutputStream(
				partial.openOutputStream(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), 64 * 1024), 64 * 1024, true);
			written = 0;
		}
		List<byte[]> rows;
		synchronized (this)
		{
			rows = new ArrayList<>(held.subList(written, held.size()));
		}
		for (byte[] row : rows)
		{
			out.write(row);
		}
		out.flush();
		written += rows.size();
	}

	private void closeQuietly()
	{
		if (out != null)
		{
			try { out.close(); }
			catch (IOException ignored) { }
			out = null;
		}
		if (partial != null)
		{
			try { partial.deleteIfExists(); }
			catch (IOException | RuntimeException ignored) { }
			partial = null;
		}
		written = 0;
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
				writeHeld();
				// The gzip trailer: a finished part is a whole gzip file.
				out.finish();
				out.close();
				out = null;
				// Same-directory rename without REPLACE_EXISTING. ATOMIC_MOVE can silently
				// replace its destination on some platforms, so do not request that option.
				partial.moveTo(sealed);
				partial = null;
				written = 0;
				synchronized (this)
				{
					held.clear();
					bytes = 0;
					state = State.COMPLETE;
				}
				saving.complete(null);
			}
			catch (IOException | RuntimeException e)
			{
				closeQuietly();
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

	public synchronized void discard()
	{
		if (state != State.FAILED)
		{
			throw new IllegalStateException("Only a failed save can be discarded.");
		}
		held.clear();
		bytes = 0;
		state = State.COMPLETE;
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
