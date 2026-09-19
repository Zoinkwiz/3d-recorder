package com.extrinsiccognition.embertide;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.NPC;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.util.Text;

/**
 * Live observations of the scene around the local player, in two layers.
 *
 * The generic layer is the spatial profile the cubes page already produces:
 * cells, poses, and exact transitions with no invented cause. Any Studio can
 * draw it. The OSRS layer (`ec.mccr.osrs/3`) carries what a renderer with the
 * player's own game cache needs to rebuild the scene exactly: object ids with
 * their packed type and orientation, NPC ids, animation and facing on every
 * pose, player appearance, the map regions, and the cache revision. The app's
 * store passes those records through untouched; a viewer that does not know
 * them keeps them as evidence.
 *
 * Coverage is honest by construction: tiles are discovered within a radius of
 * the player on the player's plane, and nothing is interpolated across the
 * 0.6 s between game ticks.
 */
@Slf4j
public final class OsrsCapture
{
	public static final String OSRS_PROFILE = "ec.mccr.osrs/4";
	public static final int MAX_CELLS = 40_000;
	public static final int MAX_OBJECTS = 40_000;
	public static final int MAX_POSES = 800_000;
	public static final int MAX_EVENTS = 10_000;
	/**
	 * People are budgeted apart from each other, because they were not.
	 * One shared slate of 48 let a goblin field spend every slot in the first
	 * minute, and once spent it never reopened: two of seventeen recordings on
	 * this machine hit the cap inside ninety seconds, and from then on no
	 * passer-by could enter the memory at all. A crowd of NPCs can no longer
	 * lock the door on players, or the other way round. The budget is this
	 * plugin's own: Studio takes thousands of figures (it once took 64 and
	 * counted the player among them, so 64 others was one too many, fixed
	 * 2026-09-15 on Studio's side). It is a budget for the whole file, not
	 * the screen: a session at a hub meets far more than 32 people over
	 * twenty minutes, and every one after the 32nd was left out. Only the
	 * nearest TRACKED_PER_TICK figures get a pose each tick, so a larger cast
	 * costs a registration each, not a pose stream each.
	 */
	public static final int MAX_NPC_ACTORS = 8192;
	public static final int MAX_PLAYER_ACTORS = 8192;
	public static final int MAX_ACTORS = MAX_NPC_ACTORS + MAX_PLAYER_ACTORS;
	/** Actors given a pose on one game tick, nearest first: the pose budget's guard now that reach is the drawn scene. */
	public static final int TRACKED_PER_TICK = 1024;
	/** Client-tick motion samples per capture; past this the game tick alone carries movement. */
	public static final int MAX_MOTION = 600_000;
	public static final int SNAPSHOT_CHUNK = 1024;
	public static final int OBJECT_CHUNK = 512;
	public static final double MAX_SECONDS = 1200;
	public static final double POSE_MAX_GAP_S = 1.2;
	static final String PLAYER = "player";
	private static final String PLAYER_COLOR = "#e8863a";
	private static final String NPC_COLOR = "#75b7b2";
	private static final String OTHER_PLAYER_COLOR = "#c9a8ff";
	private static final int KIND_GAME = 0;
	private static final int KIND_WALL = 1;
	private static final int KIND_GROUND = 2;
	private static final int KIND_DECORATIVE = 3;

	private final Client client;
	private final Recorder recorder;
	private final MccrSession session;
	private final int radius;
	private final boolean trackNpcs;
	private final boolean trackPlayers;
	private final String playerName;
	private final int world;
	private final boolean instance;
	private final Map<Long, String> known = new HashMap<>();
	private final Set<Long> objectTiles = new HashSet<>();
	private final Set<String> actors = new HashSet<>();
	private final Map<String, Integer> appearance = new HashMap<>();
	/**
	 * OTHER PLAYERS ARE UNNAMED. A memory shows who was there as figures wearing
	 * what they wore, never as display names: a person's name is theirs, and a
	 * file that leaves this machine must not carry it. Each other player gets a
	 * number in order of first sighting, stable for this memory and meaningless
	 * outside it. The player themselves is PLAYER, and their own name is theirs
	 * to keep.
	 */
	private final Map<String, String> pseudonyms;
	/**
	 * A SESSION IS A CHAIN OF FILES. Each capture is one file, capped by
	 * `maxSeconds`; the plugin starts the next one where this ended and gives
	 * every file in the chain the same `series` and a rising `part`, so Studio
	 * can join the evening back into one timeline. The pseudonym table is
	 * shared along the chain for the same reason: Adventurer 3 in part four is
	 * the same figure as Adventurer 3 in part one.
	 */
	private final String series;
	private final int part;
	private final double maxSeconds;
	private int poseCount;
	private int eventCount;
	private int objectCount;
	private int motionCount;
	private boolean motionLimit;
	private final Map<String, long[]> lastMotion = new HashMap<>();
	private boolean cellLimit;
	private boolean objectLimit;
	private int npcActors;
	private int playerActors;
	private long lastPlayerCell = Long.MIN_VALUE;
	/** Tiles waiting to be discovered, nearest first; a bounded slice is walked per game tick so the client never stalls. */
	private final java.util.ArrayDeque<int[]> pending = new java.util.ArrayDeque<>();
	private static final int TILES_PER_TICK = 60;
	private int[] lastRegions = new int[0];
	private volatile boolean recording;
	private volatile String reason = "";
	/** Whether the `reason` above describes an ordinary ending rather than a fault. */
	private volatile boolean plannedEnd = false;
	private volatile String statusLine = "Starting";
	private CompletableFuture<Void> finishing;

	public OsrsCapture(Client client, Recorder recorder, EmbertideConfig config, String dimension, String playerName, int world)
	{
		this(client, recorder, config, dimension, playerName, world, java.util.UUID.randomUUID().toString(), 1, new HashMap<>(), MAX_SECONDS);
	}

	public OsrsCapture(Client client, Recorder recorder, EmbertideConfig config, String dimension, String playerName, int world,
		String series, int part, Map<String, String> pseudonyms, double maxSeconds)
	{
		this.client = client;
		this.recorder = recorder;
		this.series = series;
		this.part = part;
		this.pseudonyms = pseudonyms;
		this.maxSeconds = Math.max(60, Math.min(MAX_SECONDS, maxSeconds));
		this.session = new MccrSession(dimension, this.maxSeconds);
		this.radius = Math.max(4, Math.min(26, config.captureRadius()));
		this.trackNpcs = config.trackNpcs();
		this.trackPlayers = config.trackPlayers();
		this.playerName = playerName == null || playerName.isEmpty() ? "You" : playerName;
		this.world = world;
		this.instance = dimension.startsWith("osrs:instance:");
	}

	public String id()
	{
		return session.id;
	}

	public String dimension()
	{
		return session.dimension;
	}

	// ── What mattered: the significance layer ──────────
	// Combat, deaths, levels and the player's own public chat, as MCCR records
	// the app already reads as moments. Each is bounded and rate-limited so a
	// fight is a few beats, not a hitsplat storm; nothing here reads inventories,
	// other players' chat, or account data.

