package com.extrinsiccognition.embertide;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.Deque;
import net.runelite.api.GameObject;
import net.runelite.api.GraphicsObject;
import net.runelite.api.Hitsplat;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.Point;
import net.runelite.api.Preferences;
import net.runelite.api.Projectile;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarbitID;

/**
 * A raid room of fakes advanced one client cycle (20 ms) at a time: the local player, a party, a boss and
 * its adds, projectiles, spot animations, hitsplats and sounds. Everyone keeps a distinct distance from the
 * player and at most one spot animation ends a cycle, so the rows never depend on identity hash order.
 */
final class RaidScene
{
	static final int BASE_X = 3264;
	static final int BASE_Y = 5440;
	static final int PARTY = 7;
	static final int CYCLES_PER_TICK = 30;
	static final Instant STARTED = Instant.parse("2026-10-20T18:00:00Z");
	private static final int SIZE = Constants.SCENE_SIZE;
	private static final Integer[] BOXES = new Integer[70_000];

	static
	{
		for (int i = 0; i < BOXES.length; i++) { BOXES[i] = i - 1; }
	}

	/** Client API calls the plugin made. */
	long calls;
	long nanos = 1_000_000_000L;
	int cycle = 1000;
	int tick = 1;
	int hp = 99;
	int cameraYaw = 512;
	int masterVolume = 100, soundVolume = 45, areaSoundVolume = 25;
	final Client client;
	final WorldView view;
	final EmbertideConfig config;
	final Body self;
	final List<Body> bodies = new ArrayList<>();
	private final Random random;
	private final List<Shot> shots = new ArrayList<>();
	private final List<Spot> spots = new ArrayList<>();
	private final List<Object> spotProxies = new ArrayList<>();
	private final List<Hit> hits = new ArrayList<>();
	private final List<int[]> sounds = new ArrayList<>();
	private final Tile[][][] tiles = new Tile[4][SIZE][SIZE];
	private final int[][][] heights = new int[4][SIZE + 1][SIZE + 1];
	private final short[][][] overlays = new short[4][SIZE][SIZE];
	private final short[][][] underlays = new short[4][SIZE][SIZE];
	private final int[][][] chunks = new int[4][13][13];
	private final ObjectComposition rock;
	private int path;

