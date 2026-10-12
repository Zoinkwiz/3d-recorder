package com.extrinsiccognition.embertide;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.DynamicObject;
import net.runelite.api.NPC;
import net.runelite.api.GameObject;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WallObject;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldEntityConfig;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static com.extrinsiccognition.embertide.Fakes.fake;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A Sailing boat's own scene and where it stands (3d-recorder#14). */
public class BoatRowsTest
{
	private static final int BASE_X = 3000, BASE_Y = 2600, SIZE_X = 8, SIZE_Y = 12;

	/** The map's local point under the boat scene's centre, and the boat turned a quarter anticlockwise. */
	private static final int MAIN_X = 5000, MAIN_Y = 6000;

	private final List<WorldEntity> entities = new ArrayList<>();
	private final Tile[][][] deck = new Tile[4][SIZE_X][SIZE_Y];
	/** The people the boat's own world view holds: its crew. */
	private final List<NPC> crew = new ArrayList<>();
	private final WorldView boatView = (WorldView) Proxy.newProxyInstance(WorldView.class.getClassLoader(), new Class<?>[]{WorldView.class}, (proxy, method, args) ->
	{
		switch (method.getName())
		{
			case "getId": return 7;
			case "getSizeX": return SIZE_X;
			case "getSizeY": return SIZE_Y;
			case "getBaseX": return 15296;
			case "getBaseY": return 3200;
			case "getScene": return fake(Scene.class, Map.of("getTiles", deck));
			case "getTileHeights": return new int[4][SIZE_X + 1][SIZE_Y + 1];
			case "npcs": return set(crew);
			case "players": return set(List.of());
			case "isTopLevel": return false;
			case "hashCode": return System.identityHashCode(proxy);
			case "equals": return proxy == args[0];
			default: return method.getReturnType() == int.class ? 0 : method.getReturnType() == boolean.class ? false : null;
		}
	});
	private final WorldEntity boat = fake(WorldEntity.class, Map.of("getWorldView", boatView,
		"getConfig", fake(WorldEntityConfig.class, Map.of("getId", 3))));
	private final Fakes.Rows rows = new Fakes.Rows();
	private final OsrsCapture capture;

	public BoatRowsTest()
	{
		deck[0][3][5] = fake(Tile.class, Map.of("getGameObjects", new GameObject[]{fake(GameObject.class, Map.of("getId", 59000, "getConfig", 10))}));
		Map<String, Object> answers = new HashMap<>(Map.of("getScene", fake(Scene.class, Map.of("getTiles", new Tile[4][Constants.SCENE_SIZE][Constants.SCENE_SIZE])),
			"getBaseX", BASE_X, "getBaseY", BASE_Y, "getMapRegions", new int[]{11823}, "getTileHeights", new int[4][Constants.SCENE_SIZE + 1][Constants.SCENE_SIZE + 1],
			"npcs", empty(), "players", empty(), "isTopLevel", true));
		answers.put("worldEntities", fake(IndexedObjectSet.class, Map.of()));
		answers.put("getSizeX", Constants.SCENE_SIZE);
		answers.put("getSizeY", Constants.SCENE_SIZE);
		WorldView top = (WorldView) Proxy.newProxyInstance(WorldView.class.getClassLoader(), new Class<?>[]{WorldView.class}, (proxy, method, args) ->
		{
			if (method.getName().equals("worldEntities")) { return set(entities); }
			if (method.getName().equals("hashCode")) { return System.identityHashCode(proxy); }
			if (method.getName().equals("equals")) { return proxy == args[0]; }
			Object answer = answers.get(method.getName());
			if (answer != null) { return answer; }
			return method.getReturnType() == int.class ? 0 : method.getReturnType() == boolean.class ? false : null;
		});
		Player me = fake(Player.class, Map.of("getWorldLocation", new WorldPoint(3039, 2646, 0), "getName", "Tester"));
		Client client = fake(Client.class, Map.of("getTopLevelWorldView", top, "getLocalPlayer", me, "getTickCount", 42,
			"getObjectDefinition", fake(ObjectComposition.class, Map.of())));
		capture = new OsrsCapture(client, rows, fake(EmbertideConfig.class, Map.of("captureRadius", 4, "trackNpcs", true)), "osrs:surface", "Tester", 301);
	}

	@Test
	public void landWritesNoBoatRows()
	{
		capture.start();
		capture.tick(true);
		capture.clientTick();
		assertTrue(of("osrs.boat").isEmpty());
		assertTrue(of("osrs.motion").stream().noneMatch(row -> payload(row).has("boats")));
	}

