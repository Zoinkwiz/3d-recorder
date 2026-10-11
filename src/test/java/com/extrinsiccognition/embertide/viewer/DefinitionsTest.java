package com.extrinsiccognition.embertide.viewer;

import org.junit.Test;
import static org.junit.Assert.*;

public class DefinitionsTest
{
	private static byte[] bytes(int... values)
	{ byte[] out = new byte[values.length]; for (int i = 0; i < values.length; i++) { out[i] = (byte) values[i]; } return out; }

	@Test public void readsAnObjectsModelsTransformsAndSkipsWhatItDoesNotUse()
	{
		Definitions.ObjectDef d = Definitions.object(bytes(
			1, 2, 0, 5, 10, 0, 6, 22,      // two models with types
			2, 'D', 'o', 'o', 'r', 0,      // name
			14, 2, 15, 3, 24, 0, 9,        // size 2x3, animation 9
			29, 0xF6, 39, 2,               // ambient -10, contrast 2 * 25
			40, 1, 0, 7, 0, 8,             // recolour 7 -> 8
			62, 65, 0, 64, 70, 0, 4,       // rotated, scale x 64, offset x 4
			77, 0xff, 0xff, 0, 1, 1, 0, 3, 0, 4,  // impostors
			249, 1, 1, 0, 0, 1, 'x', 0,    // a string param
			0));
		assertArrayEquals(new int[]{5, 6}, d.models); assertArrayEquals(new int[]{10, 22}, d.types);
		assertEquals(2, d.sizeX); assertEquals(3, d.sizeY); assertEquals(9, d.animation);
		assertEquals(-10, d.ambient); assertEquals(50, d.contrast);
		assertArrayEquals(new short[]{7}, d.recolorFrom); assertArrayEquals(new short[]{8}, d.recolorTo);
		assertTrue(d.rotated); assertEquals(64, d.scaleX); assertEquals(128, d.scaleY); assertEquals(4, d.offsetX); assertTrue(d.impostors);
		assertEquals(-1, d.fullRecolor);
	}

	@Test public void readsNpcItemAndKitModels()
	{
		Definitions.NpcDef npc = Definitions.npc(bytes(1, 1, 0, 42, 97, 0, 64, 98, 0, 200, 100, 5, 0));
		assertArrayEquals(new int[]{42}, npc.models); assertEquals(64, npc.widthScale); assertEquals(200, npc.heightScale); assertEquals(5, npc.ambient);
		Definitions.ItemDef item = Definitions.item(bytes(23, 1, 0, 3, 25, 1, 1, 5, 47, 0, 1, 0, 0, 0));
		assertEquals(256, item.maleModel0); assertEquals(3, item.maleOffset); assertEquals(257, item.femaleModel0);
		assertEquals(5, item.femaleOffset); assertEquals(65536, item.maleModel2); assertEquals(-1, item.femaleModel1);
		Definitions.KitDef kit = Definitions.kit(bytes(1, 4, 2, 2, 0, 1, 0, 2, 0));
		assertArrayEquals(new int[]{1, 2}, kit.models);
	}

	@Test public void anUnknownOpcodeOrTruncatedDefinitionIsRefused()
	{
		for (byte[] bad : new byte[][]{bytes(200, 0), bytes(14), bytes(1, 2, 0, 5)})
		{
			try { Definitions.object(bad); fail(); }
			catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
		}
		try { Definitions.kit(bytes(99, 0)); fail(); } catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("kit")); }
	}

	@Test public void readsUnderlaysAndOverlaysWithTheClientsColourMaths()
	{
		Definitions.UnderlayDef red = Definitions.underlay(bytes(1, 0xff, 0, 0, 0));
		assertEquals(0xff0000, red.rgb); assertEquals(255, red.saturation); assertEquals(127, red.lightness);
		assertEquals(255, red.hueMultiplier); assertEquals(0, red.hue);
		Definitions.UnderlayDef grey = Definitions.underlay(bytes(1, 0x80, 0x80, 0x80, 0));
		assertEquals(0, grey.saturation); assertEquals(128, grey.lightness); assertEquals(1, grey.hueMultiplier);
		// Green is a third of the way round: hue scales by the multiplier, which blending divides out again.
		Definitions.UnderlayDef green = Definitions.underlay(bytes(1, 0, 0xff, 0, 0));
		assertTrue(Math.abs(green.hueMultiplier / 3 - green.hue) <= 1);

		Definitions.OverlayDef plain = Definitions.overlay(bytes(1, 0, 0, 0xff, 0));
		assertEquals(0xff, plain.rgb); assertEquals(-1, plain.texture); assertEquals(-1, plain.secondaryRgb);
		assertTrue(plain.hideUnderlay); assertFalse(plain.hidden()); assertEquals(170, plain.hue); assertEquals(127, plain.lightness);
		Definitions.OverlayDef textured = Definitions.overlay(bytes(1, 0xff, 0, 0xff, 2, 9, 5, 7, 1, 2, 3, 0));
		assertEquals(9, textured.texture); assertEquals(0x010203, textured.secondaryRgb); assertFalse(textured.hideUnderlay);
		assertFalse(textured.hidden());
		assertTrue(Definitions.overlay(bytes(1, 0xff, 0, 0xff, 0)).hidden());
	}

	@Test public void anUnknownFloorOpcodeOrTruncatedFloorIsRefused()
	{
		for (byte[] bad : new byte[][]{bytes(2, 0), bytes(1, 0xff), bytes(1, 0, 0, 0)})
		{
			try { Definitions.underlay(bad); fail(); } catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
		}
		for (byte[] bad : new byte[][]{bytes(8, 0), bytes(7, 1, 2), bytes(2)})
		{
			try { Definitions.overlay(bad); fail(); } catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
		}
	}
}
