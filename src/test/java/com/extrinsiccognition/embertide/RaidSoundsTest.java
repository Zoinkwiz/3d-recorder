package com.extrinsiccognition.embertide;

import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import net.runelite.api.Actor;
import net.runelite.api.events.AreaSoundEffectPlayed;
import net.runelite.api.events.SoundEffectPlayed;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The sounds RuneLite reports in a raid reach the recording, through the plugin's own subscribers. */
public class RaidSoundsTest
{
	@Test
	public void everySoundRuneLiteReportsIsRecorded() throws Exception
	{
		RaidScene scene = new RaidScene(30, 11);
		Rows rows = new Rows();
		OsrsCapture capture = scene.capture(rows);
		capture.start();
		capture.tick(true);
		scene.serverTick();
		capture.tick(false);
		EmbertidePlugin plugin = plugin(scene, capture);
		RaidScene.Body boss = scene.bodies.get(0);

		SoundEffectPlayed own = new SoundEffectPlayed(scene.me());
		own.setSoundId(2508);
		own.setDelay(10);
		plugin.onSoundEffectPlayed(own);
		plugin.onAreaSoundEffectPlayed(area(boss.actor, 3871, boss.x - RaidScene.BASE_X, boss.y - RaidScene.BASE_Y, 10));
		plugin.onAreaSoundEffectPlayed(area(null, 1930, 40, 52, 6));

		List<JsonObject> sounds = rows.of("osrs.sound");
		assertEquals(3, sounds.size());
		JsonObject mine = payload(sounds.get(0));
		assertEquals(2508, mine.get("sound").getAsInt());
		assertEquals(10, mine.get("delay").getAsInt());
		assertEquals(OsrsCapture.PLAYER, mine.get("source").getAsString());
		assertFalse(mine.has("at"));

		JsonObject bosses = payload(sounds.get(1));
		assertEquals(3871, bosses.get("sound").getAsInt());
		String bossId = bosses.get("source").getAsString();
		assertTrue(bossId, bossId.startsWith("npc:") && bossId.endsWith("#9000"));
		assertEquals(bossId, sounds.get(1).get("user_id").getAsString());
		assertEquals("[" + boss.x + "," + boss.y + ",0]", bosses.get("at").toString());
		assertEquals(10, bosses.get("range").getAsInt());

		JsonObject sourceless = payload(sounds.get(2));
		assertFalse(sourceless.has("source"));
		assertEquals(OsrsCapture.PLAYER, sounds.get(2).get("user_id").getAsString());
		assertEquals("[" + (RaidScene.BASE_X + 40) + "," + (RaidScene.BASE_Y + 52) + ",0]", sourceless.get("at").toString());
	}

	@Test
	public void aRecordingSaysWhenTheGameWasMuted()
	{
		RaidScene scene = new RaidScene(8, 3);
		Rows rows = new Rows();
		OsrsCapture capture = scene.capture(rows);
		capture.start();
		capture.tick(true);
		for (int i = 0; i < 3; i++)
		{
			scene.serverTick();
			capture.tick(false);
		}
		scene.masterVolume = 0;
		scene.serverTick();
		capture.tick(false);
		scene.serverTick();
		capture.tick(false);
		scene.areaSoundVolume = 60;
		scene.masterVolume = 70;
		scene.serverTick();
		capture.tick(false);

		assertEquals(List.of("100/45/25", "0/45/25", "70/45/60"), volumes(rows));
		assertTrue(rows.header.getAsJsonObject("osrs").get("records").getAsString().contains("osrs.audio {master, sounds, area_sounds}"));
	}

	@Test
	public void masterIsTheVolumeTheClientApplies()
	{
		RaidScene scene = new RaidScene(8, 3);
		scene.savedMasterVolume = 0;
		Rows rows = new Rows();
		OsrsCapture capture = scene.capture(rows);
		capture.start();
		capture.tick(true);
		scene.masterVolume = 0;
		scene.serverTick();
		capture.tick(false);
		assertEquals(List.of("100/45/25", "0/45/25"), volumes(rows));
	}

	private static List<String> volumes(Rows rows)
	{
		return rows.of("osrs.audio").stream().map(row ->
		{
			JsonObject p = payload(row);
			return p.get("master").getAsInt() + "/" + p.get("sounds").getAsInt() + "/" + p.get("area_sounds").getAsInt();
		}).collect(Collectors.toList());
	}

	private static AreaSoundEffectPlayed area(Actor source, int sound, int sceneX, int sceneY, int range)
	{
		AreaSoundEffectPlayed event = new AreaSoundEffectPlayed(source);
		event.setSoundId(sound);
		event.setSceneX(sceneX);
		event.setSceneY(sceneY);
		event.setRange(range);
		return event;
	}

	private static EmbertidePlugin plugin(RaidScene scene, OsrsCapture capture) throws Exception
	{
		EmbertidePlugin plugin = new EmbertidePlugin();
		set(plugin, "client", scene.client);
		set(plugin, "capture", capture);
		set(plugin, "rediscover", false);
		return plugin;
	}

	private static void set(Object target, String name, Object value) throws Exception
	{
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static JsonObject payload(JsonObject row)
	{
		return row.getAsJsonObject("payload");
	}

	private static final class Rows implements Recorder
	{
		final List<JsonObject> list = new ArrayList<>();
		JsonObject header;

		List<JsonObject> of(String type)
		{
			return list.stream().filter(row -> row.get("type").getAsString().equals(type)).collect(Collectors.toList());
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
