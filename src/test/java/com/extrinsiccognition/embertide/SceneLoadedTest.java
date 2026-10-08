package com.extrinsiccognition.embertide;

import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Constants;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.WallObject;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static com.extrinsiccognition.embertide.Fakes.fake;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SceneLoadedTest
{
	private static final int BASE_X = 3176;
	private static final int BASE_Y = 3168;

	@Test
	public void writesTheWholeSceneInTheFirstTick()
	{
		Fakes.Rows rows = new Fakes.Rows();
		OsrsCapture capture = capture(rows, 12, 1);
		capture.start();
		assertTrue(capture.tick(true));
		int tiles = Constants.SCENE_SIZE * Constants.SCENE_SIZE;
		assertEquals(2 * discTiles(12), count(rows, "mccr.spatial_snapshot", "cells"));
		assertEquals(tiles, count(rows, "osrs.object", "objects"));
		JsonObject last = rows.list.get(rows.list.size() - 1);
		assertEquals("osrs.scene_loaded", last.get("type").getAsString());
		JsonObject marker = payload(last);
		assertEquals(42, marker.get("tick").getAsInt());
		assertEquals(BASE_X, marker.get("base_x").getAsInt());
		assertEquals("scene", marker.get("scope").getAsString());
		assertEquals(4, marker.getAsJsonArray("planes").size());
		assertEquals(new WorldPoint(3222, 3218, 0).getRegionID(), marker.get("region").getAsInt());
		assertEquals(2 * discTiles(12), marker.getAsJsonObject("counts").get("cells").getAsInt());
		assertEquals(tiles, marker.getAsJsonObject("counts").get("objects").getAsInt());

		int before = rows.list.size();
		capture.tick(false);
		assertEquals(0, rows.list.subList(before, rows.list.size()).stream()
			.filter(row -> row.get("type").getAsString().equals("osrs.scene_loaded")).count());
	}

	@Test
	public void aReloadWritesOnlyWhatChanged()
	{
		Map<String, Object> door = new HashMap<>(Map.of("getId", 5, "getConfig", 0));
		Tile[][][] tiles = tiles(1);
		Fakes.Rows rows = new Fakes.Rows();
		OsrsCapture capture = capture(rows, 4, tiles);
		tiles[2][60][70] = fake(Tile.class, Map.of("getWallObject", fake(WallObject.class, door)));
		capture.start();
		capture.tick(true);
		int before = rows.list.size();
		capture.tick(true);
		assertEquals(0, count(rows.list.subList(before, rows.list.size()), "osrs.object", "objects"));

		door.put("getConfig", 1 << 6);
		tiles[2][60][70] = fake(Tile.class, Map.of("getWallObject", fake(WallObject.class, door)));
		before = rows.list.size();
		capture.tick(true);
		List<JsonObject> reload = rows.list.subList(before, rows.list.size());
		JsonObject gone = payload(reload.stream().filter(row -> row.get("type").getAsString().equals("osrs.object")).findFirst().get());
		assertEquals("despawned", gone.get("scope").getAsString());
		assertEquals(0, gone.getAsJsonArray("objects").get(0).getAsJsonObject().get("orientation").getAsInt());
		assertEquals(1, count(reload, "osrs.object", "objects") - 1);
		JsonObject marker = payload(reload.get(reload.size() - 1));
		assertEquals(1, marker.getAsJsonObject("counts").get("objects").getAsInt());
		assertEquals(1, marker.getAsJsonObject("counts").get("removed").getAsInt());
	}

	@Test
	public void writesTransitionsAnywhereInTheSceneOnce()
	{
		Fakes.Rows rows = new Fakes.Rows();
		OsrsCapture capture = capture(rows, 4, 1);
		capture.start();
		capture.tick(true);
		Tile far = fake(Tile.class, Map.of("getWorldLocation", new WorldPoint(BASE_X + 100, BASE_Y + 3, 2)));
		GameObject fire = fake(GameObject.class, Map.of("getId", 26185, "getConfig", 10));
		int before = rows.list.size();
		capture.objectChanged(far, fire, true);
		capture.objectChanged(far, fire, true);
		capture.objectChanged(far, fire, false);
		capture.objectChanged(far, fire, false);
		List<JsonObject> written = rows.list.subList(before, rows.list.size());
		assertEquals(2, written.size());
		assertEquals("spawned", payload(written.get(0)).get("scope").getAsString());
		assertEquals("despawned", payload(written.get(1)).get("scope").getAsString());
		assertEquals(2, payload(written.get(0)).getAsJsonArray("objects").get(0).getAsJsonObject().get("plane").getAsInt());
	}

	@Test
	public void writesTheScenesGroundItems()
	{
		Tile[][][] tiles = tiles(0);
		TileItem bones = fake(TileItem.class, Map.of("getId", 526, "getQuantity", 1));
		tiles[0][10][20] = fake(Tile.class, Map.of("getGroundItems", List.of(bones)));
		Fakes.Rows rows = new Fakes.Rows();
		OsrsCapture capture = capture(rows, 4, tiles);
		capture.start();
		capture.tick(true);
		JsonObject items = payload(rows.list.stream().filter(row -> row.get("type").getAsString().equals("osrs.ground_items")).findFirst().get());
		assertEquals("scene", items.get("scope").getAsString());
		assertEquals(BASE_X, items.get("base_x").getAsInt());
		assertEquals("[" + (BASE_X + 10) + "," + (BASE_Y + 20) + ",0,526,1]", items.getAsJsonArray("items").get(0).toString());
	}

	@Test
	public void writesGameStateRows()
	{
		Fakes.Rows rows = new Fakes.Rows();
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

	private static JsonObject payload(JsonObject row)
	{
		return row.has("payload") ? row.getAsJsonObject("payload") : row;
	}

	private static int count(Fakes.Rows rows, String type, String field)
	{
		return count(rows.list, type, field);
	}

	private static int count(List<JsonObject> list, String type, String field)
	{
		int total = 0;
		for (JsonObject row : list)
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

	private static OsrsCapture capture(Fakes.Rows rows, int radius, int objectsPerTile)
	{
		return capture(rows, radius, tiles(objectsPerTile));
	}

	/** Plane 0 filled, one game object a tile (or all four kinds), the upper planes empty. */
	private static Tile[][][] tiles(int objectsPerTile)
	{
		Tile[][][] tiles = new Tile[4][Constants.SCENE_SIZE][Constants.SCENE_SIZE];
		for (int x = 0; x < Constants.SCENE_SIZE && objectsPerTile > 0; x++)
		{
			for (int y = 0; y < Constants.SCENE_SIZE; y++)
			{
				tiles[0][x][y] = tile(1000 + x * Constants.SCENE_SIZE + y, objectsPerTile);
			}
		}
		return tiles;
	}

	private static Tile tile(int id, int objectsPerTile)
	{
		Map<String, Object> answers = new HashMap<>();
		answers.put("getGameObjects", new GameObject[]{fake(GameObject.class, Map.of("getId", id, "getConfig", 10 << 0))});
		if (objectsPerTile > 1)
		{
			answers.put("getWallObject", fake(WallObject.class, Map.of("getId", id + 1)));
			answers.put("getGroundObject", fake(GroundObject.class, Map.of("getId", id + 2)));
			answers.put("getDecorativeObject", fake(DecorativeObject.class, Map.of("getId", id + 3)));
		}
		return fake(Tile.class, answers);
	}

	private static OsrsCapture capture(Recorder rows, int radius, Tile[][][] tiles)
	{
		return Fakes.capture(rows, Fakes.view(BASE_X, BASE_Y, tiles, new int[4][Constants.SCENE_SIZE + 1][Constants.SCENE_SIZE + 1], null), radius);
	}
}
