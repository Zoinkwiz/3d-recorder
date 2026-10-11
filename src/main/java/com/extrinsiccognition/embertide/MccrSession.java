package com.extrinsiccognition.embertide;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class MccrSession
{
	public static final String FORMAT = "mccr/0.1";
	public static final String PROFILE = "ec.mccr.spatial/1";
	public static final String SOURCE = "osrs.runelite";
	/** The plugin version, as build.gradle and runelite-plugin.properties give it. */
	public static final String VERSION = "0.5.0";
	public static final String RECORDER = "embertide-runelite/" + VERSION;
	public static final String GAME = "osrs";

	public final String id;
	public Instant capturedAt;
	/** Current coordinate space; the file header retains the initial dimension. */
	public String dimension;
	private long startedNanos;
	private final double maxSeconds;
	private int serial;
	private double lastRecordTime;
	private LongSupplier nanos = System::nanoTime;
	private Supplier<Instant> wall = Instant::now;
	// The rows of a tick share one time; its text is formatted once.
	private Instant tsBase;
	private double tsTime = -1;
	private String ts;
	private final Map<String, String> sessionIds = new HashMap<>();

	public MccrSession(String dimension, double maxSeconds)
	{
		this(dimension, maxSeconds, Instant.now(), System.nanoTime());
	}

	MccrSession(String dimension, double maxSeconds, Instant capturedAt, long startedNanos)
	{
		this.id = "osrs-" + UUID.randomUUID();
		this.dimension = dimension;
		this.maxSeconds = maxSeconds;
		this.capturedAt = capturedAt;
		this.startedNanos = startedNanos;
	}

	/** Tests drive the clock. */
	void clock(LongSupplier nanos, Supplier<Instant> wall)
	{
		this.nanos = nanos;
		this.wall = wall;
	}

	public void rebase()
	{
		rebase(wall.get(), nanos.getAsLong());
	}

	void rebase(Instant capturedAt, long startedNanos)
	{
		this.capturedAt = capturedAt;
		this.startedNanos = startedNanos;
	}

	public double time()
	{
		return time(nanos.getAsLong());
	}

	double time(long nowNanos)
	{
		double seconds = (nowNanos - startedNanos) / 1_000_000_000.0;
		seconds = Math.max(0, Math.min(maxSeconds, seconds));
		return Math.round(seconds * 10000.0) / 10000.0;
	}

	public JsonObject record(String type, double t, JsonObject payload, String user)
	{
		// Registering a newly observed event actor can emit rows inside a callback
		// that already sampled its time. Keep file order monotonic for the importer.
		t = Math.max(lastRecordTime, Math.max(0, Math.min(maxSeconds, t)));
		lastRecordTime = t;
		if (t != tsTime || capturedAt != tsBase)
		{
			ts = capturedAt.plusNanos((long) (t * 1_000_000_000L)).toString();
			tsTime = t;
			tsBase = capturedAt;
		}
		JsonObject row = new JsonObject();
		row.addProperty("id", id + ":r" + (++serial));
		row.addProperty("ts", ts);
		row.addProperty("elapsed_s", t);
		row.addProperty("source", SOURCE);
		row.addProperty("confidence", 1);
		row.addProperty("session_id", sessionIds.computeIfAbsent(user, who -> id + "/" + who));
		row.addProperty("user_id", user);
		row.add("character_id", JsonNull.INSTANCE);
		JsonObject salience = new JsonObject();
		salience.addProperty("rule_score", 0);
		salience.addProperty("first_time", false);
		salience.addProperty("danger", 0);
		row.add("salience_hints", salience);
		row.add("coverage", JsonNull.INSTANCE);
		row.addProperty("type", type);
		row.add("payload", payload);
		if (type.equals("world.event"))
		{
			row.add("caused_by", new JsonArray());
		}
		return row;
	}

	public int serial()
	{
		return serial;
	}
}
