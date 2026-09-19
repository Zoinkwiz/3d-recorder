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
import net.runelite.api.GraphicsObject;
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

@Slf4j
public final class OsrsCapture
{
	public static final String OSRS_PROFILE = "ec.mccr.osrs/5";
	public static final int MAX_CELLS = 40_000;
	public static final int MAX_SURFACE_REGIONS = 64;
	public static final int MAX_OBJECTS = 40_000;
	public static final int MAX_POSES = 800_000;
	public static final int MAX_EVENTS = 10_000;
	public static final int MAX_NPC_ACTORS = 8192;
	public static final int MAX_PLAYER_ACTORS = 8192;
	public static final int MAX_ACTORS = MAX_NPC_ACTORS + MAX_PLAYER_ACTORS;
	public static final int TRACKED_PER_TICK = 1024;
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
	private final EmbertideConfig config;
	private List<Actor> crowdActors = java.util.Collections.emptyList();
	private final Set<Actor> presentActors = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
	private final Map<Actor, String> actorIds = new java.util.IdentityHashMap<>();
	private final String playerName;
	private final int world;
	private boolean instance;
	private int segment;
	private final Set<String> leftIds = new HashSet<>();
	private final Map<Long, String> known = new HashMap<>();
	private final Set<Long> objectTiles = new HashSet<>();
	private final Set<String> actors = new HashSet<>();
	private final Map<String, Integer> appearance = new HashMap<>();
	private final Map<String, String> pseudonyms;
	private final String series;
	private final int part;
	private final double maxSeconds;
	private int poseCount;
	private int eventCount;
	private int objectCount;
	private int motionCount;
	private Integer skyColor;
	private final SceneSkybox sceneSkybox = new SceneSkybox();
	private int skyboxCount;
	private int environmentCount;
	private int graphicCount;
	private final Map<GraphicsObject, Integer> sceneGraphics = new java.util.IdentityHashMap<>();
	private final Map<String, long[]> lastMotion = new HashMap<>();
	private boolean cellLimit;
	private boolean objectLimit;
	private int npcActors;
	private int playerActors;
	private long lastPlayerCell = Long.MIN_VALUE;
	private final java.util.ArrayDeque<int[]> pending = new java.util.ArrayDeque<>();
	private static final int TILES_PER_TICK = 60;
	private int[] lastRegions = new int[0];
	private final Set<Integer> visitedRegions = new HashSet<>();
	private boolean instanceHeightsWritten;
	private volatile boolean recording;
	private volatile String reason = "";
	private volatile boolean plannedEnd = false;
	private volatile String statusLine = "Starting";
	private CompletableFuture<Void> finishing;
	private volatile double sealedAt = -1;

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
		this.config = config;
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


	private final Map<Skill, Integer> levels = new EnumMap<>(Skill.class);
	private final Map<String, Double> fightNoted = new HashMap<>();
	private String lastAttacker;
	private double lastAttackedAt = -1;
	private double lastNearDeathAt = -60;
	private int momentCount;
	private static final int MAX_HITS = 200000;
	private int hitCount = 0;
	private static final int MAX_MOMENTS = 2000;
	private int[] lastCamera;
	private int cameraCount;
	private static final int MAX_CAMERA = 40000;
	private int speechCount;
	private static final int MAX_SPEECH = 600;
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
		if (!acceptingEvents())
		{
			return false;
		}
		if (momentCount >= MAX_MOMENTS) { stop("moment limit", true); return false; }
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

	public void hitsplat(Actor target, int amount, boolean mine, Player me)
	{
		hitsplat(target, amount, mine, me, -1, -1);
	}

	public void hitsplat(Actor target, net.runelite.api.Hitsplat splat, Player me)
	{
		if (splat == null) { return; }
		hitsplat(target, splat.getAmount(), splat.isMine(), me, splat.getHitsplatType(),
			Math.max(0, Math.min(500, splat.getDisappearsOnGameCycle() - client.getGameCycle())) / 50.0);
	}

