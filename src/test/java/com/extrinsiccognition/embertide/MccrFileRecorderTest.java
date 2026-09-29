package com.extrinsiccognition.embertide;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
	public void aFailedSaveRetriesFromThePartial() throws Exception
	{
		MccrFileRecorder recorder = recorder();
		List<String> rows = record(recorder, 300);
		Thread.sleep(MccrFileRecorder.FLUSH_NANOS / 1_000_000 + 50);
		rows.addAll(record(recorder, 300, 300));
		Path blocker = dir.resolve(recorder.path().getFileName() + "");
		Files.createDirectory(blocker);
		try
		{
			recorder.finish().get(10, TimeUnit.SECONDS);
			throw new AssertionError("the save should fail");
		}
		catch (java.util.concurrent.ExecutionException expected)
		{
			// The sealed name is taken.
		}
		assertEquals(Recorder.State.FAILED, recorder.state());
		assertEquals(rows, lines(Files.readAllBytes(partials().get(0))));
		Files.delete(blocker);
		recorder.finish().get(10, TimeUnit.SECONDS);
		assertEquals(Recorder.State.COMPLETE, recorder.state());
		assertEquals(rows, lines(Files.readAllBytes(sealed())));
		assertEquals(0, partials().size());
	}

	@Test
	public void discardDeletesThePartial() throws Exception
	{
		MccrFileRecorder recorder = recorder();
		record(recorder, 100);
		Path blocker = dir.resolve(recorder.path().getFileName() + "");
		Files.createDirectory(blocker);
		try { recorder.finish().get(10, TimeUnit.SECONDS); }
		catch (java.util.concurrent.ExecutionException expected) { }
		recorder.discard();
		assertEquals(Recorder.State.COMPLETE, recorder.state());
		assertEquals(0, partials().size());
	}

	@Test
	public void theByteBudgetStillFillsWithoutHeldRows() throws Exception
	{
		MccrFileRecorder recorder = new MccrFileRecorder(gson, Filepath.Unchecked.getRooted(dir), () -> "osrs-test", worker,
			MccrFileRecorder.END_RESERVE + 4096);
		JsonObject header = new JsonObject();
		header.addProperty("type", "mccr.header");
		recorder.open(header).get();
		int accepted = 0;
		while (recorder.enqueue(row(accepted), false)) { accepted++; }
		assertTrue(recorder.full());
		assertTrue(accepted > 10);
		assertTrue(!recorder.enqueue(row(0), false));
		// Control rows may still use the end reserve.
		assertTrue(recorder.enqueue(row(0), true));
		recorder.finish().get(10, TimeUnit.SECONDS);
		assertEquals(accepted + 2, lines(Files.readAllBytes(sealed())).size());
	}

	@Test
	public void theRecordCapStillHolds() throws Exception
	{
		MccrFileRecorder recorder = recorder();
		JsonObject header = new JsonObject();
		header.addProperty("type", "mccr.header");
		recorder.open(header).get();
		JsonObject tiny = new JsonObject();
		int accepted = 1;
		while (recorder.enqueue(tiny, false)) { accepted++; }
		assertEquals(MccrFileRecorder.MAX_RECORDS, accepted);
		assertTrue(recorder.enqueue(tiny, true));
		assertTrue(!recorder.enqueue(tiny, true));
		recorder.finish().get(30, TimeUnit.SECONDS);
		assertEquals(MccrFileRecorder.MAX_RECORDS + 1, lines(Files.readAllBytes(sealed())).size());
	}

	private static JsonObject row(int i)
	{
		JsonObject row = new JsonObject();
		row.addProperty("type", "osrs.motion");
		row.addProperty("elapsed_s", i * 0.6);
		return row;
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

	@Test
	public void writesMotionAsThousandthDeltasThatExpandExactly() throws Exception
	{
		MccrFileRecorder recorder = recorder();
		JsonObject header = new JsonObject();
		header.addProperty("type", "mccr.header");
		JsonObject osrs = new JsonObject();
		osrs.addProperty("profile", "ec.mccr.osrs/5");
		header.add("osrs", osrs);
		recorder.open(header).get();
		assertTrue(!osrs.has("motion_xy"));
		List<JsonObject> sent = new ArrayList<>();
		for (int i = 0; i < 200; i++)
		{
			JsonArray samples = new JsonArray();
			samples.add(sample("player", 3222.5 + i * 0.0078125, 3218.063 - i * 0.047));
			if (i % 3 == 0)
			{
				samples.add(sample("npc:cow#" + (i % 2), 3200 + (i % 7) * 0.125, 3199.5));
			}
			JsonObject payload = new JsonObject();
			payload.addProperty("dimension", "osrs:surface");
			payload.add("samples", samples);
			JsonObject row = new JsonObject();
			row.addProperty("type", "osrs.motion");
			row.add("payload", payload);
			String before = gson.toJson(row);
			assertTrue(recorder.enqueue(row, false));
			assertEquals(before, gson.toJson(row));
			sent.add(row);
		}
		recorder.finish().get(10, TimeUnit.SECONDS);
		List<String> lines = lines(Files.readAllBytes(sealed()));
		JsonObject written = gson.fromJson(lines.get(0), JsonObject.class);
		assertEquals("delta-milli", written.getAsJsonObject("osrs").get("motion_xy").getAsString());
		Map<String, long[]> last = new HashMap<>();
		for (int r = 0; r < sent.size(); r++)
		{
			JsonArray got = gson.fromJson(lines.get(r + 1), JsonObject.class).getAsJsonObject("payload").getAsJsonArray("samples");
			JsonArray want = sent.get(r).getAsJsonObject("payload").getAsJsonArray("samples");
			assertEquals(want.size(), got.size());
			for (int k = 0; k < want.size(); k++)
			{
				JsonArray g = got.get(k).getAsJsonArray(), w = want.get(k).getAsJsonArray();
				String id = g.get(0).getAsString();
				long[] seen = last.get(id);
				long x = g.get(1).getAsLong() + (seen == null ? 0 : seen[0]), y = g.get(2).getAsLong() + (seen == null ? 0 : seen[1]);
				last.put(id, new long[]{x, y});
				assertEquals(w.get(1).getAsDouble(), x / 1000.0, 0.0);
				assertEquals(w.get(2).getAsDouble(), y / 1000.0, 0.0);
				for (int f = 3; f < w.size(); f++)
				{
					assertEquals(w.get(f), g.get(f));
				}
			}
		}
	}

	private static JsonArray sample(String id, double x, double y)
	{
		JsonArray sample = new JsonArray();
		sample.add(id);
		sample.add(Math.round(x * 1000.0) / 1000.0);
		sample.add(Math.round(y * 1000.0) / 1000.0);
		sample.add(0);
		sample.add(512);
		sample.add(-1);
		sample.add(-1);
		sample.add(808);
		return sample;
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
