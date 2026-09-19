package com.extrinsiccognition.embertide;

import java.util.Locale;

/**
 * Pure mapping from Old School RuneScape's tile world to the MCCR spatial
 * profile's cells. No client dependency, so it is testable without a game.
 *
 * Axes, as declared in every header this plugin writes: x east, y up, z south.
 * A world tile (x, y, plane) becomes cell (x, plane * 8 + elevation, -y), so
 * north points into the screen of a y-up right-handed viewer and the four
 * planes stack eight blocks apart. Elevation is the tile's rendered height,
 * one block per tile width, clamped so a plane never climbs into the next.
 */
public final class SceneMapper
{
	/** Local units per tile; RuneLite's Perspective.LOCAL_TILE_SIZE. */
	public static final int LOCAL_TILE_SIZE = 128;
	/** Blocks between planes; elevation is clamped below this. */
	public static final int PLANE_HEIGHT = 8;
	public static final int MAX_MATERIAL_LENGTH = 64;
	public static final String AIR = "air";

	private SceneMapper()
	{
	}

	/** Tile heights are negative upward in local units; one block per tile width, at most the plane gap. */
	public static int elevation(int localHeight)
	{
		int blocks = (int) Math.round(-localHeight / (double) LOCAL_TILE_SIZE);
		return Math.max(0, Math.min(PLANE_HEIGHT - 1, blocks));
	}

	public static int y(int plane, int localHeight)
	{
		return plane * PLANE_HEIGHT + elevation(localHeight);
	}

	/** The ground cell of a world tile. */
	public static int[] groundCell(int worldX, int worldY, int plane, int localHeight)
	{
		return new int[]{worldX, y(plane, localHeight), -worldY};
	}

	/** The cell an object on that tile occupies: one block above the ground. */
	public static int[] objectCell(int worldX, int worldY, int plane, int localHeight)
	{
		return new int[]{worldX, y(plane, localHeight) + 1, -worldY};
	}

	/** An overlay names the walkable surface when present; otherwise the underlay does. */
	public static String groundMaterial(short overlayId, short underlayId)
	{
		if (overlayId != 0)
		{
			return "osrs:overlay:" + (overlayId & 0xFFFF);
		}
		return "osrs:underlay:" + (underlayId & 0xFFFF);
	}

	/** A readable material for a named object, or null for the nameless ones the cache calls "null". */
	public static String objectMaterial(String name)
	{
		if (name == null)
		{
			return null;
		}
		String trimmed = name.trim().toLowerCase(Locale.ROOT);
		if (trimmed.isEmpty() || trimmed.equals("null"))
		{
			return null;
		}
		return trimmed.length() > MAX_MATERIAL_LENGTH ? trimmed.substring(0, MAX_MATERIAL_LENGTH) : trimmed;
	}

	/** The object type packed in a tile object's config: bits 0..4. */
	public static int objectType(int config)
	{
		return config & 31;
	}

	/** The placement orientation packed in a tile object's config: bits 6..7. */
	public static int objectOrientation(int config)
	{
		return (config >>> 6) & 3;
	}

	/**
	 * A heading in the MEMORY's space, not the client's.
	 *
	 * The memory's axes are x east, y up, z south, so a heading is
	 * `atan2(dx, dz)` and turning anticlockwise raises it. The engine's
	 * orientation runs the other way: 0..2047 clockwise from south, because the
	 * client's vertical axis points down. Writing it through unchanged mirrored
	 * every facing in the file, which a replay shows as turning the wrong way
	 * round. Measured over nine walking steps of a 2026-09-11 recording, the
	 * old value missed the direction actually walked by 151 degrees and its
	 * mirror by 19, which is a player mid-turn between ticks.
	 *
	 * Files written before this carry `ec.mccr.osrs/1` and readers mirror them
	 * back; this writes `ec.mccr.osrs/2`.
	 */
	public static double yawDegrees(int orientation)
	{
		int mirrored = (2048 - (orientation & 2047)) & 2047;
		return Math.round((mirrored * 360.0 / 2048.0) * 10000.0) / 10000.0;
	}

	/**
	 * Whether a tile sits inside the scene the client has loaded, on the plane
	 * being recorded. This is the reach for people: what the client draws is
	 * what the memory should be able to show.
	 */
	public static boolean inScene(int baseX, int baseY, int sizeX, int sizeY, int plane, int x, int y, int p)
	{
		int sx = x - baseX;
		int sy = y - baseY;
		return p == plane && sx >= 0 && sy >= 0 && sx < sizeX && sy < sizeY;
	}

	/** One long per cell so the known-cell set stays allocation-free. */
	public static long key(int x, int y, int z)
	{
		return ((long) (x & 0xFFFFFF) << 40) | ((long) (y & 0xFFFF) << 24) | (z & 0xFFFFFF);
	}

	public static long key(int[] cell)
	{
		return key(cell[0], cell[1], cell[2]);
	}
}
