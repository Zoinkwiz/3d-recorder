package com.extrinsiccognition.embertide.viewer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import net.runelite.api.Client;
import net.runelite.api.IndexDataBase;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import com.extrinsiccognition.embertide.viewer.Definitions.ItemDef;
import com.extrinsiccognition.embertide.viewer.Definitions.KitDef;
import com.extrinsiccognition.embertide.viewer.Definitions.NpcDef;
import com.extrinsiccognition.embertide.viewer.Definitions.ObjectDef;

/** Client-thread model builder over the client's own config and models. Every model being edited has private arrays. */
final class CacheModels
{
	private final Client client;
	private final Map<String, Model> models = lru(ReplayRenderer.MAX_OBJECT_LIMIT + 256);
	private final Map<Integer, ObjectDef> objects = lru(ReplayRenderer.MAX_OBJECT_LIMIT);
	private final Map<Integer, ItemDef> items = lru(1024);
	private final Map<Integer, KitDef> kits = lru(512);
	private final Map<Integer, NpcDef> npcs = lru(512);
	private final Map<Integer, Definitions.UnderlayDef> underlays = new HashMap<>();
	private final Map<Integer, Definitions.OverlayDef> overlays = new HashMap<>();
	private final TerrainModels terrainModels;
	private final Map<String, Long> retryAfter = lru(2048);
	private final Map<String, Boolean> emptyModels = lru(ReplayRenderer.MAX_OBJECT_LIMIT);
	private int buildsRemaining;
	private String lastProblem = "";

	CacheModels(Client client)
	{
		this.client = client;
		terrainModels = new TerrainModels(client);
	}

	void beginTick() { buildsRemaining = 8; }
	String lastProblem() { return lastProblem; }
	int emptyModelCount() { return emptyModels.size(); }
	int mismatchedGround() { return terrainModels.mismatched(); }

	void clear()
	{
		models.clear();
		objects.clear();
		items.clear();
		kits.clear();
		npcs.clear();
		underlays.clear();
		overlays.clear();
		terrainModels.clear();
		retryAfter.clear();
		emptyModels.clear();
		lastProblem = "";
	}

	/** npcId already includes the appearance's transformation. */
	Model actor(Recording.Appearance appearance, int npcId, int leftHand, int rightHand)
	{
		String key = npcId >= 0 ? "npc:" + npcId : appearance == null ? "missing-player"
			: "player:" + appearance.female + ":" + Arrays.toString(appearance.equipment) + ":" + Arrays.toString(appearance.colors) + ":" + leftHand + ":" + rightHand;
		return cached(key, () -> npcId >= 0 ? npc(npcId) : appearance == null ? null : player(appearance, leftHand, rightHand));
	}

	ObjectDef definition(int id) { return definition(objects, Definitions.OBJECT, id, Definitions::object); }

	Model scenery(Recording.Scenery object)
	{
		String key = sceneryKey(object);
		return cached(key, () ->
		{
			ObjectDef d = definition(object.id);
			if (d == null)
			{
				return null;
			}
			if (d.models == null)
			{
				if (!d.impostors)
				{
					emptyModels.put(key, true);
				}
				return null;
			}
			int type = object.type == 11 ? 10 : object.type >= 5 && object.type <= 8 ? 4 : object.type;
			List<Integer> selected = new ArrayList<>();
			for (int i = 0; i < d.models.length; i++)
			{
				if (d.types == null ? type == 10 : d.types[i] == type)
				{
					selected.add(d.models[i]);
				}
			}
			if (selected.isEmpty())
			{
				emptyModels.put(key, true);
				return null;
			}
			int[] ids = selected.stream().mapToInt(Integer::intValue).toArray();
			ModelData data;
			if (type == 2)
			{
				// A corner is two boundary renderables, the mirrored first side and the next compass side, in one loc entry.
				ModelData first = sceneryPart(d, ids, object.orientation + 4);
				ModelData second = sceneryPart(d, ids, (object.orientation + 1) & 3);
				data = first == null || second == null ? null : client.mergeModels(first, second);
			}
			else
			{
				data = sceneryPart(d, ids, object.orientation);
			}
			return data == null ? null : data.light(64 + d.ambient, 768 + d.contrast, -50, -10, -50);
		});
	}

