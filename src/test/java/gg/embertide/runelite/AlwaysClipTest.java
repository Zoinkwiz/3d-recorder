package gg.embertide.runelite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;

public class AlwaysClipTest
{
	private static final ClipTriggers.Wanted ALL = new ClipTriggers.Wanted(true, true, 1_000_000, true, true, true, true);
	private static final ClipTriggers.Wanted NONE = new ClipTriggers.Wanted(false, false, 1_000_000, false, false, false, false);

	private static JsonObject row(String type, JsonObject payload)
	{
		JsonObject row = new JsonObject();
		row.addProperty("type", type);
		row.add("payload", payload);
		return row;
	}

	private static JsonObject payload(Object... pairs)
	{
		JsonObject payload = new JsonObject();
		for (int i = 0; i < pairs.length; i += 2)
		{
			Object value = pairs[i + 1];
			if (value instanceof Number) { payload.addProperty((String) pairs[i], (Number) value); }
			else if (value instanceof JsonObject) { payload.add((String) pairs[i], (JsonObject) value); }
			else { payload.addProperty((String) pairs[i], String.valueOf(value)); }
		}
		return payload;
	}

	@Test
	public void eachKindTheyTickedSetsOffAClip()
	{
		assertEquals("death", ClipTriggers.of(row("player.death", payload("cause", "Vorkath")), ALL).reason);
		assertEquals("Died to Vorkath", ClipTriggers.of(row("player.death", payload("cause", "Vorkath")), ALL).title);
		assertEquals("Died", ClipTriggers.of(row("player.death", payload("cause", "unknown")), ALL).title);
		ClipTriggers.Trigger drop = ClipTriggers.of(row("osrs.loot", payload("source_name", "Vorkath", "total_ge", 2_400_000)), ALL);
		assertEquals("drop", drop.reason);
		assertEquals("Vorkath drop, 2.4m gp", drop.title);
		assertEquals("Slayer level 99", ClipTriggers.of(row("advancement", payload("advancement_id", "osrs:level:slayer:99", "title", "Slayer level 99")), ALL).title);
		assertEquals("pet", ClipTriggers.of(row("osrs.game_message", payload("kind", "pet", "fields", payload("how", "follower"))), ALL).reason);
		assertEquals("Abyssal whip", ClipTriggers.of(row("osrs.game_message", payload("kind", "collection_log", "fields", payload("item", "Abyssal whip"))), ALL).title);
		assertEquals("Zulrah kill 512", ClipTriggers.of(row("osrs.game_message", payload("kind", "kill_count", "fields", payload("boss", "Zulrah", "count", 512))), ALL).title);
		assertEquals("clip_key", ClipTriggers.of(row("osrs.clip_mark", payload("look_back_s", 0)), ALL).reason);
	}

	@Test
	public void nothingTheyLeftOffAndNothingSmall()
	{
		assertNull(ClipTriggers.of(row("player.death", payload()), NONE));
		assertNull(ClipTriggers.of(row("osrs.loot", payload("total_ge", 999_999)), ALL));
		assertNull(ClipTriggers.of(row("advancement", payload("advancement_id", "osrs:quest:x")), ALL));
		assertNull(ClipTriggers.of(row("osrs.game_message", payload("kind", "clue_complete")), ALL));
		assertNull(ClipTriggers.of(row("osrs.motion", payload()), ALL));
		assertFalse(NONE.any());
		// The clip key alone runs no captures: it only counts while something else is ticked.
		assertFalse(new ClipTriggers.Wanted(false, false, 1, false, false, false, true).any());
	}

	@Test
	public void coinsReadTheWayTheGameWritesThem()
	{
		assertEquals("900 gp", ClipTriggers.gp(900));
		assertEquals("350k gp", ClipTriggers.gp(350_000));
		assertEquals("1.2m gp", ClipTriggers.gp(1_234_567));
		assertEquals("31m gp", ClipTriggers.gp(31_200_000));
	}

