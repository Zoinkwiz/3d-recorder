package com.extrinsiccognition.embertide;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.UUID;

public final class MccrSession
{
	public static final String FORMAT = "mccr/0.1";
	public static final String PROFILE = "ec.mccr.spatial/1";
	public static final String SOURCE = "osrs.runelite";
	public static final String RECORDER = "embertide-runelite/0.1.0";
	public static final String GAME = "osrs";

	public final String id;
	public Instant capturedAt;
	public final String dimension;
	private long startedNanos;
	private final double maxSeconds;
	private int serial;

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

	public void rebase()
	{
		rebase(Instant.now(), System.nanoTime());
	}

	void rebase(Instant capturedAt, long startedNanos)
	{
		this.capturedAt = capturedAt;
		this.startedNanos = startedNanos;
	}

	public double time()
	{
		return time(System.nanoTime());
	}

	double time(long nowNanos)
	{
		double seconds = (nowNanos - startedNanos) / 1_000_000_000.0;
		seconds = Math.max(0, Math.min(maxSeconds, seconds));
		return Math.round(seconds * 10000.0) / 10000.0;
	}

	public JsonObject record(String type, double t, JsonObject payload, String user)
	{
		JsonObject row = new JsonObject();
		row.addProperty("id", id + ":r" + (++serial));
		row.addProperty("ts", capturedAt.plusNanos((long) (t * 1_000_000_000L)).toString());
		row.addProperty("elapsed_s", t);
		row.addProperty("source", SOURCE);
		row.addProperty("confidence", 1);
		row.addProperty("session_id", id + "/" + user);
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
