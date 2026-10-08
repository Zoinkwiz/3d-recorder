package com.extrinsiccognition.embertide;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import net.runelite.client.util.Filepath;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** The rows of a fixed raid, as the capture builds them, as the file keeps them and as the app is sent them. */
public class RaidRowsTest
{
	private static final Gson GSON = new Gson();
	private static final int TICKS = 12;
	private static final String ROWS = "1260:684d187bb9e4c8e5c7378b937555b050a0eff19e9541b84f0ba188d6d75fda7f";
	private static final String FILE = "1261:67c486c2f4d8e8d25b3416e7a5a9ec092cb4655c6a95e61d63dfea5d94133a3c";
	private Path dir;

	@Before
	public void setUp() throws IOException
	{
		dir = Files.createTempDirectory("raid-rows");
	}

	@After
	public void tearDown() throws IOException
	{
		try (Stream<Path> files = Files.walk(dir))
		{
			files.sorted((a, b) -> b.compareTo(a)).forEach(path -> path.toFile().delete());
		}
	}

	@Test
	public void aRaidWritesTheSameRows() throws Exception
	{
		List<Runnable> fileWork = new ArrayList<>();
		LiveMirror mirror = RaidScene.streamerRecorder(GSON, Filepath.Unchecked.getRooted(dir), fileWork::add);
		Kept kept = new Kept(mirror);
		RaidScene scene = new RaidScene(30, 11);
		OsrsCapture capture = scene.capture(kept);
		capture.start();
		capture.tick(true);
		capture.clientTick();
		for (int i = 0; i < TICKS; i++)
		{
			scene.serverTick();
			capture.tick(false);
			scene.tickEvents(capture);
			for (int c = 0; c < RaidScene.CYCLES_PER_TICK; c++)
			{
				scene.clientCycle();
				capture.clientTick();
				scene.cycleEvents(capture);
			}
		}
		CompletableFuture<Void> saved = capture.finish("raid over", false, true);
		for (Runnable work : fileWork) { work.run(); }
		saved.get(10, TimeUnit.SECONDS);

		// Nothing the capture handed over changed afterwards.
		List<String> after = new ArrayList<>();
		for (JsonObject row : kept.rows) { after.add(GSON.toJson(row)); }
		assertEquals(kept.json, after);
		assertEquals(kept.json, mirror.live().queued());
		String id = capture.id();
		Path file = Filepath.Unchecked.getPath(((MccrFileRecorder) mirror.file()).path());
		List<String> filed = lines(Files.readAllBytes(file));
		assertEquals(kept.json.size() + 1, filed.size());
		assertEquals(ROWS, digest(kept.json, id));
		assertEquals(FILE, digest(filed, id));
	}

	private static String digest(List<String> rows, String id) throws Exception
	{
		MessageDigest sha = MessageDigest.getInstance("SHA-256");
		for (String row : rows) { sha.update((row.replace(id, "osrs-session") + "\n").getBytes(StandardCharsets.UTF_8)); }
		StringBuilder hex = new StringBuilder(rows.size() + ":");
		for (byte b : sha.digest()) { hex.append(String.format("%02x", b)); }
		return hex.toString();
	}

	private static List<String> lines(byte[] gzip) throws IOException
	{
		try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gzip)))
		{
			String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			List<String> lines = new ArrayList<>();
			for (String line : text.split("\n")) { if (!line.isEmpty()) { lines.add(line); } }
			return lines;
		}
	}

	/** Keeps every row and its text at the moment it was handed over. */
	private static final class Kept implements Recorder
	{
		final Recorder next;
		final List<JsonObject> rows = new ArrayList<>();
		final List<String> json = new ArrayList<>();

		Kept(Recorder next)
		{
			this.next = next;
		}

		@Override public State state() { return next.state(); }
		@Override public String error() { return next.error(); }
		@Override public String captureId() { return next.captureId(); }
		@Override public CompletableFuture<Void> open(JsonObject header) { return next.open(header); }
		@Override public boolean enqueue(JsonObject record, boolean control)
		{
			rows.add(record);
			json.add(GSON.toJson(record));
			return next.enqueue(record, control);
		}
		@Override public boolean full() { return next.full(); }
		@Override public CompletableFuture<Void> flush() { return next.flush(); }
		@Override public CompletableFuture<Void> finish() { return next.finish(); }
		@Override public CompletableFuture<Void> openStudio() { return next.openStudio(); }
		@Override public String destination() { return next.destination(); }
	}
}
