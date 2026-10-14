package com.extrinsiccognition.embertide.viewer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.coords.LocalPoint;

/** Owns only its registered projections; never changes the real scene. */
final class ReplayRenderer
{
	static final int DEFAULT_OBJECT_LIMIT = 4096;
	static final int MAX_OBJECT_LIMIT = 8192;
	static final String NO_GROUND = "No ground in this recording, so only figures and objects are shown. Recordings made with an older recorder do not save the ground.";
	static final String PARTIAL_GROUND = "This recording saved the floor only near where you walked. Recordings made with a newer recorder save all of it.";
	private final Client client;
	private final CacheModels models;
	private final Map<String, ReplayObject> rendered = new LinkedHashMap<>();
	private final Map<String, List<Terrain.Tile>> groundPatches = new LinkedHashMap<>();
	private final Map<String, Model> patchModels = new HashMap<>();
	private final Map<String, Long> patchRetryAfter = new HashMap<>();
	private List<Map.Entry<String, List<Recording.Scenery>>> scenery = new ArrayList<>();
	private Recording.Scene scene;
	private Placement placement;
	private int sourcePlane, radius;
	private int waiting, visibleActors, visibleScenery, capped, cappedActors;
	private boolean allFloors;
	private int objectLimit = DEFAULT_OBJECT_LIMIT;

	ReplayRenderer(Client client)
	{
		this.client = client;
		models = new CacheModels(client);
	}

	boolean placed() { return placement != null; }
	Placement placement() { return placement; }
	void objectLimit(int limit) { objectLimit = Math.max(128, Math.min(MAX_OBJECT_LIMIT, limit)); }

	void place(Recording.Scene scene, Recording.Sample origin, LocalPoint anchor, int plane, int anchorZ,
		boolean miniature, int size, int radius, int rotation, int lift, boolean surround, int extraArea, boolean allFloors)
	{
		clear();
		this.scene = scene;
		this.allFloors = allFloors;
		models.snapshotGround(scene);
		radius = Math.max(4, Math.min(24, radius)) + (surround ? Math.max(0, Math.min(24, extraArea)) : 0);
		this.radius = radius;
		sourcePlane = origin.plane;
		double scale = miniature ? size / (2.0 * radius + 1) : 1;
		placement = new Placement(origin.x, origin.y, scene.height(origin.x, origin.y, sourcePlane), scale,
			anchor.getX(), anchor.getY(), anchorZ - lift, plane, anchor.getWorldView(), rotation);
		for (int p = allFloors ? 0 : sourcePlane; p <= (allFloors ? 3 : sourcePlane); p++)
		{
			for (int x = (int) Math.ceil(origin.x - radius - .5); x <= Math.floor(origin.x + radius - .5); x++)
			{
				for (int y = (int) Math.ceil(origin.y - radius - .5); y <= Math.floor(origin.y + radius - .5); y++)
				{
					Terrain.Tile tile = scene.terrain.tile(x, y, p, allFloors);
					if (tile != null)
					{
						String key = p + ":" + Math.floorDiv(x, 4) + ":" + Math.floorDiv(y, 4);
						groundPatches.computeIfAbsent(key, ignored -> new ArrayList<>()).add(tile);
					}
				}
			}
		}
		List<Map.Entry<String, List<Terrain.Tile>>> patches = new ArrayList<>(groundPatches.entrySet());
		patches.sort(Comparator.comparingDouble(e -> distance(e.getValue().get(0).x, e.getValue().get(0).y)));
		groundPatches.clear();
		patches.forEach(e -> groundPatches.put(e.getKey(), e.getValue()));
		scenery = new ArrayList<>(scene.objects.entrySet());
		scenery.sort(Comparator.comparingDouble(e -> distance(e.getValue().get(0).x, e.getValue().get(0).y)));
	}

