package com.extrinsiccognition.embertide.viewer;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntFunction;
import static com.extrinsiccognition.embertide.SceneMapper.OVERLAY;
import static com.extrinsiccognition.embertide.SceneMapper.UNDERLAY;

/**
 * Floor for a scene recorded before osrs.ground rows: its spatial snapshot cells, which name each tile's underlay or
 * overlay near where the player walked, coloured from the client's own definitions the way the client colours a
 * tile. Cells store a rounded height clamped to a plane, so corners use saved instance heights where they exist.
 * Underlays blend over the neighbouring recorded underlays (the client's 11 by 11 window); hidden overlays and tiles
 * with neither draw nothing, and object shadows are not reproduced. Runs on the client thread.
 */
final class SnapshotTerrain
{
	static final int BLEND = 5;

	private SnapshotTerrain() { }

	/** Whether a cell names an underlay or overlay; id 0 is a tile with neither. */
	static boolean names(String material)
	{
		return (material.startsWith(UNDERLAY) || material.startsWith(OVERLAY)) && !material.endsWith(":0");
	}

	/** Fills the scene's terrain from its cells once; osrs.ground rows win. Returns the tiles added. */
	static int build(Recording.Scene scene, IntFunction<Definitions.UnderlayDef> underlays, IntFunction<Definitions.OverlayDef> overlays)
	{
		Terrain terrain = scene.terrain;
		if (terrain.sampled || terrain.recorded() || scene.ground.isEmpty())
		{
			return 0;
		}
		terrain.sampled = true;
		// {overlay?, definition id, client height (negative up)}, keyed per plane and tile.
		Map<Long, int[]> cells = new HashMap<>();
		for (Recording.Ground g : scene.ground.values())
		{
			if (!names(g.material))
			{
				continue;
			}
			boolean overlay = g.material.startsWith(OVERLAY);
			// Tiles store definition id plus one.
			int stored = Integer.parseInt(g.material.substring(g.material.lastIndexOf(':') + 1));
			cells.put(key(g.plane, g.x, g.y), new int[]{overlay ? 1 : 0, stored - 1, (int) -Math.round(g.height)});
		}
		for (Map.Entry<Long, int[]> e : cells.entrySet())
		{
			int plane = plane(e.getKey()), x = x(e.getKey()), y = y(e.getKey());
			for (int c = 0; c < 4; c++) { corner(terrain, cells, plane, x + (c == 1 || c == 2 ? 1 : 0), y + (c >= 2 ? 1 : 0)); }
		}
		int added = 0;
		for (Map.Entry<Long, int[]> e : cells.entrySet())
		{
			int plane = plane(e.getKey()), x = x(e.getKey()), y = y(e.getKey());
			int[] cell = e.getValue();
			int hsl, texture = -1;
			if (cell[0] == 1)
			{
				Definitions.OverlayDef d = overlays.apply(cell[1]);
				if (d == null || d.hidden())
				{
					continue;
				}
				texture = d.texture;
				hsl = texture >= 0 ? -1 : packHsl(d.hue, d.saturation, d.lightness);
			}
			else
			{
				hsl = blendedUnderlay(cells, underlays, plane, x, y);
				if (hsl == Integer.MIN_VALUE)
				{
					continue;
				}
			}
			int[] entry = {0, 0, 0, 0, 0, texture};
			for (int c = 0; c < 4; c++)
			{
				entry[1 + c] = shade(hsl, light(terrain, plane, x + (c == 1 || c == 2 ? 1 : 0), y + (c >= 2 ? 1 : 0)));
			}
			terrain.tile(plane, x, y, entry);
			added++;
		}
		return added;
	}

