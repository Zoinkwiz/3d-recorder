package com.extrinsiccognition.embertide;

import java.util.Locale;

/**
 * Map world tiles to MCCR cells: (x, y, plane) becomes (x, plane * 8 + elevation, -y).
 * The recording axes are east, up and south; elevation is clamped below the next plane.
 */
public final class SceneMapper
{
	public static final int LOCAL_TILE_SIZE = 128;
	public static final int PLANE_HEIGHT = 8;
	public static final int MAX_MATERIAL_LENGTH = 64;
	public static final String AIR = "air";

	private SceneMapper()
	{
	}

	public static int elevation(int localHeight)
	{
		int blocks = (int) Math.round(-localHeight / (double) LOCAL_TILE_SIZE);
		return Math.max(0, Math.min(PLANE_HEIGHT - 1, blocks));
	}

	public static int y(int plane, int localHeight)
	{
		return plane * PLANE_HEIGHT + elevation(localHeight);
	}

	public static int[] groundCell(int worldX, int worldY, int plane, int localHeight)
	{
		return new int[]{worldX, y(plane, localHeight), -worldY};
	}

	public static int[] objectCell(int worldX, int worldY, int plane, int localHeight)
	{
		return new int[]{worldX, y(plane, localHeight) + 1, -worldY};
	}

	public static String groundMaterial(short overlayId, short underlayId)
	{
		if (overlayId != 0)
		{
			return "osrs:overlay:" + (overlayId & 0xFFFF);
		}
		return "osrs:underlay:" + (underlayId & 0xFFFF);
	}

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

	public static int objectType(int config)
	{
		return config & 31;
	}

	public static int objectOrientation(int config)
	{
		return (config >>> 6) & 3;
	}

	/**
	 * Convert the client's clockwise orientation (0..2047 from south) to replay yaw
	 * in the east/up/south coordinate system, where positive rotation is anticlockwise.
	 */
	public static double yawDegrees(int orientation)
	{
		int mirrored = (2048 - (orientation & 2047)) & 2047;
		return Math.round((mirrored * 360.0 / 2048.0) * 10000.0) / 10000.0;
	}

	public static boolean inScene(int baseX, int baseY, int sizeX, int sizeY, int plane, int x, int y, int p)
	{
		int sx = x - baseX;
		int sy = y - baseY;
		return p == plane && sx >= 0 && sy >= 0 && sx < sizeX && sy < sizeY;
	}

	public static long key(int x, int y, int z)
	{
		return ((long) (x & 0xFFFFFF) << 40) | ((long) (y & 0xFFFF) << 24) | (z & 0xFFFFFF);
	}

	public static long key(int[] cell)
	{
		return key(cell[0], cell[1], cell[2]);
	}
}
