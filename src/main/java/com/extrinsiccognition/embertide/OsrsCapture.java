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
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.GraphicsObject;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.Projectile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.util.Text;

public final class OsrsCapture
{
	public static final String OSRS_PROFILE = "ec.mccr.osrs/6";
	public static final int MAX_CELLS = 40_000;
	public static final int MAX_SURFACE_REGIONS = 160;
	public static final int MAX_SCENE_OBJECTS = 40_000;
	public static final int MAX_SCENE_ITEMS = 10_000;
	public static final int MAX_OBJECT_EVENTS = 50_000;
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
	// Per baselined tile, the sorted signatures of the objects written for it; absent means empty.
	private final Map<Long, long[]> sceneObjects = new HashMap<>();
	private final Set<String> actors = new HashSet<>();
	private final Map<String, Integer> appearance = new HashMap<>();
	private final Map<String, Integer> shownNpc = new HashMap<>();
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
	private int projectileCount;
	private int soundCount;
	/** Projectiles already written: RuneLite reports each one on every frame it moves. */
	private final Map<Projectile, Boolean> projectilesSeen = new java.util.WeakHashMap<>();
	private final Map<GraphicsObject, Integer> sceneGraphics = new java.util.IdentityHashMap<>();
	private final Map<String, long[]> lastMotion = new HashMap<>();
	private boolean cellLimit;
	private boolean sceneTruncated;
	private int objectEvents;
	private int npcActors;
	private int playerActors;
	private long lastPlayerCell = Long.MIN_VALUE;
	private int[] lastRegions = new int[0];
	private final Set<Integer> visitedRegions = new HashSet<>();
	private boolean instanceHeightsWritten;
	private volatile boolean recording;
	private volatile String reason = "";
	private volatile boolean plannedEnd = false;
	private volatile String statusLine = "Starting";
	private CompletableFuture<Void> finishing;
	private volatile double sealedAt = -1;
	private String account;

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