	private ModelData sceneryPart(ObjectDef d, int[] ids, int orientation)
	{
		ModelData data = merge(ids);
		if (data == null)
		{
			return null;
		}
		if (d.rotated ^ orientation > 3)
		{
			float[] z = data.getVerticesZ();
			for (int i = 0; i < data.getVerticesCount(); i++)
			{
				z[i] = -z[i];
			}
			int[] a = data.getFaceIndices1(), c = data.getFaceIndices3();
			for (int i = 0; i < data.getFaceCount(); i++)
			{
				int tmp = a[i];
				a[i] = c[i];
				c[i] = tmp;
			}
		}
		switch (orientation & 3)
		{
			case 1:
				data.rotateY90Ccw();
				break;
			case 2:
				data.rotateY180Ccw();
				break;
			case 3:
				data.rotateY270Ccw();
				break;
			default:
				break;
		}
		paint(data, d.recolorFrom, d.recolorTo, d.retextureFrom, d.retextureTo, d.fullRecolor);
		data.scale(d.scaleX, d.scaleHeight, d.scaleY);
		data.translate((short) d.offsetX, (short) d.offsetHeight, (short) d.offsetY);
		return data;
	}

	private static String sceneryKey(Recording.Scenery object)
	{
		return "object:" + object.id + ":" + object.type + ":" + object.orientation;
	}

	boolean emptyScenery(Recording.Scenery object) { return emptyModels.containsKey(sceneryKey(object)); }

	private Model npc(int id)
	{
		NpcDef d = definition(npcs, Definitions.NPC, id, Definitions::npc);
		ModelData data = d == null ? null : merge(d.models);
		if (data == null)
		{
			return null;
		}
		paint(data, d.recolorFrom, d.recolorTo, d.retextureFrom, d.retextureTo, d.fullRecolor);
		// Native NPC resize is applied after animation in ReplayObject.
		return data.light(64 + d.ambient, 850 + d.contrast, -30, -50, -30);
	}

	int npcWidth(int id)
	{
		NpcDef d = npcs.get(id);
		return d == null ? 128 : d.widthScale;
	}

	int npcHeight(int id)
	{
		NpcDef d = npcs.get(id);
		return d == null ? 128 : d.heightScale;
	}

	private Model player(Recording.Appearance a, int leftHand, int rightHand)
	{
		int[] equipment = a.equipment.clone();
		if (equipment.length > 5 && leftHand >= 0)
		{
			equipment[5] = leftHand;
		}
		if (equipment.length > 3 && rightHand >= 0)
		{
			equipment[3] = rightHand;
		}
		List<ModelData> parts = new ArrayList<>();
		for (int value : equipment)
		{
			if (value < 256)
			{
				continue;
			}
			ModelData part;
			if (value >= 2048)
			{
				ItemDef d = definition(items, Definitions.ITEM, value - 2048, Definitions::item);
				if (d == null)
				{
					return null;
				}
				if ((a.female ? d.femaleModel0 : d.maleModel0) < 0)
				{
					continue;
				}
				part = merge(a.female ? new int[]{d.femaleModel0, d.femaleModel1, d.femaleModel2} : new int[]{d.maleModel0, d.maleModel1, d.maleModel2});
				if (part == null)
				{
					return null;
				}
				part.translate(0, a.female ? d.femaleOffset : d.maleOffset, 0);
				paint(part, d.recolorFrom, d.recolorTo, d.retextureFrom, d.retextureTo, d.fullRecolor);
			}
			else
			{
				KitDef d = definition(kits, Definitions.KIT, value - 256, Definitions::kit);
				if (d == null)
				{
					return null;
				}
				if (d.models == null || d.models.length == 0)
				{
					continue;
				}
				part = merge(d.models);
				if (part == null)
				{
					return null;
				}
				paint(part, d.recolorFrom, d.recolorTo, d.retextureFrom, d.retextureTo, -1);
			}
			parts.add(part);
		}
		if (parts.isEmpty())
		{
			return null;
		}
		ModelData data = client.mergeModels(parts.toArray(new ModelData[0]));
		BodyColors.apply(data, a.colors);
		return data.light(64, 850, -30, -50, -30);
	}

	Model groundPatch(List<Terrain.Tile> tiles, double originX, double originY, double originHeight)
	{
		try
		{
			return terrainModels.patch(tiles, originX, originY, originHeight);
		}
		catch (RuntimeException e)
		{
			lastProblem = "Ground reconstruction unavailable: " + e.getMessage();
			return null;
		}
	}

