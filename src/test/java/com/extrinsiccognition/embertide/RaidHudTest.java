package com.extrinsiccognition.embertide;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;
import net.runelite.api.HeadIcon;
import net.runelite.api.SkullIcon;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Head icons and the client's HP bar in a raid: written when they change, and nothing else moves. */
public class RaidHudTest
{
	private static final Gson GSON = new Gson();
	private static final String SCRIPTED = "1274:5f18a30f142a6ef1351edb54644c44bc41cc94ff8c7e75b65fc0bdc42c05c06c";

	@Test
	public void headIconsAreWrittenWhenTheyChange()
	{
		RaidScene scene = new RaidScene(30, 11);
		Rows rows = new Rows();
		OsrsCapture capture = started(scene, rows);
		tick(scene, capture, 3);
		assertEquals(0, rows.of("osrs.head_icons").size());

		RaidScene.Body boss = scene.bodies.get(0), raider = scene.bodies.get(1);
		scene.self.prayer = HeadIcon.MAGIC;
		raider.prayer = HeadIcon.MELEE;
		raider.skull = SkullIcon.SKULL;
		boss.iconArchives = new int[]{-1, 440};
		boss.iconSprites = new short[]{-1, 2};
		tick(scene, capture, 1);
		List<JsonObject> written = rows.of("osrs.head_icons");
		assertEquals(1, written.size());
		JsonArray icons = written.get(0).getAsJsonObject("payload").getAsJsonArray("icons");
		String bossId = rows.idOf(9000), raiderId = icons.get(2).getAsJsonArray().get(0).getAsString();
		assertEquals("[[\"player\",2,-1],[\"" + bossId + "\",-1,-1,440,2],[\"" + raiderId + "\",0,0]]", icons.toString());
		assertTrue(raiderId, raiderId.startsWith("player:adventurer-"));

		// The same icons in other slots, then no change at all: nothing written.
		boss.iconArchives = new int[]{440, -1, -1};
		boss.iconSprites = new short[]{2, -1, -1};
		tick(scene, capture, 5);
		assertEquals(1, rows.of("osrs.head_icons").size());

		boss.iconArchives = null;
		boss.iconSprites = null;
		scene.self.prayer = HeadIcon.RANGED;
		tick(scene, capture, 2);
		written = rows.of("osrs.head_icons");
		assertEquals(2, written.size());
		assertEquals("[[\"player\",1,-1],[\"" + bossId + "\",-1,-1]]", written.get(1).getAsJsonObject("payload").getAsJsonArray("icons").toString());
	}

	@Test
	public void theHpBarIsWrittenWhenItChanges()
	{
		RaidScene scene = new RaidScene(30, 11);
		Rows rows = new Rows();
		OsrsCapture capture = started(scene, rows);
		tick(scene, capture, 3);
		assertEquals(0, rows.of("osrs.boss_hp").size());

		scene.hpBarShown = true;
		scene.hpBarNpc = 14800;
		scene.hpBarHp = 640;
		scene.hpBarMax = 640;
		scene.hpBarBoss = 1;
		scene.hpBarMarkers[0] = 321;
		tick(scene, capture, 4);
		scene.hpBarHp = 598;
		tick(scene, capture, 1);
		scene.hpBarShown = false;
		tick(scene, capture, 3);

		List<JsonObject> written = rows.of("osrs.boss_hp");
		assertEquals(3, written.size());
		String bossId = rows.idOf(9000);
		assertEquals("{\"shown\":true,\"npc_id\":14800,\"hp\":640,\"max\":640,\"boss\":true,\"markers\":[321,0,0,0],\"actor\":\"" + bossId + "\"}",
			withoutDimension(written.get(0)));
		assertEquals(bossId, written.get(0).get("user_id").getAsString());
		assertEquals(598, written.get(1).getAsJsonObject("payload").get("hp").getAsInt());
		JsonObject hidden = written.get(2).getAsJsonObject("payload");
		assertFalse(hidden.get("shown").getAsBoolean());
		assertFalse(hidden.has("actor"));
		assertEquals(OsrsCapture.PLAYER, written.get(2).get("user_id").getAsString());
	}