	/** Opaque account key; null leaves the recording unattributed. */
	void account(String key)
	{
		account = key;
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
	/** Throttled per NPC name. */
	private final Map<String, Double> fightNoted = new HashMap<>();
	/** Last hit on each actor, to attribute kills. */
	private final Map<String, Double> ownHits = new HashMap<>();
	static final double KILL_WINDOW = 60;
	private int gameMessageCount;
	private static final int MAX_GAME_MESSAGES = 5000;
	private int lootCount;
	private static final int MAX_LOOT = 5000;
	static final int MAX_LOOT_STACKS = 64;
	private String lastAttacker;
	private double lastAttackedAt = -1;
	private double lastNearDeathAt = -60;
	private int momentCount;
	private static final int MAX_HITS = 200000;
	private int hitCount = 0;
	private static final int MAX_MOMENTS = 2000;
	private int[] lastCamera;
	private int[] lastVitals;
	private int cameraCount;
	// Camera rows land on every change, roughly ten a second while turning.
	private static final int MAX_CAMERA = 4_000_000;
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
			Actor attacker = attacker(me);
			lastAttacker = attacker != null && eventActor(attacker, me) ? actorName(attacker) : null;
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
		String hitId = actorId(target);
		if (hitId != null)
		{
			ownHits.put(hitId, t);
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
			payload.addProperty("hitsplat_tint_disabled", client.getVarbitValue(VarbitID.HITSPLAT_TINT_DISABLED));
			payload.addProperty("hitsplat_maxhit_disabled", client.getVarbitValue(VarbitID.HITSPLAT_MAXHIT_DISABLED));
		}
		Actor attacker = mine || target != me ? null : attacker(me);
		String source = mine ? PLAYER : (attacker != null && eventActor(attacker, me) ? actorId(attacker) : null);
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

	/**
	 * A projectile once, when it is first seen: what flies (a spot animation), from whom or where,
	 * at whom or where, and the client cycles it leaves and lands on, with the heights and slope the
	 * client arcs it by. Cycles are 20 ms; they are written as recording seconds.
	 */
	public void projectile(Projectile projectile, Player me)
	{
		if (!acceptingEvents() || projectile == null || projectilesSeen.containsKey(projectile)) { return; }
		projectilesSeen.put(projectile, Boolean.TRUE);
		if (projectileCount >= MAX_EVENTS) { return; }
		double now = session.time();
		int cycle = client.getGameCycle();
		JsonObject payload = new JsonObject();
		payload.addProperty("graphic", projectile.getId());
		Actor source = projectile.getSourceActor(), target = projectile.getTargetActor();
		String sourceId = source == null ? null : source == me ? PLAYER : eventActor(source, me) ? actorId(source) : null;
		String targetId = target == null ? null : target == me ? PLAYER : eventActor(target, me) ? actorId(target) : null;
		if (sourceId != null) { payload.addProperty("source", sourceId); }
		if (targetId != null) { payload.addProperty("target", targetId); }
		WorldPoint from = projectile.getSourcePoint(), to = projectile.getTargetPoint();
		if (from != null) { payload.add("from", point(from)); }
		if (to != null) { payload.add("to", point(to)); }
		payload.addProperty("start_t", now + (projectile.getStartCycle() - cycle) * 0.02);
		payload.addProperty("end_t", now + (projectile.getEndCycle() - cycle) * 0.02);
		payload.addProperty("start_height", projectile.getStartHeight());
		payload.addProperty("end_height", projectile.getEndHeight());
		payload.addProperty("slope", projectile.getSlope());
		payload.addProperty("start_pos", projectile.getStartPos());
		payload.addProperty("dimension", session.dimension);
		if (emit("osrs.projectile", now, payload, sourceId == null ? PLAYER : sourceId, false)) { projectileCount++; }
	}

	/**
	 * A sound the client played: the effect id (index 4 of the cache), its delay as the client was
	 * told it, who made it when an actor did, and for an area sound the tile and range it carries.
	 */
	public void sound(int soundId, int delay, Actor source, Integer sceneX, Integer sceneY, Integer range, Player me)
	{
		if (!acceptingEvents() || soundId < 0 || soundCount >= MAX_HITS) { return; }
		double now = session.time();
		JsonObject payload = new JsonObject();
		payload.addProperty("sound", soundId);
		payload.addProperty("delay", delay);
		String sourceId = source == null ? null : source == me ? PLAYER : eventActor(source, me) ? actorId(source) : null;
		if (sourceId != null) { payload.addProperty("source", sourceId); }
		if (sceneX != null && sceneY != null)
		{
			WorldView view = client.getTopLevelWorldView();
			JsonArray at = new JsonArray();
			at.add(view.getBaseX() + sceneX);
			at.add(view.getBaseY() + sceneY);
			at.add(view.getPlane());
			payload.add("at", at);
			if (range != null) { payload.addProperty("range", range); }
		}
		payload.addProperty("dimension", session.dimension);
		if (emit("osrs.sound", now, payload, sourceId == null ? PLAYER : sourceId, false)) { soundCount++; }
	}

	private static JsonArray point(WorldPoint point)
	{
		JsonArray at = new JsonArray();
		at.add(point.getX());
		at.add(point.getY());
		at.add(point.getPlane());
		return at;
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
		Double hitAt = deadId == null ? null : ownHits.remove(deadId);
		if (hitAt != null && t - hitAt <= KILL_WINDOW)
		{
			moment("combat.kill", "Killed " + name, t);
		}
	}

	public void statChanged(Skill skill, int level, int xp, int boosted)
	{
		if (!acceptingEvents() || skill == null)
		{
			return;
		}
		double t = session.time();
		JsonObject stat = new JsonObject();
		stat.addProperty("skill", skill.name().toLowerCase(Locale.ROOT));
		stat.addProperty("level", level);
		stat.addProperty("xp", xp);
		stat.addProperty("boosted", boosted);
		stat.addProperty("dimension", session.dimension);
		emit("osrs.stat", t, stat, PLAYER, false);
		Integer before = levels.put(skill, level);
		if (before == null || level <= before)
		{
			return;
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("advancement_id", "osrs:level:" + skill.name().toLowerCase(Locale.ROOT) + ":" + level);
		payload.addProperty("title", skill.getName() + " level " + level);
		payload.addProperty("dimension", session.dimension);
		emit("advancement", t, payload, PLAYER, false);
	}

	public void gameMessage(GameMessages.Match match)
	{
		if (!acceptingEvents() || !config.recordMoments() || match == null)
		{
			return;
		}
		if (gameMessageCount >= MAX_GAME_MESSAGES) { stop("game message limit", true); return; }
		JsonObject payload = new JsonObject();
		payload.addProperty("kind", match.kind);
		payload.add("fields", match.fields);
		payload.addProperty("dimension", session.dimension);
		if (emit("osrs.game_message", session.time(), payload, PLAYER, false))
		{
			gameMessageCount++;
		}
	}

	/** items: [item id, quantity, GE price each]; no source for players. */
	public void loot(String sourceKind, String sourceName, NPC npc, List<int[]> items)
	{
		if (!acceptingEvents() || !config.recordMoments() || items == null || items.isEmpty())
		{
			return;
		}
		if (lootCount >= MAX_LOOT) { stop("loot limit", true); return; }
		String sourceActor = null;
		if (npc != null)
		{
			String id = actorIds.get(npc);
			sourceActor = id != null && actors.contains(id) ? id : null;
		}
		JsonObject payload = lootPayload(sourceKind, sourceName, npc == null ? -1 : npc.getId(), sourceActor, items);
		payload.addProperty("dimension", session.dimension);
		if (emit("osrs.loot", session.time(), payload, PLAYER, false))
		{
			lootCount++;
		}
	}

	static JsonObject lootPayload(String sourceKind, String sourceName, int npcId, String sourceActor, List<int[]> items)
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("source_kind", sourceKind);
		if (sourceName != null && !"player".equals(sourceKind))
		{
			String name = Text.removeTags(sourceName).trim();
			payload.addProperty("source_name", name.length() > 64 ? name.substring(0, 64) : name);
		}
		if (npcId >= 0)
		{
			payload.addProperty("npc_id", npcId);
		}
		if (sourceActor != null)
		{
			payload.addProperty("source_actor", sourceActor);
		}
		JsonArray list = new JsonArray();
		long total = 0;
		for (int[] item : items)
		{
			if (item == null || item.length < 3 || item[1] <= 0)
			{
				continue;
			}
			long each = Math.max(0, item[2]);
			total += each * item[1];
			if (list.size() < MAX_LOOT_STACKS)
			{
				JsonArray entry = new JsonArray();
				entry.add(item[0]);
				entry.add(item[1]);
				entry.add(each);
				list.add(entry);
			}
		}
		payload.add("items", list);
		payload.addProperty("total_ge", total);
		return payload;
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

	/** Likeliest attacker of the player, or null if ambiguous. */
	private Actor attacker(Player me)
	{
		return likelyAttacker(me, me.getInteracting(), presentActors, Actor::getInteracting);
	}

	static <A> A likelyAttacker(A me, A mine, Iterable<? extends A> present, java.util.function.Function<A, ?> interacting)
	{
		if (mine != null && interacting.apply(mine) == me)
		{
			return mine;
		}
		A only = null;
		for (A actor : present)
		{
			if (actor != me && interacting.apply(actor) == me)
			{
				if (only != null)
				{
					return null;
				}
				only = actor;
			}
		}
		return only != null ? only : mine;
	}

	private String interactingId(Actor actor, Player me)
	{
		Actor target = actor.getInteracting();
		if (target == null)
		{
			return null;
		}
		if (target == me)
		{
			return PLAYER;
		}
		String id = actorIds.get(target);
		return id != null && actors.contains(id) ? id : null;
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
			npc ? ((NPC) actor).getId() : -1, npc ? shownNpcId((NPC) actor) : -1,
			npc ? ((NPC) actor).getCombatLevel() : -1, t))
		{
			return false;
		}
		if (actor instanceof Player) { appearance((Player) actor, id, t); }
		if (npc) { npcVariant((NPC) actor, id, t); }
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
		seedLevels();
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
		statusLine = "Recording";
	}

