package com.extrinsiccognition.embertide;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.lang.management.ManagementFactory;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.runelite.client.util.Filepath;
import org.junit.Test;
import static org.junit.Assert.assertTrue;

/**
 * The plugin's client-thread cost in a raid: each game tick with its 30 client cycles and the projectiles,
 * hitsplats and sounds between them, written to the file and mirrored live as a streamer runs it.
 * {@code ./gradlew bench} prints the table at 1, 8 and 30 actors; the test holds a raid tick to its budget.
 */
public class RaidTickBench
{
	private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
	private static final Gson GSON = new Gson();

	static final class Result
	{
		final int actors;
		final double meanMicros, p99Micros, tickMicros, cycleMicros, cycleP99Micros, bytes, calls, rows;

		Result(int actors, long[] nanos, long[] tickNanos, long[] cycleNanos, double bytes, double calls, double rows)
		{
			this.actors = actors;
			this.meanMicros = mean(nanos) / 1000;
			this.p99Micros = p99(nanos) / 1000;
			this.tickMicros = mean(tickNanos) / 1000;
			this.cycleMicros = mean(cycleNanos) / 1000;
			this.cycleP99Micros = p99(cycleNanos) / 1000;
			this.bytes = bytes;
			this.calls = calls;
			this.rows = rows;
		}

		@Override
		public String toString()
		{
			return String.format(Locale.ROOT, "%2d actors | %8.1f | %8.1f | %7.1f | %6.1f | %6.1f | %9.0f | %6.0f | %5.1f",
				actors, meanMicros, p99Micros, tickMicros, cycleMicros, cycleP99Micros, bytes, calls, rows);
		}
	}

	@Test
	public void aRaidTickStaysInItsBudget()
	{
		Result raid = run(30, 40, 40);
		assertTrue("rows " + raid.rows, raid.rows > 30);
	}

	public static void main(String[] args)
	{
		int ticks = args.length > 0 ? Integer.parseInt(args[0]) : 300;
		System.out.println("per game tick (600 ms of play): mean us | p99 us | tick us | cycle us | cycle p99 us | bytes | client calls | rows");
		for (int actors : new int[]{1, 8, 30})
		{
			run(actors, ticks, ticks);
			System.out.println(run(actors, ticks, ticks));
		}
	}

	static Result run(int actors, int warmup, int ticks)
	{
		RaidScene scene = new RaidScene(actors, 7);
		Swapped recorder = new Swapped();
		OsrsCapture capture = scene.capture(recorder);
		recorder.swap(capture);
		capture.start();
		capture.tick(true);
		capture.clientTick();
		long[] nanos = new long[ticks], tickNanos = new long[ticks], cycleNanos = new long[ticks * RaidScene.CYCLES_PER_TICK];
		long bytes = 0, calls = 0, rows = 0;
		long overhead = allocated();
		overhead = allocated() - overhead;
		for (int i = 0; i < warmup + ticks; i++)
		{
			// The app has not answered yet, so the live queue fills: start a fresh pair before it does.
			if (i % 4 == 0) { recorder.swap(capture); }
			boolean measured = i >= warmup;
			int at = i - warmup;
			scene.serverTick();
			long calls0 = scene.calls, rows0 = recorder.rows;
			long b0 = allocated(), t0 = System.nanoTime();
			capture.tick(false);
			scene.tickEvents(capture);
			long spent = System.nanoTime() - t0, made = allocated() - b0 - overhead;
			long counted = scene.calls - calls0, written = recorder.rows - rows0;
			if (measured) { tickNanos[at] = spent; }
			for (int c = 0; c < RaidScene.CYCLES_PER_TICK; c++)
			{
				scene.clientCycle();
				long cycleCalls = scene.calls, cycleRows = recorder.rows;
				long cb = allocated(), ct = System.nanoTime();
				capture.clientTick();
				scene.cycleEvents(capture);
				long cycle = System.nanoTime() - ct;
				made += allocated() - cb - overhead;
				counted += scene.calls - cycleCalls;
				written += recorder.rows - cycleRows;
				spent += cycle;
				if (measured) { cycleNanos[at * RaidScene.CYCLES_PER_TICK + c] = cycle; }
			}
			if (measured)
			{
				nanos[at] = spent;
				bytes += made;
				calls += counted;
				rows += written;
			}
		}
		if (!capture.recording()) { throw new IllegalStateException("the capture stopped: " + capture.reason()); }
		return new Result(actors, nanos, tickNanos, cycleNanos, bytes / (double) ticks, calls / (double) ticks, rows / (double) ticks);
	}

	private static long allocated()
	{
		return THREADS.getThreadAllocatedBytes(Thread.currentThread().getId());
	}

	private static double mean(long[] values)
	{
		return Arrays.stream(values).average().orElse(0);
	}

	private static double p99(long[] values)
	{
		long[] sorted = values.clone();
		Arrays.sort(sorted);
		return sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * 0.99) - 1)];
	}

	/** The streamer's recorders, replaced by a fresh pair now and then outside the measured work. */
	private static final class Swapped implements Recorder
	{
		private Recorder current;
		long rows;

		void swap(OsrsCapture capture)
		{
			current = RaidScene.streamerRecorder(GSON, Filepath.Unchecked.getRooted(Paths.get("/nonexistent/raid-bench")), task -> { });
			current.open(capture.header());
		}

		@Override public State state() { return current.state(); }
		@Override public String error() { return current.error(); }
		@Override public String captureId() { return current.captureId(); }
		@Override public CompletableFuture<Void> open(JsonObject header) { return CompletableFuture.completedFuture(null); }
		@Override public boolean enqueue(JsonObject record, boolean control) { rows++; return current.enqueue(record, control); }
		@Override public boolean full() { return current.full(); }
		@Override public CompletableFuture<Void> flush() { return current.flush(); }
		@Override public CompletableFuture<Void> finish() { return CompletableFuture.completedFuture(null); }
		@Override public CompletableFuture<Void> openStudio() { return CompletableFuture.completedFuture(null); }
		@Override public String destination() { return ""; }
	}
}
