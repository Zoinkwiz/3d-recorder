package com.extrinsiccognition.embertide;

import com.google.gson.Gson;
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
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;

/** Client objects answered from a map, and a recorder that keeps its rows; shared by the recorder and viewer tests. */
public final class Fakes
{
	private static final int N = Constants.SCENE_SIZE;

	private Fakes()
	{
	}

	/** A proxy answering each method by name, defaults for the rest. */
	@SuppressWarnings("unchecked")
	public static <T> T fake(Class<T> type, Map<String, Object> answers)
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

	public static Tile paint(int sw, int se, int ne, int nw, int texture)
	{
		return fake(Tile.class, Map.of("getSceneTilePaint", paintOf(sw, se, ne, nw, texture)));
	}

	public static SceneTilePaint paintOf(int sw, int se, int ne, int nw, int texture)
	{
		return fake(SceneTilePaint.class, Map.of("getSwColor", sw, "getSeColor", se, "getNeColor", ne, "getNwColor", nw, "getTexture", texture));
	}

	/** A shaped tile; a, b, c and textures are per face. */
	public static Tile shaped(int shape, int rotation, int[] a, int[] b, int[] c, int[] textures)
	{
		return fake(Tile.class, Map.of("getSceneTileModel", fake(SceneTileModel.class, Map.of("getShape", shape, "getRotation", rotation,
			"getTriangleColorA", a, "getTriangleColorB", b, "getTriangleColorC", c, "getTriangleTextureId", textures))));
	}

	/** A loaded scene: display-plane tiles, corner heights [4][105][105] and tile settings (2 on plane 1 marks a bridge). */
	public static WorldView view(int baseX, int baseY, Tile[][][] tiles, int[][][] heights, byte[][][] settings)
	{
		IndexedObjectSet<?> empty = fake(IndexedObjectSet.class, Map.of("iterator", Collections.emptyIterator()));
		Scene scene = fake(Scene.class, Map.of("getTiles", tiles, "getOverlayIds", new short[4][N][N], "getUnderlayIds", new short[4][N][N]));
		Map<String, Object> answers = new HashMap<>(Map.of("getScene", scene, "getBaseX", baseX, "getBaseY", baseY,
			"getMapRegions", new int[]{12850}, "getTileHeights", heights, "npcs", empty, "players", empty));
		answers.put("getTileSettings", settings);
		return fake(WorldView.class, answers);
	}

	public static OsrsCapture capture(Recorder rows, WorldView view, int radius)
	{
		Player me = fake(Player.class, Map.of("getWorldLocation", new WorldPoint(3222, 3218, 0), "getName", "Tester"));
		ObjectComposition plain = fake(ObjectComposition.class, Map.of());
		Client client = fake(Client.class, Map.of("getTopLevelWorldView", view, "getLocalPlayer", me, "getTickCount", 42, "getObjectDefinition", plain));
		EmbertideConfig config = fake(EmbertideConfig.class, Map.of("captureRadius", radius));
		return new OsrsCapture(client, rows, config, "osrs:surface", "Tester", 301);
	}

	/** A recorder that keeps every row and counts its bytes. */
	public static final class Rows implements Recorder
	{
		public final List<JsonObject> list = new ArrayList<>();
		private final Gson gson = new Gson();
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
