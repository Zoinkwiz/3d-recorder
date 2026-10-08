package com.extrinsiccognition.embertide.viewer;

import net.runelite.api.Animation;
import net.runelite.api.AnimationController;
import net.runelite.api.Client;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class AnimationPlaybackTest
{
	static Animation classic(int step, int... lengths)
	{
		Animation a=mock(Animation.class);
		when(a.getDuration()).thenReturn(lengths.length); when(a.getNumFrames()).thenReturn(lengths.length);
		when(a.getFrameLengths()).thenReturn(lengths); when(a.getFrameStep()).thenReturn(step); return a;
	}
	static Animation maya(int frames,int step)
	{
		Animation a=mock(Animation.class); when(a.isMayaAnim()).thenReturn(true);
		when(a.getDuration()).thenReturn(frames); when(a.getNumFrames()).thenReturn(frames); when(a.getFrameStep()).thenReturn(step); return a;
	}
	@Test public void slowKeyframesFinishBeforeTheAnimationLoops()
	{
		Animation a=classic(4,10,10,10,10);
		assertEquals(0,ReplayObject.frameAt(a,0,.10));
		assertEquals(1,ReplayObject.frameAt(a,0,.30));
		assertEquals(2,ReplayObject.frameAt(a,0,.50));
		assertEquals(3,ReplayObject.frameAt(a,0,.80));
		assertEquals(0,ReplayObject.frameAt(a,0,.82));
	}
	@Test public void matchesNativeClassicTicksIncludingPartialAndFallbackLoops()
	{
		for (int step:new int[]{4,2,-1,0,7})
		{ for (int start:new int[]{0,2})
		{ assertNativeTicks(classic(step,3,11,6,4),start); } }
	}
	@Test public void matchesNativeMayaTicksAcrossManyLoopsAndSeeks()
	{
		for (int step:new int[]{16,5,-1,0,21})
		{ for (int start:new int[]{0,9})
		{ assertNativeTicks(maya(16,step),start); } }
	}
	private void assertNativeTicks(Animation a,int start)
	{
		Client client=mock(Client.class); AnimationController nativeClock=new AnimationController(client,a);
		nativeClock.setFrame(start); int[] expected=new int[1501]; expected[0]=start;
		for (int tick=1;tick<expected.length;tick++) { nativeClock.tick(1); expected[tick]=nativeClock.getFrame(); }
		// Independent native tick progression is the oracle; the viewer is sampled out of order.
		for (int tick=expected.length-1;tick>=0;tick--)
		{ assertEquals("tick "+tick+", step "+a.getFrameStep(),expected[tick],ReplayObject.frameAt(a,start,tick/50.0)); }
	}
	@Test public void longSeeksRetainTheLoopSuffixInsteadOfReplayingTheIntro()
	{
		Animation a=classic(2,3,11,6,4);
		assertEquals(2,ReplayObject.frameAt(a,0,20000.50));
		assertEquals(3,ReplayObject.frameAt(a,0,20000.62));
		assertEquals(2,ReplayObject.frameAt(a,0,20000.70));
		assertEquals(12,ReplayObject.frameAt(maya(16,5),0,20000.04));
		assertEquals(25,ReplayObject.frameAt(maya(100,100),20,.1));
	}
	@Test public void pauseAndNegativeTimeDoNotAdvanceFrames()
	{
		Animation a=classic(3,5,10,15);
		assertEquals(2,ReplayObject.frameAt(a,2,-1));
		int paused=ReplayObject.frameAt(a,0,.56);
		for (int i=0;i<100;i++) { assertEquals(paused,ReplayObject.frameAt(a,0,.56)); }
		assertEquals(0,ReplayObject.frameAt(a,0,Double.NaN));
	}
	@Test public void incompleteAnimationDataCannotBreakPlayback()
	{
		assertEquals(0,ReplayObject.frameAt(null,0,1));
		assertEquals(0,ReplayObject.frameAt(null,20,100));
		assertEquals(0,ReplayObject.frameAt(classic(2,0,5),0,1));
		Animation a=mock(Animation.class); when(a.getDuration()).thenReturn(5);
		assertEquals(0,ReplayObject.frameAt(a,0,1));
	}
}