	/** Actors other than the local player: a boss, then up to seven party members, then adds. */
	RaidScene(int actors, long seed)
	{
		random = new Random(seed);
		rock = fake(ObjectComposition.class, (name, args) -> "getName".equals(name) ? "Rubble" : null);
		for (int x = 0; x < SIZE; x++)
		{
			for (int y = 0; y < SIZE; y++)
			{
				heights[0][x][y] = -((x * 7 + y * 3) % 5) * 60;
				overlays[0][x][y] = (short) ((x + y) % 9 == 0 ? 12 : 0);
				underlays[0][x][y] = (short) (1 + (x * y) % 6);
				tiles[0][x][y] = tile(x, y);
			}
		}
		for (int p = 0; p < 4; p++)
		{
			for (int x = 0; x < 13; x++)
			{
				for (int y = 0; y < 13; y++) { chunks[p][x][y] = p == 0 ? (x * 13 + y) << 3 : 0; }
			}
		}
		self = new Body(false, 1, "Tester", -1, 126);
		self.x = BASE_X + 40;
		self.y = BASE_Y + 52;
		for (int i = 0; i < actors; i++)
		{
			Body body = i == 0 ? new Body(true, 9000, "Archivist", 14800, 640)
				: i <= PARTY ? new Body(false, 1 + i, "Raider " + i, -1, 100 + i)
				: new Body(true, 9000 + i, i % 2 == 0 ? "Shade" : "Wisp", 14810 + i % 2, 92);
			body.offsetX = i + 1;
			bodies.add(body);
		}
		for (Body body : bodies) { place(body, true); }
		place(self, true);
		Scene scene = fake(Scene.class, (name, args) ->
		{
			switch (name)
			{
				case "getTiles": return tiles;
				case "getOverlayIds": return overlays;
				case "getUnderlayIds": return underlays;
				default: return null;
			}
		});
		Preferences preferences = fake(Preferences.class, (name, args) ->
			"getSoundEffectVolume".equals(name) ? box(soundVolume) : "getAreaSoundEffectVolume".equals(name) ? box(areaSoundVolume) : null);
		IndexedObjectSet<?> npcs = set(true);
		IndexedObjectSet<?> players = set(false);
		Deque<?> graphics = fake(Deque.class, (name, args) -> "iterator".equals(name) ? spotProxies.iterator() : null);
		int[] regions = {13136, 13137, 13392, 13393};
		view = fake(WorldView.class, (name, args) ->
		{
			switch (name)
			{
				case "getScene": return scene;
				case "getBaseX": return BASE_X;
				case "getBaseY": return BASE_Y;
				case "getSizeX":
				case "getSizeY": return SIZE;
				case "getPlane": return 0;
				case "getMapRegions": return regions;
				case "getTileHeights": return heights;
				case "isInstance": return true;
				case "getInstanceTemplateChunks": return chunks;
				case "npcs": return npcs;
				case "players": return players;
				case "getGraphicsObjects": return graphics;
				default: return null;
			}
		});
		client = fake(Client.class, (name, args) ->
		{
			switch (name)
			{
				case "getTopLevelWorldView": return view;
				case "getLocalPlayer": return self.actor;
				case "getTickCount": return box(tick);
				case "getGameCycle": return box(cycle);
				case "getCameraX": return box((self.localX - 300) & 0x3fff);
				case "getCameraY": return box((self.localY - 300) & 0x3fff);
				case "getCameraZ": return box(1200);
				case "getCameraYaw": return box(cameraYaw);
				case "getCameraPitch": return box(256);
				case "getScale": return box(512);
				case "getViewportWidth": return box(765);
				case "getViewportHeight": return box(503);
				case "getSkyboxColor": return box(0x203040);
				case "getBoostedSkillLevel": return box(args[0] == Skill.HITPOINTS ? hp : 90);
				case "getRealSkillLevel": return box(99);
				case "getEnergy": return box(8000);
				case "getVarpValue": return box(1000);
				case "getVarbitValue": return box((Integer) args[0] == VarbitID.OPTION_MASTER_VOLUME_DESKTOP ? masterVolume : 0);
				case "getPreferences": return preferences;
				case "getObjectDefinition": return rock;
				case "getRevision": return box(236);
				case "getWorld": return box(420);
				default: return null;
			}
		});
		config = fake(EmbertideConfig.class, (name, args) ->
		{
			switch (name)
			{
				case "captureRadius": return 12;
				case "trackNpcs":
				case "trackPlayers":
				case "recordOverheadText":
				case "recordOwnChat":
				case "recordMoments": return true;
				default: return null;
			}
		});
	}

	/** A capture of this scene on the scene's own clock. */
	OsrsCapture capture(Recorder recorder)
	{
		OsrsCapture capture = new OsrsCapture(client, recorder, config, SceneIdentity.of(view).dimension(), "Tester", 420,
			"raid-series", 1, new java.util.HashMap<>(), OsrsCapture.MAX_SECONDS);
		capture.session().clock(() -> nanos, () -> STARTED);
		return capture;
	}

	/** The recorders a streamer runs: the file, mirrored live to an app that has not answered yet, so rows queue. */
	static LiveMirror streamerRecorder(com.google.gson.Gson gson, net.runelite.client.util.Filepath folder, java.util.concurrent.Executor fileWorker)
	{
		java.util.concurrent.ScheduledExecutorService idle = (java.util.concurrent.ScheduledExecutorService) Proxy.newProxyInstance(
			RaidScene.class.getClassLoader(), new Class<?>[]{java.util.concurrent.ScheduledExecutorService.class},
			(proxy, method, args) -> method.getReturnType() == boolean.class ? false : null);
		MccrRecorderClient live = new MccrRecorderClient(HTTP, gson, idle, new RecorderEndpoint("http://127.0.0.1:9", "bench", 0));
		return new LiveMirror(new MccrFileRecorder(gson, folder, () -> "osrs-raid", fileWorker), live);
	}

	private static final okhttp3.OkHttpClient HTTP = new okhttp3.OkHttpClient();

	Player me()
	{
		return (Player) self.actor;
	}

