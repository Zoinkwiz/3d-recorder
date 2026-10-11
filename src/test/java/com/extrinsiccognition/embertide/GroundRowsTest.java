package com.extrinsiccognition.embertide;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import net.runelite.api.Constants;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static com.extrinsiccognition.embertide.Fakes.paint;
import static org.junit.Assert.assertTrue;

/** The ground a scene load writes, read from the scene's own tiles. */
public class GroundRowsTest
{
	private static final Gson GSON = new Gson();
	private static final int BASE_X = 3176;
	private static final int BASE_Y = 3168;
	private static final int N = Constants.SCENE_SIZE;
	private static final String GOLDEN = "5:ffba96eca39282855e15a46c2256c5ed895ebae7155a60cc541938d4a632a2f3";

	@Test
	public void writesPaintsShapesHeightsAndBridges()
	{
		Fixture f = new Fixture(false);
		List<JsonObject> rows = GroundRows.of(f.view(), "osrs:surface");
		JsonObject first = rows.get(0);
		assertEquals(BASE_X, first.get("base_x").getAsInt());
		assertEquals(0, first.get("plane").getAsInt());
		assertEquals(0, first.get("x").getAsInt());
		// A paint, a textured paint and a shaped tile.
		assertEquals("[100,1,2,3]", entry(rows, 0, 10, 20).toString());
		assertEquals("[100,1,2,3,7]", entry(rows, 0, 3, 0).toString());
		assertEquals("[-5,3,201,1,2,-1,204,1,2,31]", entry(rows, 0, 7, 7).toString());
		assertEquals("0", entry(rows, 0, 0, 1).toString());
		// The bridge tile at (60, 70): the client draws plane 1 on display plane 0 and keeps plane 0 under it.
		assertEquals("[900,0,0,0]", entry(rows, 0, 60, 70).toString());
		assertEquals("[901,0,0,0]", entry(rows, 1, 60, 70).toString());
		assertEquals("[902,0,0,0]", entry(rows, 2, 60, 70).toString());
		assertEquals("0", entry(rows, 3, 60, 70).toString());
		JsonArray bridges = rows.stream().filter(r -> r.get("plane").getAsInt() == 1)
			.map(r -> r.getAsJsonArray("bridges")).reduce(new JsonArray(), (a, b) -> { a.addAll(b); return a; });
		assertEquals("[60,70]", bridges.toString());
		assertEquals(-240 * 2 - 3 - 4, corner(rows, 2, 3, 4));
		assertEquals(-240 * 2 - N - N, corner(rows, 2, N, N));
	}

	@Test
	public void coversEveryColumnOfEveryPlaneOnceInRowsTheAppAccepts()
	{
		for (boolean dense : new boolean[]{false, true})
		{
			List<JsonObject> rows = GroundRows.of(new Fixture(dense).view(), "osrs:surface");
			int[] columns = new int[Constants.MAX_Z];
			for (JsonObject row : rows)
			{
				int plane = row.get("plane").getAsInt(), x = row.get("x").getAsInt(), n = row.get("columns").getAsInt();
				assertEquals(columns[plane], x);
				assertEquals(n, row.getAsJsonArray("tiles").size());
				assertEquals(n + 1, row.getAsJsonArray("heights").size());
				columns[plane] += n;
				// playroll-ui refuses a whole recording with one row over 256 KiB.
				assertTrue("row chars " + GSON.toJson(row).length(), GSON.toJson(row).length() < 160 * 1024);
			}
			for (int plane = 0; plane < Constants.MAX_Z; plane++) { assertEquals(N, columns[plane]); }
		}
	}

	@Test
	public void theFixtureSceneWritesTheSameRows() throws Exception
	{
		List<String> json = new ArrayList<>();
		for (JsonObject row : GroundRows.of(new Fixture(false).view(), "osrs:surface")) { json.add(GSON.toJson(row)); }
		MessageDigest sha = MessageDigest.getInstance("SHA-256");
		for (String row : json) { sha.update((row + "\n").getBytes(StandardCharsets.UTF_8)); }
		StringBuilder hex = new StringBuilder(json.size() + ":");
		for (byte b : sha.digest()) { hex.append(String.format("%02x", b)); }
		assertEquals(GOLDEN, hex.toString());
	}

	@Test
	public void aSceneLoadWritesTheGroundOnceBeforeTheSceneIsMarkedLoaded()
	{
		Fakes.Rows rows = new Fakes.Rows();
		OsrsCapture capture = Fakes.capture(rows, new Fixture(false).view(), 4);
		capture.start();
		assertTrue(capture.tick(true));
		List<String> types = new ArrayList<>();
		for (JsonObject row : rows.list) { types.add(row.get("type").getAsString()); }
		int ground = Collections.frequency(types, "osrs.ground");
		assertTrue(ground >= Constants.MAX_Z);
		assertTrue(types.indexOf("osrs.region") < types.indexOf("osrs.ground"));
		assertTrue(types.lastIndexOf("osrs.ground") < types.indexOf("osrs.scene_loaded"));
		int before = rows.list.size();
		capture.tick(false);
		assertEquals(0, rows.list.subList(before, rows.list.size()).stream()
			.filter(row -> row.get("type").getAsString().equals("osrs.ground")).count());
		capture.tick(true);
		assertEquals(ground, rows.list.subList(before, rows.list.size()).stream()
			.filter(row -> row.get("type").getAsString().equals("osrs.ground")).count());
	}

