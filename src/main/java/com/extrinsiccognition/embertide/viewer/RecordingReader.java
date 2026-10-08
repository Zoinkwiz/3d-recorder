package com.extrinsiccognition.embertide.viewer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.extrinsiccognition.embertide.GroundRows;
import com.extrinsiccognition.embertide.MccrFileRecorder;
import com.extrinsiccognition.embertide.MccrSession;
import com.extrinsiccognition.embertide.RowTypes;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import net.runelite.client.util.Filepath;
import static com.extrinsiccognition.embertide.viewer.Recording.*;

final class RecordingReader
{
	static final long MAX_BYTES = 128L * 1024 * 1024;
	static final int MAX_LINE = 2 * 1024 * 1024;
	static final long MAX_GROUND_VALUES = 40_000_000;
	static final int MAX_SCENE_OBJECTS = 100_000, MAX_OBJECTS = 2_000_000;
	private int samples, cells, objects, actors, appearances, terrainValues;
	private long groundValues;
	private boolean deltaMotion;
	private final Map<String, long[]> motionCoordinates = new HashMap<>();

	/** A file in the plugin's own folder; Filepath cannot name one outside it. */
	Recording read(Filepath file) throws IOException
	{
		if (!file.isFile() || file.size() > MAX_BYTES) { throw new IOException("Choose a recording file smaller than 128 MiB."); }
		return read(file.openInputStream());
	}

	Recording read(InputStream source) throws IOException
	{
		samples = cells = objects = actors = appearances = terrainValues = 0; groundValues = 0;
		motionCoordinates.clear(); deltaMotion = false;
		Recording recording = new Recording();
		try (PushbackInputStream peek = new PushbackInputStream(source, 2))
		{
			int first = peek.read(), second = peek.read();
			if (second >= 0) { peek.unread(second); }
			if (first >= 0) { peek.unread(first); }
			InputStream decoded = first == 31 && second == 139 ? new GZIPInputStream(peek) : peek;
			try (BufferedReader in = new BufferedReader(new InputStreamReader(new LimitedInput(decoded), StandardCharsets.UTF_8)))
			{
				long consumed = 0;
				String line;
				Scene scene = null;
				double previous = 0;
				while ((line = line(in)) != null)
				{
					if (Thread.currentThread().isInterrupted()) { throw new IOException("Loading cancelled."); }
					consumed += line.length() + 1;
					if (consumed > MAX_BYTES || ++recording.rows > 250_000) { throw new IOException("Recording exceeds the reader budget."); }
					if (line.isBlank()) { continue; }
					JsonObject row;
					try { depth(line); row = new JsonParser().parse(line).getAsJsonObject(); }
					catch (RuntimeException e) { throw new IOException("Invalid JSON at line " + recording.rows + ".", e); }
					try
					{
						String type = str(row, "type", "");
						if (scene == null)
						{
							if (!RowTypes.HEADER.equals(type) || !MccrSession.GAME.equals(str(row, "game", ""))
								|| !MccrSession.FORMAT.equals(str(row, "format", "")))
							{ throw new IOException("This is not an OSRS Embertide recording (mccr/0.1)."); }
							recording.revision = integer(obj(row, "osrs"), "cache_revision", 0);
							String encoding = str(obj(row, "osrs"), "motion_xy", "");
							deltaMotion = MccrFileRecorder.MOTION_XY.equals(encoding);
							if (!encoding.isEmpty() && !deltaMotion) { throw new IOException("Unsupported motion encoding: " + encoding); }
							scene = new Scene(0, str(obj(row, "spatial"), "dimension", "osrs:surface"));
							recording.scenes.add(scene);
							scene.terrain.instance |= bool(obj(row, "osrs"), "instance");
							continue;
						}
						double t = number(row, "elapsed_s", previous);
						if (t < previous || t < 0 || t > 10800) { throw new IOException("Invalid recording timeline at line " + recording.rows + "."); }
						previous = t; recording.duration = t;
						JsonObject p = obj(row, "payload");
						if (RowTypes.SCENE.equals(type))
						{
							if (recording.scenes.size() >= 128) { throw new IOException("Too many scene changes."); }
							scene = new Scene(t, str(p, "dimension", scene.dimension));
							recording.scenes.add(scene);
						}
						else { parse(recording, scene, type, str(row, "user_id", "player"), t, p); }
					}
					catch (IllegalArgumentException | IllegalStateException | IndexOutOfBoundsException e)
					{ throw new IOException("Invalid recording data at line " + recording.rows + ": " + e.getMessage(), e); }
				}
			}
		}
		if (recording.scenes.isEmpty() || samples == 0) { throw new IOException("The recording has no actor positions to replay."); }
		for (Scene scene : recording.scenes)
		{
			for (List<Scenery> track : scene.objects.values())
			{
				track.sort(Comparator.comparingDouble(Scenery::time)); Scenery previous = null;
				for (Scenery object : track)
				{
					if (previous != null && previous.present && object.present && previous.id == object.id)
					{ object.animationStart = previous.animationStart; }
					previous = object;
				}
			}
			for (Actor actor : scene.actors.values()) { actor.lastAnimationSample = null; }
		}
		return recording;
	}

