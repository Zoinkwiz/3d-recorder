package com.extrinsiccognition.embertide.viewer;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntFunction;
import org.junit.Test;
import static com.extrinsiccognition.embertide.viewer.RecordingReaderTest.*;
import static org.junit.Assert.*;

/** A recording made before osrs.ground rows gets a floor from its snapshot cells and the client's floor definitions. */
public class SnapshotTerrainTest
{
	private static final int GREEN = 0x408020, BLUE = 0x204080, GREY = 0x808080;
	private final Map<Integer, byte[]> underlays = new HashMap<>(), overlays = new HashMap<>();

	public SnapshotTerrainTest()
	{
		underlays.put(10, rgb(1, GREEN)); underlays.put(11, rgb(1, BLUE));
		overlays.put(4, rgb(1, GREY));
		overlays.put(5, new byte[]{1, 0, 0, 0, 2, 3, 7, 0x11, 0x22, 0x33, 0}); // textured, with a minimap colour
		overlays.put(6, rgb(1, Definitions.OverlayDef.HIDDEN_RGB));
	}

	private static byte[] rgb(int op, int rgb) { return new byte[]{(byte) op, (byte) (rgb >> 16), (byte) (rgb >> 8), (byte) rgb, 0}; }
	private IntFunction<Definitions.UnderlayDef> under() { return id -> underlays.containsKey(id) ? Definitions.underlay(underlays.get(id)) : null; }
	private IntFunction<Definitions.OverlayDef> over() { return id -> overlays.containsKey(id) ? Definitions.overlay(overlays.get(id)) : null; }
	private static String cell(int x, int h, String material) { return "[" + x + "," + h + ",-3200,\"" + material + "\"]"; }

	private static Recording snapshot(String... cells) throws Exception
	{
		return parse(HEADER + pose(0, 3200, 0)
			+ event(0, "mccr.spatial_snapshot", "{\"cells\":[" + String.join(",", cells) + "]}"));
	}

	@Test public void cellsBecomeAFloorWithOverlaysOverTheBlendedUnderlay() throws Exception
	{
		Recording r = snapshot(cell(3200, 2, "osrs:underlay:11"), cell(3200, 3, "air"), cell(3201, 2, "osrs:underlay:12"),
			cell(3202, 2, "osrs:overlay:5"), cell(3203, 2, "osrs:overlay:6"), cell(3204, 2, "osrs:overlay:7"),
			cell(3205, 2, "osrs:underlay:0"), cell(3206, 2, "osrs:ground"), cell(3207, 2, "osrs:overlay:99"), cell(3208, 3, "bush"));
		Recording.Scene scene = r.scenes.get(0);
		assertFalse(scene.terrain.recorded());
		assertEquals(4, SnapshotTerrain.build(scene, under(), over()));
		assertTrue(scene.terrain.recorded()); assertTrue(scene.terrain.sampled);

		// Both underlays sit in each other's blend window, so they share one colour; overlays do not take part.
		Definitions.UnderlayDef green = Definitions.underlay(rgb(1, GREEN)), blue = Definitions.underlay(rgb(1, BLUE));
		int blended = SnapshotTerrain.packHsl((green.hue + blue.hue) * 256 / (green.hueMultiplier + blue.hueMultiplier),
			(green.saturation + blue.saturation) / 2, (green.lightness + blue.lightness) / 2);
		int lit = SnapshotTerrain.shade(blended, 84);
		Terrain.Tile first = scene.terrain.tile(3200, 3200, 0);
		assertArrayEquals(new int[]{0, lit, lit, lit, lit, -1}, first.entry);
		assertArrayEquals(first.entry, scene.terrain.tile(3201, 3200, 0).entry);
		assertArrayEquals(new double[]{256, 256, 256, 256}, first.heights, 0);
		assertEquals(256, scene.height(3200.5, 3200.5, 0), 0);

		Definitions.OverlayDef grey = Definitions.overlay(rgb(1, GREY));
		int overlay = SnapshotTerrain.shade(SnapshotTerrain.packHsl(grey.hue, grey.saturation, grey.lightness), 84);
		assertNotEquals(lit, overlay);
		assertArrayEquals(new int[]{0, overlay, overlay, overlay, overlay, -1}, scene.terrain.tile(3202, 3200, 0).entry);
		// A textured overlay draws its texture lit as the client lights it; the secondary colour is the minimap's.
		assertArrayEquals(new int[]{0, 84, 84, 84, 84, 3}, scene.terrain.tile(3203, 3200, 0).entry);

		for (int x : new int[]{3204, 3205, 3206, 3207, 3208}) { assertNull("tile " + x, scene.terrain.tile(x, 3200, 0)); }
		assertNull(scene.terrain.tile(3200, 3200, 1));
		assertEquals(0, SnapshotTerrain.build(scene, under(), over()));
	}

