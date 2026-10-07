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
		final double cpuMean, cpuP99, frameMean, frameP99, cycleMean, cycleP99, wallMean, wallP99, bytes, calls, rows;

		Result(int actors, Clock window, Clock frame, Clock cycle, double bytes, double calls, double rows)
		{
			this.actors = actors;
			cpuMean = mean(window.cpu) / 1000;
			cpuP99 = p99(window.cpu) / 1000;
			frameMean = mean(frame.cpu) / 1000;
			frameP99 = p99(frame.cpu) / 1000;
			cycleMean = mean(cycle.cpu) / 1000;
			cycleP99 = p99(cycle.cpu) / 1000;
			wallMean = mean(window.wall) / 1000;
			wallP99 = p99(window.wall) / 1000;
			this.bytes = bytes;
			this.calls = calls;
			this.rows = rows;
		}

		static String heading()
		{
			return "actors | cpu us a tick, mean p99 | game-tick frame | client cycle | wall us, mean p99 | bytes a tick | client calls | rows";
		}

		@Override
		public String toString()
		{
			return String.format(Locale.ROOT, "%6d | %7.0f %7.0f | %6.0f %6.0f | %5.1f %5.1f | %7.0f %7.0f | %9.0f | %6.0f | %5.1f",
				actors, cpuMean, cpuP99, frameMean, frameP99, cycleMean, cycleP99, wallMean, wallP99, bytes, calls, rows);
		}
	}

	/** Thread CPU and wall time per sample. */
	static final class Clock
	{
		final long[] cpu, wall;

		Clock(int samples)
		{
			cpu = new long[samples];
			wall = new long[samples];
		}
	}

	@Test
	public void aRaidTickStaysInItsBudget()
	{
		Result raid = run(30, 40, 40);
		// A game tick at 30 actors, with its 30 client cycles: 2.6 MB and 22,300 client calls before these were cut.
		assertTrue("bytes a tick " + raid.bytes, raid.bytes < 1_500_000);
		assertTrue("client calls a tick " + raid.calls, raid.calls < 12_000);
	}

	public static void main(String[] args)
	{
		int ticks = args.length > 0 ? Integer.parseInt(args[0]) : 300;
		System.out.println("A game tick is 600 ms of play: the tick, its events and its 30 client cycles. The game-tick frame is the tick and its events.");
		System.out.println(Result.heading());
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
		Clock window = new Clock(ticks), frame = new Clock(ticks), cycles = new Clock(ticks * RaidScene.CYCLES_PER_TICK);
		long bytes = 0, calls = 0, rows = 0;
		long overhead = allocated();
		overhead = allocated() - overhead;
		for (int i = 0; i < warmup + ticks; i++)
		{
			// The app has not answered yet, so the live queue fills: start a fresh pair before it does.
			if (i % 4 == 0) { recorder.swap(capture); }
			int at = i - warmup;
			scene.serverTick();
			long calls0 = scene.calls, rows0 = recorder.rows;
			long b0 = allocated(), c0 = cpu(), w0 = System.nanoTime();
			capture.tick(false);
			scene.tickEvents(capture);
			long wall = System.nanoTime() - w0, used = cpu() - c0, made = allocated() - b0 - overhead;
			if (at >= 0) { frame.cpu[at] = used; frame.wall[at] = wall; }
			for (int c = 0; c < RaidScene.CYCLES_PER_TICK; c++)
			{
				scene.clientCycle();
				long cb = allocated(), cc = cpu(), cw = System.nanoTime();
				capture.clientTick();
				scene.cycleEvents(capture);
				long cycleWall = System.nanoTime() - cw, cycleCpu = cpu() - cc;
				made += allocated() - cb - overhead;
				wall += cycleWall;
				used += cycleCpu;
				if (at >= 0) { cycles.cpu[at * RaidScene.CYCLES_PER_TICK + c] = cycleCpu; cycles.wall[at * RaidScene.CYCLES_PER_TICK + c] = cycleWall; }
			}
			if (at >= 0)
			{
				window.cpu[at] = used;
				window.wall[at] = wall;
				bytes += made;
				calls += scene.calls - calls0;
				rows += recorder.rows - rows0;
			}
		}
		if (!capture.recording()) { throw new IllegalStateException("the capture stopped: " + capture.reason()); }
		return new Result(actors, window, frame, cycles, bytes / (double) ticks, calls / (double) ticks, rows / (double) ticks);
	}

	private static long allocated()
	{
		return THREADS.getThreadAllocatedBytes(Thread.currentThread().getId());
	}

	private static long cpu()
	{
		return THREADS.getCurrentThreadCpuTime();
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
