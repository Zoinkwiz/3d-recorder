package com.extrinsiccognition.embertide.viewer;

import com.extrinsiccognition.embertide.GroundRows;
import java.io.IOException;
import org.junit.Test;
import static com.extrinsiccognition.embertide.viewer.GroundFixture.*;
import static org.junit.Assert.*;

public class TerrainTest
{
	private static final int BASE_X = 3176, BASE_Y = 3168;

	/** Plane p corner height (positive down, as the client holds it) is -(10 + x) * (p + 1). */
	private static Recording recording(PlaneTiles tiles, int... bridges) throws IOException
	{
		return RecordingReaderTest.parse(RecordingReaderTest.HEADER + RecordingReaderTest.pose(0, 3200, 0)
			+ scene(0, BASE_X, BASE_Y, tiles, (p, x, y) -> -(10 + x) * (p + 1), bridges));
	}

	@Test public void readsPaintsAndShapedTilesWithTheirColoursTexturesAndHeights() throws Exception
	{
		Terrain t = recording((p, x, y) -> p != 0 ? null : x == 34 && y == 42 ? shaped(5, 3, 201, 202, 203, -1, 204, 205, 206, 31)
			: x == 34 && y == 43 ? paint(100, 101, 102, 103, 7) : paint(50, 51, 52, 53)).scenes.get(0).terrain;
		assertTrue(t.recorded());
		Terrain.Tile paint = t.tile(BASE_X + 10, BASE_Y + 20, 0);
		assertArrayEquals(new int[]{0, 50, 51, 52, 53, -1}, paint.entry);
		assertArrayEquals(new double[]{20, 21, 21, 20}, paint.heights, 0); assertEquals(20.5, paint.height(.5, .5), 0);
		assertArrayEquals(new int[]{0, 100, 101, 102, 103, 7}, t.tile(BASE_X + 34, BASE_Y + 43, 0).entry);
		Terrain.Tile shaped = t.tile(BASE_X + 34, BASE_Y + 42, 0);
		assertFalse(shaped.paint()); assertEquals(5, shaped.shape()); assertEquals(3, shaped.rotation()); assertEquals(2, shaped.faces());
		assertArrayEquals(new int[]{5, 3, 201, 202, 203, -1, 204, 205, 206, 31}, shaped.entry);
		assertNull(t.tile(BASE_X + 10, BASE_Y + 20, 1));
		assertEquals(21.25, t.surface(BASE_X + 11.25, BASE_Y + 20.5, 0), 1e-9);
	}

	@Test public void aBridgeDrawsTheUpperFloorOnTheDisplayPlaneAndKeepsEveryMapFloor() throws Exception
	{
		Terrain t = recording((p, x, y) -> paint(p * 100, p * 100, p * 100, p * 100), 10, 10).scenes.get(0).terrain;
		int x = BASE_X + 10, y = BASE_Y + 10;
		assertEquals(100, t.tile(x, y, 0).entry[1]);
		assertArrayEquals(new double[]{40, 42, 42, 40}, t.tile(x, y, 0).heights, 0);
		for (int p = 0; p < 4; p++)
		{
			Terrain.Tile floor = t.floorTile(x, y, p);
			assertEquals(p * 100, floor.entry[1]); assertTrue(floor.physicalPlane);
			assertEquals(20.0 * (p + 1), t.height(x, y, p, true), 0);
		}
		assertEquals(0, t.tile(x + 1, y, 0).entry[1]);
	}

	@Test public void tilesOutsideTheLoadedSceneHaveNoGroundOrHeight() throws Exception
	{
		Terrain t = recording((p, x, y) -> paint(1, 1, 1, 1)).scenes.get(0).terrain;
		assertNull(t.tile(BASE_X + 104, BASE_Y, 0)); assertNull(t.tile(BASE_X - 1, BASE_Y, 0));
		assertNotNull(t.tile(BASE_X + 103, BASE_Y + 103, 3));
		assertTrue(Double.isNaN(t.height(BASE_X + 106, BASE_Y, 0)));
	}

