package com.extrinsiccognition.embertide;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(EmbertideConfig.GROUP)
public interface EmbertideConfig extends Config
{
	String GROUP = "embertide";

	@ConfigItem(
		keyName = "captureRadius",
		name = "Ground radius",
		description = "How many tiles around you the ground is recorded for the block view. Bigger means bigger files.",
		position = 1
	)
	@Range(min = 4, max = 26)
	default int captureRadius()
	{
		return 12;
	}

	@ConfigItem(
		keyName = "trackNpcs",
		name = "Record NPCs",
		description = "Record NPCs: where they go, what they look like and their fights.",
		position = 2
	)
	default boolean trackNpcs()
	{
		return true;
	}

	@ConfigItem(
		keyName = "trackPlayers",
		name = "Record other players",
		description = "Record other players as unnamed figures: where they go, what they wear and their fights. Their names and chat are never read.",
		position = 3
	)
	default boolean trackPlayers()
	{
		return true;
	}

	@ConfigItem(
		keyName = "recordOverheadText",
		name = "Record NPC overhead text",
		description = "Record what NPCs say above their heads.",
		position = 4
	)
	default boolean recordOverheadText()
	{
		return true;
	}

	@ConfigItem(
		keyName = "recordOwnChat",
		name = "Record your public chat",
		description = "Record your own public chat. Private, clan and friends chat, and other players' chat, are never read.",
		position = 5
	)
	default boolean recordOwnChat()
	{
		return true;
	}

	@ConfigItem(
		keyName = "recordMoments",
		name = "Record drops and milestones",
		description = "Record your loot and milestones like kill counts, pets, collection log slots and quests, so Studio can find your best moments.",
		position = 6
	)
	default boolean recordMoments()
	{
		return true;
	}

	@ConfigItem(
		keyName = "recordOnLogin",
		name = "Record when you log in",
		description = "Start recording when you log in. Turn off to start it yourself from the side panel.",
		position = 7
	)
	default boolean recordOnLogin()
	{
		return true;
	}

	@ConfigItem(
		keyName = "chunkMinutes",
		name = "Minutes per file",
		description = "Long sessions are saved as one file per this many minutes. Studio shows them as one recording.",
		position = 8
	)
	@Range(min = 1, max = 20)
	default int chunkMinutes()
	{
		return 20;
	}

	@ConfigItem(
		keyName = "openStudioOnLogout",
		name = "Open Studio when you log out",
		description = "Open Studio in your browser when you log out, ready for you to drag your recording in.",
		position = 9
	)
	default boolean openStudioOnLogout()
	{
		return false;
	}
}
