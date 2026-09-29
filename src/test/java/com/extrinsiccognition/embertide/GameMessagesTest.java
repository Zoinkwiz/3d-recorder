package com.extrinsiccognition.embertide;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.Collections;
import net.runelite.api.ChatMessageType;
import org.junit.Test;

public class GameMessagesTest
{
	private static GameMessages.Match game(String line)
	{
		return GameMessages.match(ChatMessageType.GAMEMESSAGE, line);
	}

	private static GameMessages.Match expect(String line, String kind)
	{
		GameMessages.Match m = game(line);
		assertNotNull("should match: " + line, m);
		assertEquals(line, kind, m.kind);
		return m;
	}

	@Test
	public void killCount()
	{
		JsonObject f = expect("Your Vorkath kill count is: <col=ff0000>500</col>.", "kill_count").fields;
		assertEquals("Vorkath", f.get("boss").getAsString());
		assertEquals(500, f.get("count").getAsLong());
		f = expect("Your completed Chambers of Xeric count is: 1,204.", "kill_count").fields;
		assertEquals("Chambers of Xeric", f.get("boss").getAsString());
		assertEquals(1204, f.get("count").getAsLong());
		assertEquals("Barrows", expect("Your Barrows chest count is: 12.", "kill_count").fields.get("boss").getAsString());
		assertEquals("Gauntlet", expect("Your Gauntlet completion count is: 7.", "kill_count").fields.get("boss").getAsString());
		assertEquals("Hueycoatl", expect("Your subdued Hueycoatl count is: 3.", "kill_count").fields.get("boss").getAsString());
		assertEquals("herbiboar", expect("Your herbiboar harvest count is: 40.", "kill_count").fields.get("boss").getAsString());
	}

	@Test
	public void personalBest()
	{
		assertEquals(83.4, expect("Fight duration: <col=ff0000>1:23.40</col> (new personal best).", "personal_best")
			.fields.get("duration_s").getAsDouble(), 1e-9);
		assertEquals(1530.0, expect("Congratulations - your raid is complete! Team size: Solo Duration: 25:30 (new personal best)", "personal_best")
			.fields.get("duration_s").getAsDouble(), 1e-9);
		assertEquals(3723.0, expect("Challenge duration: 1:02:03 (new personal best).", "personal_best")
			.fields.get("duration_s").getAsDouble(), 1e-9);
	}

	@Test
	public void pets()
	{
		assertEquals("follower", expect("You have a funny feeling like you're being followed.", "pet").fields.get("how").getAsString());
		assertEquals("backpack", expect("You feel something weird sneaking into your backpack.", "pet").fields.get("how").getAsString());
		assertEquals("duplicate", expect("You have a funny feeling like you would have been followed...", "pet").fields.get("how").getAsString());
	}

	@Test
	public void collectionLog()
	{
		assertEquals("Dragon warhammer",
			expect("New item added to your collection log: <col=ef1020>Dragon warhammer</col>", "collection_log").fields.get("item").getAsString());
	}

	@Test
	public void drops()
	{
		JsonObject f = expect("<col=ef1020>Valuable drop: 3 x Dragon bones (7,500 coins)</col>", "valuable_drop").fields;
		assertEquals("Dragon bones", f.get("item").getAsString());
		assertEquals(3, f.get("quantity").getAsLong());
		assertEquals(7500, f.get("value").getAsLong());
		f = expect("Valuable drop: Dragon warhammer (38,200,000 coins)", "valuable_drop").fields;
		assertEquals("Dragon warhammer", f.get("item").getAsString());
		assertEquals(1, f.get("quantity").getAsLong());
		assertEquals(38200000, f.get("value").getAsLong());
		f = expect("Untradeable drop: Draconic visage", "untradeable_drop").fields;
		assertEquals("Draconic visage", f.get("item").getAsString());
	}

	@Test
	public void clues()
	{
		JsonObject f = expect("You have completed <col=ff0000>42</col> elite Treasure Trails.", "clue_complete").fields;
		assertEquals(42, f.get("count").getAsLong());
		assertEquals("elite", f.get("tier").getAsString());
		assertEquals("beginner", expect("You have completed 1 beginner Treasure Trail.", "clue_complete").fields.get("tier").getAsString());
	}

	@Test
	public void combatTask()
	{
		assertEquals("easy", expect("Congratulations, you've completed an easy combat task: <col=06600c>Noxious Foe</col> (1 point).", "combat_task")
			.fields.get("tier").getAsString());
		assertEquals("grandmaster", expect("CA_ID:123|Congratulations, you've completed a grandmaster combat task: Perfect Zulrah (6 points).", "combat_task")
			.fields.get("tier").getAsString());
	}

	@Test
	public void slayerTask()
	{
		JsonObject f = expect("You've completed <col=ff0000>25 tasks</col> and received <col=ff0000>10 points</col>, giving you a total of 100; return to a Slayer master.",
			"slayer_task_complete").fields;
		assertEquals(25, f.get("tasks").getAsLong());
		assertEquals(10, f.get("points").getAsLong());
		f = expect("You've completed 1 task; return to a Slayer master.", "slayer_task_complete").fields;
		assertEquals(1, f.get("tasks").getAsLong());
		assertFalse(f.has("points"));
	}

