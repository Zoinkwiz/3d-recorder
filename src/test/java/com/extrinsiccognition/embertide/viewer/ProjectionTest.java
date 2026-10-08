package com.extrinsiccognition.embertide.viewer;

import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.Model;
import net.runelite.api.RuneLiteObjectController;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.ObjectID;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class ProjectionTest
{
	@Test public void projectsOnlyInsideAnInstanceWithTheHouseExitPortal()
	{
		WorldView view = mock(WorldView.class); Scene scene = mock(Scene.class); Tile tile = mock(Tile.class); GameObject portal = mock(GameObject.class);
		when(view.isInstance()).thenReturn(true); when(view.getScene()).thenReturn(scene);
		when(scene.getTiles()).thenReturn(new Tile[][][]{{{tile}}}); when(tile.getGameObjects()).thenReturn(new GameObject[]{null, portal});
		when(portal.getId()).thenReturn(ObjectID.POH_EXIT_PORTAL); assertTrue(HouseGate.containsHouse(view));
		when(view.isInstance()).thenReturn(false); assertFalse(HouseGate.containsHouse(view));
		when(view.isInstance()).thenReturn(true); when(portal.getId()).thenReturn(1); assertFalse(HouseGate.containsHouse(view));
		assertFalse(HouseGate.containsHouse(null));
	}
	@Test public void miniatureScalesTheAnimatedCopyAndNeverTheCachedBase()
	{
		Client client = mock(Client.class); Model base = mock(Model.class), animated = mock(Model.class);
		when(client.applyTransformations(eq(base), isNull(), anyInt(), isNull(), anyInt())).thenReturn(animated);
		ReplayObject object = new ReplayObject(client); object.model(base, .25, 128, 256); object.animation(-1, 0, -1, 0, 0);
		assertSame(animated, object.getModel()); verify(animated).scale(32, 64, 32); verify(base, never()).scale(anyInt(), anyInt(), anyInt());
	}
	@Test public void guardsAgainstAnApiReturningTheBaseModel()
	{
		Client client = mock(Client.class); Model base = mock(Model.class);
		when(client.applyTransformations(eq(base), isNull(), anyInt(), isNull(), anyInt())).thenReturn(base);
		ReplayObject object = new ReplayObject(client); object.model(base, .25, 128, 128); object.getModel();
		verify(base, never()).scale(anyInt(), anyInt(), anyInt());
	}
	@Test public void softwareBoundsCoverTheScaledMesh()
	{
		Client client = mock(Client.class); Model model = mock(Model.class);
		when(model.getVerticesCount()).thenReturn(2);
		when(model.getVerticesX()).thenReturn(new float[]{-512, 512});
		when(model.getVerticesZ()).thenReturn(new float[]{-512, 512});
		ReplayObject object = new ReplayObject(client); object.model(model, 1, 128, 128);
		assertEquals(725, object.getRadius());
		object.model(model, .25, 128, 128); assertEquals(182, object.getRadius());
	}
	@Test public void intentionallyEmptyCacheModelsAreSkippedWithoutRebuilding()
	{
		Client client = mock(Client.class, RETURNS_DEEP_STUBS);
		when(client.getIndexConfig().loadData(9, 42)).thenReturn(new byte[]{1, 1, 0, 1, 0});
		net.runelite.api.ModelData data = mock(net.runelite.api.ModelData.class); Model empty = mock(Model.class);
		when(client.loadModelData(1)).thenReturn(data);
		when(client.mergeModels(any(net.runelite.api.ModelData[].class))).thenReturn(data);
		when(data.light(anyInt(), anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(empty);
		CacheModels models = new CacheModels(client); models.beginTick();
		assertNull(models.actor(null, 42, -1, -1)); models.beginTick();
		assertNull(models.actor(null, 42, -1, -1)); assertEquals(1, models.emptyModelCount());
		verify(client, times(1)).loadModelData(1);
	}
	@Test public void rendererRebuildsModesAtTheSameAnchorAndClearIsIdempotent()
	{
		Client client = mock(Client.class); ReplayRenderer renderer = new ReplayRenderer(client);
		Recording.Scene scene = new Recording.Scene(0, "osrs:surface");
		Recording.Sample origin = new Recording.Sample(0, 3200.5, 3200.5, 0, 0, -1, -1, -1, -1);
		LocalPoint anchor = new LocalPoint(6400, 6400, -1);
		renderer.place(scene, origin, anchor, 0, -100, true, 4, 12, 0, 96, false, 0, false);
		assertEquals(4.0 / 25, renderer.placement().scale, 0); assertEquals(-196, renderer.placement().anchorZ);
		renderer.place(scene, origin, anchor, 0, -100, false, 4, 12, 0, 0, false, 0, false);
		assertEquals(1, renderer.placement().scale, 0); assertEquals(-100, renderer.placement().anchorZ);
		renderer.clear(); renderer.clear(); assertFalse(renderer.placed());
	}
	@Test public void unknownModelsNeverRegisterEmptySceneObjects() throws Exception
	{
		Client client = mock(Client.class); ReplayRenderer renderer = new ReplayRenderer(client);
		Recording recording = RecordingReaderTest.parse(RecordingReaderTest.HEADER + RecordingReaderTest.pose(0, 3200, 0));
		Recording.Scene scene = recording.sceneAt(0);
		renderer.place(scene, scene.origin(0), new LocalPoint(6400, 6400, -1), 0, 0, false, 4, 12, 0, 0, false, 0, false);
		renderer.draw(recording, 0, false, false);
		verify(client, never()).registerRuneLiteObject(any()); assertTrue(renderer.status().contains("pending/unavailable"));
	}
	@Test public void widerAreaExpandsBothModesAndCanBeTurnedOff()
	{
		ReplayRenderer renderer=new ReplayRenderer(mock(Client.class)); Recording.Scene scene=new Recording.Scene(0,"osrs:surface");
		Recording.Sample origin=new Recording.Sample(0,3200.5,3200.5,0,0,-1,-1,-1,-1);
		LocalPoint anchor=new LocalPoint(6400,6400,-1);
		renderer.place(scene,origin,anchor,0,0,true,4,12,0,0,true,12, false);
		assertEquals(4.0/49,renderer.placement().scale,0);
		renderer.place(scene,origin,anchor,0,0,false,4,12,0,0,true,12, false); assertEquals(1,renderer.placement().scale,0);
		renderer.place(scene,origin,anchor,0,0,true,4,12,0,0,false,12, false);
		assertEquals(4.0/25,renderer.placement().scale,0);
	}
	@Test public void aRecordingWithoutGroundSaysSoAndDrawsNoGround() throws Exception
	{
		Client client = mock(Client.class); ReplayRenderer renderer = new ReplayRenderer(client);
		Recording r = RecordingReaderTest.parse(RecordingReaderTest.HEADER + RecordingReaderTest.pose(0, 3200, 0)
			+ RecordingReaderTest.event(0, "mccr.spatial_snapshot", "{\"cells\":[[3200,0,-3200,\"osrs:underlay:10\"]]}"));
		renderer.place(r.sceneAt(0), r.sceneAt(0).origin(0), new LocalPoint(6400, 6400, -1), 0, 0, true, 4, 12, 0, 0, false, 0, false);
		renderer.draw(r, 0, true, false);
		assertTrue(renderer.status(), renderer.status().contains(ReplayRenderer.NO_GROUND));
		verify(client, never()).loadModelData(anyInt());
	}
	@Test public void anOlderRecordingDrawsItsSnapshotFloorAndSaysItIsPartial() throws Exception
	{
		Client client = mock(Client.class, RETURNS_DEEP_STUBS);
		when(client.getIndexConfig().loadData(Definitions.UNDERLAY, 9)).thenReturn(new byte[]{1, 0x40, (byte) 0x80, 0x20, 0});
		ReplayRenderer renderer = new ReplayRenderer(client);
		Recording r = RecordingReaderTest.parse(RecordingReaderTest.HEADER + RecordingReaderTest.pose(0, 3200, 0)
			+ RecordingReaderTest.event(0, "mccr.spatial_snapshot", "{\"cells\":[[3200,1,-3200,\"osrs:underlay:10\"],[3201,1,-3200,\"osrs:underlay:0\"]]}"));
		Recording.Scene scene = r.sceneAt(0);
		renderer.place(scene, scene.origin(0), new LocalPoint(6400, 6400, -1), 0, 0, true, 4, 12, 0, 0, false, 0, false);
		assertNotNull(scene.terrain.tile(3200, 3200, 0)); assertNull(scene.terrain.tile(3201, 3200, 0));
		assertEquals(128, renderer.placement().sourceHeight, 0);
		renderer.draw(r, 0, true, false);
		assertTrue(renderer.status(), renderer.status().contains(ReplayRenderer.PARTIAL_GROUND));
		assertFalse(renderer.status().contains(ReplayRenderer.NO_GROUND));
		verify(client, atLeastOnce()).loadModelData(3728);

		Client empty = mock(Client.class, RETURNS_DEEP_STUBS); ReplayRenderer none = new ReplayRenderer(empty);
		Recording blank = RecordingReaderTest.parse(RecordingReaderTest.HEADER + RecordingReaderTest.pose(0, 3200, 0)
			+ RecordingReaderTest.event(0, "mccr.spatial_snapshot", "{\"cells\":[[3200,1,-3200,\"osrs:underlay:0\"]]}"));
		none.place(blank.sceneAt(0), blank.sceneAt(0).origin(0), new LocalPoint(6400, 6400, -1), 0, 0, true, 4, 12, 0, 0, false, 0, false);
		assertTrue(none.status(), none.status().contains(ReplayRenderer.NO_GROUND));
	}
	@Test public void everyRegisteredObjectIsRemovedOnClear() throws Exception
	{
		Client client = mock(Client.class, RETURNS_DEEP_STUBS);
		// A synthetic NPC exercises the real decoder and cache assembly seam.
		when(client.getIndexConfig().loadData(9, 42)).thenReturn(new byte[]{1, 1, 0, 1, 0});
		net.runelite.api.ModelData data = mock(net.runelite.api.ModelData.class);
		Model model = mock(Model.class);
		when(model.getVerticesCount()).thenReturn(3); when(model.getFaceCount()).thenReturn(1);
		when(client.loadModelData(1)).thenReturn(data);
		when(client.mergeModels(any(net.runelite.api.ModelData[].class))).thenReturn(data);
		when(data.light(anyInt(), anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(model);
		Recording r = RecordingReaderTest.parse(RecordingReaderTest.HEADER
			+ RecordingReaderTest.event(0, "osrs.poses", "{\"poses\":[[\"npc:1\",3200,3200,0,0,0,-1,-1,0,-1,42]]}"));
		ReplayRenderer renderer = new ReplayRenderer(client);
		renderer.place(r.sceneAt(0), r.sceneAt(0).origin(0), new LocalPoint(6400, 6400, -1), 0, 0, true, 4, 12, 0, 0, false, 0, false);
		renderer.draw(r, 0, false, false);
		org.mockito.ArgumentCaptor<RuneLiteObjectController> capture = org.mockito.ArgumentCaptor.forClass(RuneLiteObjectController.class);
		verify(client).registerRuneLiteObject(capture.capture());
		renderer.clear(); verify(client).removeRuneLiteObject(capture.getValue());
	}

	@Test public void recordedObjectsRespectDespawnSeeksAndLayerCleanup() throws Exception
	{
		Client client = mock(Client.class, RETURNS_DEEP_STUBS);
		when(client.getIndexConfig().loadData(6, 42)).thenReturn(new byte[]{5, 1, 0, 1, 0});
		net.runelite.api.ModelData data = mock(net.runelite.api.ModelData.class);
		Model model = mock(Model.class);
		when(model.getVerticesCount()).thenReturn(3); when(model.getFaceCount()).thenReturn(1);
		when(client.loadModelData(1)).thenReturn(data);
		when(client.mergeModels(any(net.runelite.api.ModelData[].class))).thenReturn(data);
		when(data.light(anyInt(), anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(model);
		Recording r = RecordingReaderTest.parse(RecordingReaderTest.HEADER + RecordingReaderTest.pose(0, 3200, 0));
		Recording.Scene scene = r.sceneAt(0);
		Recording.Scenery present = new Recording.Scenery(0, 3201, 3201, 0, 42, 10, 0, 0, true);
		Recording.Scenery removal = new Recording.Scenery(1, 3201, 3201, 0, 42, 10, 0, 0, false);
		scene.objects.put(present.key(), List.of(present, removal));
		ReplayRenderer renderer = new ReplayRenderer(client);
		LocalPoint anchor = new LocalPoint(6400, 6400, -1);
		renderer.place(scene, scene.origin(0), anchor, 0, 0, true, 4, 12, 0, 0, true, 12, false);
		renderer.draw(r, 0, false, true); assertTrue(renderer.status().contains("1 objects"));
		renderer.draw(r, 1, false, true); assertTrue(renderer.status().contains("0 objects"));
		renderer.draw(r, 0, false, true); assertTrue(renderer.status().contains("1 objects"));
		renderer.draw(r, 0, false, false); assertTrue(renderer.status().contains("0 objects"));
		renderer.draw(r, 0, false, true);
		renderer.place(scene, scene.origin(0), anchor, 0, 0, true, 4, 12, 0, 0, false, 12, false);
		renderer.draw(r, 0, false, true); renderer.clear(); renderer.clear();
		org.mockito.ArgumentCaptor<RuneLiteObjectController> capture = org.mockito.ArgumentCaptor.forClass(RuneLiteObjectController.class);
		verify(client, times(4)).registerRuneLiteObject(capture.capture());
		verify(client, times(4)).removeRuneLiteObject(any());
	}
}