	/** Real skill levels last seen, so a level-up is an increase and not a login. */
	private final Map<Skill, Integer> levels = new EnumMap<>(Skill.class);
	/** Per opponent, when a "fighting" beat was last written. */
	private final Map<String, Double> fightNoted = new HashMap<>();
	private String lastAttacker;
	private double lastAttackedAt = -1;
	private double lastNearDeathAt = -60;
	private int momentCount;
	/** Hits are frequent; a recording that runs for hours is bounded the way moments are. */
	private static final int MAX_HITS = 200000;
	private int hitCount = 0;
	private static final int MAX_MOMENTS = 2000;
	/** The last camera sample sent, so a still camera costs nothing. */
	private int[] lastCamera;
	private int cameraCount;
	private static final int MAX_CAMERA = 40000;
	private int speechCount;
	private static final int MAX_SPEECH = 600;
	/** The last line each actor was seen saying, so one line is recorded once. */
	private final Map<String, String> lastSaid = new HashMap<>();
	private static final double FIGHT_NOTE_GAP = 10;
	private static final double NEAR_DEATH_GAP = 30;

	private String actorName(Actor actor)
	{
		if (actor == null || actor.getName() == null)
		{
			return "someone";
		}
		if (actor instanceof Player)
		{
			return pseudonym(actor.getName());
		}
		return net.runelite.client.util.Text.removeTags(actor.getName());
	}