	@Test
	public void oneCaptureAlwaysReachesBackFarEnough()
	{
		assertEquals(40, ClipBuffers.period(20), 0);
		assertEquals(20, ClipBuffers.period(5), 0);
		assertTrue(ClipBuffers.startSecond(0, 0, 40));
		assertFalse(ClipBuffers.startSecond(1, 19.9, 40));
		assertTrue(ClipBuffers.startSecond(1, 20, 40));
		assertFalse(ClipBuffers.startSecond(2, 30, 40));
		assertTrue(ClipBuffers.due(40, 40));
		// Two captures half a period apart, each started again at a period: the older is always half a period old or more.
		double period = ClipBuffers.period(20);
		for (double now = period; now < 10 * period; now += 0.6)
		{
			double a = now % period, b = (now + period / 2) % period;
			assertTrue(Math.max(a, b) >= period / 2 - 1e-9);
		}
	}

	@Test
	public void laterTriggersStretchAClipUpToItsLimit()
	{
		assertEquals(35, ClipBuffers.extended(0, 25, 30, 5), 1e-9);
		assertEquals(25, ClipBuffers.extended(0, 25, 10, 5), 1e-9);
		assertEquals(ClipBuffers.MAX_CLIP_S, ClipBuffers.extended(0, 25, 200, 5), 1e-9);
	}

	@Test
	public void aKeptBufferWritesWhatItHeldThenEverythingAfter()
	{
		List<JsonObject> watched = new ArrayList<>();
		ClipBuffer buffer = new ClipBuffer(watched::add);
		JsonObject header = new JsonObject();
		header.addProperty("schema", "mccr");
		header.add("session", payload("title", "Old School RuneScape memory"));
		buffer.open(header);
		for (int i = 0; i < 3; i++) { assertTrue(buffer.enqueue(row("osrs.motion", payload("i", i)), false)); }
		assertEquals(3, watched.size());
		Captured file = new Captured();
		JsonObject clip = payload("reason", "death", "title", "Died to Vorkath");
		assertTrue(buffer.keep(file, clip));
		assertTrue(buffer.kept());
		assertEquals("death", file.header.getAsJsonObject("clip").get("reason").getAsString());
		assertEquals("Died to Vorkath", file.header.getAsJsonObject("session").get("title").getAsString());
		assertEquals("Old School RuneScape memory", header.getAsJsonObject("session").get("title").getAsString());
		assertFalse(header.has("clip"));
		assertEquals(3, file.rows.size());
		assertTrue(buffer.enqueue(row("player.death", payload()), false));
		assertEquals(4, file.rows.size());
		assertEquals(4, watched.size());
		assertFalse(buffer.keep(new Captured(), clip));
		buffer.finish();
		assertTrue(file.finished);
	}

	@Test
	public void aBufferNeverKeptLeavesNothing()
	{
		ClipBuffer buffer = new ClipBuffer(null);
		buffer.open(new JsonObject());
		buffer.enqueue(row("osrs.motion", payload()), false);
		buffer.finish();
		assertEquals(0, buffer.size());
		assertEquals(Recorder.State.COMPLETE, buffer.state());
		assertFalse(buffer.keep(new Captured(), new JsonObject()));
	}

	@Test
	public void aFullBufferRefusesRowsButNotItsEnd()
	{
		ClipBuffer buffer = new ClipBuffer(null);
		buffer.open(new JsonObject());
		for (int i = 1; i < ClipBuffer.MAX_ROWS; i++) { buffer.enqueue(new JsonObject(), false); }
		assertFalse(buffer.enqueue(new JsonObject(), false));
		assertTrue(buffer.enqueue(row("mccr.capture_end", new JsonObject()), true));
	}

	/** A file recorder that keeps what it is given. */
	private static final class Captured implements Recorder
	{
		JsonObject header;
		final JsonArray rows = new JsonArray();
		boolean finished;

		@Override public State state() { return finished ? State.COMPLETE : State.READY; }
		@Override public String error() { return ""; }
		@Override public String captureId() { return "file"; }
		@Override public CompletableFuture<Void> open(JsonObject header) { this.header = header; return CompletableFuture.completedFuture(null); }
		@Override public boolean enqueue(JsonObject record, boolean control) { rows.add(record); return true; }
		@Override public CompletableFuture<Void> flush() { return CompletableFuture.completedFuture(null); }
		@Override public CompletableFuture<Void> finish() { finished = true; return CompletableFuture.completedFuture(null); }
	}
}