	/** Prints the bytes a scene load adds, as the file stores them, and the time to build the rows. */
	@Test
	public void measuresAScene() throws IOException
	{
		for (boolean dense : new boolean[]{false, true})
		{
			WorldView view = new Fixture(dense).view();
			long best = Long.MAX_VALUE;
			List<JsonObject> rows = null;
			for (int i = 0; i < 20; i++)
			{
				long start = System.nanoTime();
				rows = GroundRows.of(view, "osrs:surface");
				StringBuilder text = new StringBuilder();
				for (JsonObject row : rows) { text.append(GSON.toJson(row)).append('\n'); }
				best = Math.min(best, System.nanoTime() - start);
			}
			StringBuilder text = new StringBuilder();
			int largest = 0;
			for (JsonObject row : rows)
			{
				String line = GSON.toJson(row);
				largest = Math.max(largest, line.length());
				text.append(line).append('\n');
			}
			ByteArrayOutputStream gz = new ByteArrayOutputStream();
			try (GZIPOutputStream out = new GZIPOutputStream(gz)) { out.write(text.toString().getBytes(StandardCharsets.UTF_8)); }
			System.out.printf("ground rows %s: %d rows, %d bytes, largest row %d, %d gzip bytes, %.1f ms to build and serialize%n",
				dense ? "worst case" : "surface", rows.size(), text.length(), largest, gz.size(), best / 1e6);
		}
	}

	private static JsonArray tilesOf(List<JsonObject> rows, int plane, int x)
	{
		for (JsonObject row : rows)
		{
			int start = row.get("x").getAsInt();
			if (row.get("plane").getAsInt() == plane && x >= start && x < start + row.get("columns").getAsInt())
			{
				return row.getAsJsonArray("tiles").get(x - start).getAsJsonArray();
			}
		}
		throw new AssertionError("no column " + x);
	}

	private static com.google.gson.JsonElement entry(List<JsonObject> rows, int plane, int x, int y)
	{
		return tilesOf(rows, plane, x).get(y);
	}

	private static int corner(List<JsonObject> rows, int plane, int x, int y)
	{
		for (JsonObject row : rows)
		{
			int start = row.get("x").getAsInt();
			if (row.get("plane").getAsInt() == plane && x >= start && x <= start + row.get("columns").getAsInt())
			{
				JsonArray column = row.getAsJsonArray("heights").get(x - start).getAsJsonArray();
				int height = 0;
				for (int i = 0; i <= y; i++) { height += column.get(i).getAsInt(); }
				return height;
			}
		}
		throw new AssertionError("no corner " + x);
	}

	/**
	 * A surface scene shaped like the recordings the viewer was checked on: every plane-0 tile painted, about 7%
	 * shaped, upper floors sparse. The dense one fills every tile of every plane with six-face shapes.
	 */
	static final class Fixture
	{
		final Tile[][][] tiles = new Tile[4][N][N];
		final int[][][] heights = new int[4][N + 1][N + 1];
		final byte[][][] settings = new byte[4][N][N];

		Fixture(boolean dense)
		{
			java.util.Random random = new java.util.Random(7);
			for (int p = 0; p < 4; p++)
			{
				for (int x = 0; x <= N; x++) { for (int y = 0; y <= N; y++) { heights[p][x][y] = -240 * p - x - y; } }
			}
			for (int p = 0; p < 4; p++)
			{
				double share = dense ? 1 : p == 0 ? 1 : p == 1 ? .1 : p == 2 ? .05 : .01;
				for (int x = 0; x < N; x++)
				{
					for (int y = 0; y < N; y++)
					{
						if (!dense && p == 0 && x == 0 && y == 1) { continue; }
						if (random.nextDouble() >= share) { continue; }
						boolean fixed = p == 0 && ((x == 10 && y == 20) || (x == 3 && y == 0));
						if (!fixed && (dense || (x == 7 && y == 7 && p == 0) || random.nextDouble() < .07))
						{
							tiles[p][x][y] = shaped(dense, random, x == 7 && y == 7 && p == 0);
						}
						else
						{
							int hsl = fixed ? 100 : 8000 + random.nextInt(30000);
							tiles[p][x][y] = paint(hsl, hsl + 1, hsl + 2, hsl + 3,
								(x == 3 && y == 0 && p == 0) ? 7 : !fixed && random.nextDouble() < .03 ? random.nextInt(90) : -1);
						}
					}
				}
			}
			if (!dense)
			{
				// One bridge: display planes 0..2 hold map planes 1..3, the map's plane 0 is under display plane 0.
				settings[1][60][70] = 2;
				Tile under = paint(900, 900, 900, 900, -1);
				tiles[0][60][70] = Fakes.fake(Tile.class, Map.of("getSceneTilePaint", Fakes.paintOf(901, 901, 901, 901, -1), "getBridge", under));
				tiles[1][60][70] = paint(902, 902, 902, 902, -1);
				tiles[2][60][70] = null;
				tiles[3][60][70] = null;
			}
		}

		WorldView view()
		{
			return Fakes.view(BASE_X, BASE_Y, tiles, heights, settings);
		}

		private static Tile shaped(boolean dense, java.util.Random random, boolean fixed)
		{
			int faces = fixed ? 2 : dense ? 6 : 2 + random.nextInt(5);
			int[] a = new int[faces], b = new int[faces], c = new int[faces], t = new int[faces];
			for (int i = 0; i < faces; i++)
			{
				int hsl = fixed ? 201 + i * 3 : 8000 + random.nextInt(30000);
				a[i] = hsl; b[i] = hsl + 1; c[i] = hsl + 2;
				t[i] = fixed ? (i == 0 ? -1 : 31) : random.nextDouble() < .1 ? random.nextInt(90) : -1;
			}
			int shape = fixed ? 5 : 2 + random.nextInt(11);
			return Fakes.shaped(shape, fixed ? 3 : random.nextInt(4), a, b, c, t);
		}
	}
}
