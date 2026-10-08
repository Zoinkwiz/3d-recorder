package com.extrinsiccognition.embertide.viewer;

import org.junit.Test;
import static org.junit.Assert.*;

public class TileGeometryTest
{
	@Test public void everyMaskAndRotationCoversExactlyOneTileWithUpwardFaces()
	{
		for (int shape = 0; shape < 13; shape++) { for (int rotation = 0; rotation < 4; rotation++)
		{
			double area = 0;
			for (TileGeometry.Face face : TileGeometry.faces(shape, rotation))
			{
				TileGeometry.Vertex a = face.vertices[0], b = face.vertices[1], c = face.vertices[2];
				double triangle = ((b.x - a.x) * (c.z - a.z) - (b.z - a.z) * (c.x - a.x)) / 2.0;
				assertTrue("Back-facing or degenerate shape " + shape, triangle > 0); area += triangle;
				for (TileGeometry.Vertex v : face.vertices) { assertTrue(v.x >= 0 && v.x <= 128 && v.z >= 0 && v.z <= 128); }
			}
			assertEquals("Mask " + shape + " rotation " + rotation, 128 * 128, area, 0);
		} }
	}
	@Test public void diagonalOverlayCoversOnlyHalfTile()
	{
		for (int rotation = 0; rotation < 4; rotation++)
		{ assertEquals(1, TileGeometry.faces(2, rotation).stream().filter(f -> f.overlay).count()); }
	}
	@Test public void rotatesDiagonalEndpoints()
	{
		TileGeometry.Face original = TileGeometry.faces(2, 0).get(1), rotated = TileGeometry.faces(2, 1).get(1);
		assertEquals(0, original.vertices[0].x); assertEquals(0, original.vertices[0].z);
		assertEquals(0, rotated.vertices[0].x); assertEquals(128, rotated.vertices[0].z);
	}
	@Test public void interpolationUsesCornerHeightsForSlopedEdges()
	{
		TileGeometry.Vertex midpoint = TileGeometry.faces(3, 0).get(1).vertices[2];
		assertEquals(64, midpoint.x); assertEquals(128, midpoint.z);
		assertEquals(300, midpoint.interpolate(new double[]{0,100,200,400}), 0);
	}
	@Test(expected = IllegalArgumentException.class) public void rejectsUnknownShape() { TileGeometry.faces(13, 0); }
}
