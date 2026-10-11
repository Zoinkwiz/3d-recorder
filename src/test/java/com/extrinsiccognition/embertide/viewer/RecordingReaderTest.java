package com.extrinsiccognition.embertide.viewer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import org.junit.Test;
import static org.junit.Assert.*;

public class RecordingReaderTest
{
	static final String HEADER = "{\"type\":\"mccr.header\",\"format\":\"mccr/0.1\",\"game\":\"osrs\",\"osrs\":{\"cache_revision\":240},\"spatial\":{\"dimension\":\"osrs:surface\"}}\n";
	static String event(double time, String type, String payload)
	{ return "{\"type\":\"" + type + "\",\"elapsed_s\":" + time + ",\"user_id\":\"player\",\"payload\":" + payload + "}\n"; }
	static String pose(double time, int x, int plane)
	{ return event(time, "player.position", "{\"plane\":" + plane + ",\"osrs\":{\"world_x\":" + x + ",\"world_y\":3200,\"orientation\":2040,\"animation\":-1,\"pose_animation\":808}}"); }
	static String motion(double time, double x, int orientation)
	{ return event(time, "osrs.motion", "{\"samples\":[[\"player\"," + x + ",3200.5,0," + orientation + ",-1,-1,819]]}"); }
	static Recording parse(String text) throws IOException
	{ return new RecordingReader().read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))); }

	@Test public void readsActualRecorderShapesAndInterpolatesAcrossNorth() throws Exception
	{
		Recording r = parse(HEADER + pose(0, 3200, 0) + motion(0, 3200.5, 2040) + pose(.6, 3201, 0) + motion(.6, 3201.5, 8));
		Recording.Sample s = r.scenes.get(0).actors.get("player").sample(.3);
		assertEquals(3201, s.x, 1e-9); assertEquals(0, s.orientation); assertEquals(240, r.revision);
	}
	@Test public void supportsGzipByMagicNotFilename() throws Exception
	{
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (GZIPOutputStream zip = new GZIPOutputStream(bytes)) { zip.write((HEADER + pose(0, 3200, 0)).getBytes(StandardCharsets.UTF_8)); }
		assertEquals(1, new RecordingReader().read(new ByteArrayInputStream(bytes.toByteArray())).scenes.size());
	}
	@Test public void seeksBothWaysAcrossScenesWithoutCoordinateBlending() throws Exception
	{
		Recording r = parse(HEADER + pose(0, 3200, 0) + event(1, "mccr.scene", "{\"dimension\":\"osrs:instance:2\",\"reset\":true}") + pose(1, 5000, 1));
		assertEquals(2, r.scenes.size()); assertEquals(5000.5, r.sceneAt(1).origin(1).x, 0);
		assertEquals(3200.5, r.sceneAt(.2).origin(.2).x, 0);
	}
	@Test public void sparseFinePositionsRemainValidWhileCoarseHeartbeatsContinue() throws Exception
	{
		Recording r = parse(HEADER + pose(0, 3200, 0) + motion(0, 3200.75, 0) + pose(8, 3200, 0));
		assertEquals(3200.75, r.sceneAt(8).actors.get("player").sample(8).x, 0);
	}
	@Test public void coarseDataHidesActorsAfterObservationGap() throws Exception
	{ assertNull(parse(HEADER + pose(0, 3200, 0)).scenes.get(0).actors.get("player").sample(3)); }
	@Test public void noMotionAcrossTeleports() throws Exception
	{
		Recording r = parse(HEADER + pose(0, 3200, 0) + motion(0, 3200.5, 0) + motion(.2, 3250.5, 0));
		assertEquals(3200.5, r.scenes.get(0).actors.get("player").sample(.1).x, 0);
	}
	@Test public void actorsLeaveAndRejoinWithoutReusingOldFineCoordinates() throws Exception
	{
		Recording r = parse(HEADER + pose(0, 3200, 0) + motion(0, 3200.8, 0)
			+ event(.2, "mccr.actor_lifecycle", "{\"state\":\"left\"}")
			+ event(.8, "mccr.actor_lifecycle", "{\"state\":\"joined\"}") + pose(.8, 3204, 0));
		Recording.Actor actor = r.scenes.get(0).actors.get("player");
		assertNull(actor.sample(.5)); assertEquals(3204.5, actor.sample(.9).x, 0);
		assertEquals(3200.8, actor.sample(.1).x, 0);
	}
	@Test public void reconstructsObjectStateWhenSeekingBackwards() throws Exception
	{
		String object = "{\"id\":100,\"x\":3200,\"y\":3200,\"plane\":0,\"kind\":0,\"type\":10}";
		Recording r = parse(HEADER + pose(0, 3200, 0)
			+ event(.2, "osrs.object", "{\"scope\":\"baseline\",\"objects\":[" + object + "]}")
			+ event(1, "osrs.object", "{\"scope\":\"despawned\",\"objects\":[" + object + "]}"));
		java.util.List<Recording.Scenery> track = r.scenes.get(0).objects.values().iterator().next();
		assertFalse(Recording.at(track, 1.1).present); assertTrue(Recording.at(track, .1).present);
	}
	@Test public void neverInterpolatesTowardAnActorAfterItHasLeftAndRejoined() throws Exception
	{
		Recording r = parse(HEADER + pose(0, 3200, 0) + motion(0, 3200.5, 0)
			+ event(.2, "mccr.actor_lifecycle", "{\"state\":\"left\"}")
			+ event(.8, "mccr.actor_lifecycle", "{\"state\":\"joined\"}") + pose(.8, 3204, 0) + motion(.8, 3204.5, 0));
		assertEquals(3200.5, r.sceneAt(0).actors.get("player").sample(.1).x, 0);
	}
	@Test public void despawnsAnObjectEvenIfItsImpostorChanged() throws Exception
	{
		String object = "{\"id\":100,\"impostor_id\":101,\"x\":3200,\"y\":3200,\"plane\":0,\"kind\":0,\"type\":10}";
		Recording r = parse(HEADER + pose(0, 3200, 0)
			+ event(0, "osrs.object", "{\"scope\":\"baseline\",\"objects\":[" + object + "]}")
			+ event(1, "osrs.object", "{\"scope\":\"despawned\",\"objects\":[" + object.replace("101", "102") + "]}"));
		assertEquals(1, r.sceneAt(0).objects.size());
		java.util.List<Recording.Scenery> track = r.sceneAt(0).objects.values().iterator().next();
		assertFalse(Recording.at(track, 1).present); assertEquals(101, Recording.at(track, .5).id);
	}
	@Test public void appearanceChangesUsePlaybackTime() throws Exception
	{
		Recording r = parse(HEADER + event(0, "osrs.appearance", "{\"equipment_ids\":[256],\"colors\":[0],\"female\":false}")
			+ pose(0, 3200, 0) + event(1, "osrs.appearance", "{\"equipment_ids\":[257],\"colors\":[1],\"female\":true}"));
		assertEquals(257, r.appearance("player", 1).equipment[0]); assertEquals(256, r.appearance("player", .5).equipment[0]);
	}
	@Test public void terrainCoordinatesAndPlanesUseRecorderAxes() throws Exception
	{
		Recording r = parse(HEADER + pose(0, 3200, 1)
			+ event(0, "mccr.spatial_snapshot", "{\"cells\":[[3200,10,-3200,\"osrs:underlay:64\"],[3200,11,-3200,\"air\"]]}"));
		assertEquals(1, r.scenes.get(0).ground.size()); assertEquals(256, r.scenes.get(0).height(3200.5, 3200.5, 1), 0);
	}
	@Test public void supportsBatchedNpcPoses() throws Exception
	{
		Recording r = parse(HEADER + event(0, "osrs.poses", "{\"poses\":[[\"npc:1\",3200,3200,0,3,512,808,819,2,-1,42]]}"));
		Recording.Sample s = r.scenes.get(0).actors.get("npc:1").sample(0);
		assertEquals(42, s.npcId); assertEquals(3200.5, s.x, 0); assertEquals(2, s.frame);
	}
	@Test public void walkingPoseClockDoesNotRestartOnEveryMotionRow() throws Exception
	{
		Recording r = parse(HEADER + pose(0, 3200, 0) + motion(0, 3200.5, 0) + motion(.02, 3200.55, 0) + motion(.04, 3200.6, 0));
		assertEquals(0, r.scenes.get(0).actors.get("player").sample(.04).poseStart, 0);
	}
	@Test public void unknownNonSpatialEventsAreIgnored() throws Exception
	{ assertEquals(3, parse(HEADER + pose(0, 3200, 0) + event(1, "osrs.loot", "{\"items\":[]}")).rows); }
	@Test public void rejectsWrongGame() { rejects(HEADER.replace("\"osrs\"", "\"wow\"") + pose(0, 3200, 0)); }
	@Test public void rejectsMissingHeader() { rejects(pose(0, 3200, 0)); }
	@Test public void rejectsEmptyRecording() { rejects(HEADER); }
	@Test public void rejectsReversedClock() { rejects(HEADER + pose(1, 3200, 0) + pose(.5, 3200, 0)); }
	@Test public void rejectsInvalidPlane() { rejects(HEADER + pose(0, 3200, 9)); }
	@Test public void budgetsObjectsPerSceneSoARaidOfManyRoomsReads() throws Exception
	{
		// Two scenes of 60,000 objects each: 120,000 in all, as a raid's rooms add up (1108 holds 115,381).
		Recording r = parse(HEADER + pose(0, 3200, 0) + objects(0, 60_000)
			+ event(1, "mccr.scene", "{\"dimension\":\"osrs:instance:2\",\"reset\":true}") + pose(1, 5000, 1) + objects(1, 60_000));
		assertEquals(2, r.scenes.size());
		assertEquals(60_000, r.scenes.get(1).objects.size());
	}
	@Test public void rejectsOneSceneOverItsObjectBudget()
	{ rejects(HEADER + pose(0, 3200, 0) + objects(0, RecordingReader.MAX_SCENE_OBJECTS + 1)); }
	private static String objects(double time, int count)
	{
		StringBuilder rows = new StringBuilder();
		for (int from = 0; from < count; from += 20_000)
		{
			StringBuilder list = new StringBuilder();
			for (int i = from; i < Math.min(count, from + 20_000); i++)
			{ list.append(i == from ? "" : ",").append("{\"id\":").append(i).append(",\"x\":3200,\"y\":3200,\"plane\":0,\"type\":10}"); }
			rows.append(event(time, "osrs.object", "{\"scope\":\"baseline\",\"objects\":[" + list + "]}"));
		}
		return rows.toString();
	}
	@Test public void rejectsBrokenJson() { rejects(HEADER + "{\"type\":"); }
	@Test public void rejectsNonFiniteCoordinates() { rejects(HEADER + pose(0, 3200, 0).replace("3200", "1e1000")); }
	@Test public void rejectsDeepJsonBeforeGsonRecursion() { rejects(HEADER + "[".repeat(40) + "0" + "]".repeat(40)); }
	@Test public void rejectsOversizedLine() { rejects(HEADER + " ".repeat(RecordingReader.MAX_LINE + 1)); }
	@Test public void rejectsMalformedAppearance() { rejects(HEADER + event(0, "osrs.appearance", "{\"equipment_ids\":[0,0,0,0,0,0,0,0,0,0,0,0,0]}") + pose(0, 3200, 0)); }
	@Test public void rejectsMalformedMotion() { rejects(HEADER + pose(0, 3200, 0) + event(0, "osrs.motion", "{\"samples\":[[\"player\",1]]}")); }
	private static void rejects(String data)
	{ try { parse(data); fail("Expected reader rejection"); } catch (IOException expected) { assertFalse(expected.getMessage().isBlank()); } }

	// Compact motion: milli-tile deltas per actor, the recorder's motion_xy encoding.
	private static final String COMPACT = HEADER.replace("\"cache_revision\":240", "\"cache_revision\":241,\"motion_xy\":\"delta-milli\"");
	private static String sample(double time, int x, int y)
	{ return event(time,"osrs.motion", "{\"samples\":[[\"player\"," + x + "," + y + ",0,0,-1,-1,808]]}"); }
	@Test public void expandsMilliTileDeltasBeforeInterpolation() throws Exception
	{
		Recording r = parse(COMPACT + pose(0,3200,0) + sample(0,3200500,3200750) + sample(.2,100,-50));
		Recording.Sample s = r.scenes.get(0).actors.get("player").sample(.1);
		assertEquals(3200.55,s.x,1e-8); assertEquals(3200.725,s.y,1e-8);
	}
	@Test public void preservesAccumulatorAcrossSceneBoundaries() throws Exception
	{
		Recording r = parse(COMPACT + pose(0,3200,0) + sample(0,3200500,3200750)
			+ event(1,"mccr.scene","{\"dimension\":\"osrs:surface\"}") + pose(1,5000,0) + sample(1,1800000,0));
		assertEquals(5000.5,r.sceneAt(1).origin(1).x,1e-8);
	}
	@Test public void preservesAccumulatorAcrossLifecycleChanges() throws Exception
	{
		Recording r = parse(COMPACT + pose(0,3200,0) + sample(0,3200500,3200750)
			+ event(.2,"mccr.actor_lifecycle","{\"state\":\"left\"}")
			+ event(1,"mccr.actor_lifecycle","{\"state\":\"joined\"}") + pose(1,3204,0) + sample(1,4000,0));
		assertEquals(3204.5,r.sceneAt(1).origin(1).x,1e-8);
	}
	@Test(expected = IOException.class) public void rejectsUnknownCoordinateEncoding() throws Exception
	{ parse(COMPACT.replace("delta-milli","future-format") + pose(0,3200,0)); }
	@Test(expected = IOException.class) public void rejectsFractionalEncodedDeltas() throws Exception
	{ parse(COMPACT + pose(0,3200,0) + sample(0,3200500,3200750).replace("3200500","3200500.5")); }
	@Test public void capturesTerrainRegionMetadata() throws Exception
	{
		Recording r = parse(HEADER + event(0,"osrs.region","{\"regions\":[12850],\"base_x\":3200,\"base_y\":3200,\"instance\":false}") + pose(0,3200,0));
		assertFalse(r.sceneAt(0).terrain.instance); assertEquals(3200,r.sceneAt(0).terrain.baseX);
	}
	@Test(expected = IOException.class) public void rejectsOversizedInstanceGrids() throws Exception
	{ parse(HEADER + event(0,"osrs.instance_heights","{\"plane\":0,\"heights\":[[1,2]]}") + pose(0,3200,0)); }
}
