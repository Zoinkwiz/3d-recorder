package com.extrinsiccognition.embertide.viewer;

import com.extrinsiccognition.embertide.GroundRows;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Ground faces take the colours and textures the client drew, per vertex, from the recorded tile. */
public class TerrainModelsTest
{
	private final Client client = mock(Client.class);
	private final List<ModelData> parts = new ArrayList<>();
	private final ModelData copy = mock(ModelData.class);
	private Model merged;

	public TerrainModelsTest()
	{
		ModelData textured = mock(ModelData.class), triangle = mock(ModelData.class);
		when(client.loadModelData(3728)).thenReturn(textured); when(client.loadModelData(823)).thenReturn(triangle);
		when(copy.getFaceCount()).thenReturn(2); when(copy.getVerticesCount()).thenReturn(6);
		when(copy.getFaceTextures()).thenReturn(new short[2]);
		when(copy.getFaceIndices1()).thenReturn(new int[2]); when(copy.getFaceIndices2()).thenReturn(new int[2]); when(copy.getFaceIndices3()).thenReturn(new int[2]);
		Model probe = mock(Model.class);
		when(probe.getTextureFaces()).thenReturn(new byte[]{0, -1});
		when(probe.getTexIndices1()).thenReturn(new int[]{3}); when(probe.getTexIndices2()).thenReturn(new int[]{4}); when(probe.getTexIndices3()).thenReturn(new int[]{5});
		when(probe.getFaceColors3()).thenReturn(new int[2]);
		when(copy.light()).thenReturn(probe);
		when(copy.shallowCopy()).thenAnswer(call -> part());
		when(client.mergeModels(any(ModelData[].class))).thenAnswer(call ->
		{
			ModelData[] args = (ModelData[]) call.getRawArguments()[0];
			if (args.length == 2 && args[0] == textured) { return copy; }
			if (args.length == 1 && args[0] == copy) { return copy; }
			ModelData result = mock(ModelData.class); merged = mock(Model.class);
			when(merged.getFaceColors1()).thenReturn(new int[args.length * 2]);
			when(merged.getFaceColors2()).thenReturn(new int[args.length * 2]);
			when(merged.getFaceColors3()).thenReturn(new int[args.length * 2]);
			when(result.light()).thenReturn(merged);
			return result;
		});
	}

	private ModelData part()
	{
		ModelData part = mock(ModelData.class);
		when(part.cloneVertices()).thenReturn(part); when(part.cloneColors()).thenReturn(part);
		when(part.cloneTextures()).thenReturn(part); when(part.cloneTransparencies()).thenReturn(part);
		float[] x = new float[6], y = new float[6], z = new float[6];
		when(part.getVerticesX()).thenReturn(x); when(part.getVerticesY()).thenReturn(y); when(part.getVerticesZ()).thenReturn(z);
		when(part.getFaceColors()).thenReturn(new short[2]); when(part.getFaceTextures()).thenReturn(new short[2]);
		parts.add(part); return part;
	}

	private static Terrain.Tile tile(int[] entry)
	{ return new Terrain.Tile(10, 20, 0, entry, new double[]{0, 8, 16, 8}, false); }

	@Test public void aPaintTakesItsFourCornerColours()
	{
		TerrainModels models = new TerrainModels(client);
		Model model = models.patch(List.of(tile(new int[]{0, 1000, 1001, 1002, 1003, -1})), 10, 20, 0);
		assertSame(merged, model); assertEquals(2, parts.size());
		// Face 0 is SE, NE, NW; face 1 is SW, SE, NW. The spare allocation faces stay hidden.
		assertArrayEquals(new int[]{1001, 0, 1000, 0}, model.getFaceColors1());
		assertArrayEquals(new int[]{1002, 0, 1001, 0}, model.getFaceColors2());
		assertArrayEquals(new int[]{1003, -2, 1003, -2}, model.getFaceColors3());
		assertEquals(-1, parts.get(0).getFaceTextures()[0]);
		// The NE vertex of face 0 is 16 up: the template's height axis points down.
		assertEquals(-16, parts.get(0).getVerticesY()[1], 0);
	}

	@Test public void hiddenCornersDropTheirTrianglesAndTexturesKeepLightness()
	{
		TerrainModels models = new TerrainModels(client);
		int hidden = GroundRows.HIDDEN;
		Model model = models.patch(List.of(tile(new int[]{0, hidden, 0x2a40, 0x2a50, 0x2a60, 7})), 10, 20, 0);
		assertEquals(1, parts.size());
		assertEquals(7, parts.get(0).getFaceTextures()[0]); assertEquals(127, parts.get(0).getFaceColors()[0]);
		assertArrayEquals(new int[]{0x40, 0}, model.getFaceColors1());
		assertArrayEquals(new int[]{0x50, 0}, model.getFaceColors2());
		assertArrayEquals(new int[]{0x60, -2}, model.getFaceColors3());
	}

	@Test public void aShapedTileUsesEachFacesColoursAndSkipsHiddenFaces()
	{
		TerrainModels models = new TerrainModels(client);
		Model model = models.patch(List.of(tile(new int[]{5, 0, 11, 12, 13, -1, GroundRows.HIDDEN, 0, 0, -1, 21, 22, 23, 31})), 10, 20, 0);
		assertEquals(2, parts.size());
		assertArrayEquals(new int[]{11, 0, 21 & 127, 0}, model.getFaceColors1());
		assertEquals(31, parts.get(1).getFaceTextures()[0]);
		assertEquals(0, models.mismatched());
	}

	@Test public void aShapedTileWithTheWrongFaceCountIsLeftOutAndCounted()
	{
		TerrainModels models = new TerrainModels(client);
		Model model = models.patch(List.of(tile(new int[]{5, 0, 11, 12, 13, -1, 21, 22, 23, -1})), 10, 20, 0);
		assertTrue(parts.isEmpty()); assertEquals(1, models.mismatched());
		assertArrayEquals(new int[]{-2, -2}, model.getFaceColors3());
	}
}