	@Test public void slopesAreLitAndCornersWithoutACellAverageTheirNeighbours() throws Exception
	{
		Recording.Scene scene = snapshot(cell(3200, 0, "osrs:underlay:11"), cell(3201, 4, "osrs:underlay:11")).scenes.get(0);
		assertEquals(2, SnapshotTerrain.build(scene, under(), over()));
		Terrain.Tile low = scene.terrain.tile(3200, 3200, 0);
		// South-west corners are the cells' own heights; the far corners average the cells that share them.
		assertArrayEquals(new double[]{0, 512, 256, 0}, low.heights, 0);
		assertArrayEquals(new double[]{512, 512, 512, 256}, scene.terrain.tile(3201, 3200, 0).heights, 0);
		assertNotEquals(low.entry[1], low.entry[2]);
		assertEquals(84, SnapshotTerrain.light(scene.terrain, 0, 3202, 3200));
	}

	@Test public void savedInstanceHeightsBeatTheRoundedCellHeight() throws Exception
	{
		Recording r = parse(HEADER + pose(0, 3200, 0)
			+ event(0, "mccr.scene", "{\"dimension\":\"osrs:instance:1\"}")
			+ event(0, "osrs.region", "{\"instance\":true,\"base_x\":1000,\"base_y\":2000,\"regions\":[]}")
			+ event(0, "osrs.instance_heights", "{\"base_x\":1000,\"base_y\":2000,\"plane\":0,\"heights\":" + TerrainTest.grid(-300) + "}")
			+ pose(0, 1002, 0)
			+ event(0, "mccr.spatial_snapshot", "{\"cells\":[[1002,2,-2003,\"osrs:underlay:11\"]]}"));
		Recording.Scene instance = r.scenes.get(1);
		assertEquals(1, SnapshotTerrain.build(instance, under(), over()));
		assertArrayEquals(new double[]{300, 300, 300, 300}, instance.terrain.tile(1002, 2003, 0).heights, 0);
	}

	@Test public void groundRowsWinOverSnapshotCells() throws Exception
	{
		Recording r = parse(HEADER + pose(0, 3200, 0)
			+ GroundFixture.scene(0, 3176, 3168, (p, x, y) -> p == 0 ? GroundFixture.paint(50, 51, 52, 53) : null, (p, x, y) -> 0)
			+ event(0, "mccr.spatial_snapshot", "{\"cells\":[" + cell(3200, 2, "osrs:underlay:11") + "]}"));
		Recording.Scene scene = r.scenes.get(0);
		assertEquals(0, SnapshotTerrain.build(scene, under(), over()));
		assertFalse(scene.terrain.sampled);
		assertArrayEquals(new int[]{0, 50, 51, 52, 53, -1}, scene.terrain.tile(3200, 3200, 0).entry);
	}

	@Test public void packsAndLightsLikeTheClient()
	{
		assertEquals((10 / 4 << 10) + (200 / 32 << 7) + 100 / 2, SnapshotTerrain.packHsl(10, 200, 100));
		assertEquals(250 / 2, SnapshotTerrain.packHsl(0, 200, 250)); // saturation halves four times, to 12
		assertEquals((5 << 7) + 2, SnapshotTerrain.shade((5 << 7) + 1, 84));
		assertEquals((5 << 7) + 126, SnapshotTerrain.shade((5 << 7) + 127, 200));
		assertEquals(126, SnapshotTerrain.shade(-1, 300));
	}
}
