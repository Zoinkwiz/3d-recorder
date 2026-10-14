package com.extrinsiccognition.embertide.viewer;

import net.runelite.api.GameObject;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.gameval.ObjectID;

/** Uses the house's exit portal, not coordinates which other instances can reuse. */
final class HouseGate
{
	static boolean containsHouse(WorldView view)
	{
		if (view == null || !view.isInstance() || view.getScene() == null)
		{
			return false;
		}
		Tile[][][] planes = view.getScene().getTiles();
		if (planes == null)
		{
			return false;
		}
		for (Tile[][] plane : planes)
		{
			if (plane == null)
			{
				continue;
			}
			for (Tile[] column : plane)
			{
				if (column == null)
				{
					continue;
				}
				for (Tile tile : column)
				{
					if (tile == null || tile.getGameObjects() == null)
					{
						continue;
					}
					for (GameObject object : tile.getGameObjects())
					{
						if (object != null && object.getId() == ObjectID.POH_EXIT_PORTAL)
						{
							return true;
						}
					}
				}
			}
		}
		return false;
	}
}
