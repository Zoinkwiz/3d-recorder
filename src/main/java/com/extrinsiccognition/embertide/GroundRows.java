package com.extrinsiccognition.embertide;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
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
 * The writer and the viewer's reader of this layout both live here.
 */
public final class GroundRows
{
	/** Tile text per row, well below the app's 256 KiB row limit with the heights and envelope added. */
	static final int MAX_TILE_CHARS = 96 * 1024;
	public static final int SIZE = Constants.SCENE_SIZE;
	/** The client's colour for a face it does not draw. */
	public static final int HIDDEN = 12345678;

	/** Where a row read back goes, in world tiles on map planes. */
	public interface Sink
	{
		/** A paint {0, sw, se, ne, nw, texture} or a shaped tile {shape, rotation, then a, b, c, texture per face}; null for none. */
		void tile(int plane, int x, int y, int[] entry);

		void corner(int plane, int x, int y, int height);

		void bridge(int x, int y);
	}

	private GroundRows()
	{
	}

	public static List<JsonObject> of(WorldView view, String dimension)
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

	/** Reads one row back, absolute values out, refusing anything the writer cannot produce. */
	public static void read(JsonObject row, Sink sink) throws IOException
	{
		int baseX = number(row, "base_x"), baseY = number(row, "base_y"), plane = number(row, "plane"), x0 = number(row, "x");
		JsonArray tiles = array(row, "tiles"), heights = array(row, "heights"), bridges = array(row, "bridges");
		if (baseX < 0 || baseY < 0 || baseX > 20_000 || baseY > 20_000 || plane < 0 || plane > 3 || x0 < 0 || x0 + tiles.size() > SIZE
			|| heights.size() > tiles.size() + 1 || bridges.size() % 2 != 0 || bridges.size() > 2 * SIZE * SIZE)
		{
			throw new IOException("Invalid ground row");
		}
		for (int c = 0; c < tiles.size(); c++)
		{
			JsonArray column = tiles.get(c).getAsJsonArray();
			if (column.size() != SIZE) { throw new IOException("Invalid ground column"); }
			for (int y = 0; y < SIZE; y++)
			{
				JsonElement e = column.get(y);
				if (!e.isJsonArray() && e.getAsInt() != 0) { throw new IOException("Invalid ground tile"); }
				sink.tile(plane, baseX + x0 + c, baseY + y, e.isJsonArray() ? entry(e.getAsJsonArray()) : null);
			}
		}
		for (int c = 0; c < heights.size(); c++)
		{
			JsonArray column = heights.get(c).getAsJsonArray();
			if (column.size() > SIZE + 1) { throw new IOException("Invalid ground heights"); }
			int height = 0;
			for (int y = 0; y < column.size(); y++)
			{
				height += value(column, y, 1 << 20);
				if (Math.abs(height) > 1 << 20) { throw new IOException("Invalid ground height"); }
				sink.corner(plane, baseX + x0 + c, baseY + y, height);
			}
		}
		for (int i = 0; i < bridges.size(); i += 2)
		{
			int x = value(bridges, i, SIZE), y = value(bridges, i + 1, SIZE);
			if (x < 0 || y < 0 || x >= SIZE || y >= SIZE) { throw new IOException("Invalid bridge tile"); }
			sink.bridge(baseX + x, baseY + y);
		}
	}

	private static int[] entry(JsonArray a) throws IOException
	{
		if (a.size() == 0) { throw new IOException("Invalid ground tile"); }
		if (a.get(0).getAsDouble() >= 0)
		{
			if (a.size() != 4 && a.size() != 5) { throw new IOException("Invalid ground paint"); }
			int sw = colour(a, 0);
			return new int[]{0, sw, sw + colour(a, 1), sw + colour(a, 2), sw + colour(a, 3), a.size() == 5 ? texture(a, 4) : -1};
		}
		int shape = -value(a, 0, 16), faces = (a.size() - 2) / 4, rotation = value(a, 1, 16);
		if (shape < 2 || shape > 12 || rotation < 0 || rotation > 3 || (a.size() - 2) % 4 != 0 || faces < 1 || faces > 6)
		{
			throw new IOException("Invalid shaped ground tile");
		}
		int[] entry = new int[a.size()];
		entry[0] = shape;
		entry[1] = rotation;
		for (int at = 2; at < a.size(); at += 4)
		{
			entry[at] = colour(a, at);
			entry[at + 1] = entry[at] + colour(a, at + 1);
			entry[at + 2] = entry[at] + colour(a, at + 2);
			entry[at + 3] = texture(a, at + 3);
		}
		return entry;
	}

	private static int colour(JsonArray a, int i) throws IOException
	{
		return value(a, i, 16_777_215);
	}

	private static int texture(JsonArray a, int i) throws IOException
	{
		int v = value(a, i, 65535);
		if (v < -1) { throw new IOException("Invalid ground texture"); }
		return v;
	}

	/** A whole number within plus or minus limit. */
	private static int value(JsonElement e, int limit) throws IOException
	{
		double v = e == null || e.isJsonNull() ? 0 : e.getAsDouble();
		if (v != Math.rint(v) || Math.abs(v) > limit) { throw new IOException("Invalid ground value"); }
		return (int) v;
	}

	private static int value(JsonArray a, int i, int limit) throws IOException
	{
		return value(a.get(i), limit);
	}

	private static int number(JsonObject o, String key) throws IOException
	{
		return value(o.get(key), 1 << 20);
	}

	private static JsonArray array(JsonObject o, String key)
	{
		return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
	}
}
