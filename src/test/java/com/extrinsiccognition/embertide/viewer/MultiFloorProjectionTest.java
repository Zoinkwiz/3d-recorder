package com.extrinsiccognition.embertide.viewer;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.RuneLiteObjectController;
import net.runelite.api.coords.LocalPoint;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static com.extrinsiccognition.embertide.viewer.RecordingReaderTest.*;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class MultiFloorProjectionTest
{
	private static Client client() { return ProjectionTest.client(); }
	private Recording recording() throws Exception
	{
		// Every floor painted; map plane p sits 240 * (p + 1) units up.
		return parse(HEADER + pose(0,3210,1)
			+ GroundFixture.scene(0, 3176, 3168, (p, x, y) -> GroundFixture.paint(1, 1, 1, 1), (p, x, y) -> -240 * (p + 1)));
	}
	private void place(ReplayRenderer renderer, Recording.Scene scene, boolean allFloors, boolean miniature, boolean map)
	{
		Recording.Sample origin = new Recording.Sample(0,3210.5,3210.5,1,0,-1,-1,-1,-1);
		renderer.place(scene,origin,new LocalPoint(6400,6400,-1),2,-100,miniature,4,4,0,0,map,0,allFloors);
	}
	@Test public void recordedFloorsShareTheHousePlaneButKeepVerticalSeparationAndClearOnToggle() throws Exception
	{
		Recording r=recording(); Recording.Scene scene=r.scenes.get(0); scene.actors.clear();
		for (int p=0;p<4;p++) { Recording.Scenery object=new Recording.Scenery(0,3210,3210,p,42,10,0,0,true); scene.objects.put(object.key(),List.of(object)); }
		Client client=client(); ReplayRenderer renderer=new ReplayRenderer(client);
		place(renderer,scene,true,false,true); renderer.draw(r,0,false,true);
		ArgumentCaptor<RuneLiteObjectController> capture=ArgumentCaptor.forClass(RuneLiteObjectController.class);
		verify(client,times(4)).registerRuneLiteObject(capture.capture());
		List<RuneLiteObjectController> objects=capture.getAllValues();
		for (int p=0;p<4;p++) { assertEquals(2,objects.get(p).getLevel()); assertEquals(140-p*240,objects.get(p).getZ()); }
		place(renderer,scene,false,false,true);
		for (RuneLiteObjectController object:objects) { verify(client).removeRuneLiteObject(object); }
		renderer.draw(r,0,false,true); assertTrue(renderer.status(),renderer.status().startsWith("0 actors · 1 objects"));
	}
	@Test public void recordedActorsAndSceneryUseTheirOwnFloorInMiniatures() throws Exception
	{
		Recording r=recording(); Recording.Scene scene=r.scenes.get(0); scene.actors.clear();
		for (int p=0;p<4;p++)
		{
			Recording.Actor actor=new Recording.Actor("npc:"+p); actor.npcId=42;
			actor.poses.add(new Recording.Sample(0,3210.5,3210.5,p,0,-1,-1,-1,42)); scene.actors.put(actor.id,actor);
			Recording.Scenery object=new Recording.Scenery(0,3210,3210,p,42,10,0,0,true);
			scene.objects.put(object.key(),List.of(object));
		}
		Client client=client(); ReplayRenderer renderer=new ReplayRenderer(client);
		place(renderer,scene,true,true,false); renderer.draw(r,0,false,true);
		ArgumentCaptor<RuneLiteObjectController> capture=ArgumentCaptor.forClass(RuneLiteObjectController.class);
		verify(client,times(8)).registerRuneLiteObject(capture.capture());
		for (int i=0;i<8;i++)
		{
			RuneLiteObjectController object=capture.getAllValues().get(i);
			assertEquals(renderer.placement().z(240*(i%4+1)),object.getZ()); assertEquals(2,object.getLevel());
		}
		place(renderer,scene,false,true,false); renderer.draw(r,0,false,true);
		assertTrue(renderer.status().startsWith("1 actors · 1 objects"));
	}
	@Test public void terrainPatchesNeverMixFloorsOrExceedSixteenTiles() throws Exception
	{
		Recording.Scene scene=recording().scenes.get(0); ReplayRenderer renderer=new ReplayRenderer(client());
		place(renderer,scene,true,true,false);
		Field field=ReplayRenderer.class.getDeclaredField("groundPatches"); field.setAccessible(true);
		@SuppressWarnings("unchecked") Map<String,List<Terrain.Tile>> patches=(Map<String,List<Terrain.Tile>>)field.get(renderer);
		assertEquals(4,patches.values().stream().flatMap(List::stream).map(t->t.plane).distinct().count());
		for (List<Terrain.Tile> patch:patches.values())
		{ assertTrue(patch.size()<=16); assertEquals(1,patch.stream().map(t->t.plane).distinct().count()); }
		place(renderer,scene,false,true,false);
		assertEquals(1,patches.values().stream().flatMap(List::stream).map(t->t.plane).distinct().count());
		assertEquals(1,patches.values().iterator().next().get(0).plane);
	}
}
