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

@Slf4j
public final class MccrFileRecorder implements Recorder
{
	public static final String STUDIO_URL = "https://embertide.gg/studio/";
	private static final String PARTIAL = ".embertide.partial";
	private static final String SEALED = ".embertide";

	private final Object lock = new Object();
	private final Gson gson;
	private final Path directory;
	private final java.util.function.Supplier<String> stem;
	private final StringBuilder held = new StringBuilder();
	private static final int MAX_HELD_CHARS = 64 * 1024 * 1024;
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

	public Path path()
	{
		synchronized (lock)
		{
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

	public static void showFolder(Path directory)
	{
		try
		{
			Files.createDirectories(directory);
		}
		catch (IOException ignored)
		{
		}
		LinkBrowser.open(directory.toString());
	}

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
		LinkBrowser.open(at.getParent().toString());
		LinkBrowser.browse(STUDIO_URL);
		return CompletableFuture.completedFuture(null);
	}


	private CompletableFuture<Void> fail(String message)
	{
		state = State.FAILED;
		error = message;
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
