package com.extrinsiccognition.embertide.viewer;

/**
 * Object, NPC, item, body-kit, underlay and overlay definitions decoded from config bytes the running client hands over through
 * Client.getIndexConfig(). The API's compositions omit the model ids, recolours and transforms a model needs.
 * Opcodes follow RuneLite's cache loaders (BSD-2, see THIRD-PARTY-NOTICES.md); an unknown opcode fails the
 * definition rather than misreading the rest.
 */
final class Definitions
{
	static final int UNDERLAY = 1, KIT = 3, OVERLAY = 4, OBJECT = 6, NPC = 9, ITEM = 10;

	private Definitions() { }

	static final class ObjectDef
	{
		int[] models, types;
		boolean impostors, rotated;
		short[] recolorFrom, recolorTo, retextureFrom, retextureTo;
		int fullRecolor = -1, sizeX = 1, sizeY = 1, animation = -1, ambient, contrast;
		int scaleX = 128, scaleHeight = 128, scaleY = 128, offsetX, offsetHeight, offsetY;
	}

	static final class NpcDef
	{
		int[] models;
		short[] recolorFrom, recolorTo, retextureFrom, retextureTo;
		int fullRecolor = -1, ambient, contrast, widthScale = 128, heightScale = 128;
	}

	static final class ItemDef
	{
		int maleModel0 = -1, maleModel1 = -1, maleModel2 = -1, femaleModel0 = -1, femaleModel1 = -1, femaleModel2 = -1;
		int maleOffset, femaleOffset, fullRecolor = -1;
		short[] recolorFrom, recolorTo, retextureFrom, retextureTo;
	}

	static final class KitDef
	{
		int[] models;
		short[] recolorFrom, recolorTo, retextureFrom, retextureTo;
	}

	/** A floor colour; hue is pre-multiplied by hueMultiplier, as the client sums it when blending neighbours. */
	static final class UnderlayDef
	{
		int rgb, hue, saturation, lightness, hueMultiplier;
	}

	/** A painted floor; texture -1 is a plain colour and rgb 0xFF00FF is a hidden one. The secondary colour is the minimap's. */
	static final class OverlayDef
	{
		static final int HIDDEN_RGB = 0xFF00FF;
		int rgb, texture = -1, secondaryRgb = -1, hue, saturation, lightness;
		boolean hideUnderlay = true;
		boolean hidden() { return texture < 0 && rgb == HIDDEN_RGB; }
	}

	static UnderlayDef underlay(byte[] bytes)
	{
		In in = new In(bytes); UnderlayDef d = new UnderlayDef();
		for (int op = in.u8(); op != 0; op = in.u8())
		{
			if (op == 1) { d.rgb = in.u24(); }
			else { throw new IllegalArgumentException("Unknown underlay opcode " + op); }
		}
		double[] hsl = hsl(d.rgb); double l = hsl[2], s = hsl[1];
		d.saturation = clamp((int) (s * 256)); d.lightness = clamp((int) (l * 256));
		d.hueMultiplier = Math.max(1, (int) ((l > .5 ? 1 - l : l) * s * 512));
		d.hue = (int) (d.hueMultiplier * hsl[0]);
		return d;
	}

	static OverlayDef overlay(byte[] bytes)
	{
		In in = new In(bytes); OverlayDef d = new OverlayDef();
		for (int op = in.u8(); op != 0; op = in.u8())
		{
			if (op == 1) { d.rgb = in.u24(); }
			else if (op == 2) { d.texture = in.u8(); }
			else if (op == 5) { d.hideUnderlay = false; }
			else if (op == 7) { d.secondaryRgb = in.u24(); }
			else { throw new IllegalArgumentException("Unknown overlay opcode " + op); }
		}
		double[] hsl = hsl(d.rgb);
		d.hue = (int) (256 * hsl[0]); d.saturation = clamp((int) (hsl[1] * 256)); d.lightness = clamp((int) (hsl[2] * 256));
		return d;
	}