	/** Seed real levels so a part's first stat change can count as a level-up. */
	private void seedLevels()
	{
		if (client.getRealSkillLevel(Skill.HITPOINTS) < 10)
		{
			return;
		}
		for (Skill skill : Skill.values())
		{
			// The total level is not a skill with a level of its own.
			if ("OVERALL".equals(skill.name()))
			{
				continue;
			}
			levels.put(skill, client.getRealSkillLevel(skill));
		}
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
		ownHits.clear();
		known.clear();
		sceneObjects.clear();
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

	/** Marks the scene as fully written: every object and ground item in the loaded scene, this tick. */
	JsonObject sceneLoaded(int tick, int baseX, int baseY, WorldPoint at, int cells, int[] scene, int npcs, int players)
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("tick", tick);
		payload.addProperty("base_x", baseX);
		payload.addProperty("base_y", baseY);
		payload.addProperty("region", at.getRegionID());
		payload.addProperty("scope", "scene");
		payload.addProperty("size", Constants.SCENE_SIZE);
		JsonArray planes = new JsonArray();
		for (int p = 0; p < Constants.MAX_Z; p++) { planes.add(p); }
		payload.add("planes", planes);
		payload.addProperty("radius", radius);
		JsonObject counts = new JsonObject();
		counts.addProperty("cells", cells);
		counts.addProperty("objects", scene[0]);
		counts.addProperty("removed", scene[1]);
		counts.addProperty("items", scene[2]);
		counts.addProperty("npcs", npcs);
		counts.addProperty("players", players);
		payload.add("counts", counts);
		if (scene[3] != 0) { payload.addProperty("truncated", true); }
		payload.addProperty("dimension", session.dimension);
		return payload;
	}

