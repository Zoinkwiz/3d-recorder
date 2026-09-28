package com.extrinsiccognition.embertide;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import net.runelite.client.util.Filepath;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MccrFileRecorderTest
{
	private final Gson gson = new Gson();
	private ExecutorService worker;
	private Path dir;

	@Before
	public void setUp() throws IOException
	{
		worker = Executors.newSingleThreadExecutor();
		dir = Files.createTempDirectory("mccr-file-recorder");
	}

	@After
	public void tearDown() throws Exception
	{
		worker.shutdownNow();
		try (Stream<Path> files = Files.walk(dir))
		{
			files.sorted((a, b) -> b.compareTo(a)).forEach(path -> path.toFile().delete());
		}
	}

	@Test
	public void sealsAWholeGzipFile() throws Exception
	{
		MccrFileRecorder recorder = recorder();
		List<String> rows = record(recorder, 500);
		recorder.finish().get(10, TimeUnit.SECONDS);
		byte[] bytes = Files.readAllBytes(sealed());
		assertEquals(0x1f, bytes[0] & 0xff);
		assertEquals(0x8b, bytes[1] & 0xff);
		assertEquals(rows, lines(bytes));
		assertEquals(0, partials().size());
	}

	@Test
	public void aCrashLeavesTheRowsUpToTheLastFlush() throws Exception
	{
		MccrFileRecorder recorder = recorder();
		List<String> rows = record(recorder, 300);
		Thread.sleep(MccrFileRecorder.FLUSH_NANOS / 1_000_000 + 50);
		rows.addAll(record(recorder, 1, 300));
		settle();
		// No finish: the partial file is what a crash leaves behind.
		List<Path> partials = partials();
		assertEquals(1, partials.size());
		byte[] bytes = Files.readAllBytes(partials.get(0));
		assertEquals(rows, lines(bytes));
		// Cut mid-stream too: every whole line before the cut still reads.
		List<String> prefix = lines(Arrays.copyOf(bytes, bytes.length * 2 / 3));
		assertTrue(prefix.size() > 1);
		assertEquals(rows.subList(0, prefix.size()), prefix);
	}

	@Test
	public void aTruncatedSealedFileReadsItsPrefix() throws Exception
	{
		MccrFileRecorder recorder = recorder();
		List<String> rows = record(recorder, 2000);
		recorder.finish().get(10, TimeUnit.SECONDS);
		byte[] bytes = Files.readAllBytes(sealed());
		List<String> prefix = lines(Arrays.copyOf(bytes, bytes.length / 2));
		assertTrue(prefix.size() > 1);
		assertEquals(rows.subList(0, prefix.size()), prefix);
	}

	private MccrFileRecorder recorder()
	{
		return new MccrFileRecorder(gson, Filepath.Unchecked.getRooted(dir), () -> "osrs-test", worker);
	}

	private List<String> record(MccrFileRecorder recorder, int count) throws Exception
	{
		JsonObject header = new JsonObject();
		header.addProperty("type", "mccr.header");
		recorder.open(header).get();
		List<String> rows = new ArrayList<>();
		rows.add(gson.toJson(header));
		rows.addAll(record(recorder, count, 0));
		return rows;
	}

	private List<String> record(MccrFileRecorder recorder, int count, int from)
	{
		List<String> rows = new ArrayList<>();
		for (int i = from; i < from + count; i++)
		{
			JsonObject row = new JsonObject();
			row.addProperty("type", "osrs.motion");
			row.addProperty("elapsed_s", i * 0.6);
			assertTrue(recorder.enqueue(row, false));
			rows.add(gson.toJson(row));
		}
		return rows;
	}

	private void settle() throws Exception
	{
		worker.submit(() -> { }).get(10, TimeUnit.SECONDS);
	}

	private Path sealed() throws IOException
	{
		try (Stream<Path> files = Files.list(dir))
		{
			return files.filter(path -> path.toString().endsWith(".embertide")).findFirst().orElseThrow();
		}
	}

	private List<Path> partials() throws IOException
	{
		try (Stream<Path> files = Files.list(dir))
		{
			return files.filter(path -> path.toString().endsWith(".partial")).collect(Collectors.toList());
		}
	}

	/** Every complete line a gzip stream yields before it ends or is cut off. */
	private static List<String> lines(byte[] gzip) throws IOException
	{
		ByteArrayOutputStream text = new ByteArrayOutputStream();
		byte[] buffer = new byte[512];
		try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gzip)))
		{
			int read;
			while ((read = in.read(buffer)) > 0)
			{
				text.write(buffer, 0, read);
			}
		}
		catch (EOFException cutOff)
		{
			// Truncated: keep what came before.
		}
		String all = new String(text.toByteArray(), StandardCharsets.UTF_8);
		String whole = all.substring(0, all.lastIndexOf('\n') + 1);
		return whole.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(whole.split("\n")));
	}
}
