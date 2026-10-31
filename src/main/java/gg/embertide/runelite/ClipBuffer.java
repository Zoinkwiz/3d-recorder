package gg.embertide.runelite;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * A recording kept in memory until something worth a clip happens. Each row the capture writes is held here; nothing
 * reaches the disk unless {@link #keep} hands the rows, the header first, to a file recorder, which then takes every
 * row after them too. A buffer never kept is dropped with everything in it.
 */
final class ClipBuffer implements Recorder
{
	/** The most rows one buffer holds; past it the buffer refuses rows and its capture stops, to be started again. */
	static final int MAX_ROWS = 60_000;

	private final String unique = UUID.randomUUID().toString();
	private final List<JsonObject> rows = new ArrayList<>();
	private final List<Boolean> controls = new ArrayList<>();
	private final Consumer<JsonObject> watcher;
	private State state = State.IDLE;
	private Recorder file;

	/** `watcher` sees every row as it is written, the header excepted, on the client thread. */
	ClipBuffer(Consumer<JsonObject> watcher)
	{
		this.watcher = watcher;
	}

	@Override
	public synchronized State state() { return file != null ? file.state() : state; }

	@Override
	public synchronized String error() { return file != null ? file.error() : ""; }

	@Override
	public synchronized String captureId() { return file != null ? file.captureId() : unique; }

	@Override
	public synchronized String destination() { return file != null ? file.destination() : ""; }

	synchronized boolean kept() { return file != null; }

	/** The file it is kept in, or null. */
	synchronized Recorder file() { return file; }

	@Override
	public synchronized CompletableFuture<Void> open(JsonObject header)
	{
		if (state != State.IDLE)
		{
			return CompletableFuture.failedFuture(new IllegalStateException("Recording already opened."));
		}
		state = State.READY;
		rows.add(header);
		controls.add(false);
		return CompletableFuture.completedFuture(null);
	}

	@Override
	public boolean enqueue(JsonObject record, boolean control)
	{
		Recorder to;
		synchronized (this)
		{
			to = file;
			if (to == null)
			{
				if (state != State.READY || (rows.size() >= MAX_ROWS && !control)) { return false; }
				rows.add(record);
				controls.add(control);
			}
		}
		if (to != null && !to.enqueue(record, control)) { return false; }
		if (watcher != null) { watcher.accept(record); }
		return true;
	}

	/**
	 * Writes what is held to `file`, `clip` added to the header, and sends it every row from now on. False when the
	 * buffer was never opened, is kept already, or the file refused a row.
	 */
	synchronized boolean keep(Recorder file, JsonObject clip)
	{
		if (this.file != null || state != State.READY || rows.isEmpty()) { return false; }
		JsonObject header = rows.get(0).deepCopy();
		header.add("clip", clip);
		// The recording's card and Studio read their title from the session: a clip is called what happened.
		if (clip.has("title") && header.has("session") && header.get("session").isJsonObject())
		{
			header.getAsJsonObject("session").add("title", clip.get("title"));
		}
		if (file.open(header).isCompletedExceptionally()) { return false; }
		for (int i = 1; i < rows.size(); i++)
		{
			if (!file.enqueue(rows.get(i), controls.get(i))) { return false; }
		}
		rows.clear();
		controls.clear();
		this.file = file;
		return true;
	}

	synchronized int size() { return rows.size(); }

	@Override
	public CompletableFuture<Void> flush() { return CompletableFuture.completedFuture(null); }

	@Override
	public synchronized CompletableFuture<Void> finish()
	{
		if (file != null) { return file.finish(); }
		rows.clear();
		controls.clear();
		state = State.COMPLETE;
		return CompletableFuture.completedFuture(null);
	}

	@Override
	public synchronized CompletableFuture<Void> openStudio()
	{
		return file != null ? file.openStudio() : CompletableFuture.completedFuture(null);
	}
}