	/** The client's RGB to hue (sixths of a turn over six, may be negative), saturation and lightness, all from 0..1 channels of /256. */
	private static double[] hsl(int rgb)
	{
		double r = (rgb >> 16 & 255) / 256.0, g = (rgb >> 8 & 255) / 256.0, b = (rgb & 255) / 256.0;
		double min = Math.min(r, Math.min(g, b)), max = Math.max(r, Math.max(g, b));
		double h = 0, s = 0, l = (min + max) / 2;
		if (min != max)
		{
			if (l < .5) { s = (max - min) / (max + min); }
			if (l >= .5) { s = (max - min) / (2 - max - min); }
			if (r == max) { h = (g - b) / (max - min); }
			else if (g == max) { h = 2 + (b - r) / (max - min); }
			else if (b == max) { h = 4 + (r - g) / (max - min); }
		}
		return new double[]{h / 6, s, l};
	}
	private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

	static ObjectDef object(byte[] bytes)
	{
		In in = new In(bytes); ObjectDef d = new ObjectDef();
		for (int op = in.u8(); op != 0; op = in.u8())
		{
			if (op == 1 || op == 6)
			{
				int n = in.u8();
				if (n > 0) { d.models = new int[n]; d.types = new int[n]; for (int i = 0; i < n; i++) { d.models[i] = op == 1 ? in.u16() : in.i32(); d.types[i] = in.u8(); } }
			}
			else if (op == 5 || op == 7)
			{
				int n = in.u8();
				if (n > 0) { d.types = null; d.models = new int[n]; for (int i = 0; i < n; i++) { d.models[i] = op == 5 ? in.u16() : in.i32(); } }
			}
			else if (op == 2) { in.string(); }
			else if (op == 14) { d.sizeX = in.u8(); }
			else if (op == 15) { d.sizeY = in.u8(); }
			else if (op == 17 || op == 18 || op == 21 || op == 22 || op == 23 || op == 27 || op == 64 || op == 73 || op == 74
				|| op == 89 || op == 90 || op == 94) { continue; }
			else if (op == 19 || op == 28 || op == 69 || op == 75 || op == 81 || op == 91 || op == 95 || op == 96) { in.u8(); }
			else if (op == 24) { d.animation = in.u16(); if (d.animation == 0xffff) { d.animation = -1; } }
			else if (op == 29) { d.ambient = in.i8(); }
			else if (op >= 30 && op < 35) { in.string(); }
			else if (op == 39) { d.contrast = in.i8() * 25; }
			else if (op == 40) { short[][] pairs = pairs(in); d.recolorFrom = pairs[0]; d.recolorTo = pairs[1]; }
			else if (op == 41) { short[][] pairs = pairs(in); d.retextureFrom = pairs[0]; d.retextureTo = pairs[1]; }
			else if (op == 42) { d.fullRecolor = (short) in.u16(); }
			else if (op == 61 || op == 68 || op == 82) { in.u16(); }
			else if (op == 62) { d.rotated = true; }
			else if (op == 65) { d.scaleX = in.u16(); }
			else if (op == 66) { d.scaleHeight = in.u16(); }
			else if (op == 67) { d.scaleY = in.u16(); }
			else if (op == 70) { d.offsetX = in.u16(); }
			else if (op == 71) { d.offsetHeight = in.u16(); }
			else if (op == 72) { d.offsetY = in.u16(); }
			else if (op == 77 || op == 92)
			{
				in.u16(); in.u16(); if (op == 92) { in.u16(); }
				int n = in.u8(); for (int i = 0; i <= n; i++) { in.u16(); }
				d.impostors = true;
			}
			else if (op == 78) { in.u16(); in.u8(); in.u8(); }
			else if (op == 79) { in.u16(); in.u16(); in.u8(); in.u8(); int n = in.u8(); for (int i = 0; i < n; i++) { in.u16(); } }
			else if (op == 93) { in.u8(); in.u16(); in.u8(); in.u16(); }
			else if (op == 100) { in.u8(); in.u8(); in.string(); }
			else if (op == 101) { in.u8(); in.u16(); in.u16(); in.i32(); in.i32(); in.string(); }
			else if (op == 102) { in.u8(); in.u16(); in.u16(); in.u16(); in.i32(); in.i32(); in.string(); }
			else if (op == 249) { in.params(); }
			else { throw new IllegalArgumentException("Unknown object opcode " + op); }
		}
		return d;
	}

