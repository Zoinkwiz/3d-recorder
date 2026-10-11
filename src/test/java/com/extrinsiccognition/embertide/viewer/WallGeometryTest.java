package com.extrinsiccognition.embertide.viewer;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.Mesh;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class WallGeometryTest
{
	@Test public void cornersContainBothPerpendicularSidesInEveryOrientation()
	{
		for (int orientation = 0; orientation < 4; orientation++)
		{
			Fixture fixture = new Fixture(false); Model corner = fixture.model(2, orientation);
			assertEquals(4, corner.getFaceCount());
			Set<String> sides = new HashSet<>();
			for (int f = 0; f < corner.getFaceCount(); f++)
			{
				int a = corner.getFaceIndices1()[f], b = corner.getFaceIndices2()[f], c = corner.getFaceIndices3()[f];
				if (corner.getVerticesX()[a] == corner.getVerticesX()[b] && corner.getVerticesX()[b] == corner.getVerticesX()[c])
				{ sides.add("x=" + (int) corner.getVerticesX()[a]); }
				else
				{
					assertEquals(corner.getVerticesZ()[a], corner.getVerticesZ()[b], 0);
					assertEquals(corner.getVerticesZ()[b], corner.getVerticesZ()[c], 0);
					sides.add("z=" + (int) corner.getVerticesZ()[a]);
				}
			}
			Set<String> expected = Set.of("x=" + (orientation == 0 || orientation == 3 ? -64 : 64),
				"z=" + (orientation < 2 ? 64 : -64));
			assertEquals(expected, sides); fixture.assertDonorUnchanged();
		}
	}

	@Test public void cornerFirstSideFlipsTheDefinitionMirrorAndKeepsItsWinding()
	{
		for (boolean rotated : new boolean[]{false, true})
		{
			Fixture fixture = new Fixture(rotated); Model corner = fixture.model(2, 0);
			// First side: native orientation 4 toggles mirroring. Both the Z
			// coordinates and triangle winding reverse, even for rotated locs.
			int first = corner.getFaceIndices1()[0];
			assertEquals(-64, corner.getVerticesX()[first], 0);
			assertEquals(-64, corner.getVerticesZ()[first], 0);
			assertEquals(rotated ? 0 : -240, corner.getVerticesY()[first], 0);
			fixture.assertDonorUnchanged();
		}
	}

	@Test public void straightWallsStaySingleAndMissingModelsRetryWithoutPartialCorners()
	{
		Fixture fixture = new Fixture(false); assertEquals(2, fixture.model(0, 0).getFaceCount());
		when(fixture.client.loadModelData(1)).thenReturn(null);
		fixture.models.beginTick();
		assertNull(fixture.models.scenery(new Recording.Scenery(0, 0, 0, 0, 42, 2, 2, 1, true)));
		fixture.assertDonorUnchanged();
	}

	private static final class Fixture
	{
		final Client client = mock(Client.class, RETURNS_DEEP_STUBS);
		final ModelData donor = data(new float[]{-64,-64,-64,-64}, new float[]{0,-240,-240,0},
			new float[]{-64,-64,64,64}, new int[]{0,0}, new int[]{1,2}, new int[]{2,3});
		final CacheModels models = new CacheModels(client);
		Fixture(boolean rotated)
		{
			// One model, selectable as a straight wall or a corner side.
			byte[] definition = {1,2,0,1,0,0,1,2,62,0};
			if (!rotated) { definition[8] = 0; }
			when(client.getIndexConfig().loadData(6,42)).thenReturn(definition);
			when(client.loadModelData(1)).thenReturn(donor);
			when(client.mergeModels(any(ModelData[].class))).thenAnswer(call ->
			{
				ModelData[] parts = call.getArgument(0) instanceof ModelData[] ? call.getArgument(0)
					: Arrays.copyOf(call.getArguments(), call.getArguments().length, ModelData[].class);
				int nv = Arrays.stream(parts).mapToInt(Mesh::getVerticesCount).sum(), nf = Arrays.stream(parts).mapToInt(Mesh::getFaceCount).sum();
				float[] x = new float[nv], y = new float[nv], z = new float[nv];
				int[] a = new int[nf], b = new int[nf], c = new int[nf]; int vi = 0, fi = 0;
				for (ModelData part : parts)
				{
					System.arraycopy(part.getVerticesX(),0,x,vi,part.getVerticesCount());
					System.arraycopy(part.getVerticesY(),0,y,vi,part.getVerticesCount());
					System.arraycopy(part.getVerticesZ(),0,z,vi,part.getVerticesCount());
					for (int i=0;i<part.getFaceCount();i++)
					{ a[fi]=part.getFaceIndices1()[i]+vi; b[fi]=part.getFaceIndices2()[i]+vi; c[fi++]=part.getFaceIndices3()[i]+vi; }
					vi+=part.getVerticesCount();
				}
				return data(x,y,z,a,b,c);
			});
		}
		Model model(int type,int orientation)
		{ models.beginTick(); return models.scenery(new Recording.Scenery(0,0,0,0,42,type,orientation,1,true)); }
		void assertDonorUnchanged()
		{
			assertArrayEquals(new float[]{-64,-64,64,64},donor.getVerticesZ(),0);
			assertArrayEquals(new int[]{0,0},donor.getFaceIndices1());
			assertArrayEquals(new int[]{2,3},donor.getFaceIndices3());
		}
	}

	private static ModelData data(float[] x,float[] y,float[] z,int[] a,int[] b,int[] c)
	{
		ModelData data=mock(ModelData.class); Model model=mock(Model.class);
		for (Mesh<?> mesh : new Mesh<?>[]{data,model})
		{
			when(mesh.getVerticesCount()).thenReturn(x.length); when(mesh.getFaceCount()).thenReturn(a.length);
			when(mesh.getVerticesX()).thenReturn(x); when(mesh.getVerticesY()).thenReturn(y); when(mesh.getVerticesZ()).thenReturn(z);
			when(mesh.getFaceIndices1()).thenReturn(a); when(mesh.getFaceIndices2()).thenReturn(b); when(mesh.getFaceIndices3()).thenReturn(c);
		}
		when(data.rotateY90Ccw()).thenAnswer(call -> { rotate(x,z,1); return data; });
		when(data.rotateY180Ccw()).thenAnswer(call -> { rotate(x,z,2); return data; });
		when(data.rotateY270Ccw()).thenAnswer(call -> { rotate(x,z,3); return data; });
		when(data.light(anyInt(),anyInt(),anyInt(),anyInt(),anyInt())).thenReturn(model);
		return data;
	}
	private static void rotate(float[] x,float[] z,int turns)
	{
		for (int turn=0;turn<turns;turn++)
		{ for (int i=0;i<x.length;i++) { float old=x[i]; x[i]=z[i]; z[i]=-old; } }
	}
}