	@Test
	public void questAndDiary()
	{
		assertEquals("Dragon Slayer I",
			expect("Congratulations, you've completed a quest: <col=0000ff>Dragon Slayer I</col>", "quest_complete").fields.get("quest").getAsString());
		JsonObject f = expect("Congratulations! You have completed all of the hard tasks in the Varrock area. Speak to Toby to claim your reward.",
			"diary_complete").fields;
		assertEquals("hard", f.get("tier").getAsString());
		assertEquals("Varrock", f.get("area").getAsString());
	}

	@Test
	public void superiorAndDeath()
	{
		assertEquals(0, expect("A superior foe has appeared...", "superior_spawn").fields.size());
		assertEquals(0, expect("Oh dear, you are dead!", "own_death").fields.size());
	}

	@Test
	public void spamTypeCounts()
	{
		assertNotNull(GameMessages.match(ChatMessageType.SPAM, "Your Zulrah kill count is: 5."));
	}

	@Test
	public void otherChannelsNeverMatch()
	{
		String[] lines = {
			"Your Zulrah kill count is: 5.",
			"You have a funny feeling like you're being followed.",
			"Oh dear, you are dead!",
			"Valuable drop: Dragon warhammer (38,200,000 coins)",
		};
		ChatMessageType[] others = {
			ChatMessageType.PUBLICCHAT, ChatMessageType.PRIVATECHAT, ChatMessageType.FRIENDSCHAT,
			ChatMessageType.CLAN_CHAT, ChatMessageType.CLAN_MESSAGE, ChatMessageType.CLAN_GUEST_CHAT,
			ChatMessageType.BROADCAST, ChatMessageType.MODCHAT, ChatMessageType.TRADE, ChatMessageType.ENGINE,
		};
		for (ChatMessageType type : others)
		{
			for (String line : lines)
			{
				assertNull(type + ": " + line, GameMessages.match(type, line));
			}
		}
		assertNull(GameMessages.match(ChatMessageType.GAMEMESSAGE, null));
	}

	@Test
	public void nearMissesDoNotMatch()
	{
		String[] misses = {
			"Fight duration: 1:23.40. Personal best: 1:10.00",
			"Your Zulrah kill count is: lots.",
			"Zezima has a funny feeling like he's being followed: Pet snakeling at 500 kill count.",
			"You have a funny feeling you forgot something.",
			"Oh dear, you are dead! lol",
			"Valuable drop: Dragon warhammer",
			"Your task is to kill 150 Blue dragons.",
			"You have completed 3 laps of the course.",
			"Your Canifis Agility Course lap count is: 57.",
			"Your Gnome Stronghold Agility lap count is: 12.",
			"Your Rellekka Rooftop lap count is: <col=ff0000>3</col>.",
			"Your reward count is: 3.",
			"Well done! You have completed an easy task in the Ardougne area. Your Achievement Diary has been updated.",
			"A superior foe",
			"Welcome to Old School RuneScape.",
			"",
		};
		for (String line : misses)
		{
			assertNull("should not match: " + line, game(line));
		}
	}

	@Test
	public void durations()
	{
		assertEquals(45.6, GameMessages.duration("45.6"), 1e-9);
		assertEquals(-1, GameMessages.duration("1::2"), 1e-9);
		assertEquals(-1, GameMessages.duration("1:2:3:4"), 1e-9);
	}

	@Test
	public void lootPayloadPricesAndHidesPlayers()
	{
		JsonObject p = OsrsCapture.lootPayload("npc", "Vorkath", 8061, "npc:vorkath#12",
			Arrays.asList(new int[]{536, 2, 2500}, new int[]{11286, 1, 4_000_000}, new int[]{995, 0, 1}));
		assertEquals("npc", p.get("source_kind").getAsString());
		assertEquals("Vorkath", p.get("source_name").getAsString());
		assertEquals(8061, p.get("npc_id").getAsInt());
		assertEquals("npc:vorkath#12", p.get("source_actor").getAsString());
		JsonArray items = p.getAsJsonArray("items");
		assertEquals(2, items.size());
		assertEquals(536, items.get(0).getAsJsonArray().get(0).getAsInt());
		assertEquals(2, items.get(0).getAsJsonArray().get(1).getAsInt());
		assertEquals(2500, items.get(0).getAsJsonArray().get(2).getAsLong());
		assertEquals(4_005_000L, p.get("total_ge").getAsLong());

		JsonObject pvp = OsrsCapture.lootPayload("player", "Zezima", -1, null, Collections.singletonList(new int[]{995, 1000, 1}));
		assertFalse(pvp.has("source_name"));
		assertFalse(pvp.has("npc_id"));
		assertTrue(pvp.toString().indexOf("Zezima") < 0);
		assertEquals(1000, pvp.get("total_ge").getAsLong());
	}
}