	static NpcDef npc(byte[] bytes)
	{
		In in = new In(bytes); NpcDef d = new NpcDef();
		for (int op = in.u8(); op != 0; op = in.u8())
		{
			if (op == 1 || op == 61) { int n = in.u8(); d.models = new int[n]; for (int i = 0; i < n; i++) { d.models[i] = op == 1 ? in.u16() : in.i32(); } }
			else if (op == 60 || op == 62) { int n = in.u8(); for (int i = 0; i < n; i++) { if (op == 60) { in.u16(); } else { in.i32(); } } }
			else if (op == 2) { in.string(); }
			else if (op == 12) { in.u8(); }
			else if (op == 13 || op == 14 || op == 15 || op == 16 || op == 18 || (op >= 74 && op <= 79) || op == 95 || op == 103
				|| op == 114 || op == 116 || op == 124 || op == 126 || op == 146) { in.u16(); }
			else if (op == 17 || op == 115 || op == 117) { in.u16(); in.u16(); in.u16(); in.u16(); }
			else if (op >= 30 && op < 35) { in.string(); }
			else if (op == 40) { short[][] pairs = pairs(in); d.recolorFrom = pairs[0]; d.recolorTo = pairs[1]; }
			else if (op == 41) { short[][] pairs = pairs(in); d.retextureFrom = pairs[0]; d.retextureTo = pairs[1]; }
			else if (op == 42) { d.fullRecolor = (short) in.u16(); }
			else if (op == 93 || op == 99 || op == 107 || op == 109 || op == 111 || op == 122 || op == 123 || op == 129 || op == 130
				|| op == 145 || op == 147) { continue; }
			else if (op == 97) { d.widthScale = in.u16(); }
			else if (op == 98) { d.heightScale = in.u16(); }
			else if (op == 100) { d.ambient = in.i8(); }
			else if (op == 101) { d.contrast = in.i8(); }
			else if (op == 102)
			{
				int bits = in.u8();
				for (int i = 0; bits >> i != 0; i++) { if ((bits & 1 << i) != 0) { in.bigSmart2(); in.u16SmartMinusOne(); } }
			}
			else if (op == 106 || op == 118)
			{
				in.u16(); in.u16(); if (op == 118) { in.u16(); }
				int n = in.u8(); for (int i = 0; i <= n; i++) { in.u16(); }
			}
			else if (op == 249) { in.params(); }
			else if (op == 251) { in.u8(); in.u8(); in.string(); }
			else if (op == 252) { in.u8(); in.u16(); in.u16(); in.i32(); in.i32(); in.string(); }
			else if (op == 253) { in.u8(); in.u16(); in.u16(); in.u16(); in.i32(); in.i32(); in.string(); }
			else { throw new IllegalArgumentException("Unknown NPC opcode " + op); }
		}
		return d;
	}