	@Test
	public void theBarNamesAnActorOnlyWhenItCanTellWhich()
	{
		RaidScene scene = new RaidScene(30, 11);
		Rows rows = new Rows();
		OsrsCapture capture = started(scene, rows);
		scene.hpBarShown = true;
		scene.hpBarMax = 120;
		scene.hpBarHp = 120;
		// The boss is the only one of its type; the adds come in two types of eleven each.
		scene.hpBarNpc = 14800;
		untargeted(scene, capture);
		scene.hpBarNpc = 14811;
		untargeted(scene, capture);
		scene.hpBarNpc = -1;
		untargeted(scene, capture);

		List<JsonObject> written = rows.of("osrs.boss_hp");
		assertEquals(3, written.size());
		assertEquals(rows.idOf(9000), written.get(0).getAsJsonObject("payload").get("actor").getAsString());
		assertFalse(written.get(1).getAsJsonObject("payload").has("actor"));
		assertFalse(written.get(2).getAsJsonObject("payload").has("actor"));
		assertEquals(-1, written.get(2).getAsJsonObject("payload").get("npc_id").getAsInt());
	}

	@Test
	public void theBarNamesThePlayersTargetAmongOthersOfItsType()
	{
		RaidScene scene = new RaidScene(30, 11);
		Rows rows = new Rows();
		OsrsCapture capture = started(scene, rows);
		RaidScene.Body wisp = scene.bodies.get(9);
		scene.hpBarShown = true;
		scene.hpBarNpc = wisp.npcId;
		scene.hpBarMax = 92;
		scene.hpBarHp = 92;
		scene.serverTick();
		scene.self.interacting = wisp;
		capture.tick(false);

		List<JsonObject> written = rows.of("osrs.boss_hp");
		assertEquals(1, written.size());
		assertEquals(14811, written.get(0).getAsJsonObject("payload").get("npc_id").getAsInt());
		assertEquals(rows.idOf(9009), written.get(0).getAsJsonObject("payload").get("actor").getAsString());
	}

	@Test
	public void headIconsHoldAcrossASceneChange()
	{
		RaidScene scene = new RaidScene(30, 11);
		Rows rows = new Rows();
		OsrsCapture capture = started(scene, rows);
		scene.self.prayer = HeadIcon.MAGIC;
		scene.bodies.get(0).iconArchives = new int[]{440};
		scene.bodies.get(0).iconSprites = new short[]{2};
		tick(scene, capture, 2);
		assertEquals(1, rows.of("osrs.head_icons").size());

		int scenes = rows.of("mccr.scene").size();
		capture.sceneChanged(scene.view);
		tick(scene, capture, 3);
		assertEquals(scenes + 1, rows.of("mccr.scene").size());
		assertEquals(1, rows.of("osrs.head_icons").size());

		scene.self.prayer = null;
		tick(scene, capture, 1);
		List<JsonObject> written = rows.of("osrs.head_icons");
		assertEquals(2, written.size());
		assertEquals("[[\"player\",-1,-1]]", written.get(1).getAsJsonObject("payload").getAsJsonArray("icons").toString());
	}

	@Test
	public void prayerIconsAreTheirSpriteFrames()
	{
		String[] frames = {"MELEE", "RANGED", "MAGIC", "RETRIBUTION", "SMITE", "REDEMPTION", "RANGE_MAGE", "RANGE_MELEE",
			"MAGE_MELEE", "RANGE_MAGE_MELEE", "WRATH", "SOUL_SPLIT", "DEFLECT_MELEE", "DEFLECT_RANGE", "DEFLECT_MAGE"};
		for (int frame = 0; frame < frames.length; frame++)
		{
			assertEquals(frames[frame], frame, OsrsCapture.prayerFrame(HeadIcon.valueOf(frames[frame])));
		}
		assertEquals(frames.length, HeadIcon.values().length);
		assertEquals(-1, OsrsCapture.prayerFrame(null));
	}

	@Test
	public void aScriptedRaidChangesOnlyTheNewRows() throws Exception
	{
		List<String> plain = raid(tick -> { }, new RaidScene(30, 11));
		RaidScene scene = new RaidScene(30, 11);
		List<String> scripted = raid(tick ->
		{
			RaidScene.Body boss = scene.bodies.get(0);
			if (tick == 2)
			{
				scene.self.prayer = HeadIcon.MELEE;
				scene.bodies.get(3).prayer = HeadIcon.RANGED;
				scene.hpBarShown = true;
				scene.hpBarNpc = 14800;
				scene.hpBarMax = 2000;
				scene.hpBarHp = 2000;
				scene.hpBarBoss = 1;
				scene.hpBarMarkers[0] = 1001;
				scene.hpBarMarkers[1] = 501;
			}
			if (tick == 5)
			{
				boss.iconArchives = new int[]{440};
				boss.iconSprites = new short[]{0};
				scene.self.prayer = HeadIcon.MAGIC;
			}
			if (tick >= 3 && tick <= 8) { scene.hpBarHp -= 150; }
			if (tick == 9) { scene.bodies.get(3).prayer = null; }
			if (tick == 11) { scene.hpBarShown = false; }
		}, scene);

		List<String> rest = scripted.stream()
			.filter(row -> !row.contains("\"type\":\"osrs.head_icons\"") && !row.contains("\"type\":\"osrs.boss_hp\""))
			.map(RaidHudTest::withoutSerial).collect(Collectors.toList());
		assertEquals(plain.stream().map(RaidHudTest::withoutSerial).collect(Collectors.toList()), rest);
		assertEquals(3, scripted.stream().filter(row -> row.contains("\"type\":\"osrs.head_icons\"")).count());
		assertEquals(8, scripted.stream().filter(row -> row.contains("\"type\":\"osrs.boss_hp\"")).count());
		assertEquals(SCRIPTED, digest(scripted));
	}