	/** The server's game tick: movement, animations, targets, and the tick's projectiles, hitsplats and sounds. */
	void serverTick()
	{
		tick++;
		if (tick % 2 == 0)
		{
			int side = (path / 6) % 4;
			self.x += side == 0 ? 1 : side == 2 ? -1 : 0;
			self.y += side == 1 ? 1 : side == 3 ? -1 : 0;
			path++;
		}
		place(self, false);
		Body boss = bodies.isEmpty() ? null : bodies.get(0);
		for (Body body : bodies)
		{
			if (random.nextInt(10) < 3)
			{
				int reach = Math.min(body.offsetX, 2);
				body.offsetY = random.nextInt(2 * reach + 1) - reach;
			}
			place(body, false);
			if (body.animation == -1 && random.nextInt(4) == 0)
			{
				body.animation = body.npc ? 8400 + body.npcId % 7 : 1658 + random.nextInt(3) * 40;
				body.frame = 0;
				body.animationTicks = 3 + random.nextInt(3);
			}
			else if (body.animation != -1 && --body.animationTicks <= 0)
			{
				body.animation = -1;
			}
			if (tick % 5 == 0 || body.interacting == null)
			{
				body.interacting = body == boss || body.npc ? partyMember() : boss;
			}
			if (!body.npc && (random.nextInt(200) == 0 || (body.slot == 2 && tick % 9 == 0))) { body.equipment[3] = 26000 + random.nextInt(300); }
		}
		self.interacting = boss;
		if (self.animation == -1 && tick % 4 == 0) { self.animation = 1658; self.frame = 0; self.animationTicks = 3; }
		else if (self.animation != -1 && --self.animationTicks <= 0) { self.animation = -1; }
		int volleys = bodies.isEmpty() ? 1 : 3 + bodies.size() / 2;
		for (int i = 0; i < volleys; i++)
		{
			Body from = any();
			Body to = from == boss || (from.npc && from != self) ? partyMember() : boss == null ? self : boss;
			shots.add(new Shot(from, to, cycle + random.nextInt(8), 30 + random.nextInt(40)));
		}
		hits.clear();
		int splats = 2 + bodies.size() / 3;
		for (int i = 0; i < splats; i++)
		{
			Body target = i % 2 == 0 && boss != null ? boss : partyMember();
			int amount = random.nextInt(40);
			target.healthScale = target == self ? -1 : 30;
			target.healthRatio = target == self ? -1 : Math.max(1, 30 - (tick + i) % 30);
			if (target == self) { hp = Math.max(30, Math.min(99, hp - amount + random.nextInt(40))); }
			hits.add(new Hit(target, amount, i == 0, i == 0 ? 16 : 18));
		}
		sounds.clear();
		int noises = 3 + bodies.size() / 4;
		for (int i = 0; i < noises; i++)
		{
			Body source = any();
			sounds.add(new int[]{2700 + random.nextInt(400), random.nextInt(3) * 10, bodies.indexOf(source), i % 3 == 0 ? source.x - BASE_X : -1,
				source.y - BASE_Y, 5 + i});
		}
	}

	/** One client cycle: positions slide toward their tiles, frames advance, the camera pans, spot animations come and go. */
	void clientCycle()
	{
		cycle++;
		nanos += 20_000_000L;
		slide(self);
		for (Body body : bodies) { slide(body); }
		if (cycle % 2 == 0) { cameraYaw = (cameraYaw + 6) & 2047; }
		shots.removeIf(shot -> cycle > shot.end + 2);
		for (int i = spots.size() - 1; i >= 0; i--)
		{
			if (cycle > spots.get(i).end) { spots.remove(i); spotProxies.remove(i); }
		}
		if (cycle % 7 == 0)
		{
			Body at = any();
			Spot spot = new Spot(1500 + random.nextInt(200), at.localX, at.localY, cycle, cycle + 40);
			spots.add(spot);
			spotProxies.add(spot.proxy);
		}
	}

	/** The game tick's hitsplats and sounds, as RuneLite posts them. */
	void tickEvents(OsrsCapture capture)
	{
		Player me = me();
		for (Hit hit : hits) { capture.hitsplat(hit.target.actor, hit.splat, me); }
		for (int[] sound : sounds)
		{
			Actor source = sound[2] < 0 ? self.actor : bodies.get(sound[2]).actor;
			if (sound[3] >= 0) { capture.sound(sound[0], sound[1], null, sound[3], sound[4], sound[5], me); }
			else { capture.sound(sound[0], sound[1], source, null, null, null, me); }
		}
	}

	/** ProjectileMoved for every projectile in the air this cycle. */
	void cycleEvents(OsrsCapture capture)
	{
		Player me = me();
		for (Shot shot : shots)
		{
			if (cycle >= shot.start && cycle <= shot.end) { capture.projectile(shot.proxy, me); }
		}
	}