	void draw(Recording recording, double time, boolean showGround, boolean showScenery)
	{
		if (placement == null)
		{
			return;
		}
		models.beginTick();
		waiting = visibleActors = visibleScenery = capped = cappedActors = 0;
		Set<String> keep = new HashSet<>();
		drawActors(recording, time, keep);
		if (showGround)
		{
			drawGround(keep);
		}
		if (showScenery)
		{
			drawScenery(time, keep);
		}
		rendered.entrySet().removeIf(entry ->
		{
			if (keep.contains(entry.getKey()))
			{
				return false;
			}
			client.removeRuneLiteObject(entry.getValue());
			return true;
		});
	}

	private void drawActors(Recording recording, double time, Set<String> keep)
	{
		List<ActorAt> actors = new ArrayList<>();
		for (Recording.Actor actor : scene.actors.values())
		{
			Recording.Sample sample = actor.sample(time);
			if (sample != null && (allFloors || sample.plane == sourcePlane) && inRange(sample.x, sample.y))
			{
				actors.add(new ActorAt(actor, sample));
			}
		}
		actors.sort(Comparator.comparingDouble(a -> "player".equals(a.actor.id) ? -1 : distance(a.sample.x, a.sample.y)));
		int count = 0;
		for (ActorAt at : actors)
		{
			if (count++ >= 128)
			{
				cappedActors++;
				continue;
			}
			Recording.Sample sample = at.sample;
			Recording.Appearance appearance = recording.appearance(at.actor.id, time);
			int npcId = sample.npcId >= 0 ? sample.npcId : at.actor.npcId;
			if (appearance != null && appearance.npcId >= 0)
			{
				npcId = appearance.npcId;
			}
			Animation animation = sample.animation < 0 ? null : client.loadAnimation(sample.animation);
			Model model = models.actor(appearance, npcId, animation == null ? -1 : animation.getLeftHandItem(), animation == null ? -1 : animation.getRightHandItem());
			if (model == null)
			{
				waiting++;
				continue;
			}
			String key = "actor:" + at.actor.id;
			ReplayObject object = get(key);
			object.model(model, placement.scale, npcId >= 0 ? models.npcWidth(npcId) : 128, npcId >= 0 ? models.npcHeight(npcId) : 128);
			object.animation(sample.animation, sample.frame, sample.pose, time - sample.frameStart, time - sample.poseStart);
			if (position(object, sample.x, sample.y, scene.height(sample.x, sample.y, sample.plane), sample.orientation))
			{
				keep.add(key);
				visibleActors++;
			}
		}
	}

	/** Builds at most one missing patch model a tick. */
	private void drawGround(Set<String> keep)
	{
		boolean built = false;
		for (Map.Entry<String, List<Terrain.Tile>> patch : groundPatches.entrySet())
		{
			Terrain.Tile first = patch.getValue().get(0);
			double patchX = Math.floorDiv(first.x, 4) * 4 + 2, patchY = Math.floorDiv(first.y, 4) * 4 + 2;
			Model model = patchModels.get(patch.getKey());
			if (model == null && !built && System.nanoTime() >= patchRetryAfter.getOrDefault(patch.getKey(), 0L))
			{
				built = true;
				model = models.groundPatch(patch.getValue(), patchX, patchY, placement.sourceHeight);
				if (model != null)
				{
					patchModels.put(patch.getKey(), model);
				}
				else
				{
					patchRetryAfter.put(patch.getKey(), System.nanoTime() + 2_000_000_000L);
				}
			}
			if (model == null)
			{
				waiting++;
				continue;
			}
			String key = "ground:" + patch.getKey();
			ReplayObject object = get(key);
			object.model(model, placement.scale, 128, 128);
			object.animation(-1, 0, -1, 0, 0);
			if (position(object, patchX, patchY, placement.sourceHeight, 0))
			{
				keep.add(key);
			}
		}
	}

