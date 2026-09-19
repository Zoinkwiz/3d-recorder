package com.extrinsiccognition.embertide;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.runelite.api.Model;

/** The displayed sky has no public cache id. Keep its bounded, already-lit scene mesh. */
final class SceneSkybox
{
	static final int MAX_VERTICES = 16384, MAX_FACES = 16384;
	private Long previous;

	JsonObject changed(Model model, double brightness)
	{
		if (!Double.isFinite(brightness) || brightness < 0.1 || brightness > 2) { brightness = 0.8; }
		if (model == null) return absent("none");
		int vertices = model.getVerticesCount(), faces = model.getFaceCount();
		if (vertices < 1 || faces < 1 || vertices > MAX_VERTICES || faces > MAX_FACES) return absent("unsupported");
		float[] x = model.getVerticesX(), y = model.getVerticesY(), z = model.getVerticesZ();
		int[] a = model.getFaceIndices1(), b = model.getFaceIndices2(), c = model.getFaceIndices3();
		int[] ca = model.getFaceColors1(), cb = model.getFaceColors2(), cc = model.getFaceColors3();
		byte[] alpha = model.getFaceTransparencies(), textureFaces = model.getTextureFaces();
		short[] textures = model.getFaceTextures();
		int[] ta = model.getTexIndices1(), tb = model.getTexIndices2(), tc = model.getTexIndices3();
		if (x == null || y == null || z == null || x.length < vertices || y.length < vertices || z.length < vertices
			|| a == null || b == null || c == null || a.length < faces || b.length < faces || c.length < faces
			|| ca == null || cb == null || cc == null || ca.length < faces || cb.length < faces || cc.length < faces
			|| (alpha != null && alpha.length < faces) || (textures != null && textures.length < faces)
			|| (textureFaces != null && textureFaces.length < faces)) return absent("unsupported");
		long hash = mix(mix(mix(mix(Double.doubleToLongBits(brightness), model.getOverrideAmount()),
			model.getOverrideHue()), model.getOverrideSaturation()), model.getOverrideLuminance());
		for (int v = 0; v < vertices; v++)
		{
			if (!Float.isFinite(x[v]) || !Float.isFinite(y[v]) || !Float.isFinite(z[v])
				|| Math.abs(x[v]) > 1000000 || Math.abs(y[v]) > 1000000 || Math.abs(z[v]) > 1000000) return absent("unsupported");
			hash = mix(mix(mix(hash, Float.floatToIntBits(x[v])), Float.floatToIntBits(y[v])), Float.floatToIntBits(z[v]));
		}
		for (int f = 0; f < faces; f++)
		{
			if (!vertex(a[f], vertices) || !vertex(b[f], vertices) || !vertex(c[f], vertices)) return absent("unsupported");
			hash = mix(mix(mix(mix(mix(mix(hash, a[f]), b[f]), c[f]), ca[f]), cb[f]), cc[f]);
			hash = mix(mix(hash, alpha == null ? 0 : alpha[f] & 255), textures == null ? -1 : textures[f]);
			int uv = textureFaces == null || textureFaces[f] == -1 ? -1 : textureFaces[f] & 255;
			if (uv >= 0 && (ta == null || tb == null || tc == null || uv >= ta.length || uv >= tb.length || uv >= tc.length
				|| !vertex(ta[uv], vertices) || !vertex(tb[uv], vertices) || !vertex(tc[uv], vertices))) return absent("unsupported");
			hash = mix(mix(mix(hash, uv < 0 ? a[f] : ta[uv]), uv < 0 ? b[f] : tb[uv]), uv < 0 ? c[f] : tc[uv]);
		}
		if (previous != null && previous == hash) return null;
		previous = hash;
		JsonArray positions = new JsonArray(), indices = new JsonArray(), colors = new JsonArray();
		JsonArray alphas = new JsonArray(), textureIds = new JsonArray(), uvFaces = new JsonArray();
		for (int v = 0; v < vertices; v++) { positions.add(x[v]); positions.add(y[v]); positions.add(z[v]); }
		for (int f = 0; f < faces; f++)
		{
			indices.add(a[f]); indices.add(b[f]); indices.add(c[f]);
			boolean plain = textures == null || textures[f] == -1;
			colors.add(plain ? color(ca[f], model) : ca[f]);
			colors.add(plain ? color(cb[f], model) : cb[f]);
			colors.add(plain ? color(cc[f], model) : cc[f]);
			alphas.add(alpha == null ? 0 : alpha[f] & 255);
			textureIds.add(textures == null || textures[f] == -1 ? -1 : textures[f] & 65535);
			int uv = textureFaces == null || textureFaces[f] == -1 ? -1 : textureFaces[f] & 255;
			uvFaces.add(uv < 0 ? a[f] : ta[uv]); uvFaces.add(uv < 0 ? b[f] : tb[uv]); uvFaces.add(uv < 0 ? c[f] : tc[uv]);
		}
		JsonObject mesh = new JsonObject();
		mesh.add("vertices", positions); mesh.add("faces", indices); mesh.add("colors", colors);
		mesh.add("alpha", alphas); mesh.add("textures", textureIds); mesh.add("uv_faces", uvFaces);
		mesh.addProperty("brightness", brightness);
		JsonObject payload = new JsonObject(); payload.add("mesh", mesh);
		return payload;
	}

	private JsonObject absent(String status)
	{
		long hash = status.equals("none") ? Long.MIN_VALUE : Long.MAX_VALUE;
		if (previous != null && previous == hash) return null;
		previous = hash;
		JsonObject payload = new JsonObject(); payload.add("mesh", JsonNull.INSTANCE); payload.addProperty("status", status);
		return payload;
	}

	private static boolean vertex(int index, int count) { return index >= 0 && index < count; }
	private static int color(int hsl, Model model)
	{
		int amount = model.getOverrideAmount();
		if (hsl < 0 || amount <= 0) { return hsl; }
		int hue = hsl >> 10 & 63, saturation = hsl >> 7 & 7, light = hsl & 127;
		if (model.getOverrideHue() != -1) hue += amount * (model.getOverrideHue() - hue) >> 7;
		if (model.getOverrideSaturation() != -1) saturation += amount * (model.getOverrideSaturation() - saturation) >> 7;
		if (model.getOverrideLuminance() != -1) light += amount * (model.getOverrideLuminance() - light) >> 7;
		return (hue << 10 | saturation << 7 | light) & 65535;
	}
	private static long mix(long hash, int value) { return (hash ^ value) * 0x100000001b3L; }
}