	private Body partyMember()
	{
		List<Body> party = new ArrayList<>();
		party.add(self);
		for (Body body : bodies) { if (!body.npc) { party.add(body); } }
		return party.get(random.nextInt(party.size()));
	}

	private Body any()
	{
		int pick = random.nextInt(bodies.size() + 1);
		return pick == bodies.size() ? self : bodies.get(pick);
	}

	private void place(Body body, boolean snap)
	{
		if (body != self)
		{
			body.x = self.x + body.offsetX;
			body.y = self.y + body.offsetY;
		}
		if (snap)
		{
			body.localX = (body.x - BASE_X) * 128 + 64;
			body.localY = (body.y - BASE_Y) * 128 + 64;
		}
	}

	private static void slide(Body body)
	{
		int tx = (body.x - BASE_X) * 128 + 64, ty = (body.y - BASE_Y) * 128 + 64;
		boolean moving = tx != body.localX || ty != body.localY;
		body.localX += Integer.signum(tx - body.localX) * Math.min(8, Math.abs(tx - body.localX));
		body.localY += Integer.signum(ty - body.localY) * Math.min(8, Math.abs(ty - body.localY));
		body.pose = moving ? 819 : 808;
		if (moving) { body.orientation = (body.orientation + 32) & 2047; }
		if (body.animation != -1 && (body.cycles++ & 1) == 1) { body.frame = body.frame >= 14 ? 0 : body.frame + 1; }
	}

	private Tile tile(int sx, int sy)
	{
		GameObject object = (sx * 31 + sy * 17) % 11 == 0 ? fake(GameObject.class, (name, args) ->
		{
			switch (name)
			{
				case "getId": return box(30000 + (sx + sy) % 40);
				case "getConfig": return box(10 | ((sx + sy) % 4) << 6);
				case "getSceneMinLocation": return new Point(sx, sy);
				case "getHash": return (long) (sx << 8 | sy);
				default: return null;
			}
		}) : null;
		WallObject wall = sx == 0 || sy == 0 || sx == SIZE - 1 || sy == SIZE - 1 ? fake(WallObject.class, (name, args) ->
		{
			switch (name)
			{
				case "getId": return box(29000 + sx % 3);
				case "getConfig": return box(0);
				case "getHash": return (long) (1 << 20 | sx << 8 | sy);
				default: return null;
			}
		}) : null;
		GameObject[] objects = object == null ? new GameObject[5] : new GameObject[]{object, null, null, null, null};
		WorldPoint at = new WorldPoint(BASE_X + sx, BASE_Y + sy, 0);
		return fake(Tile.class, (name, args) ->
		{
			switch (name)
			{
				case "getGameObjects": return objects;
				case "getWallObject": return wall;
				case "getWorldLocation": return at;
				default: return null;
			}
		});
	}

	private IndexedObjectSet<?> set(boolean npcs)
	{
		return fake(IndexedObjectSet.class, (name, args) ->
		{
			if (!"iterator".equals(name)) { return null; }
			List<Object> list = new ArrayList<>();
			for (Body body : bodies) { if (body.npc == npcs) { list.add(body.actor); } }
			if (!npcs) { list.add(self.actor); }
			return list.iterator();
		});
	}

	static Integer box(int value)
	{
		return value >= -1 && value < BOXES.length - 1 ? BOXES[value + 1] : Integer.valueOf(value);
	}

	interface Answer
	{
		Object answer(String name, Object[] args);
	}