	private void hitsplat(Actor target, int amount, boolean mine, Player me, int type, double remaining)
	{
		if (!acceptingEvents() || !eventActor(target, me))
		{
			return;
		}
		double t = session.time();
		hit(target, amount, mine, me, t, type, remaining);
		if (target == me)
		{
			Actor attacker = me.getInteracting();
			lastAttacker = eventActor(attacker, me) ? actorName(attacker) : null;
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
	 * Preserve observed health ratios and scales; omit unknown values (-1).
	 * Only the local player's health is available as exact hitpoints.
	 */
	private void hit(Actor target, int amount, boolean mine, Player me, double t, int type, double remaining)
	{
		String targetId = target == me ? PLAYER : actorId(target);
		if (targetId == null)
		{
			return;
		}
		if (hitCount >= MAX_HITS) { stop("hit limit", true); return; }
		JsonObject payload = new JsonObject();
		payload.addProperty("target", targetId);
		payload.addProperty("amount", Math.max(0, amount));
		if (type >= 0)
		{
			payload.addProperty("hitsplat_type", type);
			payload.addProperty("hitsplat_remaining", remaining);
			payload.addProperty("hitsplat_tint_disabled", client.getVarbitValue(10236));
			payload.addProperty("hitsplat_maxhit_disabled", client.getVarbitValue(14196));
		}
		String source = mine ? PLAYER : (target == me && eventActor(me.getInteracting(), me) ? actorId(me.getInteracting()) : null);
		if (source != null)
		{
			payload.addProperty("source", source);
		}
		int ratio = target.getHealthRatio();
		int scale = target.getHealthScale();
		if (target == me)
		{
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

	public void actorDied(Actor actor, Player me)
	{
		if (!acceptingEvents() || !eventActor(actor, me))
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

	public void statChanged(Skill skill, int level)
	{
		if (!acceptingEvents() || skill == null)
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

	public void ownChat(String text, String name)
	{
		if (!acceptingEvents() || !config.recordOwnChat() || text == null || text.trim().isEmpty())
		{
			return;
		}
		String line = text.trim();
		line = line.length() > 200 ? line.substring(0, 200) : line;
		if (line.equals(lastSaid.get(PLAYER))) { return; }
		if (speechCount >= MAX_SPEECH) { stop("speech limit", true); return; }
		JsonObject payload = new JsonObject();
		payload.addProperty("sender", name == null ? "You" : name);
		Player speaker = client.getLocalPlayer();
		payload.addProperty("overhead_remaining", speaker != null && speaker.getOverheadCycle() > 0 ? Math.min(500, speaker.getOverheadCycle()) / 50.0 : 3.0);
		payload.addProperty("text", line);
		payload.addProperty("dimension", session.dimension);
		if (emit("chat.message", session.time(), payload, PLAYER, false))
		{
			lastSaid.put(PLAYER, line);
			speechCount++;
		}
	}

	public void overhead(Actor actor, String text, Player me)
	{
		if (!acceptingEvents() || !config.recordOverheadText() || !(actor instanceof NPC) || actor == me || text == null || !eventActor(actor, me))
		{
			return;
		}
		String line = text.trim();
		line = line.length() > 200 ? line.substring(0, 200) : line;
		if (line.isEmpty())
		{
			return;
		}
		String id = actorId(actor);
		if (id == null || line.equals(lastSaid.get(id)))
		{
			return;
		}
		if (speechCount >= MAX_SPEECH) { stop("speech limit", true); return; }
		lastSaid.put(id, line);
		JsonObject payload = new JsonObject();
		payload.addProperty("sender", actor.getName() == null ? "Someone" : Text.removeTags(actor.getName()));
		payload.addProperty("overhead_remaining", actor.getOverheadCycle() > 0 ? Math.min(500, actor.getOverheadCycle()) / 50.0 : 3.0);
		payload.addProperty("text", line.length() > 200 ? line.substring(0, 200) : line);
		payload.addProperty("dimension", session.dimension);
		if (emit("chat.message", session.time(), payload, id, false))
		{
			speechCount++;
		}
	}

	private boolean acceptingEvents()
	{
		return recording && !armed;
	}

	private boolean eligible(Actor actor, Player me)
	{
		if (actor == null || me == null) { return false; }
		if (actor == me) { return true; }
		WorldView view = client.getTopLevelWorldView();
		return view != null && me.getWorldLocation() != null
			&& ((actor instanceof NPC && config.trackNpcs())
				|| (actor instanceof Player && config.trackPlayers() && actor.getName() != null))
			&& drawn(view, me.getWorldLocation(), actor);
	}

	/** Event actors must have a presence and initial pose before they are referenced. */
	private boolean eventActor(Actor actor, Player me)
	{
		if (!eligible(actor, me)) { return false; }
		if (actor == me) { return true; }
		String id = actorId(actor);
		if (actors.contains(id)) { return true; }
		double t = session.time();
		boolean npc = actor instanceof NPC;
		if (!announce(id, npc ? actorName(actor) : pseudonym(actor.getName()),
			npc ? NPC_COLOR : OTHER_PLAYER_COLOR, npc ? "npc" : "player",
			npc ? ((NPC) actor).getId() : -1, npc ? ((NPC) actor).getCombatLevel() : -1, t))
		{
			return false;
		}
		if (actor instanceof Player) { appearance((Player) actor, id, t); }
		pose(client.getTopLevelWorldView(), actor, id, actor.getWorldLocation(), t, false,
			npc ? ((NPC) actor).getId() : -1);
		return recording;
	}

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

	public double seconds()
	{
		return sealedAt >= 0 ? sealedAt : armed ? 0 : session.time();
	}

	public int part()
	{
		return part;
	}

	public double time()
	{
		return session.time();
	}

	public CompletableFuture<Void> start()
	{
		recording = true;
		armed = true;
		statusLine = "Waiting for the next game tick";
		return opened;
	}

	private boolean armed;
	private final CompletableFuture<Void> opened = new CompletableFuture<>();

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
		statusLine = "Recording in memory";
	}

	/**
	 * Emit a scene boundary and reset coordinate-dependent baselines without closing the file.
	 * Mark actors as left so replay cannot interpolate between different scene spaces.
	 */
	public void sceneChanged(WorldView view)
	{
		String dimension = SceneIdentity.of(view).dimension();
		if (armed || !recording)
		{
			session.dimension = dimension;
			instance = view != null && view.isInstance();
			return;
		}
		double t = session.time();
		for (String id : new java.util.HashSet<>(actorIds.values()))
		{
			JsonObject left = new JsonObject();
			left.addProperty("actor_id", id);
			left.addProperty("state", "left");
			left.addProperty("dimension", session.dimension);
			if (!emit("mccr.actor_lifecycle", t, left, id, false)) { return; }
			leftIds.add(id);
		}
		presentActors.clear();
		actorIds.clear();
		known.clear();
		objectTiles.clear();
		visitedRegions.clear();
		lastRegions = new int[0];
		lastPlayerCell = Long.MIN_VALUE;
		instanceHeightsWritten = false;
		session.dimension = dimension;
		instance = view != null && view.isInstance();
		segment++;
		JsonObject scene = new JsonObject();
		scene.addProperty("dimension", dimension);
		scene.addProperty("reset", true);
		scene.addProperty("segment", segment);
		scene.addProperty("instance", instance);
		emit("mccr.scene", t, scene, PLAYER, false);
	}

	public void actorSpawned(Actor actor) { if (actor != null) { presentActors.add(actor); } }

	public void actorDespawned(Actor actor)
	{
		presentActors.remove(actor);
		String id = actorIds.remove(actor);
		if (recording && !armed && id != null && leftIds.add(id))
		{
			JsonObject payload = new JsonObject();
			payload.addProperty("actor_id", id);
			payload.addProperty("state", "left");
			payload.addProperty("dimension", session.dimension);
			emit("mccr.actor_lifecycle", session.time(), payload, id, false);
		}
	}

	public void loading(boolean active)
	{
		if (!recording || armed) { return; }
		JsonObject payload = new JsonObject();
		payload.addProperty("active", active);
		emit("mccr.loading", session.time(), payload, PLAYER, false);
	}

	private void seedActors(WorldView view)
	{
		presentActors.clear();
		actorIds.clear();
		for (NPC npc : view.npcs()) { actorSpawned(npc); }
		for (Player player : view.players()) { actorSpawned(player); }
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
			+ "osrs.camera independently sampled as [x, y, height, yaw, pitch, scale, viewport_width, viewport_height]; "
			+ "osrs.poses batches game-tick poses including slot, appearance and animation fields; osrs.hit and osrs.death for enabled actor categories; "
			+ "osrs.region includes instance_template_chunks; osrs.instance_heights stores each plane's actual scene corner heights for instanced cache reconstruction");
		osrs.addProperty("baseline", "The static world is the cache's own map at cache_revision; recorded objects override it where they differ.");
		header.add("osrs", osrs);
		JsonObject meta = new JsonObject();
		meta.addProperty("id", session.id);
		meta.addProperty("title", "Old School RuneScape memory");
		meta.addProperty("series", series);
		meta.addProperty("part", part);
		meta.addProperty("duration", 0);
		meta.addProperty("description", "Local client observations begin after the writer accepts recording. "
			+ "Player and actor positions once per game tick, with each actor's facing: eligible actors in the loaded scene on the player's plane, "
			+ "nearest first, at most " + TRACKED_PER_TICK + " at a time and " + MAX_NPC_ACTORS + " NPCs and " + MAX_PLAYER_ACTORS + " players in all. "
			+ "Ground tiles and named objects are discovered within " + radius + " tiles on the player's plane; at most "
			+ MAX_CELLS + " cells and " + MAX_OBJECTS + " objects. Objects appearing or vanishing are exact transitions with no known cause. "
			+ "Optional own public chat and NPC overhead speech share a " + MAX_SPEECH + "-line budget. "
			+ "Instanced areas include the loaded scene's corner heights on all planes. "
			+ "Unloaded space, earlier history, other players' chat, private/clan/friends chat, inventories and actors on other planes are absent.");
		header.add("session", meta);
		JsonArray actorList = new JsonArray();
		actorList.add(actorEntry(PLAYER, playerName, PLAYER_COLOR));
		header.add("actors", actorList);
		header.addProperty("channel_rule", "Local scene observations, optional own public chat and NPC overhead speech; other-player names and chat, private/clan/friends chat and account credentials are excluded.");
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

	private void camera(WorldView view)
	{
		if (cameraCount >= MAX_CAMERA)
		{
			stop("camera limit", true);
			return;
		}
		int[] now = {
			client.getCameraX(),
			client.getCameraY(),
			client.getCameraZ(),
			client.getCameraYaw(),
			client.getCameraPitch(),
			client.getScale(),
			client.getViewportWidth(), client.getViewportHeight(), view.getBaseX(), view.getBaseY(),
		};
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
		// Client camera Y is northward position; Z is height.
		// Convert yaw/pitch from 16384 to 2048 units per turn for the recording format.
		sample.add(Math.round((view.getBaseX() + now[0] / tile) * 1000.0) / 1000.0);
		sample.add(Math.round((view.getBaseY() + now[1] / tile) * 1000.0) / 1000.0);
		// Convert downward-positive client height to upward-positive replay height.
		sample.add(-now[2]);
		sample.add(Math.round((now[3] / 8.0) * 1000.0) / 1000.0);
		sample.add(Math.round((now[4] / 8.0) * 1000.0) / 1000.0);
		sample.add(now[5]);
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
		if (armed || (!recording && !control)) { return false; }
		JsonObject row = session.record(type, t, payload, user);
		if (recorder.enqueue(row, control))
		{
			return true;
		}
		if (!control)
		{
			stop(recorder.full() ? "file size limit" : "recorder unavailable", recorder.full());
		}
		return false;
	}

	public void tick(boolean rediscover)
	{
		if (!recording)
		{
			return;
		}
		if (recorder.state() == Recorder.State.FAILED)
		{
			stop("recorder unavailable");
			return;
		}
		double t = session.time();
		if (!armed && checkLimit(t))
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
			// Wait until the welcome screen closes; the loaded world is not yet animating.
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
		if (rediscover) { seedActors(view); }
		if (rediscover || !Arrays.equals(lastRegions, regions(view)))
		{
			region(view, t);
		}
		if (!recording) { return; }
		Scene skyScene = view.getScene();
		JsonObject sky = sceneSkybox.changed(skyScene == null ? null : skyScene.getSkybox(),
			client.getTextureProvider() == null ? 0.8 : client.getTextureProvider().getBrightness());
		if (sky != null)
		{
			if (skyboxCount >= 2000) { stop("skybox limit", true); return; }
			sky.addProperty("dimension", session.dimension);
			if (!emit("osrs.skybox", t, sky, PLAYER, false)) { return; }
			skyboxCount++;
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
		JsonArray crowd = new JsonArray();
		crowdActors = inThePicture(view, me, at);
		for (Actor other : crowdActors)
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
				stop("pose limit", true);
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

	private boolean welcomeScreenUp()
	{
		net.runelite.api.widgets.Widget screen = client.getWidget(net.runelite.api.gameval.InterfaceID.WelcomeScreen.UNIVERSE);
		return screen != null && !screen.isHidden();
	}

	public void clientTick()
	{
		if (!recording || armed)
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
		for (Actor other : crowdActors)
		{
			String id = idOf(other);
			if (presentActors.contains(other) && eligible(other, me) && actors.contains(id))
			{
				motionSample(samples, other, id, view);
			}
		}
		sceneEffects(view, at.getPlane());
		if (!recording) { return; }
		camera(view);
		if (!recording || samples.size() == 0)
		{
			return;
		}
		if (motionCount + samples.size() > MAX_MOTION)
		{
			stop("motion limit", true);
			return;
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("dimension", session.dimension);
		payload.add("samples", samples);
		if (emit("osrs.motion", session.time(), payload, PLAYER, false))
		{
			motionCount += samples.size();
		}
	}

	private void sceneEffects(WorldView view, int plane)
	{
		double t = session.time();
		int color = client.getSkyboxColor() & 0xffffff;
		if (skyColor == null || skyColor != color)
		{
			if (environmentCount >= MAX_CAMERA) { stop("environment limit", true); return; }
			JsonObject payload = new JsonObject();
			payload.addProperty("dimension", session.dimension);
			payload.addProperty("sky_color", color);
			if (!emit("osrs.environment", t, payload, PLAYER, false)) { return; }
			skyColor = color;
			environmentCount++;
		}
		Map<GraphicsObject, Integer> visible = new java.util.IdentityHashMap<>();
		JsonArray started = new JsonArray(), ended = new JsonArray();
		net.runelite.api.Deque<GraphicsObject> graphics = view.getGraphicsObjects();
		int scanned = 0;
		if (graphics != null) for (GraphicsObject graphic : graphics)
		{
			if (++scanned > 4096) { stop("scene effect limit", true); return; }
			LocalPoint local = graphic.getLocation();
			if (graphic.finished() || graphic.getLevel() != plane || local == null
				|| graphic.getStartCycle() > client.getGameCycle()) { continue; }
			Integer key = sceneGraphics.get(graphic);
			if (key == null)
			{
				if (graphicCount >= MAX_EVENTS) { stop("scene effect limit", true); return; }
				key = ++graphicCount;
				JsonArray sample = new JsonArray();
				sample.add(key);
				sample.add(graphic.getId());
				sample.add(view.getBaseX() + local.getX() / 128.0);
				sample.add(view.getBaseY() + local.getY() / 128.0);
				sample.add(plane);
				sample.add(-graphic.getZ());
				sample.add(Math.max(0, graphic.getAnimationFrame()));
				started.add(sample);
			}
			visible.put(graphic, key);
		}
		for (Map.Entry<GraphicsObject, Integer> entry : sceneGraphics.entrySet())
		{
			if (!visible.containsKey(entry.getKey())) { ended.add(entry.getValue()); }
		}
		if (started.size() == 0 && ended.size() == 0) { return; }
		JsonObject payload = new JsonObject();
		payload.addProperty("dimension", session.dimension);
		payload.add("spawn", started);
		payload.add("end", ended);
		if (emit("osrs.graphics", t, payload, PLAYER, false))
		{
			sceneGraphics.clear();
			sceneGraphics.putAll(visible);
		}
	}

	private void motionSample(JsonArray samples, Actor actor, String id, WorldView view)
	{
		LocalPoint local = actor.getLocalLocation();
		WorldPoint world = actor.getWorldLocation();
		if (local == null || world == null)
		{
			return;
		}
		int x = local.getX(), y = local.getY(), plane = world.getPlane();
		int orientation = actor.getCurrentOrientation(), animation = actor.getAnimation();
		int frame = actor.getAnimationFrame(), poseAnimation = actor.getPoseAnimation();
		long[] previous = lastMotion.get(id);
		// The viewer advances animation frames from elapsed time.
		// Record frame rewinds to preserve animation restarts.
		if (previous != null && previous[0] == x && previous[1] == y && previous[2] == plane
			&& previous[3] == orientation && previous[4] == animation && previous[6] == poseAnimation && frame >= previous[5]
			&& previous[7] == view.getBaseX() && previous[8] == view.getBaseY())
		{
			previous[5] = frame;
			return;
		}
		lastMotion.put(id, new long[]{x, y, plane, orientation, animation, frame, poseAnimation, view.getBaseX(), view.getBaseY()});
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

	private void region(WorldView view, double t)
	{
		int[] regions = regions(view);
		if (!view.isInstance())
		{
			for (int id : regions) { visitedRegions.add(id); }
			if (visitedRegions.size() > MAX_SURFACE_REGIONS)
			{
				stop("map area limit", true);
				return;
			}
		}
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
		if (view.isInstance() && view.getInstanceTemplateChunks() != null)
		{
			JsonArray planes = new JsonArray();
			for (int[][] plane : view.getInstanceTemplateChunks())
			{
				JsonArray columns = new JsonArray();
				if (plane != null) { for (int[] column : plane) { columns.add(ints(column)); } }
				planes.add(columns);
			}
			payload.add("instance_template_chunks", planes);
		}
		payload.addProperty("dimension", session.dimension);
		emit("osrs.region", t, payload, PLAYER, false);
		if (view.isInstance() && !instanceHeightsWritten)
		{
			int[][][] heights = view.getTileHeights();
			if (heights != null && heights.length > 0)
			{
				// A separate bounded row per plane stays below the importer's 256 KiB
				// record limit. Heights come from the assembled scene, including seams
				// and the outer corner border; cache plane heights cannot substitute.
				for (int p = 0; p < Math.min(4, heights.length); p++)
				{
					if (heights[p] == null || heights[p].length > 105) { continue; }
					JsonArray columns = new JsonArray();
					for (int[] column : heights[p])
					{
						columns.add(ints(column == null ? null : Arrays.copyOf(column, Math.min(105, column.length))));
					}
					JsonObject grid = new JsonObject();
					grid.addProperty("base_x", view.getBaseX());
					grid.addProperty("base_y", view.getBaseY());
					grid.addProperty("plane", p);
					grid.add("heights", columns);
					if (!emit("osrs.instance_heights", t, grid, PLAYER, false)) { return; }
				}
				instanceHeightsWritten = true;
			}
		}
	}

	private List<Actor> inThePicture(WorldView view, Player me, WorldPoint at)
	{
		List<Actor> found = new ArrayList<>();
		for (Actor actor : presentActors)
		{
			if (actor != me && eligible(actor, me)) { found.add(actor); }
		}
		found.sort(java.util.Comparator.comparingInt(actor -> at.distanceTo(actor.getWorldLocation())));
		return found.size() > TRACKED_PER_TICK ? found.subList(0, TRACKED_PER_TICK) : found;
	}

	/** Test loaded-scene bounds and plane; this is not an on-screen visibility check. */
	private static boolean drawn(WorldView view, WorldPoint at, Actor actor)
	{
		WorldPoint here = actor == null ? null : actor.getWorldLocation();
		return here != null && SceneMapper.inScene(view.getBaseX(), view.getBaseY(), view.getSizeX(), view.getSizeY(),
			at.getPlane(), here.getX(), here.getY(), here.getPlane());
	}

	private String idOf(Actor actor)
	{
		String cached = actorIds.get(actor);
		if (cached != null) { return cached; }
		String id;
		if (actor instanceof NPC)
		{
			NPC npc = (NPC) actor;
			String name = SceneMapper.objectMaterial(npc.getName());
			id = "npc:" + (name == null ? "unnamed" : name) + "#" + npc.getIndex();
		}
		else
		{
			id = "player:" + pseudonym(actor.getName()).toLowerCase(Locale.ROOT).replace(' ', '-');
		}
		actorIds.put(actor, id);
		return id;
	}

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

	private boolean announce(String id, String name, String color, String kind, int npcId, int combatLevel, double t)
	{
		if (leftIds.remove(id))
		{
			JsonObject rejoin = new JsonObject();
			rejoin.addProperty("actor_id", id);
			rejoin.addProperty("state", "joined");
			rejoin.addProperty("dimension", session.dimension);
			if (!emit("mccr.actor_lifecycle", t, rejoin, id, false)) { return false; }
		}
		if (actors.contains(id))
		{
			return true;
		}
		boolean isNpc = "npc".equals(kind);
		if (isNpc ? npcActors >= MAX_NPC_ACTORS : playerActors >= MAX_PLAYER_ACTORS)
		{
			stop("actor limit", true);
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
			stop("pose limit", true);
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
		osrs.addProperty("graphic_height", actor.getGraphicHeight());
		osrs.addProperty("logical_height", actor.getLogicalHeight());
		osrs.addProperty("health_ratio", actor.getHealthRatio());
		osrs.addProperty("health_scale", actor.getHealthScale());
		payload.add("osrs", osrs);
		if (emit("player.position", t, payload, id, false))
		{
			poseCount++;
		}
	}

	/**
	 * Positional osrs.poses schema:
	 * [id, x, y, plane, height, orientation, animation, pose animation, frame, graphic,
	 * npc id or -1, graphic height, client slot, logical height, health ratio, health scale].
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
		// The client slot preserves draw ordering for actors sharing a tile.
		entry.add(actor instanceof Player ? ((Player) actor).getId() : actor instanceof NPC ? ((NPC) actor).getIndex() : -1);
		entry.add(actor.getLogicalHeight());
		entry.add(actor.getHealthRatio());
		entry.add(actor.getHealthScale());
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

	private void enqueueDiscovery(WorldPoint at)
	{
		if (cellLimit && objectLimit)
		{
			return;
		}
		pending.removeIf(tile -> tile[2] != at.getPlane()
			|| Math.pow(tile[0] - at.getX(), 2) + Math.pow(tile[1] - at.getY(), 2) > radius * radius);
		Set<Long> queued = new HashSet<>();
		for (int[] tile : pending) { queued.add(SceneMapper.key(tile[0], tile[2], tile[1])); }
		List<int[]> ring = new ArrayList<>();
		for (int dx = -radius; dx <= radius; dx++)
		{
			for (int dy = -radius; dy <= radius; dy++)
			{
				if (dx * dx + dy * dy > radius * radius
					|| queued.contains(SceneMapper.key(at.getX() + dx, at.getPlane(), at.getY() + dy)))
				{
					continue;
				}
				ring.add(new int[]{at.getX() + dx, at.getY() + dy, at.getPlane(), dx * dx + dy * dy});
			}
		}
		ring.sort((a, b) -> Integer.compare(a[3], b[3]));
		pending.addAll(ring);
	}

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
			int height = heights != null && plane < heights.length && sx < heights[plane].length && sy < heights[plane][sx].length
				? heights[plane][sx][sy] : 0;
			Tile tile = tiles[plane][sx][sy];
			long tileKey = SceneMapper.key(worldX, plane, worldY);
			if (known.containsKey(SceneMapper.key(SceneMapper.groundCell(worldX, worldY, plane, height)))
				&& known.containsKey(SceneMapper.key(SceneMapper.objectCell(worldX, worldY, plane, height)))
				&& objectTiles.contains(tileKey)) { continue; }
			walked++;
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
			if (!objectLimit)
			{
				if (!objectTiles.contains(tileKey))
				{
					objectTiles.add(tileKey);
					if (tile != null && !collectObjects(tile, worldX, worldY, plane, objects, t))
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
			GameObject game = (GameObject) object;
			if (game.getSceneMinLocation() != null)
			{
				entry.addProperty("anchor_x", game.getSceneMinLocation().getX() + client.getTopLevelWorldView().getBaseX());
				entry.addProperty("anchor_y", game.getSceneMinLocation().getY() + client.getTopLevelWorldView().getBaseY());
			}
		}
		return entry;
	}

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
			flushObjects(objects, t);
			stop("object limit", true);
			return false;
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
			stop("cell limit", true);
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

	public void objectChanged(Tile tile, TileObject object, boolean spawned)
	{
		if (!acceptingEvents() || tile == null || object == null)
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
			if (emit("osrs.object", t, payload, PLAYER, false)) { eventCount++; }
			objectTiles.add(SceneMapper.key(at.getX(), at.getPlane(), at.getY()));
		}
		if (before.equals(after))
		{
			return;
		}
		if (eventCount >= MAX_EVENTS)
		{
			stop("event limit", true);
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
			stop(limit, true);
			return true;
		}
		return false;
	}

	public boolean exhausted()
	{
		return !recording && !reason.isEmpty();
	}

	private void stop(String why)
	{
		stop(why, false);
	}

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

	public synchronized CompletableFuture<Void> finish(String why, boolean openStudio, boolean planned)
	{
		if (finishing != null)
		{
			return finishing;
		}
		recording = false;
		sealedAt = armed ? 0 : session.time();
		reason = why == null ? "" : why;
		if (armed)
		{
			armed = false;
			statusLine = "Recording cancelled before the first scene.";
			opened.complete(null);
			finishing = CompletableFuture.completedFuture(null);
			return finishing;
		}
		statusLine = openStudio ? "Saving the last moments" : "Finishing";
		JsonObject payload = endPayload(session.dimension, openStudio, reason, planned);
		boolean queued = emit("mccr.capture_end", sealedAt, payload, PLAYER, true);
		CompletableFuture<Void> done = queued
			? recorder.finish()
			: failed("Could not add the recording end record.");
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
