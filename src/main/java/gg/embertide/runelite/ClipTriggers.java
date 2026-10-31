package gg.embertide.runelite;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.text.NumberFormat;
import java.util.Locale;

/** Which recorded rows are worth a clip of their own, and what the clip is called. */
final class ClipTriggers
{
	/** What sets a clip off: the reason a clip file's header names, and a title for it. */
	static final class Trigger
	{
		final String reason;
		final String title;

		Trigger(String reason, String title)
		{
			this.reason = reason;
			this.title = title;
		}
	}

	/** The kinds the player ticked. */
	static final class Wanted
	{
		final boolean deaths, drops, levels, pets, kills, key;
		final long dropValue;

		Wanted(boolean deaths, boolean drops, long dropValue, boolean levels, boolean pets, boolean kills, boolean key)
		{
			this.deaths = deaths;
			this.drops = drops;
			this.dropValue = dropValue;
			this.levels = levels;
			this.pets = pets;
			this.kills = kills;
			this.key = key;
		}

		boolean any()
		{
			return deaths || drops || levels || pets || kills;
		}
	}

	private ClipTriggers() {}

	/** The clip a row asks for, or null. */
	static Trigger of(JsonObject row, Wanted wanted)
	{
		if (row == null || wanted == null) { return null; }
		String type = text(row, "type");
		JsonObject payload = row.has("payload") && row.get("payload").isJsonObject() ? row.getAsJsonObject("payload") : new JsonObject();
		switch (type)
		{
			case "player.death":
			{
				// The cause is the attacker as the recording names it: an NPC's name, or another player's stand-in.
				String cause = text(payload, "cause");
				boolean known = !cause.isEmpty() && !"unknown".equals(cause) && !"damage".equals(cause) && !"someone".equals(cause);
				return wanted.deaths ? new Trigger("death", known ? "Died to " + cause : "Died") : null;
			}
			case "osrs.loot":
			{
				long value = number(payload, "total_ge");
				if (!wanted.drops || value < Math.max(1, wanted.dropValue)) { return null; }
				String source = text(payload, "source_name");
				return new Trigger("drop", (source.isEmpty() ? "Drop" : source + " drop") + ", " + gp(value));
			}
			case "advancement":
			{
				String id = text(payload, "advancement_id");
				return wanted.levels && id.startsWith("osrs:level:") ? new Trigger("level", orElse(text(payload, "title"), "Level up")) : null;
			}
			case "osrs.game_message":
			{
				String kind = text(payload, "kind");
				JsonObject fields = payload.has("fields") && payload.get("fields").isJsonObject() ? payload.getAsJsonObject("fields") : new JsonObject();
				if (wanted.pets && "pet".equals(kind)) { return new Trigger("pet", "Pet"); }
				if (wanted.pets && "collection_log".equals(kind)) { return new Trigger("collection_log", orElse(text(fields, "item"), "New collection log slot")); }
				if (wanted.kills && "kill_count".equals(kind))
				{
					String boss = text(fields, "boss");
					return new Trigger("kill", boss.isEmpty() ? "Boss kill" : boss + " kill " + number(fields, "count"));
				}
				return null;
			}
			case "osrs.clip_mark":
				return wanted.key ? new Trigger("clip_key", "Clip") : null;
			default:
				return null;
		}
	}

	/** "1.2m gp", "350k gp", "900 gp". */
	static String gp(long value)
	{
		if (value >= 10_000_000) { return (value / 1_000_000) + "m gp"; }
		if (value >= 1_000_000) { return String.format(Locale.ROOT, "%.1fm gp", value / 1_000_000.0); }
		if (value >= 100_000) { return (value / 1_000) + "k gp"; }
		return NumberFormat.getIntegerInstance(Locale.ROOT).format(value) + " gp";
	}

	private static String orElse(String value, String fallback)
	{
		return value.isEmpty() ? fallback : value;
	}

	private static String text(JsonObject object, String key)
	{
		JsonElement value = object.get(key);
		return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
	}

	private static long number(JsonObject object, String key)
	{
		JsonElement value = object.get(key);
		try { return value != null && value.isJsonPrimitive() ? value.getAsLong() : 0; }
		catch (NumberFormatException e) { return 0; }
	}
}
