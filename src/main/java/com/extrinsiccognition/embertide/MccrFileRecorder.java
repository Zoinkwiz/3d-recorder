package com.extrinsiccognition.embertide;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.LinkBrowser;

/**
 * A memory as a file in RuneLite's own folder.
 *
 * NOTHING IS ON DISK UNTIL THE MEMORY IS SEALED. The records are held in
 * memory while you play and written in one go when the memory ends: Stop,
 * Finish, logging out, or the client closing (which waits for it). A file
 * that grew line by line while you played would be a live feed of where you
 * and everyone around you stand, readable by anything on the machine, and a
 * recorder must not be that. The cost is honest: a hard crash loses the
 * memory in progress, which is at most the capture's own twenty minutes.
 *
 * The write itself is {@code <name>.embertide.partial} moved atomically to
 * {@code <name>.embertide}, so a reader never sees a half-written file. The
 * file is named for the product: a person should read what
 * it is off the name. Inside it is still {@code mccr/0.x}, which is what
 * every reader checks. "Open in
 * Studio" means: show the folder, and open embertide.gg/studio for the file
 * to be dragged into.
 */
@Slf4j
public final class MccrFileRecorder implements Recorder
{
	public static final String STUDIO_URL = "https://embertide.gg/studio/";
	private static final String PARTIAL = ".embertide.partial";
	private static final String SEALED = ".embertide";

	private final Object lock = new Object();
	private final Gson gson;
	private final Path directory;
	/** Read when the file opens, not when the recorder is made: the character's name is not known until the first tick. */
	private final java.util.function.Supplier<String> stem;
	/** The memory so far, bounded by the capture's budgets and the size cap below. */
	private final StringBuilder held = new StringBuilder();
	private static final int MAX_HELD_CHARS = 64 * 1024 * 1024;
	/** The stem the file opened with; the supplier's answer at that moment. */
	private String name = "";
	private Path partial;
	private Path sealed;
	private State state = State.IDLE;
	private String error = "";
	private long lines;

	public MccrFileRecorder(Gson gson, Path directory, String stem)
	{
		this(gson, directory, () -> stem);
	}

	public MccrFileRecorder(Gson gson, Path directory, java.util.function.Supplier<String> stem)
	{
		this.gson = gson;
		this.directory = directory;
		this.stem = stem;
	}

	@Override
	public State state()
	{
		synchronized (lock)
		{
			return state;
		}
	}

	@Override
	public String error()
	{
		synchronized (lock)
		{
			return error;
		}
	}

	@Override
	public String captureId()
	{
		return name.isEmpty() ? stem.get() : name;
	}

	/** The sealed file once it exists, else the one being written. */
	public Path path()
	{
		synchronized (lock)
		{
			// The sealed name is the only one a person is ever pointed at.
			return sealed;
		}
	}

	@Override
	public String destination()
	{
		Path at = path();
		return at == null ? "" : "Will be saved as " + at;
	}

	@Override
	public CompletableFuture<Void> open(JsonObject header)
	{
		synchronized (lock)
		{
			if (state != State.IDLE)
			{
				return CompletableFuture.completedFuture(null);
			}
			try
			{
				// The folder is made now so a problem with it is known at the start,
				// not at the end; the file itself waits for the seal.
				Files.createDirectories(directory);
				name = stem.get();
				partial = directory.resolve(name + PARTIAL);
				sealed = directory.resolve(name + SEALED);
				held.setLength(0);
				held.append(gson.toJson(header)).append('\n');
				lines = 1;
				state = State.READY;
				return CompletableFuture.completedFuture(null);
			}
			catch (IOException e)
			{
				return fail("Could not prepare the recording folder: " + e.getMessage());
			}
		}
	}

	@Override
	public boolean enqueue(JsonObject record, boolean control)
	{
		synchronized (lock)
		{
			if (state != State.READY)
			{
				return false;
			}
			String line = gson.toJson(record);
			if (held.length() + line.length() + 1 > MAX_HELD_CHARS)
			{
				fail("The recording grew past the size a file may be.");
				return false;
			}
			held.append(line).append('\n');
			lines++;
			return true;
		}
	}

	@Override
	public CompletableFuture<Void> flush()
	{
		synchronized (lock)
		{
			// Nothing reaches disk before the seal, so there is nothing to flush.
			return state == State.FAILED ? failed() : CompletableFuture.completedFuture(null);
		}
	}

	@Override
	public CompletableFuture<Void> finish()
	{
		synchronized (lock)
		{
			if (state == State.COMPLETE)
			{
				return CompletableFuture.completedFuture(null);
			}
			if (state != State.READY)
			{
				return failed();
			}
			state = State.FINISHING;
			try
			{
				Files.write(partial, held.toString().getBytes(StandardCharsets.UTF_8),
					StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
				Files.move(partial, sealed, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				held.setLength(0);
				state = State.COMPLETE;
				log.debug("sealed memory {} ({} lines)", sealed, lines);
				return CompletableFuture.completedFuture(null);
			}
			catch (IOException e)
			{
				return fail("Could not seal the recording file: " + e.getMessage());
			}
		}
	}

	/** Show the folder the memories are in. The client's own opener: the Hub does not accept java.awt.Desktop. */
	public static void showFolder(Path directory)
	{
		try
		{
			Files.createDirectories(directory);
		}
		catch (IOException ignored)
		{
			// The opener says so itself if the folder cannot be shown.
		}
		LinkBrowser.open(directory.toString());
	}

	/** Show the file where it is, and open the web Studio it can be dragged into. */
	@Override
	public CompletableFuture<Void> openStudio()
	{
		Path at = path();
		if (at == null || !Files.exists(at))
		{
			CompletableFuture<Void> missing = new CompletableFuture<>();
			missing.completeExceptionally(new IOException("The recording file is not there yet."));
			return missing;
		}
		// The client's own openers: the Hub does not accept java.awt.Desktop.
		LinkBrowser.open(at.getParent().toString());
		LinkBrowser.browse(STUDIO_URL);
		return CompletableFuture.completedFuture(null);
	}


	private CompletableFuture<Void> fail(String message)
	{
		state = State.FAILED;
		error = message;
		// What was held is not kept: a failed memory leaves nothing behind.
		held.setLength(0);
		return failed();
	}

	private CompletableFuture<Void> failed()
	{
		CompletableFuture<Void> future = new CompletableFuture<>();
		future.completeExceptionally(new IOException(error.isEmpty() ? "Recording failed." : error));
		return future;
	}
}
