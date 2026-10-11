package com.extrinsiccognition.embertide.viewer;

/** One rigid transform, shared by terrain, scenery and every animated actor. */
final class Placement
{
	final double sourceX, sourceY, sourceHeight, scale;
	final int anchorX, anchorY, anchorZ, plane, worldView, quarterTurns;

	Placement(double sourceX, double sourceY, double sourceHeight, double scale,
		int anchorX, int anchorY, int anchorZ, int plane, int worldView, int quarterTurns)
	{
		this.sourceX = sourceX; this.sourceY = sourceY; this.sourceHeight = sourceHeight;
		this.scale = scale; this.anchorX = anchorX; this.anchorY = anchorY; this.anchorZ = anchorZ;
		this.plane = plane; this.worldView = worldView; this.quarterTurns = Math.floorMod(quarterTurns, 4);
	}
	int x(double x, double y) { return anchorX + (int) Math.round(rotateX(x - sourceX, y - sourceY) * 128 * scale); }
	int y(double x, double y) { return anchorY + (int) Math.round(rotateY(x - sourceX, y - sourceY) * 128 * scale); }
	int z(double height) { return anchorZ - (int) Math.round((height - sourceHeight) * scale); }
	int orientation(int orientation) { return (orientation + quarterTurns * 512) & 2047; }
	private double rotateX(double x, double y)
	{
		switch (quarterTurns) { case 1: return y; case 2: return -x; case 3: return -y; default: return x; }
	}
	private double rotateY(double x, double y)
	{
		switch (quarterTurns) { case 1: return -x; case 2: return -y; case 3: return x; default: return y; }
	}
}