	private void parse(Recording r, Scene s, String type, String user, double t, JsonObject p) throws IOException
	{
		switch (type)
		{
			case RowTypes.REGION:
				Terrain terrain = s.terrain;
				terrain.instance = bool(p, "instance");
				terrain.baseX = integer(p, "base_x", 0); terrain.baseY = integer(p, "base_y", 0);
				break;
			case RowTypes.GROUND:
				if ((groundValues += (long) array(p, "tiles").size() * GroundRows.SIZE) > MAX_GROUND_VALUES) { s.terrain.truncated = true; }
				else { GroundRows.read(p, s.terrain); }
				break;
			case RowTypes.INSTANCE_HEIGHTS:
				if (integer(p, "base_x", 0) != s.terrain.baseX || integer(p, "base_y", 0) != s.terrain.baseY)
				{ throw new IOException("Instance heights do not match the region"); }
				s.terrain.instanceHeights[plane(integer(p, "plane", 0))] = grid(array(p, "heights"), 105, 105, -32768, 32768);
				break;
			case RowTypes.PRESENCE: actor(s, user).npcId = integer(p, "npc_id", -1); break;
			case RowTypes.LIFECYCLE:
				actor(s, str(p, "actor_id", user)).presence.add(new Presence(t, !"left".equals(str(p, "state", "")))); break;
			case RowTypes.APPEARANCE:
				if (++appearances > 40_000) { throw new IOException("Too many appearance changes."); }
				r.appearances.computeIfAbsent(user, ignored -> new ArrayList<>()).add(new Appearance(t,
					ints(array(p, "equipment_ids"), 12), ints(array(p, "colors"), 5), bool(p, "female"), integer(p, "transformed_npc_id", -1)));
				break;
			case RowTypes.POSITION:
				JsonObject o = obj(p, "osrs");
				if (!o.has("world_x") || !o.has("world_y")) { break; }
				Actor actor = actor(s, user);
				add(actor, actor.poses, new Sample(t, number(o, "world_x", 0) + .5, number(o, "world_y", 0) + .5,
					plane(integer(p, "plane", 0)), integer(o, "orientation", 0), integer(o, "animation", -1),
					integer(o, "animation_frame", -1), integer(o, "pose_animation", -1), integer(o, "npc_id", actor.npcId)));
				break;
			case RowTypes.POSES:
				for (JsonElement e : array(p, "poses"))
				{
					JsonArray a = e.getAsJsonArray(); if (a.size() < 11) { throw new IllegalArgumentException("Short pose"); }
					Actor target = actor(s, a.get(0).getAsString());
					add(target, target.poses, new Sample(t, num(a, 1) + .5, num(a, 2) + .5, plane((int) num(a, 3)),
						(int) num(a, 5), (int) num(a, 6), (int) num(a, 8), (int) num(a, 7), (int) num(a, 10)));
				}
				break;
			case RowTypes.MOTION:
				for (JsonElement e : array(p, "samples"))
				{
					JsonArray a = e.getAsJsonArray(); if (a.size() < 8) { throw new IllegalArgumentException("Short motion sample"); }
					String id = a.get(0).getAsString(); Actor moving = actor(s, id);
					double x = num(a, 1), y = num(a, 2);
					if (deltaMotion)
					{
						if (x != Math.rint(x) || y != Math.rint(y) || Math.abs(x) > 200_000_000 || Math.abs(y) > 200_000_000)
						{ throw new IOException("Invalid compact motion coordinates"); }
						long[] last = motionCoordinates.get(id);
						// The recorder keeps this accumulator across scene changes and actor rejoins.
						long[] next = {(long) x + (last == null ? 0 : last[0]), (long) y + (last == null ? 0 : last[1])};
						motionCoordinates.put(id, next); x = next[0] / 1000.0; y = next[1] / 1000.0;
					}
					add(moving, moving.motion, new Sample(t, x, y, plane((int) num(a, 3)),
						(int) num(a, 4), (int) num(a, 5), (int) num(a, 6), (int) num(a, 7), -1));
				}
				break;
			case RowTypes.SNAPSHOT:
				for (JsonElement e : array(p, "cells"))
				{
					JsonArray a = e.getAsJsonArray();
					if (a.size() < 4) { throw new IllegalArgumentException("Short ground cell"); }
					String material = a.get(3).getAsString();
					if (!material.startsWith("osrs:underlay:") && !material.startsWith("osrs:overlay:") && !"osrs:ground".equals(material)) { continue; }
					if (!"osrs:ground".equals(material))
					{
						int materialId = Integer.parseInt(material.substring(material.lastIndexOf(':') + 1));
						if (materialId < 0 || materialId > 65535) { throw new IOException("Invalid ground material ID"); }
					}
					if (++cells > 100_000) { throw new IOException("Too many ground cells."); }
					int x = (int) num(a, 0), y = -(int) num(a, 2), h = (int) num(a, 1), plane = plane(Math.floorDiv(h, 8));
					s.ground.put(groundKey(x, y, plane), new Ground(x, y, plane, (h - plane * 8) * 128.0, material));
				}
				break;
			case RowTypes.OBJECT:
				String scope = str(p, "scope", "baseline");
				for (JsonElement e : array(p, "objects"))
				{
					// Budget per scene: a raid loads a new instance per room, so a whole recording holds many scenes' objects.
					if (++s.objectEntries > MAX_SCENE_OBJECTS || ++objects > MAX_OBJECTS) { throw new IOException("Too many scene objects."); }
					JsonObject j = e.getAsJsonObject();
					Scenery object = new Scenery("baseline".equals(scope) ? s.start : t,
						integer(j, "anchor_x", integer(j, "x", 0)), integer(j, "anchor_y", integer(j, "y", 0)),
						plane(integer(j, "plane", 0)), integer(j, "impostor_id", integer(j, "id", -1)),
						integer(j, "type", 10), integer(j, "orientation", 0) & 3, integer(j, "kind", 0), !"despawned".equals(scope));
					// Impostors may change between spawn and despawn; identity is the original object.
					List<Scenery> track = s.objects.computeIfAbsent(object.key(integer(j, "id", object.id)), ignored -> new ArrayList<>());
					if (!"baseline".equals(scope) || track.stream().noneMatch(v -> v.t == s.start)) { track.add(object); }
				}
				break;
			default: break;
		}
	}

