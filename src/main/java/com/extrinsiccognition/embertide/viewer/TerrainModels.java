package com.extrinsiccognition.embertide.viewer;

import com.extrinsiccognition.embertide.GroundRows;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;

/** Converts recorded ground tiles into private native models, with the colours and textures the client drew. */
final class TerrainModels
{
	private final Client client;
	private ModelData template;
	private int[] faceVertices, uvVertices;
	private int mismatched;
	TerrainModels(Client client) { this.client = client; }

	void clear()
	{
		template = null;
		mismatched = 0;
	}

	/** Shaped tiles whose recorded face count does not match the mask; they are left out. */
	int mismatched() { return mismatched; }

	Model patch(List<Terrain.Tile> tiles, double originX, double originY, double originHeight)
	{
		if (!template())
		{
			return null;
		}
		List<ModelData> parts = new ArrayList<>();
		List<int[]> colours = new ArrayList<>();
		for (Terrain.Tile tile : tiles)
		{
			List<TileGeometry.Face> faces = TileGeometry.faces(tile.paint() ? 0 : tile.shape(), tile.rotation());
			if (faces.size() != tile.faces())
			{
				mismatched++;
				continue;
			}
			for (int f = 0; f < faces.size(); f++)
			{
				TileGeometry.Face face = faces.get(f);
				int[] hsl = new int[3];
				int texture;
				if (tile.paint())
				{
					// Paint vertices are tile corners: SW, SE, NE, NW are entry[1..4].
					for (int i = 0; i < 3; i++) { hsl[i] = tile.entry[1 + face.vertices[i].cornerA]; }
					texture = tile.entry[5];
					if (hsl[0] == GroundRows.HIDDEN || hsl[1] == GroundRows.HIDDEN || hsl[2] == GroundRows.HIDDEN)
					{
						continue;
					}
				}
				else
				{
					int at = 2 + f * 4;
					hsl[0] = tile.entry[at];
					hsl[1] = tile.entry[at + 1];
					hsl[2] = tile.entry[at + 2];
					texture = tile.entry[at + 3];
					if (hsl[0] == GroundRows.HIDDEN)
					{
						continue;
					}
				}
				// Indices belong to our template and stay immutable. Only mutable arrays are cloned.
				ModelData part = template.shallowCopy().cloneVertices().cloneColors().cloneTextures().cloneTransparencies();
				part.getFaceTextures()[0] = (short) texture;
				part.getFaceColors()[0] = (short) (texture >= 0 ? 127 : hsl[0]);
				if (part.getFaceTransparencies() != null)
				{
					Arrays.fill(part.getFaceTransparencies(), (byte) 0);
				}
				int[] shades = new int[3];
				for (int i = 0; i < 3; i++)
				{
					TileGeometry.Vertex v = face.vertices[i];
					vertex(part, faceVertices[i], (tile.x - originX) * 128 + v.x, originHeight - v.interpolate(tile.heights), (tile.y - originY) * 128 + v.z);
					// A textured face keeps only the lightness of its recorded colour.
					shades[i] = texture >= 0 ? clamp(hsl[i] & 127, 2, 126) : hsl[i];
				}
				// Every triangle in this tile uses the same UV plane, including rotated partial overlays.
				vertex(part, uvVertices[0], (tile.x - originX) * 128, originHeight - tile.heights[0], (tile.y - originY) * 128);
				vertex(part, uvVertices[1], (tile.x + 1 - originX) * 128, originHeight - tile.heights[1], (tile.y - originY) * 128);
				vertex(part, uvVertices[2], (tile.x - originX) * 128, originHeight - tile.heights[3], (tile.y + 1 - originY) * 128);
				parts.add(part);
				colours.add(shades);
			}
		}
		if (parts.isEmpty())
		{
			Model empty = client.mergeModels(template).light();
			Arrays.fill(empty.getFaceColors3(), -2);
			return empty;
		}
		if (parts.size() > 240)
		{
			throw new IllegalArgumentException("Terrain patch exceeds texture-index budget");
		}
		Model model = client.mergeModels(parts.toArray(new ModelData[0])).light();
		for (int i = 0; i < colours.size(); i++)
		{
			int[] shades = colours.get(i);
			model.getFaceColors1()[i * 2] = shades[0];
			model.getFaceColors2()[i * 2] = shades[1];
			model.getFaceColors3()[i * 2] = shades[2];
			model.getFaceColors3()[i * 2 + 1] = -2; // Spare allocation face is degenerate and invisible.
		}
		return model;
	}

	private boolean template()
	{
		if (template != null)
		{
			return true;
		}
		ModelData textured = client.loadModelData(3728), triangle = client.loadModelData(823);
		if (textured == null || triangle == null)
		{
			return false;
		}
		// The spare triangle allocates three face vertices independently of the donor's UV vertices.
		ModelData copy = client.mergeModels(textured, triangle);
		Model probe = copy.light();
		if (copy.getFaceCount() != 2 || copy.getVerticesCount() < 6 || copy.getFaceTextures() == null
			|| probe.getTextureFaces() == null || probe.getTexIndices1() == null)
		{
			throw new IllegalStateException("Textured terrain template changed in this cache");
		}
		int texture = probe.getTextureFaces()[0] & 255;
		uvVertices = new int[]{probe.getTexIndices1()[texture], probe.getTexIndices2()[texture], probe.getTexIndices3()[texture]};
		faceVertices = new int[3];
		int count = 0;
		for (int i = 0; i < copy.getVerticesCount() && count < 3; i++)
		{
			if (i != uvVertices[0] && i != uvVertices[1] && i != uvVertices[2])
			{
				faceVertices[count++] = i;
			}
		}
		if (count != 3)
		{
			throw new IllegalStateException("Terrain UV vertices overlap the face");
		}
		copy.getFaceIndices1()[0] = faceVertices[0];
		copy.getFaceIndices2()[0] = faceVertices[1];
		copy.getFaceIndices3()[0] = faceVertices[2];
		copy.getFaceIndices1()[1] = copy.getFaceIndices2()[1] = copy.getFaceIndices3()[1] = uvVertices[0];
		template = copy;
		return true;
	}

	private static void vertex(ModelData model, int i, double x, double y, double z)
	{
		model.getVerticesX()[i] = (float) x;
		model.getVerticesY()[i] = (float) y;
		model.getVerticesZ()[i] = (float) z;
	}

	private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
