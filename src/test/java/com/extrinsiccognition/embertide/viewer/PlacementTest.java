package com.extrinsiccognition.embertide.viewer;

import org.junit.Test;
import static org.junit.Assert.*;

public class PlacementTest
{
	private Placement p(double scale, int turn) { return new Placement(3200.5, 3200.5, 256, scale, 6400, 6400, -100, 0, -1, turn); }
	@Test public void lifeSizeKeepsTileDistances()
	{ Placement p = p(1, 0); assertEquals(6528, p.x(3201.5, 3200.5)); assertEquals(6400, p.y(3201.5, 3200.5)); }
	@Test public void miniatureShrinksPositionAndHeightTogether()
	{ Placement p = p(.25, 0); assertEquals(6432, p.x(3201.5, 3200.5)); assertEquals(-132, p.z(384)); }
	@Test public void rotationAndFacingAgree()
	{ Placement p = p(1, 1); assertEquals(6400, p.x(3201.5, 3200.5)); assertEquals(6272, p.y(3201.5, 3200.5)); assertEquals(0, p.orientation(1536)); }
	@Test public void rotationReturnsToSameAnchor()
	{ for (int turn = 0; turn < 8; turn++) { Placement p = p(1, turn); assertEquals(6400, p.x(3200.5, 3200.5)); assertEquals(6400, p.y(3200.5, 3200.5)); assertEquals(-100, p.z(256)); } }
}
