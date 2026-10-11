package com.extrinsiccognition.embertide.viewer;

import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.RuneLiteObjectController;

/** The recording supplies the animation clock; RuneLite's real-time tick is ignored. */
final class ReplayObject extends RuneLiteObjectController
{
	private final Client client;
	private Model base;
	private Animation action, pose;
	private int actionFrame, poseFrame;
	private double scale = 1;
	private int width = 128, height = 128;

	ReplayObject(Client client) { this.client = client; }
	void model(Model model, double scale, int width, int height)
	{
		if (base != model || this.scale != scale || this.width != width)
		{
			double extent = 0;
			if (model != null && model.getVerticesX() != null && model.getVerticesZ() != null)
			{
				for (int i = 0; i < model.getVerticesCount(); i++)
				{ extent = Math.max(extent, Math.hypot(model.getVerticesX()[i], model.getVerticesZ()[i])); }
			}
			// The software scene renderer needs every covered tile drawn first.
			setRadius(Math.max(60, (int) Math.ceil(extent * scale * width / 128)));
		}
		this.base = model; this.scale = scale; this.width = width; this.height = height;
	}

	void animation(int actionId, int frame, int poseId, double elapsed, double poseElapsed)
	{
		action = load(actionId); pose = load(poseId);
		actionFrame = frameAt(action, frame, elapsed);
		poseFrame = frameAt(pose, 0, poseElapsed);
	}
	private Animation load(int id)
	{
		if (id < 0 || id > 1_000_000) { return null; }
		try { return client.loadAnimation(id); } catch (RuntimeException ignored) { return null; }
	}
	static int frameAt(Animation animation, int recordedFrame, double elapsed)
	{
		if (animation == null || animation.getDuration() <= 0) { return 0; }
		// Quantize replay seconds to client ticks. The tiny tolerance avoids losing a
		// tick through floating-point subtraction at exact 20 ms sample boundaries.
		long ticks = Double.isFinite(elapsed) ? (long) Math.min(1_000_000_000_000L, Math.max(0, elapsed * 50 + 1e-7)) : 0;
		if (animation.isMayaAnim())
		{
			int duration = animation.getDuration();
			long frame = Math.max(0, Math.min(duration - 1, recordedFrame)) + ticks;
			int loop = loopStart(duration, animation.getFrameStep());
			return frame < duration ? (int) frame : loop + (int) ((frame - duration) % (duration - loop));
		}
		int[] lengths = animation.getFrameLengths();
		if (lengths == null || lengths.length == 0) { return 0; }
		int start = Math.max(0, Math.min(lengths.length - 1, recordedFrame));
		int loop = loopStart(lengths.length, animation.getFrameStep());
		long firstTicks = 0, loopTicks = 0;
		for (int i = 0; i < lengths.length; i++)
		{
			if (lengths[i] <= 0) { return 0; }
			if (i >= start) { firstTicks += lengths[i]; }
			if (i >= loop) { loopTicks += lengths[i]; }
		}
		// Classic duration counts keyframes, not ticks. Only the sequence's
		// loop suffix repeats, and RuneLite advances when elapsed > frame length.
		if (ticks > firstTicks) { ticks = 1 + (ticks - firstTicks - 1) % loopTicks; start = loop; }
		for (int i = start; i < lengths.length; i++)
		{ if (ticks <= lengths[i]) { return i; } ticks -= lengths[i]; }
		return lengths.length - 1;
	}
	private static int loopStart(int frames, int step)
	{ return step > 0 && step <= frames ? frames - step : 0; }

	@Override public Model getModel()
	{
		if (base == null) { return null; }
		// applyTransformations supplies a shared scratch model, which may be returned
		// by controllers. Never resize the cached base, even for a static actor.
		Model result = client.applyTransformations(base, action, actionFrame, pose, poseFrame);
		if (result == null) { return null; }
		if (result != base)
		{
			result.scale(Math.max(1, (int) Math.round(scale * width)), Math.max(1, (int) Math.round(scale * height)), Math.max(1, (int) Math.round(scale * width)));
		}
		return result;
	}
}
