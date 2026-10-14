package com.extrinsiccognition.embertide.viewer;

import com.extrinsiccognition.embertide.GroundRows;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * The ground a recording saved: its osrs.ground rows, keyed by world tile and map plane, or for older recordings a floor
 * SnapshotTerrain builds once from snapshot cells on the client thread. Otherwise immutable during playback.
 * Each scene load the recorder wrote replaces the tiles it covered.
 */
final class Terrain implements GroundRows.Sink
{
	static final int MAX_TILES = 600_000, MAX_BLOCKS = 1024;
	boolean instance;
	int baseX, baseY;
	/** Heights written for old instance recordings, before ground rows; [plane][scene x][scene y]. */
	final int[][][] instanceHeights = new int[4][][];
	private final Map<Long, Block> blocks = new HashMap<>();
	private int tiles;
	/** Ground past the budget is left out; a long walk keeps its first areas. */
	boolean truncated;

	/** Whether this scene has ground: osrs.ground rows, or a floor built from snapshot cells. */
	boolean recorded() { return !blocks.isEmpty(); }
	/** The floor was built from spatial snapshot cells (a recording made before osrs.ground rows), or that was tried. */
	boolean sampled;

	@Override
	public void tile(int plane, int x, int y, int[] entry)
	{
		Block block = block(plane, x, y, true);
		if (block == null)
		{
			return;
		}
		int i = index(x, y);
		if (block.tiles[i] == null && entry != null)
		{
			if (tiles >= MAX_TILES)
			{
				truncated = true;
				return;
			}
			tiles++;
		}
		else if (block.tiles[i] != null && entry == null)
		{
			tiles--;
		}
		block.tiles[i] = entry;
	}

	@Override
	public void corner(int plane, int x, int y, int height)
	{
		Block block = block(plane, x, y, true);
		if (block != null)
		{
			block.heights[index(x, y)] = height;
		}
	}

	@Override
	public void bridge(int x, int y)
	{
		Block block = block(1, x, y, true);
		if (block != null)
		{
			block.bridge[index(x, y)] = true;
		}
	}

	private boolean isBridge(int x, int y)
	{
		Block block = block(1, x, y, false);
		return block != null && block.bridge[index(x, y)];
	}

	Tile tile(int x, int y, int plane) { return tile(x, y, plane, false); }

	/** physicalPlane reads the map plane itself, without moving a bridge up to the plane it is drawn on. */
	Tile tile(int x, int y, int plane, boolean physicalPlane)
	{
		if (plane < 0 || plane > 3)
		{
			return null;
		}
		int p = mapPlane(x, y, plane, physicalPlane);
		Block block = block(p, x, y, false);
		int[] entry = block == null ? null : block.tiles[index(x, y)];
		if (entry == null)
		{
			return null;
		}
		double[] heights = {height(x, y, p, true), height(x + 1, y, p, true), height(x + 1, y + 1, p, true), height(x, y + 1, p, true)};
		for (double h : heights)
		{
			if (!Double.isFinite(h))
			{
				return null;
			}
		}
		return new Tile(x, y, plane, entry, heights, physicalPlane);
	}

	double height(int x, int y, int plane) { return height(x, y, plane, false); }

	/** Corner height, up positive, or NaN where nothing was recorded. */
	double height(int x, int y, int plane, boolean physicalPlane)
	{
		if (plane < 0 || plane > 3)
		{
			return Double.NaN;
		}
		int p = mapPlane(x, y, plane, physicalPlane);
		Block block = block(p, x, y, false);
		int h = block == null ? Integer.MIN_VALUE : block.heights[index(x, y)];
		if (h != Integer.MIN_VALUE)
		{
			return -h;
		}
		int[][] grid = instance ? instanceHeights[plane] : null;
		int dx = x - baseX, dy = y - baseY;
		return grid != null && dx >= 0 && dx < grid.length && grid[dx] != null && dy >= 0 && dy < grid[dx].length
			? -grid[dx][dy] : Double.NaN;
	}

	/** Height inside a tile, interpolated between its recorded corners. */
	double surface(double x, double y, int plane)
	{
		int tx = (int) Math.floor(x), ty = (int) Math.floor(y);
		double fx = x - tx, fy = y - ty;
		double a = height(tx, ty, plane), b = height(tx + 1, ty, plane), c = height(tx + 1, ty + 1, plane), d = height(tx, ty + 1, plane);
		return (a * (1 - fx) + b * fx) * (1 - fy) + (d * (1 - fx) + c * fx) * fy;
	}

	private int mapPlane(int x, int y, int plane, boolean physicalPlane)
	{
		return !physicalPlane && plane < 3 && isBridge(x, y) ? plane + 1 : plane;
	}

	private Block block(int plane, int x, int y, boolean create)
	{
		long key = (long) plane << 40 | (long) (x >> 6 & 0xfffff) << 20 | (y >> 6 & 0xfffff);
		Block block = blocks.get(key);
		if (block == null && create)
		{
			if (blocks.size() >= MAX_BLOCKS)
			{
				truncated = true;
				return null;
			}
			block = new Block();
			blocks.put(key, block);
		}
		return block;
	}

	private static int index(int x, int y) { return (x & 63) << 6 | (y & 63); }

	private static final class Block
	{
		final int[][] tiles = new int[4096][];
		final int[] heights = new int[4096];
		final boolean[] bridge = new boolean[4096];
		Block() { Arrays.fill(heights, Integer.MIN_VALUE); }
	}

	static final class Tile
	{
		final int x, y, plane;
		final int[] entry;
		final double[] heights;
		final boolean physicalPlane;
		Tile(int x, int y, int plane, int[] entry, double[] heights, boolean physicalPlane)
		{ this.x = x; this.y = y; this.plane = plane; this.entry = entry; this.heights = heights; this.physicalPlane = physicalPlane; }
		boolean paint() { return entry[0] == 0; }
		int shape() { return entry[0]; }
		int rotation() { return paint() ? 0 : entry[1]; }
		int faces() { return paint() ? 2 : (entry.length - 2) / 4; }

		double height(double x, double y)
		{ return (heights[0] * (1 - x) + heights[1] * x) * (1 - y) + (heights[3] * (1 - x) + heights[2] * x) * y; }
	}
}
