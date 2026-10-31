package gg.embertide.runelite;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import net.runelite.api.WorldView;

/**
 * Always clip: recent play kept in memory, and a clip file saved when something the player ticked happens, whether or
 * not they are recording.
 *
 * Two captures run into memory, started half a period apart and each started again once it is a period old, so one
 * of them always reaches at least `before` seconds back (the period is twice that). A trigger keeps the older one:
 * what it holds is written to a file and it carries on into that file until `after` seconds past the last trigger,
 * then seals as an ordinary recording with a `clip` header. Each clip is a whole recording that opens on its own,
 * made by the same code that makes every recording. Nothing reaches the disk without a trigger.
 */
final class ClipBuffers
{
	/** The longest one clip runs, triggers that keep coming included. */
	static final double MAX_CLIP_S = 120;
	/** Two triggers of one kind this close are one event, seen by both captures. */
	static final double SAME_EVENT_S = 1.5;
	/** How long a capture in memory may run before it must be started again: past any period. */
	static final double CAPTURE_MAX_S = 600;

	static final class Slot
	{
		final OsrsCapture capture;
		final ClipBuffer buffer;
		final long startedNanos;
		boolean rediscover = true;
		/** Once kept: what the clip is called. */
		String title = "";
		/** Once kept: when the clip began on the wall clock, and when it ends. */
		long clipFromNanos = -1, keepUntilNanos = -1;

		Slot(OsrsCapture capture, ClipBuffer buffer, long startedNanos)
		{
			this.capture = capture;
			this.buffer = buffer;
			this.startedNanos = startedNanos;
		}

		boolean kept() { return keepUntilNanos >= 0; }
	}

	/** A clip saved to its file: its title, the file's recorder and its length in seconds. Called off the client thread. */
	interface Sealed
	{
		void accept(String title, Recorder file, double seconds);
	}

	/** Makes a capture into memory: the buffer to write into, and the capture, already started. */
	interface Factory
	{
		OsrsCapture make(ClipBuffer buffer);
	}

	private final List<Slot> slots = new ArrayList<>();
	private final LongSupplier nanos;
	private final Factory factory;
	/** A file recorder named after the clip's reason. */
	private final Function<String, Recorder> files;
	private final Consumer<String> saved;
	private final Sealed sealed;
	private ClipTriggers.Wanted wanted;
	private ClipTriggers.Trigger pending;
	private long pendingNanos = -1;
	private String lastReason = "";
	private long lastNanos = Long.MIN_VALUE;

	/** `saved` is told a clip's title as it starts being kept, `sealed` once its file is written. */
	ClipBuffers(LongSupplier nanos, Factory factory, Function<String, Recorder> files, Consumer<String> saved, Sealed sealed)
	{
		this.nanos = nanos;
		this.factory = factory;
		this.files = files;
		this.saved = saved;
		this.sealed = sealed;
	}

	/** The seconds of a rotation: twice the look-back, never under 20. */
	static double period(int beforeS)
	{
		return Math.max(20, 2.0 * beforeS);
	}

	/** True when a capture in memory is old enough to be started again. */
	static boolean due(double ageS, double periodS)
	{
		return ageS >= periodS;
	}

	/** True when a second capture should start beside the only one: once that one is half a period old. */
	static boolean startSecond(int running, double onlyAgeS, double periodS)
	{
		return running == 0 || (running == 1 && onlyAgeS >= periodS / 2);
	}

	/** Where a kept clip ends after another trigger at `nowS`: `after` past it, and never past MAX_CLIP_S from its start. */
	static double extended(double fromS, double untilS, double nowS, double afterS)
	{
		return Math.min(fromS + MAX_CLIP_S, Math.max(untilS, nowS + afterS));
	}

	/** Every capture in memory that has its scene, for the plugin's events. */
	void each(Consumer<OsrsCapture> action)
	{
		for (Slot slot : new ArrayList<>(slots))
		{
			if (!slot.rediscover && slot.capture.recording()) { action.accept(slot.capture); }
		}
	}

	/** Every capture in memory, scene or not, for the events a capture takes before its first scene. */
	void all(Consumer<OsrsCapture> action)
	{
		for (Slot slot : new ArrayList<>(slots)) { action.accept(slot.capture); }
	}

	/** The rows the captures write, checked for a trigger; acted on at the next tick. */
	void watch(JsonObject row)
	{
		ClipTriggers.Trigger trigger = ClipTriggers.of(row, wanted);
		if (trigger == null) { return; }
		long now = nanos.getAsLong();
		boolean same = trigger.reason.equals(lastReason) && (now - lastNanos) / 1e9 < SAME_EVENT_S;
		lastReason = trigger.reason;
		lastNanos = now;
		if (same) { return; }
		if (pending == null)
		{
			pending = trigger;
			pendingNanos = now;
		}
		else
		{
			// A second trigger before the tick: the clip is the first one's, and runs past both.
			pendingNanos = Math.max(pendingNanos, now);
		}
	}

	boolean running() { return !slots.isEmpty(); }

