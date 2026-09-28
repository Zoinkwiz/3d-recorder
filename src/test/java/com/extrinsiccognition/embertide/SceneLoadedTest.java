package com.extrinsiccognition.embertide;

import com.google.gson.JsonObject;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SceneLoadedTest
{
	private static final int BASE_X = 3176;
	private static final int BASE_Y = 3168;

	@Test
	public void writesTheWholeRadiusInTheFirstTick()
	{
		Rows rows = new Rows();
		OsrsCapture capture = capture(rows, 12, 1);
		capture.start();
		assertTrue(capture.tick(true));
		int tiles = discTiles(12);
		assertEquals(2 * tiles, count(rows, "mccr.spatial_snapshot", "cells"));
		assertEquals(tiles, count(rows, "osrs.object", "objects"));
		JsonObject last = rows.list.get(rows.list.size() - 1);
		assertEquals("osrs.scene_loaded", last.get("type").getAsString());
		JsonObject marker = payload(last);
		assertEquals(42, marker.get("tick").getAsInt());
		assertEquals(BASE_X, marker.get("base_x").getAsInt());
		assertEquals(new WorldPoint(3222, 3218, 0).getRegionID(), marker.get("region").getAsInt());
		assertEquals(2 * tiles, marker.getAsJsonObject("counts").get("cells").getAsInt());
		assertEquals(tiles, marker.getAsJsonObject("counts").get("objects").getAsInt());

		int before = rows.list.size();
		capture.tick(false);
		assertEquals(0, rows.list.subList(before, rows.list.size()).stream()
			.filter(row -> row.get("type").getAsString().equals("osrs.scene_loaded")).count());
	}

	@Test
	public void writesGameStateRows()
	{
		Rows rows = new Rows();
		OsrsCapture capture = capture(rows, 4, 1);
		capture.start();
		capture.gameState("LOADING");
		assertTrue(rows.list.isEmpty());
		capture.tick(true);
		capture.gameState("HOPPING");
		JsonObject last = rows.list.get(rows.list.size() - 1);
		assertEquals("osrs.game_state", last.get("type").getAsString());
		assertEquals("HOPPING", payload(last).get("state").getAsString());
	}

	/** Cost of the plugin's own work for a dense first tick; prints, does not assert a time. */
	@Test
	public void firstTickCost()
	{
		for (int round = 0; round < 10; round++)
		{
			int radius = round % 2 == 0 ? 12 : 26;
			Rows rows = new Rows();
			OsrsCapture capture = capture(rows, radius, radius == 12 ? 1 : 4);
			capture.start();
			long began = System.nanoTime();
			capture.tick(true);
			long spent = System.nanoTime() - began;
			System.out.printf("first tick, radius %d, %d objects, %d cells, %d bytes: %.1f ms%n",
				radius, count(rows, "osrs.object", "objects"), count(rows, "mccr.spatial_snapshot", "cells"), rows.bytes, spent / 1e6);
		}
	}

	private static JsonObject payload(JsonObject row)
	{
		return row.has("payload") ? row.getAsJsonObject("payload") : row;
	}

	private static int count(Rows rows, String type, String field)
	{
		int total = 0;
		for (JsonObject row : rows.list)
		{
			if (row.get("type").getAsString().equals(type)) { total += payload(row).getAsJsonArray(field).size(); }
		}
		return total;
	}

	private static int discTiles(int radius)
	{
		int n = 0;
		for (int dx = -radius; dx <= radius; dx++) { for (int dy = -radius; dy <= radius; dy++) { if (dx * dx + dy * dy <= radius * radius) { n++; } } }
		return n;
	}

	private static OsrsCapture capture(Rows rows, int radius, int objectsPerTile)
	{
		Tile[][][] tiles = new Tile[4][Constants.SCENE_SIZE][Constants.SCENE_SIZE];
		for (int x = 0; x < Constants.SCENE_SIZE; x++)
		{
			for (int y = 0; y < Constants.SCENE_SIZE; y++)
			{
				int id = 1000 + x * Constants.SCENE_SIZE + y;
				Map<String, Object> answers = new HashMap<>();
				answers.put("getGameObjects", new GameObject[]{fake(GameObject.class, Map.of("getId", id, "getConfig", 10 << 0))});
				if (objectsPerTile > 1)
				{
					answers.put("getWallObject", fake(WallObject.class, Map.of("getId", id + 1)));
					answers.put("getGroundObject", fake(GroundObject.class, Map.of("getId", id + 2)));
					answers.put("getDecorativeObject", fake(DecorativeObject.class, Map.of("getId", id + 3)));
				}
				tiles[0][x][y] = fake(Tile.class, answers);
			}
		}
		IndexedObjectSet<?> empty = fake(IndexedObjectSet.class, Map.of("iterator", Collections.emptyIterator()));
		Scene scene = fake(Scene.class, Map.of("getTiles", tiles,
			"getOverlayIds", new short[4][Constants.SCENE_SIZE][Constants.SCENE_SIZE],
			"getUnderlayIds", new short[4][Constants.SCENE_SIZE][Constants.SCENE_SIZE]));
		WorldView view = fake(WorldView.class, Map.of("getScene", scene, "getBaseX", BASE_X, "getBaseY", BASE_Y,
			"getMapRegions", new int[]{12850}, "getTileHeights", new int[4][Constants.SCENE_SIZE + 1][Constants.SCENE_SIZE + 1],
			"npcs", empty, "players", empty));
		Player me = fake(Player.class, Map.of("getWorldLocation", new WorldPoint(3222, 3218, 0), "getName", "Tester"));
		Client client = fake(Client.class, Map.of("getTopLevelWorldView", view, "getLocalPlayer", me, "getTickCount", 42));
		EmbertideConfig config = fake(EmbertideConfig.class, Map.of("captureRadius", radius));
		return new OsrsCapture(client, rows, config, "osrs:surface", "Tester", 301);
	}

	@SuppressWarnings("unchecked")
	private static <T> T fake(Class<T> type, Map<String, Object> answers)
	{
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) ->
		{
			if (answers.containsKey(method.getName())) { return answers.get(method.getName()); }
			if (method.getName().equals("hashCode")) { return System.identityHashCode(proxy); }
			if (method.getName().equals("equals")) { return proxy == args[0]; }
			if (method.isDefault()) { return invokeDefault(type, proxy, method, args); }
			Class<?> r = method.getReturnType();
			if (r == boolean.class) { return false; }
			if (r == int.class) { return 0; }
			if (r == long.class) { return 0L; }
			if (r == double.class) { return 0d; }
			if (r == float.class) { return 0f; }
			if (r == short.class) { return (short) 0; }
			if (r == byte.class) { return (byte) 0; }
			if (r == char.class) { return (char) 0; }
			return null;
		});
	}

	private static Object invokeDefault(Class<?> type, Object proxy, Method method, Object[] args) throws Throwable
	{
		return MethodHandles.privateLookupIn(type, MethodHandles.lookup()).unreflectSpecial(method, type)
			.bindTo(proxy).invokeWithArguments(args == null ? new Object[0] : args);
	}

	private static final class Rows implements Recorder
	{
		final List<JsonObject> list = new ArrayList<>();
		final com.google.gson.Gson gson = new com.google.gson.Gson();
		long bytes;

		@Override public State state() { return State.READY; }
		@Override public String error() { return null; }
		@Override public String captureId() { return "test"; }
		@Override public CompletableFuture<Void> open(JsonObject header) { return CompletableFuture.completedFuture(null); }
		@Override public boolean enqueue(JsonObject record, boolean control)
		{
			bytes += gson.toJson(record).length() + 1;
			list.add(record);
			return true;
		}
		@Override public CompletableFuture<Void> flush() { return CompletableFuture.completedFuture(null); }
		@Override public CompletableFuture<Void> finish() { return CompletableFuture.completedFuture(null); }
		@Override public CompletableFuture<Void> openStudio() { return CompletableFuture.completedFuture(null); }
		@Override public String destination() { return ""; }
	}
}