	static ItemDef item(byte[] bytes)
	{
		In in = new In(bytes); ItemDef d = new ItemDef();
		for (int op = in.u8(); op != 0; op = in.u8())
		{
			if (op == 1 || op == 4 || op == 5 || op == 6 || op == 7 || op == 8 || op == 90 || op == 91 || op == 92 || op == 93
				|| op == 94 || op == 95 || op == 97 || op == 98 || (op >= 110 && op <= 112) || op == 139 || op == 140 || op == 148 || op == 149
				|| op == 75) { in.u16(); }
			else if (op == 2 || op == 3 || op == 9 || (op >= 30 && op < 40)) { in.string(); }
			else if (op == 11 || op == 15 || op == 16 || op == 65 || op == 160 || op == 251) { continue; }
			else if (op == 12 || op == 44 || (op >= 51 && op <= 54)) { in.i32(); }
			else if (op == 13 || op == 14 || op == 27 || op == 42 || op == 113 || op == 114 || op == 115) { in.u8(); }
			else if (op == 23) { d.maleModel0 = in.u16(); d.maleOffset = in.u8(); }
			else if (op == 24) { d.maleModel1 = in.u16(); }
			else if (op == 25) { d.femaleModel0 = in.u16(); d.femaleOffset = in.u8(); }
			else if (op == 26) { d.femaleModel1 = in.u16(); }
			else if (op == 45) { d.maleModel0 = in.i32(); d.maleOffset = in.u8(); }
			else if (op == 46) { d.maleModel1 = in.i32(); }
			else if (op == 47) { d.maleModel2 = in.i32(); }
			else if (op == 48) { d.femaleModel0 = in.i32(); d.femaleOffset = in.u8(); }
			else if (op == 49) { d.femaleModel1 = in.i32(); }
			else if (op == 50) { d.femaleModel2 = in.i32(); }
			else if (op == 78) { d.maleModel2 = in.u16(); }
			else if (op == 79) { d.femaleModel2 = in.u16(); }
			else if (op == 40) { short[][] pairs = pairs(in); d.recolorFrom = pairs[0]; d.recolorTo = pairs[1]; }
			else if (op == 41) { short[][] pairs = pairs(in); d.retextureFrom = pairs[0]; d.retextureTo = pairs[1]; }
			else if (op == 43) { in.u8(); while (in.u8() != 0) { in.string(); } }
			else if (op == 99) { d.fullRecolor = (short) in.u16(); }
			else if (op >= 100 && op < 110) { in.u16(); in.u16(); }
			else if (op == 161) { int n = in.u16(); for (int i = 0; i < n; i++) { in.u16(); } }
			else if (op == 200) { in.u8(); in.u8(); in.string(); }
			else if (op == 201) { in.u8(); in.u16(); in.u16(); in.i32(); in.i32(); in.string(); }
			else if (op == 202) { in.u8(); in.u16(); in.u16(); in.u16(); in.i32(); in.i32(); in.string(); }
			else if (op == 249) { in.params(); }
			else { throw new IllegalArgumentException("Unknown item opcode " + op); }
		}
		return d;
	}

	static KitDef kit(byte[] bytes)
	{
		In in = new In(bytes); KitDef d = new KitDef();
		for (int op = in.u8(); op != 0; op = in.u8())
		{
			if (op == 1) { in.u8(); }
			else if (op == 2 || op == 5) { int n = in.u8(); d.models = new int[n]; for (int i = 0; i < n; i++) { d.models[i] = op == 2 ? in.u16() : in.i32(); } }
			else if (op == 3) { continue; }
			else if (op == 40) { short[][] pairs = pairs(in); d.recolorFrom = pairs[0]; d.recolorTo = pairs[1]; }
			else if (op == 41) { short[][] pairs = pairs(in); d.retextureFrom = pairs[0]; d.retextureTo = pairs[1]; }
			else if (op >= 60 && op < 70) { in.u16(); }
			else if (op >= 70 && op < 80) { in.i32(); }
			else { throw new IllegalArgumentException("Unknown kit opcode " + op); }
		}
		return d;
	}

	private static short[][] pairs(In in)
	{
		int n = in.u8(); short[] from = new short[n], to = new short[n];
		for (int i = 0; i < n; i++) { from[i] = (short) in.u16(); to[i] = (short) in.u16(); }
		return new short[][]{from, to};
	}

	/** Big-endian config bytes; reading past the end throws, so a truncated definition is never half-used. */
	private static final class In
	{
		private final byte[] b; private int i;
		In(byte[] b) { this.b = b; }
		int u8() { if (i >= b.length) { throw new IllegalArgumentException("Truncated definition"); } return b[i++] & 255; }
		int i8() { return (byte) u8(); }
		int u16() { return u8() << 8 | u8(); }
		int u24() { return u8() << 16 | u16(); }
		int i32() { return u16() << 16 | u16(); }
		void string() { int start = i; while (u8() != 0) { if (i - start > 4096) { throw new IllegalArgumentException("Overlong string"); } } }
		void bigSmart2() { if (i < b.length && b[i] < 0) { i32(); } else { u16(); } }
		void u16SmartMinusOne() { if (u8() >= 128) { u8(); } }
		void params()
		{
			int n = u8();
			for (int k = 0; k < n; k++)
			{
				int type = u8(); u8(); u16();
				if (type == 1) { string(); } else if (type == 2) { i32(); i32(); } else { i32(); }
			}
		}
	}
}