	/** The scene changed or a load began: every capture in memory finds its scene again. */
	void sceneChanged(WorldView view)
	{
		for (Slot slot : slots)
		{
			slot.capture.sceneChanged(view);
			slot.rediscover = true;
		}
	}

	void rediscover()
	{
		for (Slot slot : slots) { slot.rediscover = true; }
	}

	/**
	 * A game tick: keep a clip a trigger asked for, seal clips whose time is up, start captures again. `can` is false
	 * while there is nothing to clip (logged out, no folder, nothing ticked): every capture in memory is dropped and a
	 * clip being kept is sealed.
	 */
	void tick(boolean can, ClipTriggers.Wanted wanted, int beforeS, int afterS)
	{
		this.wanted = wanted;
		long now = nanos.getAsLong();
		if (!can || wanted == null || !wanted.any())
		{
			stop("clips off");
			return;
		}
		takeTrigger(now, afterS);
		double period = period(beforeS);
		for (Slot slot : new ArrayList<>(slots))
		{
			double age = (now - slot.startedNanos) / 1e9;
			if (slot.kept())
			{
				if (now >= slot.keepUntilNanos || !slot.capture.recording()) { seal(slot, "clip"); }
			}
			else if (slot.capture.exhausted() || due(age, period) || age > CAPTURE_MAX_S)
			{
				drop(slot);
			}
		}
		List<Slot> open = unkept();
		double onlyAge = open.size() == 1 ? (now - open.get(0).startedNanos) / 1e9 : 0;
		if (startSecond(open.size(), onlyAge, period))
		{
			ClipBuffer buffer = new ClipBuffer(this::watch);
			OsrsCapture capture = factory.make(buffer);
			if (capture != null) { slots.add(new Slot(capture, buffer, now)); }
		}
		for (Slot slot : new ArrayList<>(slots))
		{
			if (!slot.capture.recording()) { continue; }
			boolean opening = slot.rediscover;
			if (slot.capture.tick(slot.rediscover)) { slot.rediscover = false; }
			if (opening) { slot.capture.clientTick(); }
		}
	}

	void clientTick()
	{
		for (Slot slot : slots)
		{
			if (!slot.rediscover && slot.capture.recording()) { slot.capture.clientTick(); }
		}
	}

	/** The Clip that key with no recording running: a trigger like any other, when clips run. */
	void clipKey()
	{
		for (Slot slot : slots)
		{
			if (!slot.rediscover && slot.capture.recording()) { slot.capture.clipMark(0); }
		}
	}

	/** Seals a clip being kept and drops every capture in memory. */
	void stop(String why)
	{
		pending = null;
		for (Slot slot : new ArrayList<>(slots))
		{
			if (slot.kept()) { seal(slot, why); }
			else { drop(slot); }
		}
	}

	private void takeTrigger(long now, int afterS)
	{
		ClipTriggers.Trigger trigger = pending;
		long at = pendingNanos;
		pending = null;
		if (trigger == null) { return; }
		for (Slot slot : slots)
		{
			if (slot.kept())
			{
				double from = slot.clipFromNanos / 1e9;
				slot.keepUntilNanos = (long) (extended(from, slot.keepUntilNanos / 1e9, at / 1e9, afterS) * 1e9);
				return;
			}
		}
		Slot oldest = null;
		for (Slot slot : unkept())
		{
			if (slot.capture.opened() && (oldest == null || slot.startedNanos < oldest.startedNanos)) { oldest = slot; }
		}
		if (oldest == null) { return; }
		double atS = Math.max(0, oldest.capture.seconds() - (now - at) / 1e9);
		JsonObject clip = new JsonObject();
		clip.addProperty("reason", trigger.reason);
		clip.addProperty("title", trigger.title);
		clip.addProperty("at_s", Math.round(atS * 1000) / 1000.0);
		clip.addProperty("after_s", afterS);
		Recorder file = files.apply(trigger.reason);
		if (file == null || !oldest.buffer.keep(file, clip))
		{
			drop(oldest);
			return;
		}
		oldest.clipFromNanos = at - (long) (atS * 1e9);
		oldest.keepUntilNanos = Math.min(at + (long) (afterS * 1e9), oldest.clipFromNanos + (long) (MAX_CLIP_S * 1e9));
		oldest.title = trigger.title;
		if (saved != null) { saved.accept(trigger.title); }
	}

	private List<Slot> unkept()
	{
		List<Slot> open = new ArrayList<>();
		for (Slot slot : slots) { if (!slot.kept()) { open.add(slot); } }
		return open;
	}

	private void seal(Slot slot, String why)
	{
		slots.remove(slot);
		Recorder file = slot.buffer.file();
		String title = slot.title;
		slot.capture.finish(why, false, true).whenComplete((ignored, failed) ->
		{
			if (failed == null && file != null && sealed != null) { sealed.accept(title, file, slot.capture.seconds()); }
		});
	}

	private void drop(Slot slot)
	{
		slots.remove(slot);
		slot.capture.finish("not kept", false, true);
	}
}