	public void gameState(String state)
	{
		if (!recording || armed) { return; }
		JsonObject payload = new JsonObject();
		payload.addProperty("state", state);
		payload.addProperty("tick", client.getTickCount());
		payload.addProperty("dimension", session.dimension);
		emit("osrs.game_state", session.time(), payload, PLAYER, false);
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
		if (account != null)
		{
			JsonObject source = new JsonObject();
			source.addProperty("account", account);
			header.add("source", source);
		}
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
		osrs.addProperty("records", "osrs.region on each scene load; osrs.object baselines of the whole loaded scene on all planes at each scene load (only what changed for tiles already written), "
			+ "and transitions anywhere in the loaded scene, with id, kind, type and orientation from the object config, a game object once, on its anchor tile; "
			+ "osrs.ground_items [x, y, plane, id, quantity], scope scene (replacing the box it names), baseline, spawned or despawned; "
			+ "osrs.appearance for players; every player.position carries osrs.{npc_id, orientation, animation, pose_animation, animation_frame, graphic}; "
			+ "osrs.motion on the 20 ms client tick with the visible state of nearby actors as [id, x, y, plane, orientation, animation, frame, pose_animation], x and y in fractional tiles, only when changed; "
			+ "osrs.camera independently sampled as [x, y, height, yaw, pitch, scale, viewport_width, viewport_height]; "
			+ "osrs.poses batches game-tick poses including slot, appearance and animation fields; osrs.hit and osrs.death for enabled actor categories; "
			+ "osrs.scene_loaded once the whole scene is written on a scene load, the same tick; osrs.game_state on login state changes; "
			+ "osrs.region includes instance_template_chunks; osrs.instance_heights stores each plane's actual scene corner heights for instanced cache reconstruction; "
			+ "osrs.poses entries end with the id of the actor each one is targeting (\"player\" for the local player) or null, and player.position carries osrs.interacting the same way; "
			+ "with drops and milestones on, osrs.loot {source_kind, source_name, npc_id, source_actor, items [[item_id, quantity, ge_each]], total_ge} per loot received "
			+ "and osrs.game_message {kind, fields} for a closed list of the game's own milestone messages, never their text; "
			+ "osrs.projectile {graphic, source, target, from, to, start_t, end_t, start_height, end_height, slope, start_pos} once per projectile, times in recording seconds; "
			+ "osrs.sound {sound, delay, source, at, range} for every sound effect the client played, at [x, y, plane] with range for an area sound");
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
			+ "Ground cells are discovered within " + radius + " tiles on the player's plane, at most " + MAX_CELLS + ". "
			+ "Objects and ground items cover the whole loaded scene on all planes, at most " + MAX_SCENE_OBJECTS + " objects a scene load. "
			+ "Objects appearing or vanishing are exact transitions with no known cause. "
			+ "Optional own public chat and NPC overhead speech share a " + MAX_SPEECH + "-line budget. "
			+ "Instanced areas include the loaded scene's corner heights on all planes. "
			+ "Optional drops and milestones: loot received as item ids, quantities and Grand Exchange values, and a closed list of the game's own milestone messages "
			+ "(kill counts, pets, collection log, clues, tasks and the like) reduced to their kind and numbers, never the message text. "
			+ "Unloaded space, earlier history, other players' chat, private/clan/friends chat, inventories and actors on other planes are absent.");
		header.add("session", meta);
		JsonArray actorList = new JsonArray();
		actorList.add(actorEntry(PLAYER, playerName, PLAYER_COLOR));
		header.add("actors", actorList);
		header.addProperty("channel_rule", "Local scene observations, optional own public chat and NPC overhead speech, optional drops and allowlisted milestone messages as structured fields only; other-player names and chat, private/clan/friends chat and account credentials are excluded; the account is named only by an opaque salted key.");
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

