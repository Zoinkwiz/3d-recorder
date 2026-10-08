package com.extrinsiccognition.embertide;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Constants;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;

/**
 * The loaded scene's ground as the client built it: osrs.ground rows, one per physical plane and band of columns.
 * A tile is 0 (none), a paint [sw, se - sw, ne - sw, nw - sw] with a fifth texture when it has one, or a shaped tile
 * [-shape, rotation, then a, b - a, c - a, texture per face], colours as the client's HSL with 12345678 for hidden.
 * Corner colours of a tile share hue and saturation, so the differences are short.
 */
final class GroundRows
{
	/** Tile text per row, well below the app's 256 KiB row limit with the heights and envelope added. */
	static final int MAX_TILE_CHARS = 96 * 1024;
	static final int SIZE = Constants.SCENE_SIZE;

	private GroundRows()
	{
	}

	static List<JsonObject> of(WorldView view, String dimension)
	{
		List<JsonObject> rows = new ArrayList<>();
		Scene scene = view.getScene();
		Tile[][][] tiles = scene == null ? null : scene.getTiles();
		if (tiles == null)
		{
			return rows;
		}
		int[][][] heights = view.getTileHeights();
		byte[][][] settings = view.getTileSettings();
		for (int plane = 0; plane < Constants.MAX_Z; plane++)
		{
			int start = 0, chars = 0;
			List<JsonArray> band = new ArrayList<>();
			for (int x = 0; x < SIZE; x++)
			{
				JsonArray column = new JsonArray();
				for (int y = 0; y < SIZE; y++)
				{
					tile(column, physical(tiles, settings, plane, x, y));
				}
				int length = column.toString().length() + 1;
				if (!band.isEmpty() && chars + length > MAX_TILE_CHARS)
				{
					rows.add(row(view, dimension, plane, start, band, heights, settings));
					start = x;
					chars = 0;
					band = new ArrayList<>();
				}
				band.add(column);
				chars += length;
			}
			rows.add(row(view, dimension, plane, start, band, heights, settings));
		}
		return rows;
	}

	/** The client moves a bridge's floors down one display plane; rows keep the map's own planes. */
	static boolean bridge(byte[][][] settings, int x, int y)
	{
		return settings != null && settings.length > 1 && settings[1] != null && x < settings[1].length
			&& settings[1][x] != null && y < settings[1][x].length && (settings[1][x][y] & 2) != 0;
	}

	private static Tile physical(Tile[][][] tiles, byte[][][] settings, int plane, int x, int y)
	{
		if (!bridge(settings, x, y))
		{
			return at(tiles, plane, x, y);
		}
		if (plane > 0)
		{
			return at(tiles, plane - 1, x, y);
		}
		Tile top = at(tiles, 0, x, y);
		return top == null ? null : top.getBridge();
	}

	private static Tile at(Tile[][][] tiles, int plane, int x, int y)
	{
		return plane < tiles.length && tiles[plane] != null && x < tiles[plane].length && tiles[plane][x] != null
			&& y < tiles[plane][x].length ? tiles[plane][x][y] : null;
	}

	private static void tile(JsonArray column, Tile tile)
	{
		SceneTilePaint paint = tile == null ? null : tile.getSceneTilePaint();
		if (paint != null)
		{
			JsonArray entry = new JsonArray();
			int sw = paint.getSwColor();
			entry.add(sw);
			entry.add(paint.getSeColor() - sw);
			entry.add(paint.getNeColor() - sw);
			entry.add(paint.getNwColor() - sw);
			if (paint.getTexture() >= 0) { entry.add(paint.getTexture()); }
			column.add(entry);
			return;
		}
		SceneTileModel model = tile == null ? null : tile.getSceneTileModel();
		int[] a = model == null ? null : model.getTriangleColorA(), b = model == null ? null : model.getTriangleColorB();
		int[] c = model == null ? null : model.getTriangleColorC();
		if (a == null || b == null || c == null)
		{
			column.add(0);
			return;
		}
		int[] textures = model.getTriangleTextureId();
		JsonArray entry = new JsonArray();
		entry.add(-model.getShape());
		entry.add(model.getRotation());
		for (int i = 0; i < Math.min(a.length, Math.min(b.length, c.length)); i++)
		{
			entry.add(a[i]);
			entry.add(b[i] - a[i]);
			entry.add(c[i] - a[i]);
			entry.add(textures != null && i < textures.length ? textures[i] : -1);
		}
		column.add(entry);
	}

	private static JsonObject row(WorldView view, String dimension, int plane, int start, List<JsonArray> band,
		int[][][] heights, byte[][][] settings)
	{
		JsonObject row = new JsonObject();
		row.addProperty("base_x", view.getBaseX());
		row.addProperty("base_y", view.getBaseY());
		row.addProperty("plane", plane);
		row.addProperty("x", start);
		row.addProperty("columns", band.size());
		JsonArray tiles = new JsonArray();
		for (JsonArray column : band) { tiles.add(column); }
		row.add("tiles", tiles);
		int[][] grid = heights != null && plane < heights.length ? heights[plane] : null;
		if (grid != null)
		{
			// Corner heights for x .. x + columns, 105 a column: the first, then each one's difference from the one south of it.
			JsonArray corners = new JsonArray();
			for (int x = start; x <= start + band.size() && x < grid.length; x++)
			{
				JsonArray column = new JsonArray();
				int[] values = grid[x];
				for (int y = 0; values != null && y <= SIZE && y < values.length; y++) { column.add(y == 0 ? values[0] : values[y] - values[y - 1]); }
				corners.add(column);
			}
			row.add("heights", corners);
		}
		if (plane == 1)
		{
			JsonArray bridges = new JsonArray();
			for (int x = start; x < start + band.size(); x++)
			{
				for (int y = 0; y < SIZE; y++)
				{
					if (bridge(settings, x, y)) { bridges.add(x); bridges.add(y); }
				}
			}
			row.add("bridges", bridges);
		}
		row.addProperty("dimension", dimension);
		return row;
	}
}