	@Test
	public void aBoatsSceneIsWrittenOnceInItsOwnTiles()
	{
		entities.add(sailing());
		capture.start();
		capture.tick(true);
		List<JsonObject> boatRows = of("osrs.boat");
		JsonObject scene = payload(boatRows.get(0));
		assertEquals("scene", scene.get("scope").getAsString());
		assertEquals("boat:7", scene.get("boat").getAsString());
		assertEquals(3, scene.get("config_id").getAsInt());
		assertEquals(SIZE_X, scene.get("size_x").getAsInt());
		assertEquals(SIZE_Y, scene.get("size_y").getAsInt());
		JsonObject hull = scene.getAsJsonArray("objects").get(0).getAsJsonObject();
		assertEquals(59000, hull.get("id").getAsInt());
		assertEquals(3, hull.get("x").getAsInt());
		assertEquals(5, hull.get("y").getAsInt());
		assertEquals(4, boatRows.stream().filter(row -> "ground".equals(payload(row).get("scope").getAsString())).count());
		JsonObject ground = payload(boatRows.get(1));
		assertEquals("boat:7", ground.get("boat").getAsString());
		assertEquals(SIZE_X, ground.get("columns").getAsInt());
		assertEquals(SIZE_Y, ground.getAsJsonArray("tiles").get(0).getAsJsonArray().size());
		// Nothing on the map at the boat's own far-off tiles.
		assertTrue(of("osrs.object").stream().flatMap(row -> stream(payload(row).getAsJsonArray("objects")))
			.noneMatch(object -> object.get("x").getAsInt() > 10_000));

		int before = rows.list.size();
		capture.tick(false);
		assertEquals(0, rows.list.subList(before, rows.list.size()).stream().filter(row -> type(row).equals("osrs.boat")).count());
	}

	@Test
	public void theBoatsPoseRidesTheMotionRowWhenItMoves()
	{
		entities.add(sailing());
		capture.start();
		capture.tick(true);
		capture.clientTick();
		JsonArray pose = boats().get(0).getAsJsonArray();
		assertEquals("boat:7", pose.get(0).getAsString());
		assertEquals(BASE_X + MAIN_X / 128.0, pose.get(1).getAsDouble(), 0.001);
		assertEquals(BASE_Y + MAIN_Y / 128.0, pose.get(2).getAsDouble(), 0.001);
		assertEquals(0, pose.get(3).getAsInt());
		// The boat's own +x points north on the map: a quarter turn anticlockwise.
		assertEquals(0.0, pose.get(4).getAsDouble(), 0.0);
		assertEquals(1.0, pose.get(5).getAsDouble(), 0.0);

		int before = rows.list.size();
		capture.clientTick();
		assertTrue(rows.list.subList(before, rows.list.size()).stream().noneMatch(row -> payload(row).has("boats")));
	}

	@Test
	public void somethingChangingOnDeckRewritesTheBoatNotTheMap()
	{
		entities.add(sailing());
		capture.start();
		capture.tick(true);
		WallObject rail = fake(WallObject.class, Map.of("getId", 59100, "getWorldView", boatView));
		deck[0][4][6] = fake(Tile.class, Map.of("getWallObject", rail));
		int before = rows.list.size();
		capture.objectChanged(deck[0][4][6], rail, true);
		capture.tick(false);
		List<JsonObject> after = rows.list.subList(before, rows.list.size());
		assertTrue(after.stream().noneMatch(row -> type(row).equals("osrs.object")));
		List<JsonObject> boatRows = after.stream().filter(row -> type(row).equals("osrs.boat")).collect(Collectors.toList());
		assertEquals(1, boatRows.size());
		assertEquals(2, payload(boatRows.get(0)).getAsJsonArray("objects").size());
	}

	@Test
	public void aBoatThatUnloadsIsGone()
	{
		entities.add(sailing());
		capture.start();
		capture.tick(true);
		entities.clear();
		entities.add(fake(WorldEntity.class, Map.of()));
		capture.tick(false);
		JsonObject last = payload(of("osrs.boat").get(of("osrs.boat").size() - 1));
		assertEquals("gone", last.get("scope").getAsString());
		assertEquals("boat:7", last.get("boat").getAsString());
		assertFalse(last.has("objects"));
	}