	@SuppressWarnings("unchecked")
	<T> T fake(Class<T> type, Answer answer)
	{
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) ->
		{
			String name = method.getName();
			if (name.equals("hashCode") && args == null) { return System.identityHashCode(proxy); }
			if (name.equals("equals") && args != null && args.length == 1) { return proxy == args[0]; }
			if (name.equals("toString") && args == null) { return type.getSimpleName() + "@" + System.identityHashCode(proxy); }
			calls++;
			Object value = answer.answer(name, args);
			if (value != null) { return value; }
			Class<?> r = method.getReturnType();
			if (r == boolean.class) { return false; }
			if (r == int.class) { return box(0); }
			if (r == long.class) { return 0L; }
			if (r == double.class) { return 0d; }
			if (r == float.class) { return 0f; }
			if (r == short.class) { return (short) 0; }
			if (r == byte.class) { return (byte) 0; }
			if (r == char.class) { return (char) 0; }
			return null;
		});
	}

	final class Body
	{
		final boolean npc;
		final int slot;
		final String name;
		final int npcId;
		final int combat;
		final int[] equipment = {0, 0, 0, 26000, 0, 0, 0, 0, 0, 0, 0, 0};
		final int[] colors = {1, 2, 3, 4, 5};
		final Actor actor;
		int x, y, localX, localY, offsetX, offsetY;
		int orientation = 1024, animation = -1, frame, pose = 808, graphic = -1, healthRatio = -1, healthScale = -1;
		int animationTicks, cycles;
		Body interacting;

		Body(boolean npc, int slot, String name, int npcId, int combat)
		{
			this.npc = npc;
			this.slot = slot;
			this.name = name;
			this.npcId = npcId;
			this.combat = combat;
			NPCComposition shown = npc ? fake(NPCComposition.class, (n, args) -> "getId".equals(n) ? box(npcId) : null) : null;
			PlayerComposition look = npc ? null : fake(PlayerComposition.class, (n, args) ->
			{
				switch (n)
				{
					case "getEquipmentIds": return equipment;
					case "getColors": return colors;
					case "getTransformedNpcId": return box(-1);
					default: return null;
				}
			});
			Answer answer = (n, args) ->
			{
				switch (n)
				{
					case "getName": return name;
					case "getWorldLocation": return new WorldPoint(x, y, 0);
					case "getLocalLocation": return new LocalPoint(localX, localY, -1);
					case "getCurrentOrientation": return box(orientation);
					case "getAnimation": return box(animation);
					case "getAnimationFrame": return box(frame);
					case "getPoseAnimation": return box(pose);
					case "getGraphic": return box(graphic);
					case "getGraphicHeight": return box(0);
					case "getLogicalHeight": return box(npc && slot == 9000 ? 400 : 200);
					case "getHealthRatio": return box(healthRatio);
					case "getHealthScale": return box(healthScale);
					case "getInteracting": return interacting == null ? null : interacting.actor;
					case "getCombatLevel": return box(combat);
					case "getId": return box(npc ? npcId : slot);
					case "getIndex": return box(slot);
					case "getTransformedComposition": return shown;
					case "getPlayerComposition": return look;
					default: return null;
				}
			};
			actor = npc ? fake(NPC.class, answer) : fake(Player.class, answer);
		}
	}

	private final class Shot
	{
		final Projectile proxy;
		final int start, end;

		Shot(Body from, Body to, int start, int flight)
		{
			this.start = start;
			this.end = start + flight;
			int graphic = 1300 + random.nextInt(300);
			proxy = fake(Projectile.class, (name, args) ->
			{
				switch (name)
				{
					case "getId": return box(graphic);
					case "getSourceActor": return from.actor;
					case "getTargetActor": return to.actor;
					case "getSourcePoint": return new WorldPoint(from.x, from.y, 0);
					case "getTargetPoint": return new WorldPoint(to.x, to.y, 0);
					case "getStartCycle": return box(start);
					case "getEndCycle": return box(end);
					case "getStartHeight": return box(43);
					case "getEndHeight": return box(31);
					case "getSlope": return box(16);
					case "getStartPos": return box(64);
					default: return null;
				}
			});
		}
	}

	private final class Spot
	{
		final GraphicsObject proxy;
		final int end;

		Spot(int id, int localX, int localY, int start, int end)
		{
			this.end = end;
			LocalPoint at = new LocalPoint(localX, localY, -1);
			proxy = fake(GraphicsObject.class, (name, args) ->
			{
				switch (name)
				{
					case "getId": return box(id);
					case "getLocation": return at;
					case "getStartCycle": return box(start);
					case "getLevel": return box(0);
					case "getZ": return box(-40);
					case "finished": return cycle >= end;
					case "getAnimationFrame": return box((cycle - start) / 2);
					default: return null;
				}
			});
		}
	}

	private final class Hit
	{
		final Body target;
		final Hitsplat splat;

		Hit(Body target, int amount, boolean mine, int type)
		{
			this.target = target;
			int disappears = cycle + 60;
			splat = fake(Hitsplat.class, (name, args) ->
			{
				switch (name)
				{
					case "getAmount": return box(amount);
					case "isMine": return mine;
					case "getHitsplatType": return box(type);
					case "getDisappearsOnGameCycle": return box(disappears);
					default: return null;
				}
			});
		}
	}
}