	private boolean moment(String kind, String summary, double t)
	{
		if (!recording() || momentCount >= MAX_MOMENTS)
		{
			return false;
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("kind", kind);
		payload.addProperty("summary", summary);
		payload.addProperty("dimension", session.dimension);
		JsonArray actors = new JsonArray();
		actors.add(PLAYER);
		payload.add("actors", actors);
		payload.add("observed_cause", JsonNull.INSTANCE);
		if (emit("world.event", t, payload, PLAYER, false))
		{
			momentCount++;
			return true;
		}
		return false;
	}

	/** A hitsplat on any actor: the start of a fight with someone, or damage taken. */
	public void hitsplat(Actor target, int amount, boolean mine, Player me)
	{
		if (!recording() || target == null)
		{
			return;
		}
		double t = session.time();
		hit(target, amount, mine, me, t);
		if (target == me)
		{
			Actor attacker = me.getInteracting();
			lastAttacker = attacker == null ? lastAttacker : actorName(attacker);
			lastAttackedAt = t;
			int hp = client.getBoostedSkillLevel(Skill.HITPOINTS);
			int max = Math.max(1, client.getRealSkillLevel(Skill.HITPOINTS));
			if (hp * 4 <= max && t - lastNearDeathAt >= NEAR_DEATH_GAP)
			{
				lastNearDeathAt = t;
				JsonObject payload = new JsonObject();
				payload.addProperty("cause", lastAttacker == null ? "damage" : lastAttacker);
				payload.addProperty("health_remaining", hp);
				payload.addProperty("dimension", session.dimension);
				emit("player.near_death", t, payload, PLAYER, false);
			}
			return;
		}
		if (!mine)
		{
			return;
		}
		String name = actorName(target);
		Double noted = fightNoted.get(name);
		if (noted == null || t - noted >= FIGHT_NOTE_GAP)
		{
			fightNoted.put(name, t);
			moment("combat.fight", "Fighting " + name, t);
		}
	}

	/**
	 * One hit, in the words every game shares: who it landed on, for how much,
	 * and how much life they had left as the client reports it. The client gives
	 * an NPC's health as a ratio out of a scale rather than in hit points, and
	 * the record carries those two numbers as they are; the editor draws a bar
	 * from them without pretending to know the hit points. -1 means the client
	 * did not say, and then nothing is written for it.
	 *
	 * Written for every hitsplat the client shows, not only the player's own:
	 * a fight has two sides, and a bar over an opponent that never moved when
	 * they were struck would be a bar saying something false.
	 */
	private void hit(Actor target, int amount, boolean mine, Player me, double t)
	{
		String targetId = target == me ? PLAYER : actorId(target);
		if (targetId == null || hitCount >= MAX_HITS)
		{
			return;
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("target", targetId);
		payload.addProperty("amount", Math.max(0, amount));
		String source = mine ? PLAYER : (target == me && me.getInteracting() != null ? actorId(me.getInteracting()) : null);
		if (source != null)
		{
			payload.addProperty("source", source);
		}
		int ratio = target.getHealthRatio();
		int scale = target.getHealthScale();
		if (target == me)
		{
			// The player's own life is known in hit points, which is better than a ratio.
			ratio = client.getBoostedSkillLevel(Skill.HITPOINTS);
			scale = Math.max(1, client.getRealSkillLevel(Skill.HITPOINTS));
		}
		if (ratio >= 0 && scale > 0)
		{
			payload.addProperty("health_ratio", ratio);
			payload.addProperty("health_scale", scale);
		}
		payload.addProperty("dimension", session.dimension);
		if (emit("osrs.hit", t, payload, targetId, false))
		{
			hitCount++;
		}
	}

	/** Someone died: the player, or an opponent they were fighting. */
	public void actorDied(Actor actor, Player me)
	{
		if (!recording() || actor == null)
		{
			return;
		}
		double t = session.time();
		if (actor == me)
		{
			JsonObject payload = new JsonObject();
			payload.addProperty("cause", lastAttacker == null || t - lastAttackedAt > 30 ? "unknown" : lastAttacker);
			payload.addProperty("dimension", session.dimension);
			emit("player.death", t, payload, PLAYER, false);
			return;
		}
		String name = actorName(actor);
		// The same death in the shared words, over the body it happened to.
		String deadId = actorId(actor);
		if (deadId != null)
		{
			JsonObject payload = new JsonObject();
			payload.addProperty("target", deadId);
			payload.addProperty("dimension", session.dimension);
			emit("osrs.death", t, payload, deadId, false);
		}
		Double noted = fightNoted.get(name);
		if (noted != null && t - noted <= 60)
		{
			moment("combat.kill", "Killed " + name, t);
		}
	}

	/** A real level went up. The first sighting of a skill is the baseline, never a level-up. */
	public void statChanged(Skill skill, int level)
	{
		if (!recording() || skill == null)
		{
			return;
		}
		Integer before = levels.put(skill, level);
		if (before == null || level <= before)
		{
			return;
		}
		double t = session.time();
		JsonObject payload = new JsonObject();
		payload.addProperty("advancement_id", "osrs:level:" + skill.name().toLowerCase(Locale.ROOT) + ":" + level);
		payload.addProperty("title", skill.getName() + " level " + level);
		payload.addProperty("dimension", session.dimension);
		emit("advancement", t, payload, PLAYER, false);
	}

	/** The player's own public chat line, and only theirs. */
	public void ownChat(String text, String name)
	{
		if (!recording() || text == null || text.trim().isEmpty())
		{
			return;
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("sender", name == null ? "You" : name);
		payload.addProperty("text", text.length() > 200 ? text.substring(0, 200) : text);
		payload.addProperty("dimension", session.dimension);
		emit("chat.message", session.time(), payload, PLAYER, false);
	}

	/**
	 * A line the client drew above someone's head: public chat, or what an NPC
	 * says. Only what the game itself renders overhead reaches this, so private,
	 * clan and friends chat cannot: none of it is ever drawn there. The player's
	 * own lines come through {@link #ownChat} instead, so they are not doubled.
	 */
	public void overhead(Actor actor, String text, Player me)
	{
		// Another player's line is never recorded, whoever calls this.
		if (!recording() || !(actor instanceof NPC) || actor == me || text == null || speechCount >= MAX_SPEECH)
		{
			return;
		}
		String line = text.trim();
		if (line.isEmpty())
		{
			return;
		}
		String id = actorId(actor);
		if (id == null || line.equals(lastSaid.get(id)))
		{
			return;
		}
		lastSaid.put(id, line);
		JsonObject payload = new JsonObject();
		payload.addProperty("sender", actor.getName() == null ? "Someone" : Text.removeTags(actor.getName()));
		payload.addProperty("text", line.length() > 200 ? line.substring(0, 200) : line);
		payload.addProperty("dimension", session.dimension);
		if (emit("chat.message", session.time(), payload, id, false))
		{
			speechCount++;
		}
	}

	/** The id the memory knows an actor by, matching the one its poses carry. */
	private String actorId(Actor actor)
	{
		if (actor instanceof NPC)
		{
			NPC npc = (NPC) actor;
			String material = SceneMapper.objectMaterial(npc.getName());
			return "npc:" + (material == null ? "unnamed" : material) + "#" + npc.getIndex();
		}
		if (actor instanceof Player)
		{
			return idOf(actor);
		}
		return null;
	}

	public boolean recording()
	{
		return recording;
	}

	public String reason()
	{
		return reason;
	}

	public Recorder recorder()
	{
		return recorder;
	}

	public String status()
	{
		return statusLine;
	}

	/** How long this memory has run, in its own clock. */
	public double seconds()
	{
		return session.time();
	}

	/** This memory's place in the session's chain of files. */
	public int part()
	{
		return part;
	}

	public double time()
	{
		return session.time();
	}

	/**
	 * Arm the recording. The clock, the header and the recording boundary wait
	 * for the first game tick with a scene, so a memory starts on its first
	 * full snapshot rather than on half a second of nothing before it.
	 */
	public CompletableFuture<Void> start()
	{
		recording = true;
		armed = true;
		statusLine = "Waiting for the next game tick";
		return opened;
	}

	private boolean armed;
	private final CompletableFuture<Void> opened = new CompletableFuture<>();

	/** The first tick with a scene: the origin, the header, the boundary. */
	private void open()
	{
		armed = false;
		session.rebase();
		recorder.open(header()).whenComplete((v, error) ->
		{
			if (error != null)
			{
				opened.completeExceptionally(error);
			}
			else
			{
				opened.complete(null);
			}
		});
		JsonObject payload = new JsonObject();
		payload.addProperty("scope", "recording");
		payload.addProperty("dimension", session.dimension);
		emit("mccr.capture_start", session.time(), payload, PLAYER, false);
		statusLine = "Connecting to Embertide";
	}

	JsonObject header()
	{
		JsonObject header = new JsonObject();
		header.addProperty("type", "mccr.header");
		header.addProperty("format", MccrSession.FORMAT);
		header.addProperty("profile", MccrSession.PROFILE);
		header.addProperty("schema", "CorePercept envelope + ec.mccr.spatial/1 + " + OSRS_PROFILE);
		header.addProperty("game", MccrSession.GAME);
		header.addProperty("place_id", "local:osrs:world:" + world + ":" + session.dimension);
		String started = session.capturedAt.toString();
		header.addProperty("day", started.substring(0, 10));
		header.addProperty("started_at", started);
		header.addProperty("recorder", MccrSession.RECORDER);
		JsonObject worldInfo = new JsonObject();
		worldInfo.add("seed", JsonNull.INSTANCE);
		worldInfo.add("gen_version", JsonNull.INSTANCE);
		worldInfo.add("server_version", JsonNull.INSTANCE);
		worldInfo.add("client_versions", JsonNull.INSTANCE);
		worldInfo.addProperty("revision", client.getRevision());
		worldInfo.addProperty("world_number", world);
		header.add("world", worldInfo);
		JsonObject spatial = new JsonObject();
		spatial.addProperty("dimension", session.dimension);
		spatial.addProperty("axes", "x-east,y-up,z-south");
		spatial.addProperty("units", "tile");
		spatial.addProperty("coverage", "observed-cells");
		spatial.addProperty("authority", "local-client-observation");
		spatial.addProperty("pose_max_gap_s", POSE_MAX_GAP_S);
		spatial.addProperty("elevation", "y = plane * " + SceneMapper.PLANE_HEIGHT
			+ " + clamp(round(-tileHeight / " + SceneMapper.LOCAL_TILE_SIZE + "), 0, " + (SceneMapper.PLANE_HEIGHT - 1) + ")");
		header.add("spatial", spatial);
		JsonObject osrs = new JsonObject();
		osrs.addProperty("profile", OSRS_PROFILE);
		osrs.addProperty("cache_revision", client.getRevision());
		osrs.addProperty("world_number", world);
		osrs.addProperty("instance", instance);
		osrs.addProperty("coordinates", "game world tiles: x east, y north, plane; in an instance the scene's own coordinates");
		osrs.addProperty("records", "osrs.region on each scene load; osrs.object baselines and transitions with id, kind, type and orientation from the object config; "
			+ "osrs.appearance for players; every player.position carries osrs.{npc_id, orientation, animation, pose_animation, animation_frame, graphic}; "
			+ "osrs.motion on the 20 ms client tick with the visible state of nearby actors as [id, x, y, plane, orientation, animation, frame, pose_animation], x and y in fractional tiles, only when changed; "
			+ "osrs.camera on the same tick as [x, y, height, yaw, pitch, scale], the player's own camera in fractional tiles with height up-positive, only when it moved");
		osrs.addProperty("baseline", "The static world is the cache's own map at cache_revision; recorded objects override it where they differ.");
		header.add("osrs", osrs);
		JsonObject meta = new JsonObject();
		meta.addProperty("id", session.id);
		meta.addProperty("title", "Old School RuneScape memory");
		// The chain this file belongs to, and its place in it.
		meta.addProperty("series", series);
		meta.addProperty("part", part);
		meta.addProperty("duration", 0);
		meta.addProperty("description", "Local client observations begin after the writer accepts recording. "
			+ "Player and actor positions once per game tick, with each actor's facing: everyone the client draws on the player's plane, "
			+ "nearest first, at most " + TRACKED_PER_TICK + " at a time and " + MAX_NPC_ACTORS + " NPCs and " + MAX_PLAYER_ACTORS + " players in all. "
			+ "Ground tiles and named objects are discovered within " + radius + " tiles on the player's plane; at most "
			+ MAX_CELLS + " cells and " + MAX_OBJECTS + " objects. Objects appearing or vanishing are exact transitions with no known cause. "
			+ "Unobserved space, earlier history, chat, inventories, account data and other planes are absent.");
		header.add("session", meta);
		JsonArray actorList = new JsonArray();
		actorList.add(actorEntry(PLAYER, playerName, PLAYER_COLOR));
		header.add("actors", actorList);
		header.addProperty("channel_rule", "Local world observations only; no chat, no account data, no private memory captured.");
		header.addProperty("clock", "ts is capture UTC + elapsed_s; elapsed_s is monotonic wall time, not game ticks. Equal-time records retain file order.");
		return header;
	}

	private static JsonObject actorEntry(String id, String name, String color)
	{
		JsonObject actor = new JsonObject();
		actor.addProperty("id", id);
		actor.addProperty("name", name);
		actor.addProperty("color", color);
		return actor;
	}

	/**
	 * Where the player's own camera was looking, on the client tick, and only
	 * when it moved. This is what lets an edit say "show it the way I saw it":
	 * without it a replay can only offer camera positions someone invented
	 * afterwards. Position is in fractional world tiles with height in client
	 * units; yaw and pitch are the client's 0..2047; scale is its zoom.
	 */
	private void camera(WorldView view)
	{
		if (cameraCount >= MAX_CAMERA)
		{
			return;
		}
		int[] now = {
			client.getCameraX(),
			client.getCameraY(),
			client.getCameraZ(),
			client.getCameraYaw(),
			client.getCameraPitch(),
			client.getScale(),
		};
		// Before the world is drawn the client answers zeroes for all of it. A zero
		// zoom was the tell; on the opening tick the zoom is already real and the
		// position still zero, so a recording began with a camera at the world's
		// origin that flew to the player half a second in. No
		// real camera stands at (0, 0, 0).
		if (now[5] <= 0 || (now[0] == 0 && now[1] == 0 && now[2] == 0))
		{
			return;
		}
		if (lastCamera != null && Arrays.equals(lastCamera, now))
		{
			return;
		}
		lastCamera = now;
		JsonArray sample = new JsonArray();
		double tile = SceneMapper.LOCAL_TILE_SIZE;
		// The client's camera Y is the NORTHWARD local coordinate and its Z is the
		// HEIGHT. This has now been written both ways round and argued both ways in
		// this comment; the measurement settles it. Across all eleven recordings on
		// this machine, taken under three different versions of this file, reading
		// north from the client's Y puts the camera a median of 8 to 10 tiles from
		// the player it is following, which is a zoomed-out camera. Reading it from
		// Z puts it 60 to 72 tiles away, which is nothing.
		//
		// A previous pass here read one of those files as if it had been written by
		// a build an hour younger than it was, drew the opposite conclusion, and
		// swapped these two lines. Hence the layer bump: files written before
		// ec.mccr.osrs/3 carry north and height the other way round, and readers
		// put them back.
		//
		// Yaw and pitch arrive in JAU (16384 to the turn) and the memory speaks the
		// client's other unit, 2048 to the turn, so both are divided by eight.
		sample.add(Math.round((view.getBaseX() + now[0] / tile) * 1000.0) / 1000.0);
		sample.add(Math.round((view.getBaseY() + now[1] / tile) * 1000.0) / 1000.0);
		// Height grows downward for the client; the memory keeps it up-positive.
		sample.add(-now[2]);
		sample.add(Math.round((now[3] / 8.0) * 1000.0) / 1000.0);
		sample.add(Math.round((now[4] / 8.0) * 1000.0) / 1000.0);
		sample.add(now[5]);
		// The viewport the zoom applied to. The client projects a point at
		// (x * scale / z) pixels from the centre, so the field of view is the
		// viewport's size over the scale: a 900-pixel-tall view at scale 512 is
		// 83 degrees tall, far wider than the frame a lens of 35 mm gives, and
		// a replay that assumed the lens looked zoomed in.
		sample.add(client.getViewportWidth());
		sample.add(client.getViewportHeight());
		JsonObject payload = new JsonObject();
		payload.addProperty("dimension", session.dimension);
		payload.add("camera", sample);
		if (emit("osrs.camera", session.time(), payload, PLAYER, false))
		{
			cameraCount++;
		}
	}

	private boolean emit(String type, double t, JsonObject payload, String user, boolean control)
	{
		JsonObject row = session.record(type, t, payload, user);
		if (recorder.enqueue(row, control))
		{
			return true;
		}
		if (!control)
		{
			// A real fault: records were produced faster than they could be kept.
			stop("local recording queue full");
		}
		return false;
	}

	/** Once per game tick, on the client thread. `rediscover` forces a fresh walk after a scene load. */
	public void tick(boolean rediscover)
	{
		if (!recording)
		{
			return;
		}
		if (recorder.state() == Recorder.State.FAILED)
		{
			// A real fault: the file could not be written mid-recording.
			stop("recorder unavailable");
			return;
		}
		double t = session.time();
		if (checkLimit(t))
		{
			return;
		}
		WorldView view = client.getTopLevelWorldView();
		Player me = client.getLocalPlayer();
		if (view == null || me == null || me.getWorldLocation() == null)
		{
			return;
		}
		if (armed)
		{
			// THE WELCOME SCREEN IS NOT THE GAME. After login the world is loaded and
			// ticking behind "Click here to play", but nothing in it moves until the
			// player does, so a recording opened then began with a second of figures
			// standing mid-step. The file opens once that screen is gone.
			if (welcomeScreenUp())
			{
				statusLine = "Waiting for you to enter the world";
				return;
			}
			open();
			t = session.time();
			rediscover = true;
		}
		WorldPoint at = me.getWorldLocation();
		if (rediscover || !Arrays.equals(lastRegions, regions(view)))
		{
			region(view, t);
		}
		long cell = SceneMapper.key(at.getX(), at.getPlane(), at.getY());
		if (rediscover || cell != lastPlayerCell)
		{
			lastPlayerCell = cell;
			enqueueDiscovery(at);
		}
		discoverPending(view, t);
		if (!recording)
		{
			return;
		}
		appearance(me, PLAYER, t);
		pose(view, me, PLAYER, at, t, true, -1);
		// EVERYONE ELSE'S POSES RIDE IN ONE RECORD A TICK. A pose per figure per
		// tick wore the record envelope, a quarter of a kilobyte, on each; a
		// crowd at the Grand Exchange is a hundred figures, and the budget of 24
		// a tick that kept the file small left most of them unrecorded and the
		// rest recorded in turns, stuck mid-step whenever they fell out of the
		// nearest 24 (a crowd recording with 160 players in twenty seconds,
		// 2026-09-15). osrs.poses carries a tick's worth as one array.
		JsonArray crowd = new JsonArray();
		for (Actor other : inThePicture(view, me, at))
		{
			String id = idOf(other);
			if (other instanceof NPC)
			{
				NPC npc = (NPC) other;
				String name = SceneMapper.objectMaterial(npc.getName());
				if (announce(id, name == null ? "NPC" : npc.getName(), NPC_COLOR, "npc", npc.getId(), npc.getCombatLevel(), t))
				{
					crowdPose(crowd, view, npc, id, npc.getWorldLocation(), npc.getId());
				}
			}
			else
			{
				Player player = (Player) other;
				if (announce(id, pseudonym(player.getName()), OTHER_PLAYER_COLOR, "player", -1, -1, t))
				{
					appearance(player, id, t);
					crowdPose(crowd, view, player, id, player.getWorldLocation(), -1);
				}
			}
		}
		if (crowd.size() > 0)
		{
			if (poseCount + crowd.size() > MAX_POSES)
			{
				stop("pose limit");
				return;
			}
			JsonObject payload = new JsonObject();
			payload.addProperty("dimension", session.dimension);
			payload.add("poses", crowd);
			if (emit("osrs.poses", t, payload, PLAYER, false))
			{
				poseCount += crowd.size();
			}
		}
		statusLine = String.format(Locale.ROOT, "Recording %d:%02d · %d cells · %d objects%s", (int) t / 60, (int) t % 60, known.size(), objectCount,
			cellLimit || objectLimit ? " · area captured" : "");
	}

	/**
	 * Once per 20 ms client tick: the state the screen actually showed for every
	 * tracked actor near the player, interpolated position and all, emitted only
	 * when something changed. This is what the game tick cannot carry: a run
	 * across two tiles with a turn halfway.
	 */
	/** The click-to-play screen after login: the world is there, but nothing moves yet. */
	private boolean welcomeScreenUp()
	{
		net.runelite.api.widgets.Widget screen = client.getWidget(net.runelite.api.gameval.InterfaceID.WELCOME_SCREEN << 16);
		return screen != null && !screen.isHidden();
	}

	public void clientTick()
	{
		// Nothing is written before the file is open: an armed recording waits.
		if (!recording || motionLimit || armed)
		{
			return;
		}
		WorldView view = client.getTopLevelWorldView();
		Player me = client.getLocalPlayer();
		if (view == null || me == null || me.getWorldLocation() == null)
		{
			return;
		}
		WorldPoint at = me.getWorldLocation();
		JsonArray samples = new JsonArray();
		motionSample(samples, me, PLAYER, view);
		for (Actor other : inThePicture(view, me, at))
		{
			String id = idOf(other);
			if (actors.contains(id))
			{
				motionSample(samples, other, id, view);
			}
		}
		if (samples.size() == 0)
		{
			return;
		}
		if (motionCount >= MAX_MOTION)
		{
			motionLimit = true;
			return;
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("dimension", session.dimension);
		payload.add("samples", samples);
		if (emit("osrs.motion", session.time(), payload, PLAYER, false))
		{
			motionCount++;
		}
		camera(view);
	}

	private void motionSample(JsonArray samples, Actor actor, String id, WorldView view)
	{
		LocalPoint local = actor.getLocalLocation();
		WorldPoint world = actor.getWorldLocation();
		if (local == null || world == null)
		{
			return;
		}
		long[] state = {local.getX(), local.getY(), world.getPlane(), actor.getCurrentOrientation(),
			actor.getAnimation(), actor.getAnimationFrame(), actor.getPoseAnimation()};
		long[] previous = lastMotion.get(id);
		// A frame ticking forward is not a change worth a sample: Studio runs the
		// animation from the sample's frame and the time since. A frame going
		// back is the animation starting over, and that is. Without this a crowd
		// standing still in its animations cost a sample each per client tick.
		if (previous != null && previous[0] == state[0] && previous[1] == state[1] && previous[2] == state[2]
			&& previous[3] == state[3] && previous[4] == state[4] && previous[6] == state[6] && state[5] >= previous[5])
		{
			previous[5] = state[5];
			return;
		}
		lastMotion.put(id, state);
		JsonArray sample = new JsonArray();
		sample.add(id);
		sample.add(Math.round((view.getBaseX() + local.getX() / (double) SceneMapper.LOCAL_TILE_SIZE) * 1000.0) / 1000.0);
		sample.add(Math.round((view.getBaseY() + local.getY() / (double) SceneMapper.LOCAL_TILE_SIZE) * 1000.0) / 1000.0);
		sample.add(world.getPlane());
		sample.add(actor.getCurrentOrientation());
		sample.add(actor.getAnimation());
		sample.add(actor.getAnimationFrame());
		sample.add(actor.getPoseAnimation());
		samples.add(sample);
	}

	private static int[] regions(WorldView view)
	{
		int[] regions = view.getMapRegions();
		return regions == null ? new int[0] : regions;
	}

	/** The scene's identity for a cache renderer: which map regions are loaded and where the scene sits. */
	private void region(WorldView view, double t)
	{
		int[] regions = regions(view);
		lastRegions = regions.clone();
		JsonObject payload = new JsonObject();
		JsonArray list = new JsonArray();
		for (int region : regions)
		{
			list.add(region);
		}
		payload.add("regions", list);
		payload.addProperty("base_x", view.getBaseX());
		payload.addProperty("base_y", view.getBaseY());
		payload.addProperty("plane", view.getPlane());
		payload.addProperty("instance", view.isInstance());
		payload.addProperty("dimension", session.dimension);
		emit("osrs.region", t, payload, PLAYER, false);
	}

	/**
	 * WHO IS IN THE PICTURE.
	 *
	 * The capture radius is a budget for GROUND: every tile inside it costs one
	 * of the memory's 40 000 cells. A person costs a pose a tick, so binding
	 * people to the same radius hid everyone the client was plainly drawing —
	 * in a nine minute Lumbridge memory, six passers-by came to four seconds of
	 * coverage between them, and the ones standing a little further off were
	 * never recorded at all. So the reach for actors is the scene the client
	 * draws, on the player's plane, nearest first, and at most
	 * {@link #TRACKED_PER_TICK} at once: a crowd fills the slots with the people
	 * the player was actually beside, and cannot spend the pose budget early.
	 */
	private List<Actor> inThePicture(WorldView view, Player me, WorldPoint at)
	{
		List<Actor> found = new ArrayList<>();
		if (trackNpcs)
		{
			for (NPC npc : view.npcs())
			{
				if (drawn(view, at, npc))
				{
					found.add(npc);
				}
			}
		}
		if (trackPlayers)
		{
			for (Player other : view.players())
			{
				if (other != me && other != null && other.getName() != null && drawn(view, at, other))
				{
					found.add(other);
				}
			}
		}
		found.sort(java.util.Comparator.comparingInt(actor -> at.distanceTo(actor.getWorldLocation())));
		return found.size() > TRACKED_PER_TICK ? found.subList(0, TRACKED_PER_TICK) : found;
	}

	/** Inside the loaded scene, on the player's plane: what the client is drawing right now. */
	private static boolean drawn(WorldView view, WorldPoint at, Actor actor)
	{
		WorldPoint here = actor == null ? null : actor.getWorldLocation();
		return here != null && SceneMapper.inScene(view.getBaseX(), view.getBaseY(), view.getSizeX(), view.getSizeY(),
			at.getPlane(), here.getX(), here.getY(), here.getPlane());
	}

	/** One actor, one id, wherever it is read: the NPC's name and index, or the player's name. */
	private String idOf(Actor actor)
	{
		if (actor instanceof NPC)
		{
			NPC npc = (NPC) actor;
			String name = SceneMapper.objectMaterial(npc.getName());
			return "npc:" + (name == null ? "unnamed" : name) + "#" + npc.getIndex();
		}
		return "player:" + pseudonym(actor.getName()).toLowerCase(Locale.ROOT).replace(' ', '-');
	}

	/** "Adventurer 3": the same figure gets the same number for the whole memory. Package-private for the test. */
	String pseudonym(String name)
	{
		return pseudonymFor(pseudonyms, name);
	}

	static String pseudonymFor(Map<String, String> table, String name)
	{
		String key = name == null ? "" : net.runelite.client.util.Text.removeTags(name).toLowerCase(Locale.ROOT);
		String known = table.get(key);
		if (known != null)
		{
			return known;
		}
		String fresh = "Adventurer " + (table.size() + 1);
		table.put(key, fresh);
		return fresh;
	}

	/** The importer names an actor from a presence line, so each new actor gets one. False once that kind's roster is full. */
	private boolean announce(String id, String name, String color, String kind, int npcId, int combatLevel, double t)
	{
		if (actors.contains(id))
		{
			return true;
		}
		boolean isNpc = "npc".equals(kind);
		if (isNpc ? npcActors >= MAX_NPC_ACTORS : playerActors >= MAX_PLAYER_ACTORS)
		{
			return false;
		}
		JsonObject payload = new JsonObject();
		JsonArray names = new JsonArray();
		names.add(name);
		payload.add("present_players", names);
		payload.addProperty("kind", kind);
		payload.addProperty("color", color);
		if (npcId >= 0)
		{
			payload.addProperty("npc_id", npcId);
		}
		if (combatLevel >= 0)
		{
			payload.addProperty("combat_level", combatLevel);
		}
		if (!emit("presence", t, payload, id, false))
		{
			return false;
		}
		actors.add(id);
		if (isNpc)
		{
			npcActors++;
		}
		else
		{
			playerActors++;
		}
		return true;
	}

	/** A player's kit and colours, once and again whenever they change. Exactly what the cache renders a player from. */
	private void appearance(Player player, String id, double t)
	{
		PlayerComposition composition = player.getPlayerComposition();
		if (composition == null)
		{
			return;
		}
		int[] equipment = composition.getEquipmentIds();
		int[] colors = composition.getColors();
		int hash = 31 * Arrays.hashCode(equipment) + Arrays.hashCode(colors) + (composition.isFemale() ? 7 : 0)
			+ 13 * composition.getTransformedNpcId();
		Integer previous = appearance.get(id);
		if (previous != null && previous == hash)
		{
			return;
		}
		JsonObject payload = new JsonObject();
		payload.add("equipment_ids", ints(equipment));
		payload.add("colors", ints(colors));
		payload.addProperty("female", composition.isFemale());
		payload.addProperty("transformed_npc_id", composition.getTransformedNpcId());
		if (PLAYER.equals(id))
		{
			payload.addProperty("combat_level", player.getCombatLevel());
		}
		if (emit("osrs.appearance", t, payload, id, false))
		{
			appearance.put(id, hash);
		}
	}

	private static JsonArray ints(int[] values)
	{
		JsonArray list = new JsonArray();
		if (values != null)
		{
			for (int value : values)
			{
				list.add(value);
			}
		}
		return list;
	}

	private void pose(WorldView view, Actor actor, String id, WorldPoint at, double t, boolean look, int npcId)
	{
		if (poseCount >= MAX_POSES)
		{
			stop("pose limit");
			return;
		}
		int height = tileHeight(view, at);
		int[] ground = SceneMapper.groundCell(at.getX(), at.getY(), at.getPlane(), height);
		JsonObject payload = new JsonObject();
		JsonObject coords = new JsonObject();
		coords.addProperty("x", ground[0]);
		coords.addProperty("y", ground[1] + 1);
		coords.addProperty("z", ground[2]);
		payload.add("coords", coords);
		payload.addProperty("dimension", session.dimension);
		payload.addProperty("plane", at.getPlane());
		// Everyone's facing, not only the player's. The exact lane reads
		// osrs.orientation, but the generic lane a Studio without the game cache
		// draws knows only `yaw`, and writing it for the local player alone left
		// every NPC and passer-by frozen at yaw 0, facing south for the whole
		// memory whichever way they walked.
		payload.addProperty("yaw", SceneMapper.yawDegrees(actor.getCurrentOrientation()));
		if (look)
		{
			payload.addProperty("pitch", 0);
		}
		JsonObject osrs = new JsonObject();
		osrs.addProperty("world_x", at.getX());
		osrs.addProperty("world_y", at.getY());
		if (npcId >= 0)
		{
			osrs.addProperty("npc_id", npcId);
		}
		osrs.addProperty("orientation", actor.getCurrentOrientation());
		osrs.addProperty("animation", actor.getAnimation());
		osrs.addProperty("pose_animation", actor.getPoseAnimation());
		osrs.addProperty("animation_frame", actor.getAnimationFrame());
		osrs.addProperty("graphic", actor.getGraphic());
		// How high over the figure the graphic sits: an alchemy's orb floats at the hands.
		osrs.addProperty("graphic_height", actor.getGraphicHeight());
		payload.add("osrs", osrs);
		if (emit("player.position", t, payload, id, false))
		{
			poseCount++;
		}
	}

	/**
	 * One figure's pose as an entry of the tick's osrs.poses record:
	 * [id, world x, world y, plane, cell height, orientation, animation,
	 * pose animation, animation frame, graphic, npc id or -1, graphic height,
	 * client slot]. The same fields a player.position carries, without the
	 * envelope, plus the slot the client draws the figure in.
	 */
	private void crowdPose(JsonArray crowd, WorldView view, Actor actor, String id, WorldPoint at, int npcId)
	{
		int height = tileHeight(view, at);
		int[] ground = SceneMapper.groundCell(at.getX(), at.getY(), at.getPlane(), height);
		JsonArray entry = new JsonArray();
		entry.add(id);
		entry.add(at.getX());
		entry.add(at.getY());
		entry.add(at.getPlane());
		entry.add(ground[1] + 1);
		entry.add(actor.getCurrentOrientation());
		entry.add(actor.getAnimation());
		entry.add(actor.getPoseAnimation());
		entry.add(actor.getAnimationFrame());
		entry.add(actor.getGraphic());
		entry.add(npcId);
		entry.add(actor.getGraphicHeight());
		// The client's slot for the figure: the order it draws the figures of one
		// tile in, so a replay can put the same one on top. A slot number, not a
		// name, and it means nothing outside the session.
		entry.add(actor instanceof Player ? ((Player) actor).getId() : actor instanceof NPC ? ((NPC) actor).getIndex() : -1);
		crowd.add(entry);
	}

	private static int tileHeight(WorldView view, WorldPoint at)
	{
		int sx = at.getX() - view.getBaseX();
		int sy = at.getY() - view.getBaseY();
		int plane = at.getPlane();
		int[][][] heights = view.getTileHeights();
		if (heights == null || plane < 0 || plane >= heights.length || sx < 0 || sy < 0
			|| sx >= heights[plane].length || sy >= heights[plane][sx].length)
		{
			return 0;
		}
		return heights[plane][sx][sy];
	}

	/** Queue every tile within the radius, nearest first, skipping ones already known. */
	private void enqueueDiscovery(WorldPoint at)
	{
		if (cellLimit && objectLimit)
		{
			return;
		}
		pending.clear();
		List<int[]> ring = new ArrayList<>();
		for (int dx = -radius; dx <= radius; dx++)
		{
			for (int dy = -radius; dy <= radius; dy++)
			{
				if (dx * dx + dy * dy > radius * radius)
				{
					continue;
				}
				ring.add(new int[]{at.getX() + dx, at.getY() + dy, at.getPlane(), dx * dx + dy * dy});
			}
		}
		ring.sort((a, b) -> Integer.compare(a[3], b[3]));
		pending.addAll(ring);
	}

	/** Walk a bounded slice of the pending tiles: cells and objects not yet known are emitted. */
	private void discoverPending(WorldView view, double t)
	{
		if (pending.isEmpty() || (cellLimit && objectLimit))
		{
			pending.clear();
			return;
		}
		Scene scene = view.getScene();
		if (scene == null)
		{
			return;
		}
		Tile[][][] tiles = scene.getTiles();
		short[][][] overlays = scene.getOverlayIds();
		short[][][] underlays = scene.getUnderlayIds();
		int[][][] heights = view.getTileHeights();
		if (tiles == null)
		{
			return;
		}
		List<int[]> cells = new ArrayList<>();
		List<String> materials = new ArrayList<>();
		JsonArray objects = new JsonArray();
		int baseX = view.getBaseX();
		int baseY = view.getBaseY();
		int walked = 0;
		while (!pending.isEmpty() && walked < TILES_PER_TICK)
		{
			int[] next = pending.poll();
			int worldX = next[0], worldY = next[1], plane = next[2];
			int sx = worldX - baseX;
			int sy = worldY - baseY;
			if (plane < 0 || plane >= tiles.length || sx < 0 || sy < 0 || sx >= Constants.SCENE_SIZE || sy >= Constants.SCENE_SIZE
				|| sx >= tiles[plane].length || sy >= tiles[plane][sx].length)
			{
				continue;
			}
			walked++;
			int height = heights != null && plane < heights.length && sx < heights[plane].length && sy < heights[plane][sx].length
				? heights[plane][sx][sy] : 0;
			Tile tile = tiles[plane][sx][sy];
			if (!cellLimit)
			{
				int[] ground = SceneMapper.groundCell(worldX, worldY, plane, height);
				if (!known.containsKey(SceneMapper.key(ground)))
				{
					short overlay = overlays != null && plane < overlays.length && sx < overlays[plane].length && sy < overlays[plane][sx].length
						? overlays[plane][sx][sy] : 0;
					short underlay = underlays != null && plane < underlays.length && sx < underlays[plane].length && sy < underlays[plane][sx].length
						? underlays[plane][sx][sy] : 0;
					if (!offer(cells, materials, ground, SceneMapper.groundMaterial(overlay, underlay), t))
					{
						flushObjects(objects, t);
						return;
					}
				}
				int[] above = SceneMapper.objectCell(worldX, worldY, plane, height);
				if (!known.containsKey(SceneMapper.key(above)))
				{
					String material = tile == null ? SceneMapper.AIR : topMaterial(tile, 0L);
					if (!offer(cells, materials, above, material, t))
					{
						flushObjects(objects, t);
						return;
					}
				}
			}
			if (!objectLimit && tile != null)
			{
				long tileKey = SceneMapper.key(worldX, plane, worldY);
				if (!objectTiles.contains(tileKey))
				{
					objectTiles.add(tileKey);
					if (!collectObjects(tile, worldX, worldY, plane, objects, t))
					{
						snapshot(cells, materials, t);
						return;
					}
				}
			}
		}
		snapshot(cells, materials, t);
		flushObjects(objects, t);
	}

	/** Every object standing on a tile, as the cache renderer wants it: id, kind, packed type and orientation, impostor state. */
	private boolean collectObjects(Tile tile, int worldX, int worldY, int plane, JsonArray objects, double t)
	{
		GameObject[] gameObjects = tile.getGameObjects();
		if (gameObjects != null)
		{
			for (GameObject object : gameObjects)
			{
				if (object != null && !offerObject(objects, entry(object, KIND_GAME, object.getConfig(), worldX, worldY, plane), t))
				{
					return false;
				}
			}
		}
		WallObject wall = tile.getWallObject();
		if (wall != null && !offerObject(objects, entry(wall, KIND_WALL, wall.getConfig(), worldX, worldY, plane), t))
		{
			return false;
		}
		GroundObject ground = tile.getGroundObject();
		if (ground != null && !offerObject(objects, entry(ground, KIND_GROUND, ground.getConfig(), worldX, worldY, plane), t))
		{
			return false;
		}
		DecorativeObject decoration = tile.getDecorativeObject();
		if (decoration != null && !offerObject(objects, entry(decoration, KIND_DECORATIVE, decoration.getConfig(), worldX, worldY, plane), t))
		{
			return false;
		}
		return true;
	}

	private JsonObject entry(TileObject object, int kind, int config, int worldX, int worldY, int plane)
	{
		JsonObject entry = new JsonObject();
		entry.addProperty("x", worldX);
		entry.addProperty("y", worldY);
		entry.addProperty("plane", plane);
		entry.addProperty("id", object.getId());
		entry.addProperty("kind", kind);
		entry.addProperty("type", SceneMapper.objectType(config));
		entry.addProperty("orientation", SceneMapper.objectOrientation(config));
		int impostor = impostor(object.getId());
		if (impostor >= 0)
		{
			entry.addProperty("impostor_id", impostor);
		}
		if (object instanceof GameObject)
		{
			// A multi-tile object is reported from every tile it covers; its anchor tells them apart.
			GameObject game = (GameObject) object;
			if (game.getSceneMinLocation() != null)
			{
				entry.addProperty("anchor_x", game.getSceneMinLocation().getX() + client.getTopLevelWorldView().getBaseX());
				entry.addProperty("anchor_y", game.getSceneMinLocation().getY() + client.getTopLevelWorldView().getBaseY());
			}
		}
		return entry;
	}

	/** The varbit-selected variant of an object (an open door, a grown patch), or -1 when it has none. */
	private int impostor(int objectId)
	{
		try
		{
			ObjectComposition definition = client.getObjectDefinition(objectId);
			if (definition == null || definition.getImpostorIds() == null)
			{
				return -1;
			}
			ObjectComposition impostor = definition.getImpostor();
			return impostor == null ? -1 : impostor.getId();
		}
		catch (RuntimeException e)
		{
			return -1;
		}
	}

	private boolean offerObject(JsonArray objects, JsonObject entry, double t)
	{
		if (objectCount + objects.size() >= MAX_OBJECTS)
		{
			objectLimit = true;
			return flushObjects(objects, t);
		}
		objects.add(entry);
		if (objects.size() >= OBJECT_CHUNK)
		{
			return flushObjects(objects, t);
		}
		return true;
	}

	private boolean flushObjects(JsonArray objects, double t)
	{
		if (objects.size() == 0)
		{
			return true;
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("dimension", session.dimension);
		payload.addProperty("scope", "baseline");
		int size = objects.size();
		JsonArray copy = new JsonArray();
		copy.addAll(objects);
		payload.add("objects", copy);
		while (objects.size() > 0)
		{
			objects.remove(objects.size() - 1);
		}
		if (!emit("osrs.object", t, payload, PLAYER, false))
		{
			return false;
		}
		objectCount += size;
		return true;
	}

	private boolean offer(List<int[]> cells, List<String> materials, int[] cell, String material, double t)
	{
		if (known.size() + cells.size() >= MAX_CELLS)
		{
			cellLimit = true;
			snapshot(cells, materials, t);
			return false;
		}
		cells.add(cell);
		materials.add(material);
		if (cells.size() == SNAPSHOT_CHUNK)
		{
			if (!snapshot(cells, materials, t))
			{
				return false;
			}
			cells.clear();
			materials.clear();
		}
		return true;
	}

	private boolean snapshot(List<int[]> cells, List<String> materials, double t)
	{
		if (cells.isEmpty())
		{
			return true;
		}
		JsonArray list = new JsonArray();
		for (int i = 0; i < cells.size(); i++)
		{
			JsonArray cell = new JsonArray();
			cell.add(cells.get(i)[0]);
			cell.add(cells.get(i)[1]);
			cell.add(cells.get(i)[2]);
			cell.add(materials.get(i));
			list.add(cell);
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("dimension", session.dimension);
		payload.addProperty("coverage", "observed-cells");
		payload.add("cells", list);
		if (!emit("mccr.spatial_snapshot", t, payload, PLAYER, false))
		{
			return false;
		}
		for (int i = 0; i < cells.size(); i++)
		{
			known.put(SceneMapper.key(cells.get(i)), materials.get(i));
		}
		cells.clear();
		materials.clear();
		return true;
	}

	/** The top-most named thing standing on a tile, or air. `exclude` skips an object that is on its way out. */
	String topMaterial(Tile tile, long exclude)
	{
		GameObject[] objects = tile.getGameObjects();
		if (objects != null)
		{
			for (int i = objects.length - 1; i >= 0; i--)
			{
				GameObject object = objects[i];
				if (object == null || (exclude != 0L && object.getHash() == exclude))
				{
					continue;
				}
				String material = name(object);
				if (material != null)
				{
					return material;
				}
			}
		}
		WallObject wall = tile.getWallObject();
		if (wall != null && (exclude == 0L || wall.getHash() != exclude))
		{
			String material = name(wall);
			if (material != null)
			{
				return material;
			}
		}
		DecorativeObject decoration = tile.getDecorativeObject();
		if (decoration != null && (exclude == 0L || decoration.getHash() != exclude))
		{
			String material = name(decoration);
			if (material != null)
			{
				return material;
			}
		}
		GroundObject ground = tile.getGroundObject();
		if (ground != null && (exclude == 0L || ground.getHash() != exclude))
		{
			String material = name(ground);
			if (material != null)
			{
				return material;
			}
		}
		return SceneMapper.AIR;
	}

	private String name(TileObject object)
	{
		try
		{
			ObjectComposition definition = client.getObjectDefinition(object.getId());
			if (definition == null)
			{
				return null;
			}
			if (definition.getImpostorIds() != null)
			{
				ObjectComposition impostor = definition.getImpostor();
				if (impostor != null)
				{
					definition = impostor;
				}
			}
			return SceneMapper.objectMaterial(definition.getName());
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	/** An object appeared on or left a tile. Baseline spawns during a scene load are the caller's to suppress. */
	public void objectChanged(Tile tile, TileObject object, boolean spawned)
	{
		if (!recording || tile == null || object == null)
		{
			return;
		}
		double t = session.time();
		if (checkLimit(t))
		{
			return;
		}
		Player me = client.getLocalPlayer();
		WorldView view = client.getTopLevelWorldView();
		WorldPoint at = tile.getWorldLocation();
		if (me == null || view == null || at == null || me.getWorldLocation() == null)
		{
			return;
		}
		WorldPoint mine = me.getWorldLocation();
		int dx = at.getX() - mine.getX();
		int dy = at.getY() - mine.getY();
		boolean nearby = at.getPlane() == mine.getPlane() && dx * dx + dy * dy <= radius * radius;
		int height = tileHeight(view, at);
		int[] cell = SceneMapper.objectCell(at.getX(), at.getY(), at.getPlane(), height);
		long key = SceneMapper.key(cell);
		String after = topMaterial(tile, spawned ? 0L : object.getHash());
		String before = known.get(key);
		if (before == null)
		{
			if (!nearby || cellLimit)
			{
				return;
			}
			// The callback follows the mutation: the before state is what stood there without the newcomer.
			before = spawned ? topMaterial(tile, object.getHash()) : topMaterial(tile, 0L);
			List<int[]> cells = new ArrayList<>();
			List<String> materials = new ArrayList<>();
			cells.add(cell);
			materials.add(before);
			if (!snapshot(cells, materials, t))
			{
				return;
			}
		}
		// The exact transition for the cache renderer, whether or not the generic cell changed.
		if (nearby && !objectLimit && eventCount < MAX_EVENTS)
		{
			int kind = object instanceof WallObject ? KIND_WALL : object instanceof GroundObject ? KIND_GROUND
				: object instanceof DecorativeObject ? KIND_DECORATIVE : KIND_GAME;
			int config = object instanceof GameObject ? ((GameObject) object).getConfig()
				: object instanceof WallObject ? ((WallObject) object).getConfig()
				: object instanceof GroundObject ? ((GroundObject) object).getConfig()
				: object instanceof DecorativeObject ? ((DecorativeObject) object).getConfig() : 0;
			JsonObject payload = new JsonObject();
			payload.addProperty("dimension", session.dimension);
			payload.addProperty("scope", spawned ? "spawned" : "despawned");
			JsonArray objects = new JsonArray();
			objects.add(entry(object, kind, config, at.getX(), at.getY(), at.getPlane()));
			payload.add("objects", objects);
			emit("osrs.object", t, payload, PLAYER, false);
			objectTiles.add(SceneMapper.key(at.getX(), at.getPlane(), at.getY()));
		}
		if (before.equals(after))
		{
			return;
		}
		if (eventCount >= MAX_EVENTS)
		{
			stop("event limit");
			return;
		}
		JsonObject payload = new JsonObject();
		boolean removal = SceneMapper.AIR.equals(after);
		payload.addProperty("kind", removal ? "block.break" : "block.place");
		payload.addProperty("summary", (removal ? "Removed " : "Placed ") + (removal ? before : after));
		payload.addProperty("dimension", session.dimension);
		JsonObject position = new JsonObject();
		position.addProperty("x", cell[0]);
		position.addProperty("y", cell[1]);
		position.addProperty("z", cell[2]);
		payload.add("at", position);
		payload.addProperty("before", before);
		payload.addProperty("after", after);
		payload.add("actors", new JsonArray());
		payload.add("observed_cause", JsonNull.INSTANCE);
		payload.addProperty("object_id", object.getId());
		payload.addProperty("plane", at.getPlane());
		if (emit("world.event", t, payload, PLAYER, false))
		{
			eventCount++;
			known.put(key, after);
		}
	}

	private boolean checkLimit(double t)
	{
		String limit = t >= maxSeconds ? "time limit"
			: eventCount >= MAX_EVENTS ? "event limit"
			: poseCount >= MAX_POSES ? "pose limit" : "";
		if (!limit.isEmpty())
		{
			// A budget is a planned ending: the next capture starts where this
			// one stopped, and calling that an interruption told somebody their
			// twenty-minute recording had broken when it had simply filled up.
			stop(limit, true);
			return true;
		}
		return false;
	}

	/** True once a budget ended this capture and the caller should start the next one. */
	public boolean exhausted()
	{
		return !recording && !reason.isEmpty() && recorder.state() != Recorder.State.FAILED;
	}

	private void stop(String why)
	{
		stop(why, false);
	}

	/**
	 * End the capture early.
	 *
	 * `planned` is the difference between a recording that ENDED and one that
	 * BROKE. A budget rolling one capture into the next is the first: the plugin
	 * asks for a new one immediately (see `exhausted`), nothing is lost, and the
	 * person did nothing wrong. A recorder that went away is the second.
	 */
	private void stop(String why, boolean planned)
	{
		if (!recording)
		{
			return;
		}
		reason = why;
		plannedEnd = planned;
		finish(why, false);
	}

	/**
	 * Close the recording boundary and drain to the app. With `openStudio` the
	 * app is asked to show this exact capture once the last batch is acknowledged.
	 */
	/**
	 * What `mccr.capture_end` carries, which is the contract with the app.
	 *
	 * Static and separate so the one decision in it can be read and tested on
	 * its own: whether this ending was an interruption. Everything else here is
	 * description.
	 */
	static JsonObject endPayload(String dimension, boolean openStudio, String reason, boolean planned)
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("scope", "recording");
		payload.addProperty("dimension", dimension);
		if (openStudio)
		{
			payload.addProperty("open_studio", true);
		}
		if (reason != null && !reason.isEmpty())
		{
			payload.addProperty("reason", reason);
			if (!planned)
			{
				payload.addProperty("interrupted", true);
			}
		}
		return payload;
	}

	public synchronized CompletableFuture<Void> finish(String why, boolean openStudio)
	{
		return finish(why, openStudio, plannedEnd);
	}

	/**
	 * Close the recording boundary.
	 *
	 * INTERRUPTED MEANS BROKEN, not "ended with something to say". Every ending
	 * carries a reason and the reason alone used to set the flag, so pressing
	 * Stop marked your own recording interrupted, and so did a budget rolling it
	 * into the next one. Half of a real shelf said "Interrupted recording" for
	 * recordings where nothing at all had gone wrong.
	 */
	public synchronized CompletableFuture<Void> finish(String why, boolean openStudio, boolean planned)
	{
		if (finishing != null)
		{
			return finishing;
		}
		recording = false;
		reason = why == null ? "" : why;
		statusLine = openStudio ? "Saving the last moments" : "Finishing";
		JsonObject payload = endPayload(session.dimension, openStudio, reason, planned);
		boolean queued = emit("mccr.capture_end", session.time(), payload, PLAYER, true);
		CompletableFuture<Void> done = queued
			? recorder.finish()
			: failed("Final recording acknowledgement unavailable.");
		if (openStudio)
		{
			done = done.thenCompose(ignored -> recorder.openStudio());
		}
		finishing = done.whenComplete((ignored, throwable) ->
		{
			if (throwable == null)
			{
				statusLine = "Recording saved";
			}
			else
			{
				String message = throwable.getCause() != null && throwable.getCause().getMessage() != null
					? throwable.getCause().getMessage()
					: String.valueOf(throwable.getMessage());
				statusLine = "Could not save: " + message;
			}
		});
		return finishing;
	}

	private static CompletableFuture<Void> failed(String message)
	{
		CompletableFuture<Void> future = new CompletableFuture<>();
		future.completeExceptionally(new IllegalStateException(message));
		return future;
	}
}
