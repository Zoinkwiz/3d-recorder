package com.extrinsiccognition.embertide.viewer;

import java.util.ArrayList;
import java.util.List;

/** The thirteen cache tile masks. Coordinates use a 128-unit tile, north is +z. */
final class TileGeometry
{
	private static final int[][] VERTICES = {
		{1,3,5,7}, {1,3,5,7}, {1,3,5,7}, {1,3,5,7,6}, {1,3,5,7,6}, {1,3,5,7,6},
		{1,3,5,7,6}, {1,3,5,7,2,6}, {1,3,5,7,2,8}, {1,3,5,7,2,8},
		{1,3,5,7,11,12}, {1,3,5,7,11,12}, {1,3,5,7,13,14}
	};
	// Entries are material (0 underlay, 1 overlay), then three vertex indices.
	private static final int[][] FACES = {
		{0,1,2,3,0,0,1,3}, {1,1,2,3,1,0,1,3}, {0,1,2,3,1,0,1,3},
		{0,0,1,2,0,0,2,4,1,0,4,3}, {0,0,1,4,0,0,4,3,1,1,2,4},
		{0,0,4,3,1,0,1,2,1,0,2,4}, {0,1,2,4,1,0,1,4,1,0,4,3},
		{0,4,1,2,0,4,2,5,1,0,4,5,1,0,5,3}, {0,4,1,2,0,4,2,3,0,4,3,5,1,0,4,5},
		{0,0,4,5,1,4,1,2,1,4,2,3,1,4,3,5},
		{0,0,1,5,0,1,4,5,0,1,2,4,1,0,5,3,1,5,4,3,1,4,2,3},
		{1,0,1,5,1,1,4,5,1,1,2,4,0,0,5,3,0,5,4,3,0,4,2,3},
		{1,0,5,4,1,0,1,5,0,0,4,3,0,4,5,3,0,5,2,3,0,1,2,5}
	};
	private static final int[][] POINTS = {
		{0,0}, {64,0}, {128,0}, {128,64}, {128,128}, {64,128}, {0,128}, {0,64},
		{64,32}, {96,64}, {64,96}, {32,64}, {32,32}, {96,32}, {96,96}, {32,96}
	};

	static List<Face> faces(int shape, int rotation)
	{
		if (shape < 0 || shape >= VERTICES.length)
		{
			throw new IllegalArgumentException("Unknown floor shape " + shape);
		}
		Vertex[] vertices = new Vertex[VERTICES[shape].length];
		for (int i = 0; i < vertices.length; i++)
		{
			int code = VERTICES[shape][i];
			if (code <= 8 && (code & 1) == 0)
			{
				code = ((code - 2 * rotation - 1) & 7) + 1;
			}
			else if (code > 8 && code <= 12)
			{
				code = ((code - 9 - rotation) & 3) + 9;
			}
			else if (code > 12)
			{
				code = ((code - 13 - rotation) & 3) + 13;
			}
			int corner = code <= 8 ? (code - 1) / 2 : code <= 12 ? code - 9 : code - 13;
			boolean midpoint = code <= 8 ? (code & 1) == 0 : code <= 12;
			vertices[i] = new Vertex(POINTS[code - 1][0], POINTS[code - 1][1], corner, midpoint ? (corner + 1) & 3 : corner);
		}
		List<Face> result = new ArrayList<>();
		int[] mask = FACES[shape];
		for (int i = 0; i < mask.length; i += 4)
		{
			Vertex[] points = new Vertex[3];
			for (int j = 0; j < 3; j++)
			{
				int index = mask[i + 1 + j];
				points[j] = vertices[index < 4 ? (index - rotation) & 3 : index];
			}
			result.add(new Face(mask[i] == 1, points));
		}
		return result;
	}
	static final class Vertex
	{
		final int x, z, cornerA, cornerB;

		Vertex(int x, int z, int a, int b)
		{
			this.x = x;
			this.z = z;
			cornerA = a;
			cornerB = b;
		}

		double interpolate(double[] values) { return (values[cornerA] + values[cornerB]) / 2; }
	}
	static final class Face
	{
		final boolean overlay; final Vertex[] vertices;

		Face(boolean overlay, Vertex[] vertices)
		{
			this.overlay = overlay;
			this.vertices = vertices;
		}
	}
}