	@Test public void aLaterSceneLoadReplacesTheTilesItCoversAndKeepsTheRest() throws Exception
	{
		Recording r = RecordingReaderTest.parse(RecordingReaderTest.HEADER + RecordingReaderTest.pose(0, 3200, 0)
			+ scene(0, BASE_X, BASE_Y, (p, x, y) -> p == 0 ? paint(1, 1, 1, 1) : null, (p, x, y) -> 0)
			+ scene(1, BASE_X + 64, BASE_Y, (p, x, y) -> p == 0 && x > 0 ? paint(2, 2, 2, 2) : null, (p, x, y) -> -8));
		Terrain t = r.scenes.get(0).terrain;
		assertEquals(1, t.tile(BASE_X + 63, BASE_Y, 0).entry[1]);
		assertNull(t.tile(BASE_X + 64, BASE_Y, 0));
		assertEquals(2, t.tile(BASE_X + 65, BASE_Y, 0).entry[1]);
		assertEquals(2, t.tile(BASE_X + 167, BASE_Y, 0).entry[1]);
		assertEquals(8, t.height(BASE_X + 70, BASE_Y + 3, 0), 0);
	}

	@Test public void actorsStandOnTheRecordedGroundOrTheOldInstanceHeights() throws Exception
	{
		Recording.Scene scene = recording((p, x, y) -> p == 0 ? paint(1, 1, 1, 1) : null).scenes.get(0);
		assertEquals(66.75, scene.height(BASE_X + 56.75, BASE_Y + 3.25, 0), 1e-9);
		Recording old = RecordingReaderTest.parse(RecordingReaderTest.HEADER + RecordingReaderTest.pose(0, 3200, 0)
			+ RecordingReaderTest.event(0, "mccr.scene", "{\"dimension\":\"osrs:instance:1\",\"reset\":true}")
			+ RecordingReaderTest.event(0, "osrs.region", "{\"instance\":true,\"base_x\":1000,\"base_y\":2000,\"regions\":[]}")
			+ RecordingReaderTest.event(0, "osrs.instance_heights", "{\"base_x\":1000,\"base_y\":2000,\"plane\":0,\"heights\":" + grid(-500) + "}")
			+ RecordingReaderTest.pose(0, 1002, 0));
		Recording.Scene instance = old.scenes.get(1);
		assertFalse(instance.terrain.recorded()); assertNull(instance.terrain.tile(1002, 2003, 0));
		assertEquals(500, instance.height(1002.5, 2003.5, 0), 0);
	}

	@Test public void rejectsMalformedGroundRows()
	{
		String[] bad = {
			"{\"base_x\":3176,\"base_y\":3168,\"plane\":0,\"x\":100,\"columns\":5,\"tiles\":[[],[],[],[],[]]}",
			"{\"base_x\":3176,\"base_y\":3168,\"plane\":0,\"x\":0,\"columns\":1,\"tiles\":[[1]]}",
			"{\"base_x\":3176,\"base_y\":3168,\"plane\":0,\"x\":0,\"columns\":1,\"tiles\":[" + column("[-13,0,1,0,0,-1]") + "]}",
			"{\"base_x\":3176,\"base_y\":3168,\"plane\":0,\"x\":0,\"columns\":1,\"tiles\":[" + column("[-5,0,1,0,0]") + "]}",
			"{\"base_x\":3176,\"base_y\":3168,\"plane\":0,\"x\":0,\"columns\":1,\"tiles\":[" + column("[1,0,0,0,-2]") + "]}",
			"{\"base_x\":3176,\"base_y\":3168,\"plane\":1,\"x\":0,\"columns\":0,\"tiles\":[],\"bridges\":[104,3]}",
		};
		for (String row : bad)
		{
			try { RecordingReaderTest.parse(RecordingReaderTest.HEADER + RecordingReaderTest.pose(0, 3200, 0) + RecordingReaderTest.event(0, "osrs.ground", row)); fail(row); }
			catch (IOException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains("Invalid")); }
		}
	}

	@Test public void aRecordingWithoutGroundRowsHasNoGround() throws Exception
	{
		Recording r = RecordingReaderTest.parse(RecordingReaderTest.HEADER + RecordingReaderTest.pose(0, 3200, 0)
			+ RecordingReaderTest.event(0, "mccr.spatial_snapshot", "{\"cells\":[[3200,0,-3200,\"osrs:underlay:10\"]]}"));
		assertFalse(r.scenes.get(0).terrain.recorded());
		assertNull(r.scenes.get(0).terrain.tile(3200, 3200, 0));
	}

	private static String column(String first)
	{
		StringBuilder out = new StringBuilder("[").append(first);
		for (int y = 1; y < GroundRows.SIZE; y++) { out.append(",0"); }
		return out.append(']').toString();
	}
	static String grid(int value)
	{
		StringBuilder out = new StringBuilder("[");
		for (int x = 0; x < 105; x++)
		{
			out.append(x == 0 ? "[" : ",[");
			for (int y = 0; y < 105; y++) { out.append(y == 0 ? "" : ",").append(value); }
			out.append(']');
		}
		return out.append(']').toString();
	}
}