	private void drawScenery(double time, Set<String> keep)
	{
		int count = 0;
		for (Map.Entry<String, List<Recording.Scenery>> entry : scenery)
		{
			Recording.Scenery recorded = Recording.at(entry.getValue(), time);
			if (recorded == null || !recorded.present || (!allFloors && recorded.plane != sourcePlane)
				|| !inRange(recorded.x + .5, recorded.y + .5) || models.emptyScenery(recorded))
			{
				continue;
			}
			if (count >= objectLimit)
			{
				capped++;
				continue;
			}
			Model model = models.scenery(recorded);
			if (model == null && models.emptyScenery(recorded))
			{
				continue;
			}
			count++;
			if (model == null)
			{
				waiting++;
				continue;
			}
			Definitions.ObjectDef definition = models.definition(recorded.id);
			double sizeX = definition == null ? 1 : definition.sizeX, sizeY = definition == null ? 1 : definition.sizeY;
			if ((recorded.orientation & 1) != 0)
			{
				double tmp = sizeX;
				sizeX = sizeY;
				sizeY = tmp;
			}
			// Ground decorations and walls stay centred on their anchor tile.
			if (recorded.kind != 0)
			{
				sizeX = sizeY = 1;
			}
			double x = recorded.x + sizeX / 2, y = recorded.y + sizeY / 2;
			String key = "object:" + entry.getKey();
			ReplayObject object = get(key);
			object.model(model, placement.scale, 128, 128);
			object.animation(definition == null ? -1 : definition.animation, 0, -1, time - recorded.animationStart, 0);
			if (position(object, x, y, scene.height(x, y, recorded.plane), 0))
			{
				keep.add(key);
				visibleScenery++;
			}
		}
	}

	private ReplayObject get(String key) { return rendered.computeIfAbsent(key, ignored -> new ReplayObject(client)); }

	private boolean position(ReplayObject object, double x, double y, double height, int orientation)
	{
		int localX = placement.x(x, y), localY = placement.y(x, y);
		if (localX < 128 || localY < 128 || localX >= 103 * 128 || localY >= 103 * 128)
		{
			return false;
		}
		object.setLocation(new LocalPoint(localX, localY, placement.worldView), placement.plane);
		object.setZ(placement.z(height));
		object.setOrientation(placement.orientation(orientation));
		if (!client.isRuneLiteObjectRegistered(object))
		{
			client.registerRuneLiteObject(object);
		}
		return true;
	}

	private double distance(double x, double y) { return Math.hypot(x - placement.sourceX, y - placement.sourceY); }

	private boolean inRange(double x, double y)
	{
		return Math.abs(x - placement.sourceX) <= radius + .5 && Math.abs(y - placement.sourceY) <= radius + .5;
	}

	String status()
	{
		return visibleActors + " actors · " + visibleScenery + " objects"
			+ (allFloors && placement != null ? " · All floors" : "")
			+ (waiting == 0 ? "" : " · " + waiting + " assets pending/unavailable")
			+ (cappedActors == 0 ? "" : " · " + cappedActors + " actors over display limit")
			+ (capped == 0 ? "" : " · " + capped + " objects not shown; raise Object limit in the settings (" + objectLimit + ")")
			+ (scene == null ? "" : !scene.terrain.recorded() ? " · " + NO_GROUND : scene.terrain.sampled ? " · " + PARTIAL_GROUND : "")
			+ (scene == null || !scene.terrain.truncated ? "" : " · Part of the ground was too large to load")
			+ (models.mismatchedGround() == 0 ? "" : " · " + models.mismatchedGround() + " ground tiles could not be drawn")
			+ (models.lastProblem().isEmpty() ? "" : " · " + models.lastProblem());
	}

	void clear()
	{
		for (ReplayObject object : rendered.values())
		{
			client.removeRuneLiteObject(object);
		}
		rendered.clear();
		groundPatches.clear();
		patchModels.clear();
		patchRetryAfter.clear();
		scenery.clear();
		scene = null;
		placement = null;
		waiting = visibleActors = visibleScenery = capped = cappedActors = 0;
	}

	void close()
	{
		clear();
		models.clear();
	}

	private static final class ActorAt
	{
		final Recording.Actor actor;
		final Recording.Sample sample;

		ActorAt(Recording.Actor actor, Recording.Sample sample)
		{
			this.actor = actor;
			this.sample = sample;
		}
	}
}
