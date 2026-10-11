package com.extrinsiccognition.embertide.viewer;

/** Client-thread clock. Time advances with elapsed wall time, independent of game ticks. */
final class PlaybackClock
{
	private double time;
	private double duration;
	private double speed = 1;
	private boolean playing;
	private boolean loop = true;
	private long lastNanos;

	void load(double length, long now) { duration = Math.max(0, length); time = 0; playing = false; lastNanos = now; }
	void tick(long now)
	{
		double elapsed = Math.max(0, (now - lastNanos) / 1e9);
		lastNanos = now;
		if (!playing || duration == 0) { return; }
		time += elapsed * speed;
		if (time >= duration)
		{
			if (loop) { time %= duration; }
			else { time = duration; playing = false; }
		}
	}
	void seek(double value, long now) { time = Math.max(0, Math.min(duration, value)); lastNanos = now; }
	void play(boolean value, long now)
	{
		tick(now);
		if (value && time >= duration) { time = 0; }
		playing = value;
	}
	void speed(double value, long now) { tick(now); speed = Math.max(.1, Math.min(4, value)); }
	void loop(boolean value) { loop = value; }
	double time() { return time; }
	double duration() { return duration; }
	boolean playing() { return playing; }
}