	/** False when the tick stopped early, so a pending scene load is retried. */
	public boolean tick(boolean rediscover)
	{
		if (!recording)
		{
			return false;
		}
		if (recorder.state() == Recorder.State.FAILED)
		{
			stop("recorder unavailable");
			return false;
		}
		double t = session.time();
		if (!armed && checkLimit(t))
		{
			return false;
		}
		WorldView view = client.getTopLevelWorldView();
		Player me = client.getLocalPlayer();
		if (view == null || me == null || me.getWorldLocation() == null)
		{
			return false;
		}
		if (armed)
		{
			// Wait until the welcome screen closes; the loaded world is not yet animating.
			if (welcomeScreenUp())
			{
				statusLine = "Waiting for you to enter the world";
				return false;
			}
			open();
			t = session.time();
			rediscover = true;
		}
		WorldPoint at = me.getWorldLocation();
		int cellsBefore = known.size();
		int[] scene = null;
		if (rediscover) { seedActors(view); }
		vitals(t);
		if (rediscover || !Arrays.equals(lastRegions, regions(view)))
		{
			region(view, t);
		}
		if (!recording) { return false; }
		Scene skyScene = view.getScene();
		JsonObject sky = sceneSkybox.changed(skyScene == null ? null : skyScene.getSkybox(),
			client.getTextureProvider() == null ? 0.8 : client.getTextureProvider().getBrightness());
		if (sky != null)
		{
			if (skyboxCount >= 2000) { stop("skybox limit", true); return false; }
			sky.addProperty("dimension", session.dimension);
			if (!emit("osrs.skybox", t, sky, PLAYER, false)) { return false; }
			skyboxCount++;
		}
		if (rediscover)
		{
			scene = baselineScene(view, t);
			if (!recording) { return false; }
		}
		long cell = SceneMapper.key(at.getX(), at.getPlane(), at.getY());
		if (rediscover || cell != lastPlayerCell)
		{
			lastPlayerCell = cell;
			discoverCells(view, at, t);
		}
		if (!recording)
		{
			return false;
		}
		appearance(me, PLAYER, t);
		pose(view, me, PLAYER, at, t, true, -1);
		JsonArray crowd = new JsonArray();
		int npcsSeen = 0;
		int playersSeen = 0;
		crowdActors = inThePicture(view, me, at);
		for (Actor other : crowdActors)
		{
			String id = idOf(other);
			if (other instanceof NPC)
			{
				NPC npc = (NPC) other;
				String name = SceneMapper.objectMaterial(npc.getName());
				if (announce(id, name == null ? "NPC" : npc.getName(), NPC_COLOR, "npc", npc.getId(), shownNpcId(npc), npc.getCombatLevel(), t))
				{
					npcVariant(npc, id, t);
					crowdPose(crowd, view, npc, id, npc.getWorldLocation(), npc.getId());
					npcsSeen++;
				}
			}
			else
			{
				Player player = (Player) other;
				if (announce(id, pseudonym(player.getName()), OTHER_PLAYER_COLOR, "player", -1, -1, -1, t))
				{
					appearance(player, id, t);
					crowdPose(crowd, view, player, id, player.getWorldLocation(), -1);
					playersSeen++;
				}
			}
		}
		if (crowd.size() > 0)
		{
			if (poseCount + crowd.size() > MAX_POSES)
			{
				stop("pose limit", true);
				return false;
			}
			JsonObject payload = new JsonObject();
			payload.addProperty("dimension", session.dimension);
			payload.add("poses", crowd);
			if (emit("osrs.poses", t, payload, PLAYER, false))
			{
				poseCount += crowd.size();
			}
		}
		if (rediscover && recording && scene != null)
		{
			emit("osrs.scene_loaded", t, sceneLoaded(client.getTickCount(), view.getBaseX(), view.getBaseY(), at,
				known.size() - cellsBefore, scene, npcsSeen, playersSeen), PLAYER, false);
		}
		statusLine = String.format(Locale.ROOT, "Recording %d:%02d · %d cells · %d objects%s", (int) t / 60, (int) t % 60, known.size(), objectCount,
			cellLimit || sceneTruncated ? " · area captured" : "");
		return true;
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

	private boolean announce(String id, String name, String color, String kind, int npcId, int shownNpcId, int combatLevel, double t)
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
			if (shownNpcId != npcId)
			{
				payload.addProperty("shown_npc_id", shownNpcId);
			}
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
		String interacting = interactingId(actor, client.getLocalPlayer());
		if (interacting != null)
		{
			osrs.addProperty("interacting", interacting);
		}
		payload.add("osrs", osrs);
		if (emit("player.position", t, payload, id, false))
		{
			poseCount++;
		}
	}

	/** HP, prayer, run energy (0.01%) and special attack (0.1%), on change. */
	private void vitals(double t)
	{
		int[] now = {
			client.getBoostedSkillLevel(Skill.HITPOINTS),
			client.getRealSkillLevel(Skill.HITPOINTS),
			client.getBoostedSkillLevel(Skill.PRAYER),
			client.getRealSkillLevel(Skill.PRAYER),
			client.getEnergy(),
			client.getVarpValue(VarPlayerID.SA_ENERGY),
		};
		if (lastVitals != null && Arrays.equals(lastVitals, now))
		{
			return;
		}
		lastVitals = now;
		JsonObject payload = new JsonObject();
		payload.addProperty("hp", now[0]);
		payload.addProperty("hp_max", now[1]);
		payload.addProperty("prayer", now[2]);
		payload.addProperty("prayer_max", now[3]);
		payload.addProperty("energy", now[4]);
		payload.addProperty("special", now[5]);
		payload.addProperty("dimension", session.dimension);
		emit("osrs.vitals", t, payload, PLAYER, false);
	}

	// -1 while hidden
	private static int shownNpcId(NPC npc)
	{
		NPCComposition shown = npc.getTransformedComposition();
		return shown == null ? -1 : shown.getId();
	}

