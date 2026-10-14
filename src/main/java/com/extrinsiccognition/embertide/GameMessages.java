package com.extrinsiccognition.embertide;

import com.google.gson.JsonObject;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.ChatMessageType;
import net.runelite.client.util.Text;

/** Allowlisted server game messages, kept only as kind and fields. */
final class GameMessages
{
	static final class Match
	{
		final String kind;
		final JsonObject fields;

		Match(String kind, JsonObject fields)
		{
			this.kind = kind;
			this.fields = fields;
		}
	}

	private static final int MAX_NAME = 64;

	private static final Pattern KILL_COUNT = Pattern.compile(
		"Your (?!.* lap count is: )(?:completion count for |subdued |completed )?((?:[A-Z]|herbiboar).*?) (?:(?:kill|harvest|completion|chest|success) )?count is: ([\\d,]+)\\.?");
	private static final Pattern PERSONAL_BEST = Pattern.compile(
		"(?:.*\\s)?(?:[Dd]uration|[Tt]ime): ([\\d:.]+) \\(new personal best\\)\\.?(?:\\s.*)?");
	private static final Pattern PET_FOLLOWER = Pattern.compile("You have a funny feeling like you're being followed\\.?");
	private static final Pattern PET_BACKPACK = Pattern.compile("You feel something weird sneaking into your backpack\\.?");
	private static final Pattern PET_DUPLICATE = Pattern.compile("You have a funny feeling like you would have been followed\\.*");
	private static final Pattern COLLECTION_LOG = Pattern.compile("New item added to your collection log: (.+?)\\.?");
	private static final Pattern VALUABLE_DROP = Pattern.compile("Valuable drop: (?:([\\d,]+) x )?(.+?) \\(([\\d,]+) coins\\)\\.?");
	private static final Pattern UNTRADEABLE_DROP = Pattern.compile("Untradeable drop: (?:([\\d,]+) x )?(.+?)\\.?");
	private static final Pattern CLUE_COMPLETE = Pattern.compile(
		"You have completed ([\\d,]+) (beginner|easy|medium|hard|elite|master) Treasure Trails?\\.?");
	private static final Pattern COMBAT_TASK = Pattern.compile(
		"(?:CA_ID:\\d+\\|)?Congratulations, you've completed an? (easy|medium|hard|elite|master|grandmaster) combat task(?::.*)?\\.?");
	private static final Pattern SLAYER_TASK = Pattern.compile(
		"You've completed ([\\d,]+) (?:(?:Wildy|Wilderness) )?tasks?(?: and received ([\\d,]+) points?)?(?:[,;].*)?\\.?");
	private static final Pattern QUEST_COMPLETE = Pattern.compile("Congratulations, you've completed a quest: (.+?)\\.?");
	private static final Pattern DIARY_COMPLETE = Pattern.compile(
		"Congratulations! You have completed all of the (easy|medium|hard|elite) tasks in the (.+?) area\\..*");
	private static final Pattern SUPERIOR = Pattern.compile("A superior foe has appeared\\.*");
	private static final Pattern OWN_DEATH = Pattern.compile("Oh dear, you are dead!");

	private GameMessages()
	{
	}

	static Match match(ChatMessageType type, String raw)
	{
		if (raw == null || (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.SPAM))
		{
			return null;
		}
		String line = Text.removeTags(raw).replace((char) 160, ' ').trim();
		if (line.isEmpty() || line.length() > 300)
		{
			return null;
		}
		Matcher m;
		if ((m = KILL_COUNT.matcher(line)).matches())
		{
			return found("kill_count", "boss", name(m.group(1)), "count", number(m.group(2)));
		}
		if ((m = PERSONAL_BEST.matcher(line)).matches())
		{
			double seconds = duration(m.group(1));
			return seconds < 0 ? null : found("personal_best", "duration_s", seconds);
		}
		if (PET_FOLLOWER.matcher(line).matches()) { return found("pet", "how", "follower"); }
		if (PET_BACKPACK.matcher(line).matches()) { return found("pet", "how", "backpack"); }
		if (PET_DUPLICATE.matcher(line).matches()) { return found("pet", "how", "duplicate"); }
		if ((m = COLLECTION_LOG.matcher(line)).matches())
		{
			return found("collection_log", "item", name(m.group(1)));
		}
		if ((m = VALUABLE_DROP.matcher(line)).matches())
		{
			return found("valuable_drop", "item", name(m.group(2)), "quantity", quantity(m.group(1)), "value", number(m.group(3)));
		}
		if ((m = UNTRADEABLE_DROP.matcher(line)).matches())
		{
			return found("untradeable_drop", "item", name(m.group(2)), "quantity", quantity(m.group(1)));
		}
		if ((m = CLUE_COMPLETE.matcher(line)).matches())
		{
			return found("clue_complete", "count", number(m.group(1)), "tier", m.group(2));
		}
		if ((m = COMBAT_TASK.matcher(line)).matches())
		{
			return found("combat_task", "tier", m.group(1));
		}
		if ((m = SLAYER_TASK.matcher(line)).matches())
		{
			return found("slayer_task_complete", "tasks", number(m.group(1)), "points", m.group(2) == null ? null : number(m.group(2)));
		}
		if ((m = QUEST_COMPLETE.matcher(line)).matches())
		{
			return found("quest_complete", "quest", name(m.group(1)));
		}
		if ((m = DIARY_COMPLETE.matcher(line)).matches())
		{
			return found("diary_complete", "tier", m.group(1), "area", name(m.group(2)));
		}
		if (SUPERIOR.matcher(line).matches())
		{
			return found("superior_spawn");
		}
		if (OWN_DEATH.matcher(line).matches())
		{
			return found("own_death");
		}
		return null;
	}

	/** Fields as name, value pairs, in order; a null value is left out. */
	private static Match found(String kind, Object... fields)
	{
		JsonObject f = new JsonObject();
		for (int i = 0; i < fields.length; i += 2)
		{
			Object value = fields[i + 1];
			if (value instanceof String) { f.addProperty((String) fields[i], (String) value); }
			else if (value != null) { f.addProperty((String) fields[i], (Number) value); }
		}
		return new Match(kind, f);
	}

	private static long quantity(String value)
	{
		return value == null ? 1 : number(value);
	}

	private static String name(String value)
	{
		String trimmed = value.trim();
		return trimmed.length() > MAX_NAME ? trimmed.substring(0, MAX_NAME) : trimmed;
	}

	private static long number(String value)
	{
		try
		{
			return Long.parseLong(value.replace(",", ""));
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}

	/** Seconds, or -1 if not a duration. */
	static double duration(String value)
	{
		String[] parts = value.split(":");
		if (parts.length > 3)
		{
			return -1;
		}
		double total = 0;
		try
		{
			for (String part : parts)
			{
				if (part.isEmpty()) { return -1; }
				total = total * 60 + Double.parseDouble(part);
			}
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
		return Math.round(total * 100) / 100.0;
	}
}