	@Test
	public void theCrewOnABoatAreInThePictureAfterASceneLoad()
	{
		// Sailor Jakob, standing on the deck at the boat scene's (5, 7): never spawned again by a scene load.
		crew.add(fake(NPC.class, Map.of("getName", "Sailor Jakob", "getIndex", 49129, "getId", 15200, "getWorldView", boatView,
			"getLocalLocation", new LocalPoint(5 * 128 + 64, 7 * 128 + 64, boatView), "getWorldLocation", new WorldPoint(15301, 3207, 1))));
		entities.add(sailing());
		capture.start();
		capture.tick(true);
		capture.tick(true);
		List<String> posed = new ArrayList<>();
		of("osrs.poses").forEach(row -> payload(row).getAsJsonArray("poses").forEach(pose -> posed.add(pose.getAsJsonArray().get(0).getAsString())));
		assertTrue(posed.toString(), posed.contains("npc:sailor jakob#49129"));
	}

	@Test
	public void aDeckObjectWritesWhatTheClientIsPlayingAndAChangeRewritesTheBoat()
	{
		int[] playing = {13322};
		net.runelite.api.Animation fire = (net.runelite.api.Animation) Proxy.newProxyInstance(net.runelite.api.Animation.class.getClassLoader(),
			new Class<?>[]{net.runelite.api.Animation.class}, (proxy, method, args) -> method.getName().equals("getId") ? playing[0] : null);
		DynamicObject cannon = fake(DynamicObject.class, Map.of("getAnimation", fire));
		deck[1][2][7] = fake(Tile.class, Map.of("getGameObjects", new GameObject[]{fake(GameObject.class, Map.of("getId", 59452, "getConfig", 10, "getRenderable", cannon))}));
		entities.add(sailing());
		capture.start();
		capture.tick(true);
		JsonObject written = stream(payload(of("osrs.boat").get(0)).getAsJsonArray("objects")).filter(o -> o.get("id").getAsInt() == 59452).findFirst().get();
		assertEquals(13322, written.get("animation").getAsInt());
		int before = of("osrs.boat").size();
		capture.tick(false);
		assertEquals(before, of("osrs.boat").size());
		playing[0] = 13321;
		capture.tick(false);
		List<JsonObject> boatRows = of("osrs.boat");
		assertEquals(before + 1, boatRows.size());
		JsonObject rested = stream(payload(boatRows.get(boatRows.size() - 1)).getAsJsonArray("objects")).filter(o -> o.get("id").getAsInt() == 59452).findFirst().get();
		assertEquals(13321, rested.get("animation").getAsInt());
	}

	/** The boat turned a quarter anticlockwise, its scene's centre over the map's local (MAIN_X, MAIN_Y). */
	private WorldEntity sailing()
	{
		int cx = SIZE_X * 64, cy = SIZE_Y * 64;
		return (WorldEntity) Proxy.newProxyInstance(WorldEntity.class.getClassLoader(), new Class<?>[]{WorldEntity.class}, (proxy, method, args) ->
		{
			switch (method.getName())
			{
				case "transformToMainWorld":
					LocalPoint p = (LocalPoint) args[0];
					int dx = p.getX() - cx, dy = p.getY() - cy;
					return new LocalPoint(MAIN_X - dy, MAIN_Y + dx, 0);
				case "hashCode": return System.identityHashCode(proxy);
				case "equals": return proxy == args[0];
				default: return method.invoke(boat, args);
			}
		});
	}

	private JsonArray boats()
	{
		return of("osrs.motion").stream().map(BoatRowsTest::payload).filter(p -> p.has("boats")).findFirst().get().getAsJsonArray("boats");
	}

	private List<JsonObject> of(String type)
	{
		return rows.list.stream().filter(row -> type(row).equals(type)).collect(Collectors.toList());
	}

	private static String type(JsonObject row)
	{
		return row.get("type").getAsString();
	}

	private static JsonObject payload(JsonObject row)
	{
		return row.has("payload") ? row.getAsJsonObject("payload") : row;
	}

	private static java.util.stream.Stream<JsonObject> stream(JsonArray array)
	{
		List<JsonObject> list = new ArrayList<>();
		array.forEach(element -> list.add(element.getAsJsonObject()));
		return list.stream();
	}

	private static IndexedObjectSet<?> empty()
	{
		return fake(IndexedObjectSet.class, Map.of("iterator", Collections.emptyIterator()));
	}

	private static IndexedObjectSet<?> set(List<?> items)
	{
		return (IndexedObjectSet<?>) Proxy.newProxyInstance(IndexedObjectSet.class.getClassLoader(), new Class<?>[]{IndexedObjectSet.class},
			(proxy, method, args) -> method.getName().equals("iterator") ? new ArrayList<>(items).iterator() : null);
	}
}
