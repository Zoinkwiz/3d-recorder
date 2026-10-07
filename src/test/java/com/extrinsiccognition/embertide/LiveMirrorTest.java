package com.extrinsiccognition.embertide;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import okhttp3.OkHttpClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class LiveMirrorTest
{
	private static final String LIVE = "Showing live in Embertide on this computer.";

	/** The file the mirror writes beside, which takes everything. */
	private static final class File implements Recorder
	{
		final List<JsonObject> rows = new ArrayList<>();

		@Override
		public State state()
		{
			return State.READY;
		}

		@Override
		public String error()
		{
			return "";
		}

		@Override
		public String captureId()
		{
			return "file";
		}

		@Override
		public CompletableFuture<Void> open(JsonObject header)
		{
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public boolean enqueue(JsonObject record, boolean control)
		{
			rows.add(record);
			return true;
		}

		@Override
		public CompletableFuture<Void> flush()
		{
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public CompletableFuture<Void> finish()
		{
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public CompletableFuture<Void> openStudio()
		{
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public String destination()
		{
			return "";
		}
	}

	private final Gson gson = new Gson();
	private final List<FakeApp> apps = new ArrayList<>();
	private final File file = new File();
	private ScheduledExecutorService executor;
	private OkHttpClient http;
	private MccrSession session;
	private String home;
	private Path fakeHome;
	private int row;

	@Before
	public void setUp() throws Exception
	{
		executor = Executors.newSingleThreadScheduledExecutor();
		http = new OkHttpClient();
		session = new MccrSession("osrs:surface", OsrsCapture.MAX_SECONDS, Instant.parse("2026-10-20T18:00:00Z"), 0);
		home = System.getProperty("user.home");
		fakeHome = Files.createTempDirectory("live-mirror-home");
		System.setProperty("user.home", fakeHome.toString());
	}

	@After
	public void tearDown() throws Exception
	{
		for (FakeApp app : apps)
		{
			app.close();
		}
		executor.shutdownNow();
		http.dispatcher().executorService().shutdownNow();
		http.connectionPool().evictAll();
		System.setProperty("user.home", home);
		try (Stream<Path> files = Files.walk(fakeHome))
		{
			files.sorted((a, b) -> b.compareTo(a)).forEach(path -> path.toFile().delete());
		}
	}

	@Test
	public void saysItIsReconnectingWhileTheAppIsAwayAndLiveOnceItIsBack() throws Exception
	{
		FakeApp.Disk disk = new FakeApp.Disk();
		FakeApp first = app(disk).publish();
		LiveMirror mirror = new LiveMirror(file, new MccrRecorderClient(http, gson, executor, null));
		mirror.open(header());
		awaitNote(mirror, LIVE);
		first.close();
		awaitNote(mirror, "Reconnecting the live view to Embertide…");
		app(disk).publish();
		awaitNote(mirror, LIVE);
		await(() -> disk.rows.size() == file.rows.size(), 5_000);
	}

	@Test
	public void tellsThePanelWhyTheAppRefusedTheLiveView() throws Exception
	{
		FakeApp app = app(new FakeApp.Disk());
		LiveMirror mirror = new LiveMirror(file, new MccrRecorderClient(http, gson, executor,
			new RecorderEndpoint(app.endpoint().getUrl(), "not-the-pairing-secret", 1)));
		mirror.open(header());
		awaitNote(mirror, "Live view stopped: Recording authentication failed. The recording is unaffected.");
	}

	@Test
	public void stopsTheLinkAndKeepsRecordingWhenTheLiveViewGivesUp() throws Exception
	{
		FakeApp app = app(new FakeApp.Disk()).publish();
		MccrRecorderClient live = new MccrRecorderClient(http, gson, executor, null);
		LiveMirror mirror = new LiveMirror(file, live);
		mirror.open(header());
		awaitNote(mirror, LIVE);
		int written = file.rows.size();
		JsonObject payload = new JsonObject();
		payload.addProperty("pad", new String(new char[MccrRecorderClient.MAX_BATCH_BYTES]).replace('\0', 'x'));
		assertTrue(mirror.enqueue(session.record("osrs.motion", 0, payload, OsrsCapture.PLAYER), false));
		assertEquals("Live view stopped: A row was too large for the live view. The recording is unaffected.", mirror.liveNote());
		assertEquals(MccrRecorderClient.Lifetime.CLOSED, live.lifetime());
		await(() -> app.lifetimesOpen.get() == 0, 5_000);
		int appends = app.appends.size();
		assertTrue(mirror.enqueue(session.record("osrs.motion", 1, new JsonObject(), OsrsCapture.PLAYER), false));
		Thread.sleep(MccrRecorderClient.FLUSH_MS * 3);
		assertEquals(appends, app.appends.size());
		assertEquals(written + 2, file.rows.size());
	}

	private FakeApp app(FakeApp.Disk disk) throws Exception
	{
		FakeApp app = new FakeApp(disk);
		apps.add(app);
		return app;
	}

	private JsonObject header()
	{
		JsonObject header = new JsonObject();
		header.addProperty("type", "mccr.header");
		header.addProperty("format", MccrSession.FORMAT);
		header.addProperty("game", MccrSession.GAME);
		header.addProperty("started_at", session.capturedAt.toString());
		JsonObject identity = new JsonObject();
		identity.addProperty("id", session.id);
		header.add("session", identity);
		return header;
	}

	/** Feeds a row every 50 ms, as a capture does, until the panel says `note`. */
	private void awaitNote(LiveMirror mirror, String note) throws InterruptedException
	{
		long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
		while (!note.equals(mirror.liveNote()))
		{
			if (System.nanoTime() > until)
			{
				fail("the panel said \"" + mirror.liveNote() + "\", not \"" + note + "\"");
			}
			JsonObject payload = new JsonObject();
			payload.addProperty("n", row);
			mirror.enqueue(session.record("osrs.motion", row++ * 0.05, payload, OsrsCapture.PLAYER), false);
			Thread.sleep(50);
		}
	}

	private static void await(BooleanSupplier condition, long ms) throws InterruptedException
	{
		long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms);
		while (!condition.getAsBoolean())
		{
			if (System.nanoTime() > until)
			{
				fail("not within " + ms + " ms");
			}
			Thread.sleep(10);
		}
	}
}
