package com.extrinsiccognition.embertide.viewer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compact replay data; no RuneLite objects and no retained JSON trees. */
final class Recording
{
	int revision;
	double duration;
	int rows;
	final List<Scene> scenes = new ArrayList<>();
	final Map<String, List<Appearance>> appearances = new LinkedHashMap<>();

	Scene sceneAt(double time)
	{
		Scene result = scenes.get(0);
		for (Scene scene : scenes) { if (scene.start > time) { break; } result = scene; }
		return result;
	}

	Appearance appearance(String id, double time) { return at(appearances.get(id), time); }

	interface Timed { double time(); }

	static <T extends Timed> int floor(List<T> list, double time)
	{
		if (list == null) { return -1; }
		int lo = 0, hi = list.size() - 1, found = -1;
		while (lo <= hi)
		{
			int mid = (lo + hi) >>> 1;
			if (list.get(mid).time() <= time) { found = mid; lo = mid + 1; }
			else { hi = mid - 1; }
		}
		return found;
	}
	static <T extends Timed> T at(List<T> list, double time)
	{
		int i = floor(list, time); return i < 0 ? null : list.get(i);
	}

	static final class Scene
	{
		final double start;
		final String dimension;
		final Map<String, Actor> actors = new LinkedHashMap<>();
		final Map<String, Ground> ground = new LinkedHashMap<>();
		final Map<String, List<Scenery>> objects = new LinkedHashMap<>();
		final Terrain terrain = new Terrain();
		int objectEntries;
		Scene(double start, String dimension)
		{ this.start = start; this.dimension = dimension; terrain.instance = dimension.startsWith("osrs:instance"); }
		double height(double x, double y, int plane)
		{
			double h = terrain.surface(x, y, plane);
			if (Double.isFinite(h)) { return h; }
			Ground g = ground.get(groundKey((int) Math.floor(x), (int) Math.floor(y), plane));
			return g == null ? 0 : g.height;
		}
		Sample origin(double time)
		{
			Actor player = actors.get("player");
			if (player != null)
			{
				Sample sample = player.sample(time);
				if (sample != null) { return sample; }
				if (!player.motion.isEmpty()) { return player.motion.get(0); }
				if (!player.poses.isEmpty()) { return player.poses.get(0); }
			}
			for (Actor actor : actors.values())
			{
				Sample sample = actor.sample(time);
				if (sample != null) { return sample; }
			}
			return null;
		}
	}

	static String groundKey(int x, int y, int plane) { return x + ":" + y + ":" + plane; }

	static final class Ground
	{
		final int x, y, plane;
		final double height;
		final String material;
		Ground(int x, int y, int plane, double height, String material)
		{ this.x = x; this.y = y; this.plane = plane; this.height = height; this.material = material; }
	}

	static final class Actor
	{
		final String id;
		int npcId = -1;
		final List<Sample> poses = new ArrayList<>();
		final List<Sample> motion = new ArrayList<>();
		final List<Presence> presence = new ArrayList<>();
		Sample lastAnimationSample;
		Actor(String id) { this.id = id; }

		Sample sample(double time)
		{
			Sample coarse = at(poses, time);
			Presence state = at(presence, time);
			if (state != null && !state.present) { return null; }
			if (coarse == null || time - coarse.t > 2) { return null; }
			int index = floor(motion, time);
			Sample fine = index < 0 ? null : motion.get(index);
			// A rejoin/plane change invalidates a previously held motion position.
			if (fine == null || fine.plane != coarse.plane || (state != null && fine.t < state.t))
			{ return interpolate(poses, floor(poses, time), time); }
			Sample result = interpolate(motion, index, time);
			result.npcId = coarse.npcId;
			return result;
		}

		private Sample interpolate(List<Sample> list, int index, double time)
		{
			if (index < 0) { return null; }
			Sample a = list.get(index), out = a.copy();
			if (index + 1 < list.size())
			{
				Sample b = list.get(index + 1);
				double gap = b.t - a.t;
				int boundary = floor(presence, a.t) + 1;
				boolean continuous = boundary >= presence.size() || presence.get(boundary).t > b.t;
				if (continuous && gap > 0 && gap <= 1.2 && a.plane == b.plane && Math.hypot(b.x - a.x, b.y - a.y) <= 8)
				{
					double f = Math.max(0, Math.min(1, (time - a.t) / gap));
					out.x += (b.x - a.x) * f; out.y += (b.y - a.y) * f;
					int turn = ((b.orientation - a.orientation + 1024) & 2047) - 1024;
					out.orientation = (a.orientation + (int) Math.round(turn * f)) & 2047;
				}
			}
			return out;
		}
	}

	static final class Sample implements Timed
	{
		final double t;
		double poseStart, frameStart;
		double x, y;
		final int plane, animation, frame, pose;
		int orientation, npcId;
		Sample(double t, double x, double y, int plane, int orientation, int animation, int frame, int pose, int npcId)
		{ this.t = t; this.poseStart = this.frameStart = t; this.x = x; this.y = y; this.plane = plane; this.orientation = orientation; this.animation = animation; this.frame = frame; this.pose = pose; this.npcId = npcId; }
		public double time() { return t; }
		Sample copy() { Sample copy = new Sample(t, x, y, plane, orientation, animation, frame, pose, npcId); copy.poseStart = poseStart; copy.frameStart = frameStart; return copy; }
	}

	static final class Presence implements Timed
	{
		final double t; final boolean present;
		Presence(double t, boolean present) { this.t = t; this.present = present; }
		public double time() { return t; }
	}

	static final class Appearance implements Timed
	{
		final double t; final int[] equipment, colors; final boolean female; final int npcId;
		Appearance(double t, int[] equipment, int[] colors, boolean female, int npcId)
		{ this.t = t; this.equipment = equipment; this.colors = colors; this.female = female; this.npcId = npcId; }
		public double time() { return t; }
	}

	static final class Scenery implements Timed
	{
		final double t;
		double animationStart;
		final int x, y, plane, id, type, orientation, kind;
		final boolean present;
		Scenery(double t, int x, int y, int plane, int id, int type, int orientation, int kind, boolean present)
		{ this.t = t; this.animationStart = t; this.x = x; this.y = y; this.plane = plane; this.id = id; this.type = type; this.orientation = orientation; this.kind = kind; this.present = present; }
		public double time() { return t; }
		String key() { return key(id); }
		String key(int originalId) { return groundKey(x, y, plane) + ":" + kind + ":" + originalId + ":" + type + ":" + orientation; }
	}
}
