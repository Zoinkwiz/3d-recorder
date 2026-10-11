package com.extrinsiccognition.embertide.viewer;

import java.util.List;
import org.junit.Test;
import static com.extrinsiccognition.embertide.viewer.RecordingReaderTest.*;
import static org.junit.Assert.*;

public class AnimationTimelineTest
{
	private String action(double time,int frame)
	{ return event(time,"osrs.motion","{\"samples\":[[\"player\",3200.5,3200.5,0,0,100,"+frame+",808]]}"); }
	@Test public void repeatedMotionRowsKeepTimeWithinTheSameActionFrame() throws Exception
	{
		Recording r=parse(HEADER+pose(0,3200,0)+action(0,0)+action(.04,0)+action(.08,0));
		Recording.Sample sample=r.sceneAt(0).actors.get("player").sample(.08);
		assertEquals(0,sample.frameStart,0);
		assertEquals(1,ReplayObject.frameAt(AnimationPlaybackTest.classic(3,3,3,3),sample.frame,.08-sample.frameStart));
	}
	@Test public void recordedFrameChangesAndRewindsResetOnlyTheFrameAnchor() throws Exception
	{
		Recording r=parse(HEADER+pose(0,3200,0)+action(0,0)+action(.1,1)+action(.14,1)+action(.2,0));
		Recording.Actor actor=r.sceneAt(0).actors.get("player");
		assertEquals(.1,actor.sample(.14).frameStart,0);
		assertEquals(.2,actor.sample(.2).frameStart,0);
		assertEquals(0,actor.sample(.2).poseStart,0);
		assertEquals(.1,actor.sample(.14).frameStart,0);
	}
	@Test public void sparseMotionRetainsIdlePhaseThroughCoarseHeartbeats() throws Exception
	{
		Recording r=parse(HEADER+pose(0,3200,0)+action(0,0)+pose(.6,3200,0)+pose(1.2,3200,0)
			+pose(1.8,3200,0)+pose(2.4,3200,0)+action(2.5,0));
		assertEquals(0,r.sceneAt(0).actors.get("player").sample(2.5).poseStart,0);
	}
	@Test public void actorLifecycleRestartsClocksEvenWhenAnimationIdsMatch() throws Exception
	{
		Recording r=parse(HEADER+pose(0,3200,0)+action(0,0)
			+event(.2,"mccr.actor_lifecycle","{\"state\":\"left\"}")
			+event(.4,"mccr.actor_lifecycle","{\"state\":\"joined\"}")+pose(.4,3200,0)+action(.4,0));
		Recording.Sample sample=r.sceneAt(0).actors.get("player").sample(.4);
		assertEquals(.4,sample.poseStart,0); assertEquals(.4,sample.frameStart,0);
	}
	@Test public void observationGapAndPoseChangeStartNewPoseRuns() throws Exception
	{
		Recording r=parse(HEADER+pose(0,3200,0)+pose(3,3200,0)+motion(3.1,3200.5,0));
		Recording.Actor actor=r.sceneAt(0).actors.get("player");
		assertEquals(3,actor.sample(3).poseStart,0); assertEquals(3.1,actor.sample(3.1).poseStart,0);
	}
	@Test public void repeatedSceneryObservationsKeepPhaseUntilDespawnOrReplacement() throws Exception
	{
		String object="{\"id\":100,\"impostor_id\":101,\"x\":3200,\"y\":3200,\"plane\":0}";
		Recording r=parse(HEADER+pose(0,3200,0)+objects(0,"baseline",object)+objects(.6,"spawned",object)
			+objects(1.2,"despawned",object)+objects(1.8,"spawned",object)+objects(2.4,"spawned",object.replace("101","102")));
		List<Recording.Scenery> track=r.sceneAt(0).objects.values().iterator().next();
		assertEquals(0,Recording.at(track,.8).animationStart,0);
		assertEquals(1.8,Recording.at(track,2).animationStart,0);
		assertEquals(2.4,Recording.at(track,3).animationStart,0);
		assertEquals(0,Recording.at(track,.8).animationStart,0);
	}
	private String objects(double time,String scope,String object)
	{ return event(time,"osrs.object","{\"scope\":\""+scope+"\",\"objects\":["+object+"]}"); }
}