	@Test
	public void theHeaderDescribesBothRows()
	{
		RaidScene scene = new RaidScene(1, 1);
		Rows rows = new Rows();
		started(scene, rows);
		String records = rows.header.getAsJsonObject("osrs").get("records").getAsString();
		assertTrue(records, records.contains("osrs.head_icons {icons [[actor, prayer, skull, archive, sprite, ...]]}"));
		assertTrue(records, records.contains("osrs.boss_hp {shown, npc_id, hp, max, boss, markers, actor}"));
	}

	/** The fixed raid of RaidRowsTest, with the scene changed before each game tick. */
	private static List<String> raid(IntConsumer beforeTick, RaidScene scene)
	{
		Rows rows = new Rows();
		OsrsCapture capture = scene.capture(rows);
		capture.start();
		capture.tick(true);
		capture.clientTick();
		for (int i = 0; i < 12; i++)
		{
			scene.serverTick();
			beforeTick.accept(i);
			capture.tick(false);
			scene.tickEvents(capture);
			for (int c = 0; c < RaidScene.CYCLES_PER_TICK; c++)
			{
				scene.clientCycle();
				capture.clientTick();
				scene.cycleEvents(capture);
			}
		}
		String id = capture.id();
		return rows.list.stream().map(row -> GSON.toJson(row).replace(id, "osrs-session")).collect(Collectors.toList());
	}

	private static OsrsCapture started(RaidScene scene, Rows rows)
	{
		OsrsCapture capture = scene.capture(rows);
		capture.start();
		capture.tick(true);
		return capture;
	}

	private static void tick(RaidScene scene, OsrsCapture capture, int ticks)
	{
		for (int i = 0; i < ticks; i++)
		{
			scene.serverTick();
			capture.tick(false);
		}
	}

	private static void untargeted(RaidScene scene, OsrsCapture capture)
	{
		scene.serverTick();
		scene.self.interacting = null;
		capture.tick(false);
	}

	private static String withoutDimension(JsonObject row)
	{
		JsonObject payload = row.getAsJsonObject("payload").deepCopy();
		payload.remove("dimension");
		return payload.toString();
	}

	private static String withoutSerial(String row)
	{
		return row.replaceFirst("\"id\":\"osrs-session:r\\d+\"", "");
	}

	private static String digest(List<String> rows) throws Exception
	{
		MessageDigest sha = MessageDigest.getInstance("SHA-256");
		for (String row : rows) { sha.update((row + "\n").getBytes(StandardCharsets.UTF_8)); }
		StringBuilder hex = new StringBuilder(rows.size() + ":");
		for (byte b : sha.digest()) { hex.append(String.format("%02x", b)); }
		return hex.toString();
	}

	private static final class Rows implements Recorder
	{
		final List<JsonObject> list = new ArrayList<>();
		JsonObject header;

		List<JsonObject> of(String type)
		{
			return list.stream().filter(row -> row.get("type").getAsString().equals(type)).collect(Collectors.toList());
		}

		/** The recorded id of the NPC in client slot index. */
		String idOf(int index)
		{
			return of("presence").stream().map(row -> row.get("user_id").getAsString())
				.filter(id -> id.endsWith("#" + index)).findFirst().orElseThrow(AssertionError::new);
		}

		@Override public State state() { return State.READY; }
		@Override public String error() { return null; }
		@Override public String captureId() { return "test"; }
		@Override public CompletableFuture<Void> open(JsonObject header)
		{
			this.header = header;
			return CompletableFuture.completedFuture(null);
		}
		@Override public boolean enqueue(JsonObject record, boolean control)
		{
			list.add(record);
			return true;
		}
		@Override public CompletableFuture<Void> flush() { return CompletableFuture.completedFuture(null); }
		@Override public CompletableFuture<Void> finish() { return CompletableFuture.completedFuture(null); }
		@Override public CompletableFuture<Void> openStudio() { return CompletableFuture.completedFuture(null); }
		@Override public String destination() { return ""; }
	}
}