	private Actor actor(Scene scene, String id) throws IOException
	{
		if (id.length() > 256) { throw new IOException("Actor identifier is too long."); }
		Actor a = scene.actors.get(id);
		if (a == null)
		{
			if (++actors > 16_384) { throw new IOException("Too many actors."); }
			a = new Actor(id); scene.actors.put(id, a);
		}
		return a;
	}
	private int[][] grid(JsonArray array, int width, int height, int min, int max) throws IOException
	{
		if (array.size() != width) { throw new IOException("Invalid terrain grid width"); }
		int[][] result = new int[width][];
		for (int x = 0; x < width; x++)
		{
			result[x] = ints(array.get(x).getAsJsonArray(), height);
			if (result[x].length != height) { throw new IOException("Invalid terrain grid height"); }
			for (int value : result[x]) { if (value < min || value > max) { throw new IOException("Invalid terrain grid value"); } }
			if ((terrainValues += height) > 2_000_000) { throw new IOException("Terrain grid budget exceeded"); }
		}
		return result;
	}
	private void add(Actor actor, List<Sample> track, Sample sample) throws IOException
	{
		if (++samples > 1_000_000) { throw new IOException("Too many motion samples."); }
		if (Math.abs(sample.x) > 100_000 || Math.abs(sample.y) > 100_000) { throw new IOException("Invalid actor coordinates."); }
		Sample previous = actor.lastAnimationSample;
		Presence lifecycle = actor.presence.isEmpty() ? null : actor.presence.get(actor.presence.size() - 1);
		if (previous != null && previous.plane == sample.plane && sample.t - previous.t < 2
			&& (lifecycle == null || previous.t >= lifecycle.t))
		{
			if (previous.pose == sample.pose) { sample.poseStart = previous.poseStart; }
			if (previous.animation == sample.animation && previous.frame == sample.frame) { sample.frameStart = previous.frameStart; }
		}
		actor.lastAnimationSample = sample;
		track.add(sample);
	}
	private static final class LimitedInput extends FilterInputStream
	{
		private long count;
		LimitedInput(InputStream in) { super(in); }
		@Override public int read() throws IOException
		{ int value = in.read(); if (value >= 0) { counted(1); } return value; }
		@Override public int read(byte[] bytes, int offset, int length) throws IOException
		{ int amount = in.read(bytes, offset, length); if (amount > 0) { counted(amount); } return amount; }
		private void counted(int amount) throws IOException
		{ if ((count += amount) > MAX_BYTES) { throw new IOException("Decompressed recording exceeds 128 MiB."); } }
	}
	private static String line(BufferedReader in) throws IOException
	{
		StringBuilder out = new StringBuilder(); int c;
		while ((c = in.read()) != -1 && c != '\n')
		{ if (out.length() >= MAX_LINE) { throw new IOException("A recording line exceeds 2 MiB."); } out.append((char) c); }
		return c == -1 && out.length() == 0 ? null : out.toString();
	}
	private static void depth(String line)
	{
		int depth = 0; boolean string = false, escaped = false;
		for (int i = 0; i < line.length(); i++)
		{
			char c = line.charAt(i);
			if (string) { if (escaped) { escaped = false; } else if (c == '\\') { escaped = true; } else if (c == '"') { string = false; } }
			else if (c == '"') { string = true; }
			else if (c == '{' || c == '[') { if (++depth > 32) { throw new IllegalArgumentException("JSON nesting exceeds 32"); } }
			else if (c == '}' || c == ']') { depth--; }
		}
	}
	private static int plane(int value) { if (value < 0 || value > 3) { throw new IllegalArgumentException("Invalid plane"); } return value; }
	private static int[] ints(JsonArray a, int max)
	{
		if (a.size() > max) { throw new IllegalArgumentException("Appearance array is too long"); }
		int[] out = new int[a.size()]; for (int i = 0; i < out.length; i++) { out[i] = (int) num(a, i); } return out;
	}
	private static double num(JsonArray a, int i) { return finite(a.get(i).getAsDouble()); }
	private static double finite(double d) { if (!Double.isFinite(d)) { throw new IllegalArgumentException("Non-finite number"); } return d; }
	private static double number(JsonObject o, String key, double fallback) { return o.has(key) && !o.get(key).isJsonNull() ? finite(o.get(key).getAsDouble()) : fallback; }
	private static int integer(JsonObject o, String key, int fallback) { return (int) number(o, key, fallback); }
	private static boolean bool(JsonObject o, String key) { return o.has(key) && !o.get(key).isJsonNull() && o.get(key).getAsBoolean(); }
	private static String str(JsonObject o, String key, String fallback) { return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback; }
	private static JsonObject obj(JsonObject o, String key) { return o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : new JsonObject(); }
	private static JsonArray array(JsonObject o, String key) { return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray(); }
}