	/** A tile's south-west corner is its own cell's height; a corner without one averages the cells that share it. */
	private static void corner(Terrain terrain, Map<Long, int[]> cells, int plane, int x, int y)
	{
		if (Double.isFinite(terrain.height(x, y, plane, true)))
		{
			return;
		}
		int[] own = cells.get(key(plane, x, y));
		if (own != null)
		{
			terrain.corner(plane, x, y, own[2]);
			return;
		}
		int sum = 0, n = 0;
		for (int dx = -1; dx <= 0; dx++)
		{
			for (int dy = -1; dy <= 0; dy++)
			{
				int[] c = cells.get(key(plane, x + dx, y + dy));
				if (c != null)
				{
					sum += c[2];
					n++;
				}
			}
		}
		if (n > 0)
		{
			terrain.corner(plane, x, y, Math.round(sum / (float) n));
		}
	}

	private static int blendedUnderlay(Map<Long, int[]> cells, IntFunction<Definitions.UnderlayDef> underlays, int plane, int x, int y)
	{
		long hue = 0, multiplier = 0, saturation = 0, lightness = 0;
		int count = 0;
		for (int dx = -BLEND; dx <= BLEND; dx++)
		{
			for (int dy = -BLEND; dy <= BLEND; dy++)
			{
				int[] c = cells.get(key(plane, x + dx, y + dy));
				if (c == null || c[0] != 0)
				{
					continue;
				}
				Definitions.UnderlayDef d = underlays.apply(c[1]);
				if (d == null)
				{
					continue;
				}
				hue += d.hue;
				multiplier += d.hueMultiplier;
				saturation += d.saturation;
				lightness += d.lightness;
				count++;
			}
		}
		if (count == 0 || multiplier == 0 || underlays.apply(cells.get(key(plane, x, y))[1]) == null)
		{
			return Integer.MIN_VALUE;
		}
		return packHsl((int) (hue * 256 / multiplier), (int) (saturation / count), (int) (lightness / count));
	}

	/** The client's terrain light at a corner from the slope of its neighbours, without object shadows; 84 when flat. */
	static int light(Terrain terrain, int plane, int x, int y)
	{
		int here = raw(terrain, plane, x, y, 0);
		int dx = raw(terrain, plane, x + 1, y, here) - raw(terrain, plane, x - 1, y, here);
		int dy = raw(terrain, plane, x, y + 1, here) - raw(terrain, plane, x, y - 1, here);
		int length = (int) Math.sqrt(dx * dx + dy * dy + 65536);
		int nx = (dx << 8) / length, ny = 65536 / length, nz = (dy << 8) / length;
		return (nx * -50 + ny * -10 + nz * -50) / 213 + 96;
	}

	private static int raw(Terrain terrain, int plane, int x, int y, int fallback)
	{
		double h = terrain.height(x, y, plane, true);
		return Double.isFinite(h) ? (int) -h : fallback;
	}

	/** The client's packed terrain HSL: saturation halves as lightness rises. */
	static int packHsl(int hue, int saturation, int lightness)
	{
		if (lightness > 179)
		{
			saturation /= 2;
		}
		if (lightness > 192)
		{
			saturation /= 2;
		}
		if (lightness > 217)
		{
			saturation /= 2;
		}
		if (lightness > 243)
		{
			saturation /= 2;
		}
		return (hue / 4 << 10) + (saturation / 32 << 7) + lightness / 2;
	}

	/** A colour lit at a corner; a textured face (hsl -1) keeps only the light, as the client draws it. */
	static int shade(int hsl, int light)
	{
		if (hsl == -1)
		{
			return Math.max(2, Math.min(126, light));
		}
		int l = Math.max(2, Math.min(126, (hsl & 127) * light / 128));
		return (hsl & 0xff80) + l;
	}

	private static long key(int plane, int x, int y) { return (long) plane << 48 | (long) (x & 0xffffff) << 24 | (y & 0xffffff); }
	private static int plane(long key) { return (int) (key >>> 48); }
	private static int x(long key) { return (int) (key >>> 24 & 0xffffff); }
	private static int y(long key) { return (int) (key & 0xffffff); }
}
