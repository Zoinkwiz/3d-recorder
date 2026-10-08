package com.extrinsiccognition.embertide.viewer;

import com.extrinsiccognition.embertide.Fakes;
import com.extrinsiccognition.embertide.GroundRows;
import com.extrinsiccognition.embertide.RowTypes;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Tile;

/** osrs.ground rows of a scene written by the recorder's own GroundRows, from tiles given per map plane. */
final class GroundFixture
{
	interface PlaneTiles { int[] at(int plane, int x, int y); }
	interface PlaneHeights { int at(int plane, int x, int y); }

	private static final int N = GroundRows.SIZE;

	private GroundFixture() { }

	/** A paint {sw, se, ne, nw} or {sw, se, ne, nw, texture}. */
	static int[] paint(int... colours) { return colours; }
	/** A shaped tile: shape, rotation, then a, b, c, texture per face. */
	static int[] shaped(int shape, int rotation, int... faces)
	{
		int[] entry = new int[2 + faces.length]; entry[0] = -shape; entry[1] = rotation;
		System.arraycopy(faces, 0, entry, 2, faces.length); return entry;
	}

	/** One scene load: heights are positive down, as the client holds them; bridges are x, y pairs. */
	static String scene(double time, int baseX, int baseY, PlaneTiles tiles, PlaneHeights heights, int... bridges)
	{
		Map<String, Tile> made = new HashMap<>();
		Tile[][][] display = new Tile[4][N][N];
		int[][][] corners = new int[4][N + 1][N + 1];
		byte[][][] settings = new byte[4][N][N];
		for (int i = 0; i < bridges.length; i += 2) { settings[1][bridges[i]][bridges[i + 1]] = 2; }
		for (int p = 0; p < 4; p++)
		{
			for (int x = 0; x <= N; x++) { for (int y = 0; y <= N; y++) { corners[p][x][y] = heights.at(p, x, y); } }
			for (int x = 0; x < N; x++)
			{
				for (int y = 0; y < N; y++)
				{
					Tile tile = tile(made, tiles.at(p, x, y));
					// A bridge draws map plane p on display plane p - 1, with map plane 0 under display plane 0.
					if ((settings[1][x][y] & 2) == 0) { display[p][x][y] = tile; }
					else if (p > 0) { display[p - 1][x][y] = p == 1 ? withBridge(tile, tile(made, tiles.at(0, x, y))) : tile; }
				}
			}
		}
		StringBuilder out = new StringBuilder();
		Gson gson = new Gson();
		for (JsonObject row : GroundRows.of(Fakes.view(baseX, baseY, display, corners, settings), "osrs:surface"))
		{
			out.append(RecordingReaderTest.event(time, RowTypes.GROUND, gson.toJson(row)));
		}
		return out.toString();
	}

	private static Tile tile(Map<String, Tile> made, int[] t)
	{
		if (t == null) { return null; }
		return made.computeIfAbsent(Arrays.toString(t), key ->
		{
			if (t[0] >= 0) { return Fakes.paint(t[0], t[1], t[2], t[3], t.length > 4 ? t[4] : -1); }
			int faces = (t.length - 2) / 4;
			int[] a = new int[faces], b = new int[faces], c = new int[faces], textures = new int[faces];
			for (int f = 0; f < faces; f++) { a[f] = t[2 + f * 4]; b[f] = t[3 + f * 4]; c[f] = t[4 + f * 4]; textures[f] = t[5 + f * 4]; }
			return Fakes.shaped(-t[0], t[1], a, b, c, textures);
		});
	}

	private static Tile withBridge(Tile top, Tile under)
	{
		Map<String, Object> answers = new HashMap<>();
		answers.put("getSceneTilePaint", top == null ? null : top.getSceneTilePaint());
		answers.put("getSceneTileModel", top == null ? null : top.getSceneTileModel());
		answers.put("getBridge", under);
		return Fakes.fake(Tile.class, answers);
	}
}
