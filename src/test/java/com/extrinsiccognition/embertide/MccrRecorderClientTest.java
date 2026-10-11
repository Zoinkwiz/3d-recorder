package com.extrinsiccognition.embertide;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.net.ServerSocket;
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

public class MccrRecorderClientTest
{
	private final Gson gson = new Gson();
	private final List<FakeApp> apps = new ArrayList<>();
	private final List<String> sent = new ArrayList<>();
	// One thread, as RuneLite's shared executor is.
	private ScheduledExecutorService executor;
	private OkHttpClient http;
	private MccrSession session;
	private String home;
	private Path fakeHome;

	@Before
	public void setUp() throws Exception
	{
		executor = Executors.newSingleThreadScheduledExecutor();
		http = new OkHttpClient();
		session = new MccrSession("osrs:surface", OsrsCapture.MAX_SECONDS, Instant.parse("2026-10-20T18:00:00Z"), 0);
		// endpoint.json is looked up under the user's home.
		home = System.getProperty("user.home");
		fakeHome = Files.createTempDirectory("live-link-home");
		System.setProperty("user.home", fakeHome.toString());
		System.setProperty(RecorderEndpoint.DATA_HOME_PROPERTY, fakeHome.toString());
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
		System.clearProperty(RecorderEndpoint.DATA_HOME_PROPERTY);
		try (Stream<Path> files = Files.walk(fakeHome))
		{
			files.sorted((a, b) -> b.compareTo(a)).forEach(path -> path.toFile().delete());
		}
	}

