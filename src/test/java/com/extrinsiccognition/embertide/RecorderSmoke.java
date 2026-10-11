package com.extrinsiccognition.embertide;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.nio.file.Paths;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;

/** Smoke test: a synthetic capture into a running app. */
public final class RecorderSmoke
{
	public static void main(String[] args) throws Exception
	{
		RecorderEndpoint endpoint = RecorderEndpoint.read(Paths.get(args[0]));
		if (endpoint == null)
		{
			System.err.println("smoke: endpoint file unreadable or not loopback: " + args[0]);
			System.exit(2);
		}
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		try
		{
			MccrRecorderClient client = new MccrRecorderClient(new OkHttpClient(), new Gson(), executor, endpoint);
			MccrSession session = new MccrSession("osrs:surface", OsrsCapture.MAX_SECONDS);
			client.open(header(session)).get(10, TimeUnit.SECONDS);
			System.out.println("smoke: opened capture " + client.captureId() + " lifetime=" + client.lifetime());
			double t = 0;
			require(client.enqueue(session.record("mccr.capture_start", t, scope(session), OsrsCapture.PLAYER), false), "capture_start");
			JsonArray cells = new JsonArray();
			cells.add(cell(3222, 0, -3218, "osrs:underlay:3"));
			cells.add(cell(3222, 1, -3218, "air"));
			cells.add(cell(3223, 0, -3218, "osrs:overlay:7"));
			cells.add(cell(3223, 1, -3218, "tree"));
			JsonObject snapshot = new JsonObject();
			snapshot.addProperty("dimension", session.dimension);
			snapshot.addProperty("coverage", "observed-cells");
			snapshot.add("cells", cells);
			require(client.enqueue(session.record("mccr.spatial_snapshot", t, snapshot, OsrsCapture.PLAYER), false), "snapshot");
			for (int i = 0; i < 3; i++)
			{
				t += 0.6;
				JsonObject payload = new JsonObject();
				JsonObject coords = new JsonObject();
				coords.addProperty("x", 3222 + i);
				coords.addProperty("y", 1);
				coords.addProperty("z", -3218);
				payload.add("coords", coords);
				payload.addProperty("dimension", session.dimension);
				payload.addProperty("plane", 0);
				payload.addProperty("yaw", SceneMapper.yawDegrees(512 * i));
				payload.addProperty("pitch", 0);
				require(client.enqueue(session.record("player.position", t, payload, OsrsCapture.PLAYER), false), "pose " + i);
			}
			JsonObject presence = new JsonObject();
			JsonArray names = new JsonArray();
			names.add("Hans");
			presence.add("present_players", names);
			presence.addProperty("kind", "npc");
			require(client.enqueue(session.record("presence", t, presence, "npc:hans#7"), false), "presence");
			t += 0.6;
			JsonObject event = new JsonObject();
			event.addProperty("kind", "block.break");
			event.addProperty("summary", "Removed tree");
			event.addProperty("dimension", session.dimension);
			JsonObject at = new JsonObject();
			at.addProperty("x", 3223);
			at.addProperty("y", 1);
			at.addProperty("z", -3218);
			event.add("at", at);
			event.addProperty("before", "tree");
			event.addProperty("after", "air");
			event.add("actors", new JsonArray());
			event.add("observed_cause", JsonNull.INSTANCE);
			require(client.enqueue(session.record("world.event", t, event, OsrsCapture.PLAYER), false), "event");
			t += 0.6;
			JsonObject end = scope(session);
			end.addProperty("open_studio", true);
			require(client.enqueue(session.record("mccr.capture_end", t, end, OsrsCapture.PLAYER), true), "capture_end");
			client.finish().get(15, TimeUnit.SECONDS);
			System.out.println("smoke: finished state=" + client.state() + " studioRequested=" + client.studioRequested());
			if (!client.studioRequested())
			{
				client.openStudio().get(10, TimeUnit.SECONDS);
				System.out.println("smoke: studio requested explicitly");
			}
			client.closeLifetime();
			System.out.println("smoke: ok " + client.captureId());
		}
		finally
		{
			executor.shutdownNow();
		}
	}

	private static void require(boolean accepted, String what)
	{
		if (!accepted)
		{
			throw new IllegalStateException("queue refused " + what);
		}
	}

	private static JsonObject scope(MccrSession session)
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("scope", "recording");
		payload.addProperty("dimension", session.dimension);
		return payload;
	}

	private static JsonArray cell(int x, int y, int z, String material)
	{
		JsonArray cell = new JsonArray();
		cell.add(x);
		cell.add(y);
		cell.add(z);
		cell.add(material);
		return cell;
	}

	private static JsonObject header(MccrSession session)
	{
		JsonObject header = new JsonObject();
		header.addProperty("type", "mccr.header");
		header.addProperty("format", MccrSession.FORMAT);
		header.addProperty("profile", MccrSession.PROFILE);
		header.addProperty("schema", "CorePercept envelope + ec.mccr.spatial/1");
		header.addProperty("game", MccrSession.GAME);
		header.addProperty("place_id", "local:osrs:world:301:" + session.dimension);
		String started = session.capturedAt.toString();
		header.addProperty("day", started.substring(0, 10));
		header.addProperty("started_at", started);
		header.addProperty("recorder", MccrSession.RECORDER + "+smoke");
		JsonObject spatial = new JsonObject();
		spatial.addProperty("dimension", session.dimension);
		spatial.addProperty("axes", "x-east,y-up,z-south");
		spatial.addProperty("units", "tile");
		spatial.addProperty("coverage", "observed-cells");
		spatial.addProperty("authority", "local-client-observation");
		spatial.addProperty("pose_max_gap_s", OsrsCapture.POSE_MAX_GAP_S);
		header.add("spatial", spatial);
		JsonObject meta = new JsonObject();
		meta.addProperty("id", session.id);
		meta.addProperty("title", "Smoke memory");
		meta.addProperty("duration", 0);
		meta.addProperty("description", "Synthetic capture from RecorderSmoke.");
		header.add("session", meta);
		JsonArray actors = new JsonArray();
		JsonObject player = new JsonObject();
		player.addProperty("id", OsrsCapture.PLAYER);
		player.addProperty("name", "Smoke");
		player.addProperty("color", "#e8863a");
		actors.add(player);
		header.add("actors", actors);
		header.addProperty("channel_rule", "Local world observations only; no chat, no account data, no private memory captured.");
		header.addProperty("clock", "ts is capture UTC + elapsed_s; elapsed_s is monotonic wall time, not game ticks.");
		return header;
	}
}