	/** Builds a floor from snapshot cells for a scene without osrs.ground rows, once the client's config is readable. */
	void snapshotGround(Recording.Scene scene)
	{
		if (scene.terrain.sampled || scene.terrain.recorded() || scene.ground.isEmpty() || client.getIndexConfig() == null)
		{
			return;
		}
		SnapshotTerrain.build(scene,
			id -> floor(underlays, Definitions.UNDERLAY, id, Definitions::underlay),
			id -> floor(overlays, Definitions.OVERLAY, id, Definitions::overlay));
	}

	/** A floor definition; a missing or unreadable one is cached as null. */
	private <T> T floor(Map<Integer, T> cache, int archive, int id, Function<byte[], T> decode)
	{
		if (cache.containsKey(id))
		{
			return cache.get(id);
		}
		T d = null;
		try
		{
			byte[] bytes = config(archive, id);
			d = bytes == null ? null : decode.apply(bytes);
		}
		catch (RuntimeException e)
		{
			lastProblem = "Some floor colours could not be read (" + e.getMessage() + ").";
		}
		cache.put(id, d);
		return d;
	}

	/** A model's definition; a missing one is not cached, so it is asked for again. */
	private <T> T definition(Map<Integer, T> cache, int archive, int id, Function<byte[], T> decode)
	{
		T d = cache.get(id);
		if (d == null)
		{
			byte[] bytes = config(archive, id);
			if (bytes == null)
			{
				return null;
			}
			d = decode.apply(bytes);
			cache.put(id, d);
		}
		return d;
	}

	private byte[] config(int archive, int id)
	{
		IndexDataBase index = id < 0 || id > 1_000_000 ? null : client.getIndexConfig();
		return index == null ? null : index.loadData(archive, id);
	}

	private ModelData merge(int[] ids)
	{
		if (ids == null || ids.length == 0)
		{
			return null;
		}
		List<ModelData> parts = new ArrayList<>();
		for (int id : ids)
		{
			if (id < 0)
			{
				continue;
			}
			ModelData data = client.loadModelData(id);
			if (data == null)
			{
				return null;
			}
			parts.add(data);
		}
		return parts.isEmpty() ? null : client.mergeModels(parts.toArray(new ModelData[0]));
	}

	private Model cached(String key, Supplier<Model> build)
	{
		Model known = models.get(key);
		if (known != null || emptyModels.containsKey(key))
		{
			return known;
		}
		long now = System.nanoTime();
		if (buildsRemaining <= 0 || retryAfter.getOrDefault(key, 0L) > now)
		{
			return null;
		}
		buildsRemaining--;
		try
		{
			Model result = build.get();
			// Some map decorations deliberately have no visible geometry.
			if (result != null && (result.getVerticesCount() == 0 || result.getFaceCount() == 0))
			{
				emptyModels.put(key, true);
				return null;
			}
			if (result != null)
			{
				models.put(key, result);
				retryAfter.remove(key);
				return result;
			}
		}
		catch (RuntimeException e)
		{
			lastProblem = "Some models could not be built (" + e.getClass().getSimpleName() + ").";
		}
		retryAfter.put(key, now + 2_000_000_000L);
		return null;
	}

	private static void paint(ModelData data, short[] recolorFrom, short[] recolorTo, short[] retextureFrom, short[] retextureTo, int fullRecolor)
	{
		if (recolorFrom != null && recolorTo != null)
		{
			for (int i = 0; i < Math.min(recolorFrom.length, recolorTo.length); i++)
			{
				data.recolor(recolorFrom[i], recolorTo[i]);
			}
		}
		if (retextureFrom != null && retextureTo != null)
		{
			for (int i = 0; i < Math.min(retextureFrom.length, retextureTo.length); i++)
			{
				data.retexture(retextureFrom[i], retextureTo[i]);
			}
		}
		if (fullRecolor != -1)
		{
			Arrays.fill(data.getFaceColors(), (short) fullRecolor);
		}
	}

	private static <K, V> Map<K, V> lru(int limit)
	{
		return new LinkedHashMap<K, V>(16, .75f, true)
		{
			@Override
			protected boolean removeEldestEntry(Map.Entry<K, V> entry)
			{
				return size() > limit;
			}
		};
	}
}
