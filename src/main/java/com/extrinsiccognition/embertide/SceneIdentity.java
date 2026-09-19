package com.extrinsiccognition.embertide;

import java.util.Arrays;
import net.runelite.api.WorldView;

/** Surface scene loads share coordinates; each instance layout has its own space. */
final class SceneIdentity
{
	private final boolean instance;
	private final int baseX;
	private final int baseY;
	private final int[] regions;
	private final int[][][] chunks;

	private SceneIdentity(WorldView view)
	{
		instance = view != null && view.isInstance();
		baseX = instance ? view.getBaseX() : 0;
		baseY = instance ? view.getBaseY() : 0;
		regions = instance && view.getMapRegions() != null ? view.getMapRegions().clone() : new int[0];
		int[][][] source = instance ? view.getInstanceTemplateChunks() : null;
		chunks = source == null ? new int[0][][] : new int[source.length][][];
		for (int p = 0; p < chunks.length; p++)
		{
			if (source[p] == null) { continue; }
			chunks[p] = new int[source[p].length][];
			for (int x = 0; x < chunks[p].length; x++)
			{
				chunks[p][x] = source[p][x] == null ? null : source[p][x].clone();
			}
		}
	}

	static SceneIdentity of(WorldView view) { return new SceneIdentity(view); }

	String dimension() { return instance ? "osrs:instance:" + Integer.toUnsignedString(hashCode(), 16) : "osrs:surface"; }

	@Override
	public boolean equals(Object value)
	{
		if (!(value instanceof SceneIdentity)) { return false; }
		SceneIdentity other = (SceneIdentity) value;
		return instance == other.instance && baseX == other.baseX && baseY == other.baseY
			&& Arrays.equals(regions, other.regions) && Arrays.deepEquals(chunks, other.chunks);
	}

	@Override
	public int hashCode()
	{
		return (((Boolean.hashCode(instance) * 31 + baseX) * 31 + baseY) * 31 + Arrays.hashCode(regions)) * 31 + Arrays.deepHashCode(chunks);
	}
}