	private void npcVariant(NPC npc, String id, double t)
	{
		int shown = shownNpcId(npc);
		Integer before = shownNpc.put(id, shown);
		if (before == null || before == shown)
		{
			return;
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("npc_id", npc.getId());
		payload.addProperty("shown_npc_id", shown);
		emit("osrs.npc_variant", t, payload, id, false);
	}

	/** osrs.poses entry: [id, x, y, plane, height, orientation, animation, pose animation, frame, graphic,
	 * npc id, graphic height, client slot, logical height, health ratio, health scale, interacting]. Append only. */
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
		entry.add(interactingId(actor, client.getLocalPlayer()));
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

	/** Ground cells within the radius on the player's plane, nearest first. */
	private void discoverCells(WorldView view, WorldPoint at, double t)
	{
		Scene scene = view.getScene();
		Tile[][][] tiles = scene == null ? null : scene.getTiles();
		if (cellLimit || tiles == null)
		{
			return;
		}
		short[][][] overlays = scene.getOverlayIds();
		short[][][] underlays = scene.getUnderlayIds();
		int[][][] heights = view.getTileHeights();
		int plane = at.getPlane();
		List<int[]> ring = new ArrayList<>();
		for (int dx = -radius; dx <= radius; dx++)
		{
			for (int dy = -radius; dy <= radius; dy++)
			{
				if (dx * dx + dy * dy <= radius * radius) { ring.add(new int[]{dx, dy, dx * dx + dy * dy}); }
			}
		}
		ring.sort((a, b) -> Integer.compare(a[2], b[2]));
		List<int[]> cells = new ArrayList<>();
		List<String> materials = new ArrayList<>();
		for (int[] step : ring)
		{
			int worldX = at.getX() + step[0], worldY = at.getY() + step[1];
			int sx = worldX - view.getBaseX(), sy = worldY - view.getBaseY();
			if (plane < 0 || plane >= tiles.length || sx < 0 || sy < 0 || sx >= tiles[plane].length || sy >= tiles[plane][sx].length)
			{
				continue;
			}
			int height = heights != null && plane < heights.length && sx < heights[plane].length && sy < heights[plane][sx].length
				? heights[plane][sx][sy] : 0;
			int[] ground = SceneMapper.groundCell(worldX, worldY, plane, height);
			if (!known.containsKey(SceneMapper.key(ground)))
			{
				short overlay = overlays != null && plane < overlays.length && sx < overlays[plane].length && sy < overlays[plane][sx].length
					? overlays[plane][sx][sy] : 0;
				short underlay = underlays != null && plane < underlays.length && sx < underlays[plane].length && sy < underlays[plane][sx].length
					? underlays[plane][sx][sy] : 0;
				if (!offer(cells, materials, ground, SceneMapper.groundMaterial(overlay, underlay), t)) { return; }
			}
			int[] above = SceneMapper.objectCell(worldX, worldY, plane, height);
			if (!known.containsKey(SceneMapper.key(above)))
			{
				Tile tile = tiles[plane][sx][sy];
				if (!offer(cells, materials, above, tile == null ? SceneMapper.AIR : topMaterial(tile, 0L), t)) { return; }
			}
		}
		snapshot(cells, materials, t);
	}

	/** One object found on a tile, with what identifies it across scene loads. */
	private static final class Found
	{
		final TileObject object;
		final int kind;
		final int config;
		final int impostor;
		final long signature;

		Found(TileObject object, int kind, int config, int impostor)
		{
			this.object = object;
			this.kind = kind;
			this.config = config;
			this.impostor = impostor;
			this.signature = signature(object.getId(), impostor, kind, config);
		}
	}

	static long signature(int id, int impostor, int kind, int config)
	{
		return ((long) (id & 0xFFFFFF) << 33) | ((long) ((impostor + 1) & 0xFFFFFF) << 9)
			| ((long) kind << 7) | ((long) SceneMapper.objectType(config) << 2) | SceneMapper.objectOrientation(config);
	}

	/** The signature without its impostor, which follows game state rather than the object. */
	private static long base(long signature)
	{
		return signature & ~(0xFFFFFFL << 9);
	}

	/**
	 * Every object and ground item in the loaded scene, all planes, in this tick. A tile already written
	 * this scene is compared and only its differences are written; returns objects written, removed,
	 * ground items, and 1 when the per-scene object budget cut it short.
	 */
	private int[] baselineScene(WorldView view, double t)
	{
		int[] counts = new int[4];
		Scene scene = view.getScene();
		Tile[][][] tiles = scene == null ? null : scene.getTiles();
		if (tiles == null)
		{
			return counts;
		}
		int baseX = view.getBaseX(), baseY = view.getBaseY();
		JsonArray objects = new JsonArray();
		JsonArray gone = new JsonArray();
		JsonArray items = new JsonArray();
		List<Found> here = new ArrayList<>();
		sceneTruncated = false;
		for (int plane = 0; plane < tiles.length; plane++)
		{
			for (int sx = 0; sx < tiles[plane].length; sx++)
			{
				for (int sy = 0; sy < tiles[plane][sx].length; sy++)
				{
					Tile tile = tiles[plane][sx][sy];
					int worldX = baseX + sx, worldY = baseY + sy;
					long tileKey = SceneMapper.key(worldX, plane, worldY);
					here.clear();
					if (tile != null)
					{
						anchoredObjects(tile, sx, sy, here);
						items(tile, worldX, worldY, plane, items, counts);
					}
					long[] before = sceneObjects.get(tileKey);
					long[] now = new long[here.size()];
					for (int i = 0; i < now.length; i++) { now[i] = here.get(i).signature; }
					Arrays.sort(now);
					if (before == null ? now.length == 0 : Arrays.equals(before, now))
					{
						continue;
					}
					if (counts[0] + now.length > MAX_SCENE_OBJECTS)
					{
						sceneTruncated = true;
						continue;
					}
					if (before != null)
					{
						for (long old : before)
						{
							if (Arrays.binarySearch(now, old) < 0) { gone.add(removedEntry(old, worldX, worldY, plane)); counts[1]++; }
						}
					}
					for (Found found : here)
					{
						if (before != null && Arrays.binarySearch(before, found.signature) >= 0) { continue; }
						objects.add(entry(found, worldX, worldY, plane, worldX, worldY));
						counts[0]++;
					}
					if (now.length == 0) { sceneObjects.remove(tileKey); }
					else { sceneObjects.put(tileKey, now); }
				}
			}
		}
		counts[3] = sceneTruncated ? 1 : 0;
		// Removals first: an object whose look changed is written as gone and then as it stands now.
		if (!emitObjects("despawned", gone, t) || !emitObjects("baseline", objects, t)) { return counts; }
		emitItems(view, items, t);
		return counts;
	}

	/** A tile's objects; a game object is listed only on the tile it is anchored on. */
	private void anchoredObjects(Tile tile, int sx, int sy, List<Found> here)
	{
		GameObject[] gameObjects = tile.getGameObjects();
		if (gameObjects != null)
		{
			for (GameObject object : gameObjects)
			{
				if (object == null) { continue; }
				net.runelite.api.Point min = object.getSceneMinLocation();
				if (min != null && (min.getX() != sx || min.getY() != sy)) { continue; }
				here.add(new Found(object, KIND_GAME, object.getConfig(), impostor(object.getId())));
			}
		}
		WallObject wall = tile.getWallObject();
		if (wall != null) { here.add(new Found(wall, KIND_WALL, wall.getConfig(), impostor(wall.getId()))); }
		GroundObject ground = tile.getGroundObject();
		if (ground != null) { here.add(new Found(ground, KIND_GROUND, ground.getConfig(), impostor(ground.getId()))); }
		DecorativeObject decoration = tile.getDecorativeObject();
		if (decoration != null) { here.add(new Found(decoration, KIND_DECORATIVE, decoration.getConfig(), impostor(decoration.getId()))); }
	}

	private static void items(Tile tile, int worldX, int worldY, int plane, JsonArray items, int[] counts)
	{
		List<TileItem> ground = tile.getGroundItems();
		if (ground == null) { return; }
		for (TileItem item : ground)
		{
			if (item == null || counts[2] >= MAX_SCENE_ITEMS) { continue; }
			items.add(itemEntry(worldX, worldY, plane, item));
			counts[2]++;
		}
	}

	/** [x, y, plane, item id, quantity]. */
	private static JsonArray itemEntry(int worldX, int worldY, int plane, TileItem item)
	{
		JsonArray entry = new JsonArray();
		entry.add(worldX);
		entry.add(worldY);
		entry.add(plane);
		entry.add(item.getId());
		entry.add(item.getQuantity());
		return entry;
	}

	/** The scene's ground items; the first row's box replaces what an earlier load held there. */
	private void emitItems(WorldView view, JsonArray items, double t)
	{
		int from = 0;
		do
		{
			JsonArray chunk = new JsonArray();
			for (int i = from; i < Math.min(items.size(), from + OBJECT_CHUNK); i++) { chunk.add(items.get(i)); }
			JsonObject payload = new JsonObject();
			payload.addProperty("dimension", session.dimension);
			payload.addProperty("scope", from == 0 ? "scene" : "baseline");
			if (from == 0)
			{
				payload.addProperty("base_x", view.getBaseX());
				payload.addProperty("base_y", view.getBaseY());
				payload.addProperty("size", Constants.SCENE_SIZE);
			}
			payload.add("items", chunk);
			if (!emit("osrs.ground_items", t, payload, PLAYER, false)) { return; }
			from += OBJECT_CHUNK;
		}
		while (from < items.size());
	}

	public void itemChanged(Tile tile, TileItem item, boolean spawned)
	{
		if (!acceptingEvents() || tile == null || item == null) { return; }
		WorldPoint at = tile.getWorldLocation();
		if (at == null) { return; }
		double t = session.time();
		if (objectEvents >= MAX_OBJECT_EVENTS) { stop("object event limit", true); return; }
		JsonObject payload = new JsonObject();
		payload.addProperty("dimension", session.dimension);
		payload.addProperty("scope", spawned ? "spawned" : "despawned");
		JsonArray items = new JsonArray();
		items.add(itemEntry(at.getX(), at.getY(), at.getPlane(), item));
		payload.add("items", items);
		if (emit("osrs.ground_items", t, payload, PLAYER, false)) { objectEvents++; }
	}

	private JsonObject entry(Found found, int worldX, int worldY, int plane, int anchorX, int anchorY)
	{
		JsonObject entry = new JsonObject();
		entry.addProperty("x", worldX);
		entry.addProperty("y", worldY);
		entry.addProperty("plane", plane);
		entry.addProperty("id", found.object.getId());
		entry.addProperty("kind", found.kind);
		entry.addProperty("type", SceneMapper.objectType(found.config));
		entry.addProperty("orientation", SceneMapper.objectOrientation(found.config));
		if (found.impostor >= 0)
		{
			entry.addProperty("impostor_id", found.impostor);
		}
		if (found.kind == KIND_GAME)
		{
			entry.addProperty("anchor_x", anchorX);
			entry.addProperty("anchor_y", anchorY);
		}
		return entry;
	}

	/** What a despawn needs from a stored signature: tile, id, kind, type and orientation. */
	private static JsonObject removedEntry(long signature, int worldX, int worldY, int plane)
	{
		int kind = (int) ((signature >>> 7) & 3);
		JsonObject entry = new JsonObject();
		entry.addProperty("x", worldX);
		entry.addProperty("y", worldY);
		entry.addProperty("plane", plane);
		entry.addProperty("id", (int) ((signature >>> 33) & 0xFFFFFF));
		entry.addProperty("kind", kind);
		entry.addProperty("type", (int) ((signature >>> 2) & 31));
		entry.addProperty("orientation", (int) (signature & 3));
		if (kind == KIND_GAME)
		{
			entry.addProperty("anchor_x", worldX);
			entry.addProperty("anchor_y", worldY);
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

	private boolean emitObjects(String scope, JsonArray objects, double t)
	{
		for (int from = 0; from < objects.size(); from += OBJECT_CHUNK)
		{
			JsonArray chunk = new JsonArray();
			for (int i = from; i < Math.min(objects.size(), from + OBJECT_CHUNK); i++) { chunk.add(objects.get(i)); }
			JsonObject payload = new JsonObject();
			payload.addProperty("dimension", session.dimension);
			payload.addProperty("scope", scope);
			payload.add("objects", chunk);
			if (!emit("osrs.object", t, payload, PLAYER, false))
			{
				return false;
			}
			if ("baseline".equals(scope)) { objectCount += chunk.size(); }
		}
		return true;
	}

	private boolean offer(List<int[]> cells, List<String> materials, int[] cell, String material, double t)
	{
		if (known.size() + cells.size() >= MAX_CELLS)
		{
			// Cells stop here; the recording goes on, the game's own map covers the ground.
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
		int kind = object instanceof WallObject ? KIND_WALL : object instanceof GroundObject ? KIND_GROUND
			: object instanceof DecorativeObject ? KIND_DECORATIVE : KIND_GAME;
		int config = object instanceof GameObject ? ((GameObject) object).getConfig()
			: object instanceof WallObject ? ((WallObject) object).getConfig()
			: object instanceof GroundObject ? ((GroundObject) object).getConfig()
			: object instanceof DecorativeObject ? ((DecorativeObject) object).getConfig() : 0;
		int anchorX = at.getX(), anchorY = at.getY();
		if (object instanceof GameObject && ((GameObject) object).getSceneMinLocation() != null)
		{
			anchorX = view.getBaseX() + ((GameObject) object).getSceneMinLocation().getX();
			anchorY = view.getBaseY() + ((GameObject) object).getSceneMinLocation().getY();
		}
		Found found = new Found(object, kind, config, impostor(object.getId()));
		if (transition(SceneMapper.key(anchorX, at.getPlane(), anchorY), found.signature, spawned))
		{
			if (objectEvents >= MAX_OBJECT_EVENTS)
			{
				stop("object event limit", true);
				return;
			}
			JsonObject payload = new JsonObject();
			payload.addProperty("dimension", session.dimension);
			payload.addProperty("scope", spawned ? "spawned" : "despawned");
			JsonArray objects = new JsonArray();
			objects.add(entry(found, anchorX, anchorY, at.getPlane(), anchorX, anchorY));
			payload.add("objects", objects);
			if (emit("osrs.object", t, payload, PLAYER, false)) { objectEvents++; }
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

	/** Update a tile's written objects; false when the change is already written (or a despawn of nothing written). */
	private boolean transition(long tileKey, long signature, boolean spawned)
	{
		long[] held = sceneObjects.get(tileKey);
		if (spawned)
		{
			if (held != null && Arrays.binarySearch(held, signature) >= 0) { return false; }
			long[] next = held == null ? new long[]{signature} : Arrays.copyOf(held, held.length + 1);
			next[next.length - 1] = signature;
			Arrays.sort(next);
			sceneObjects.put(tileKey, next);
			return true;
		}
		if (held == null) { return false; }
		for (int i = 0; i < held.length; i++)
		{
			if (base(held[i]) != base(signature)) { continue; }
			long[] next = new long[held.length - 1];
			System.arraycopy(held, 0, next, 0, i);
			System.arraycopy(held, i + 1, next, i, held.length - i - 1);
			if (next.length == 0) { sceneObjects.remove(tileKey); }
			else { sceneObjects.put(tileKey, next); }
			return true;
		}
		return false;
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
