package com.extrinsiccognition.embertide.viewer;

import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackClockTest
{
	@Test public void pausedClockDoesNotAdvance()
	{ PlaybackClock c = new PlaybackClock(); c.load(10, 0); c.tick(5_000_000_000L); assertEquals(0, c.time(), 0); }
	@Test public void playbackSpeedAndPauseAccountForTimeBeforeChange()
	{
		PlaybackClock c = new PlaybackClock(); c.load(20, 0); c.play(true, 0);
		c.speed(2, 1_000_000_000L); c.play(false, 2_000_000_000L); c.tick(10_000_000_000L);
		assertEquals(3, c.time(), 0);
	}
	@Test public void loopPreservesOvershoot()
	{ PlaybackClock c = new PlaybackClock(); c.load(10, 0); c.play(true, 0); c.tick(22_000_000_000L); assertEquals(2, c.time(), 0); }
	@Test public void nonLoopingStopsAndCanRestart()
	{
		PlaybackClock c = new PlaybackClock(); c.load(2, 0); c.loop(false); c.play(true, 0); c.tick(3_000_000_000L);
		assertEquals(2, c.time(), 0); assertFalse(c.playing()); c.play(true, 4_000_000_000L); assertEquals(0, c.time(), 0);
	}
	@Test public void seekingDoesNotCarryPreviousElapsedTime()
	{
		PlaybackClock c = new PlaybackClock(); c.load(20, 0); c.play(true, 0); c.seek(4, 10_000_000_000L);
		c.tick(11_000_000_000L); assertEquals(5, c.time(), 0); c.seek(-2, 11_000_000_000L); assertEquals(0, c.time(), 0);
	}
	@Test public void emptyRecordingDoesNotDivideByZero()
	{ PlaybackClock c = new PlaybackClock(); c.load(0, 0); c.play(true, 0); c.tick(1_000_000_000L); assertEquals(0, c.time(), 0); }
}