	@Test
	public void redialsTheLifetimeSocketWithinTheAppsGrace() throws Exception
	{
		FakeApp app = app(new FakeApp.Disk()).publish();
		MccrRecorderClient client = new MccrRecorderClient(http, gson, executor, app.endpoint());
		client.open(header()).get(5, TimeUnit.SECONDS);
		await(() -> app.lifetimesOpen.get() == 1, 5_000);
		long dropped = System.nanoTime();
		app.dropLifetimes();
		await(() -> app.lifetimeAuths.get() == 2 && app.lifetimesOpen.get() == 1, 5_000);
		long backMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - dropped);
		// The app calls a capture ended after a second without its socket.
		assertTrue("redialed after " + backMs + " ms", backMs < 1_000);
		await(() -> client.lifetime() == MccrRecorderClient.Lifetime.CONNECTED, 2_000);
	}

	@Test
	public void carriesACaptureIntoARestartedAppWithoutLosingOrRepeatingRows() throws Exception
	{
		FakeApp.Disk disk = new FakeApp.Disk();
		FakeApp first = app(disk).publish();
		MccrRecorderClient client = new MccrRecorderClient(http, gson, executor, first.endpoint());
		client.open(header()).get(5, TimeUnit.SECONDS);
		rows(client, 0, 20);
		await(() -> disk.rows.size() == 20, 5_000);
		first.close();
		// Rows go on while the app is away.
		for (int i = 20; i < 40; i++)
		{
			rows(client, i, i + 1);
			Thread.sleep(25);
		}
		FakeApp second = app(disk).publish();
		rows(client, 40, 60);
		await(() -> disk.rows.size() == 60, 10_000);
		assertEquals(sent, disk.rows);
		assertEquals(first.opens.get(0), second.opens.get(0));
		await(() -> second.lifetimesOpen.get() == 1, 5_000);
		assertEquals(Recorder.State.READY, client.state());
	}

	@Test
	public void goesLiveWhenTheAppOpensAfterTheCaptureStarted() throws Exception
	{
		MccrRecorderClient client = new MccrRecorderClient(http, gson, executor, null);
		client.open(header());
		for (int i = 0; i < 10; i++)
		{
			rows(client, i, i + 1);
			Thread.sleep(25);
		}
		FakeApp.Disk disk = new FakeApp.Disk();
		FakeApp app = app(disk).publish();
		rows(client, 10, 20);
		await(() -> disk.rows.size() == 20, 10_000);
		assertEquals(sent, disk.rows);
		await(() -> app.lifetimesOpen.get() == 1, 5_000);
		assertEquals(Recorder.State.READY, client.state());
	}

	@Test
	public void aBatchTheAppKeptWithoutReplyingIsNotSentAsNewRows() throws Exception
	{
		FakeApp.Disk disk = new FakeApp.Disk();
		FakeApp first = app(disk).publish();
		MccrRecorderClient client = new MccrRecorderClient(http, gson, executor, first.endpoint());
		client.open(header()).get(5, TimeUnit.SECONDS);
		rows(client, 0, 5);
		await(() -> disk.rows.size() == 5, 5_000);
		first.dieAfterNextCommit = true;
		rows(client, 5, 10);
		await(() -> first.closed, 5_000);
		FakeApp second = app(disk).publish();
		rows(client, 10, 15);
		await(() -> disk.rows.size() == 15, 10_000);
		Thread.sleep(300);
		assertEquals(sent, disk.rows);
		// The lost batch went again unchanged, and the app took it as a retry.
		assertEquals(first.appends.get(first.appends.size() - 1), second.appends.get(0));
		assertEquals(1, second.opens.size());
		assertEquals(Recorder.State.READY, client.state());
	}

	@Test
	public void neverHoldsTheSharedExecutorWhileTheAppIsAway() throws Exception
	{
		MccrRecorderClient client = new MccrRecorderClient(http, gson, executor, nobody());
		client.open(header());
		long worst = 0;
		long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		int row = 0;
		while (System.nanoTime() < until)
		{
			rows(client, row, ++row);
			long asked = System.nanoTime();
			executor.submit(() -> { }).get(10, TimeUnit.SECONDS);
			worst = Math.max(worst, System.nanoTime() - asked);
			Thread.sleep(50);
		}
		assertTrue("the executor waited " + TimeUnit.NANOSECONDS.toMillis(worst) + " ms", worst < TimeUnit.MILLISECONDS.toNanos(400));
	}

	@Test
	public void givesUpTheLiveViewWhenTheAppIsAwayLongerThanItsRowsFit() throws Exception
	{
		MccrRecorderClient client = new MccrRecorderClient(http, gson, executor, nobody());
		CompletableFuture<Void> opened = client.open(header());
		long held = 0;
		JsonObject payload = new JsonObject();
		payload.addProperty("pad", new String(new char[4000]).replace('\0', 'x'));
		while (true)
		{
			JsonObject row = session.record("osrs.motion", 0, payload, OsrsCapture.PLAYER);
			if (!client.enqueue(row, false))
			{
				break;
			}
			held += gson.toJson(row).length() + 1;
		}
		assertTrue("held " + held, held > MccrRecorderClient.MAX_PENDING_BYTES - 16_000 && held <= MccrRecorderClient.MAX_PENDING_BYTES);
		assertEquals(Recorder.State.FAILED, client.state());
		assertEquals("Embertide is not open.", client.error());
		assertTrue(opened.isCompletedExceptionally());
	}

	@Test
	public void sealsAPartWhileTheAppIsAwayOnceItComesBack() throws Exception
	{
		FakeApp.Disk disk = new FakeApp.Disk();
		FakeApp first = app(disk).publish();
		MccrRecorderClient client = new MccrRecorderClient(http, gson, executor, first.endpoint());
		client.open(header()).get(5, TimeUnit.SECONDS);
		rows(client, 0, 10);
		await(() -> disk.rows.size() == 10, 5_000);
		first.close();
		rows(client, 10, 15);
		JsonObject end = session.record("mccr.capture_end", 9.0, new JsonObject(), OsrsCapture.PLAYER);
		sent.add(gson.toJson(end));
		assertTrue(client.enqueue(end, true));
		CompletableFuture<Void> finished = client.finish();
		Thread.sleep(2_000);
		assertEquals(Recorder.State.FINISHING, client.state());
		app(disk).publish();
		finished.get(15, TimeUnit.SECONDS);
		assertEquals(sent, disk.rows);
		assertEquals(Recorder.State.COMPLETE, client.state());
	}

	@Test
	public void aSealedPartWaitsForTheAppAsLongAsALivePartsRowsWouldLast()
	{
		// The measured rate the away budget is sized at.
		long rowsLastMs = MccrRecorderClient.MAX_PENDING_BYTES * 1000L / (40 * 1024);
		assertTrue("a sealed part gives up after " + MccrRecorderClient.FINISH_BUDGET_MS + " ms, a live part's rows last " + rowsLastMs + " ms",
			MccrRecorderClient.FINISH_BUDGET_MS >= rowsLastMs);
	}

	@Test
	public void aFirstOpenTheAppRefusesEndsTheLiveViewWithTheAppsReason() throws Exception
	{
		FakeApp app = app(new FakeApp.Disk());
		MccrRecorderClient client = new MccrRecorderClient(http, gson, executor,
			new RecorderEndpoint(app.endpoint().getUrl(), "not-the-pairing-secret", 1));
		CompletableFuture<Void> opened = client.open(header());
		await(opened::isDone, 5_000);
		assertTrue(opened.isCompletedExceptionally());
		assertEquals(Recorder.State.FAILED, client.state());
		assertEquals("Recording authentication failed.", client.error());
		Thread.sleep(1_000);
		assertEquals(1, app.opens.size());
	}

	@Test
	public void aRefusalAfterTheCaptureOpenedOpensItAgain() throws Exception
	{
		FakeApp app = app(new FakeApp.Disk()).publish();
		MccrRecorderClient client = new MccrRecorderClient(http, gson, executor, app.endpoint());
		client.open(header()).get(5, TimeUnit.SECONDS);
		rows(client, 0, 5);
		await(() -> app.disk.rows.size() == 5, 5_000);
		app.forget();
		rows(client, 5, 10);
		await(() -> app.disk.rows.size() == 10, 10_000);
		assertEquals(sent, app.disk.rows);
		assertEquals(2, app.opens.size());
		await(() -> app.lifetimeAuths.get() == 2 && client.lifetime() == MccrRecorderClient.Lifetime.CONNECTED, 5_000);
		assertEquals(Recorder.State.READY, client.state());
	}

	@Test
	public void stopGivesUpAClientStillLookingForTheApp() throws Exception
	{
		MccrRecorderClient client = new MccrRecorderClient(http, gson, executor, null);
		CompletableFuture<Void> opened = client.open(header());
		rows(client, 0, 5);
		Thread.sleep(300);
		client.stop("Live view turned off.");
		assertEquals(Recorder.State.FAILED, client.state());
		assertEquals("Live view turned off.", client.error());
		assertTrue(opened.isCompletedExceptionally());
		assertEquals(MccrRecorderClient.Lifetime.CLOSED, client.lifetime());
		FakeApp app = app(new FakeApp.Disk()).publish();
		// Longer than the longest backoff.
		Thread.sleep(MccrRecorderClient.MAX_BACKOFF_MS + 500);
		assertEquals(0, app.opens.size());
	}

	@Test
	public void sendsTheHeaderAndRowsExactlyAsRecorded() throws Exception
	{
		FakeApp app = app(new FakeApp.Disk()).publish();
		MccrRecorderClient client = new MccrRecorderClient(http, gson, executor, app.endpoint());
		JsonObject header = header();
		client.open(header).get(5, TimeUnit.SECONDS);
		rows(client, 0, 2);
		await(() -> app.disk.rows.size() == 2, 5_000);
		assertEquals("{\"header\":" + gson.toJson(header) + "}", app.opens.get(0));
		StringBuilder records = new StringBuilder();
		for (int i = 0; i < app.appends.size(); i++)
		{
			String body = app.appends.get(i);
			String head = "{\"sequence\":" + i + ",\"records\":[";
			assertTrue(body, body.startsWith(head) && body.endsWith("]}"));
			records.append(records.length() == 0 ? "" : ",").append(body, head.length(), body.length() - 2);
		}
		assertEquals(String.join(",", sent), records.toString());
		String own = sent.get(0).replace(session.id, "osrs-test");
		assertEquals("{\"id\":\"osrs-test:r1\",\"ts\":\"2026-10-20T18:00:00Z\",\"elapsed_s\":0.0,\"source\":\"osrs.runelite\","
			+ "\"confidence\":1,\"session_id\":\"osrs-test/player\",\"user_id\":\"player\","
			+ "\"salience_hints\":{\"rule_score\":0,\"first_time\":false,\"danger\":0},\"type\":\"osrs.motion\","
			+ "\"payload\":{\"n\":0}}", own);
	}

	private FakeApp app(FakeApp.Disk disk) throws Exception
	{
		FakeApp app = new FakeApp(disk);
		apps.add(app);
		return app;
	}

	/** An endpoint nothing listens on. */
	private static RecorderEndpoint nobody() throws Exception
	{
		try (ServerSocket socket = new ServerSocket(0))
		{
			return new RecorderEndpoint("http://127.0.0.1:" + socket.getLocalPort(), FakeApp.TOKEN, 1);
		}
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

	/** Rows from..to-1, each kept as the JSON the app should store. */
	private void rows(MccrRecorderClient client, int from, int to)
	{
		for (int i = from; i < to; i++)
		{
			JsonObject payload = new JsonObject();
			payload.addProperty("n", i);
			JsonObject row = session.record("osrs.motion", i * 0.6, payload, OsrsCapture.PLAYER);
			sent.add(gson.toJson(row));
			assertTrue("row " + i + " refused: " + client.error(), client.enqueue(row, false));
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
