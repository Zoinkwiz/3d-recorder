package com.extrinsiccognition.embertide;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(EmbertideConfig.GROUP)
public interface EmbertideConfig extends Config
{
	String GROUP = "embertide";

	@ConfigSection(name = "Watching in your house", description = "How a recording looks when you watch it in your house.", position = 10)
	String WATCH = "watch";

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
		keyName = "liveToApp",
		name = "Show live in Embertide",
		description = "Send this session to the Embertide app on this computer as you play, so it can be drawn while you are still in it. Your recording is still saved to a file exactly as it is now, and nothing leaves this computer. Off unless you turn it on.",
		// Own warning text: the Hub default would name an internet server.
		warning = "Sends this session's rows to the Embertide app on this computer over a local connection while you play. Nothing is sent anywhere else by the plugin.",
		position = 8
	)
	default boolean liveToApp()
	{
		return false;
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

	@ConfigItem(keyName = "viewerWidth", name = "Miniature width", description = "How many tiles wide a miniature is.", position = 11, section = WATCH)
	@Range(min = 2, max = 12)
	default int viewerWidth()
	{
		return 4;
	}

	@ConfigItem(keyName = "viewerRadius", name = "Scene radius", description = "How many tiles around the recorded player are shown.", position = 12, section = WATCH)
	@Range(min = 4, max = 24)
	default int viewerRadius()
	{
		return 12;
	}

	@ConfigItem(keyName = "viewerWiderArea", name = "Wider area", description = "Show more of the recorded area around the scene, as many tiles as Extra tiles says.", position = 13, section = WATCH)
	default boolean viewerWiderArea()
	{
		return false;
	}

	@ConfigItem(keyName = "viewerExtraArea", name = "Extra tiles", description = "How many extra tiles Wider area adds. A miniature keeps its width and shows them smaller.", position = 14, section = WATCH)
	@Range(max = 24)
	default int viewerExtraArea()
	{
		return 12;
	}

	@ConfigItem(keyName = "viewerAllFloors", name = "All floors", description = "Show the floors above and below the recorded player. Turn off to see inside buildings.", position = 15, section = WATCH)
	default boolean viewerAllFloors()
	{
		return true;
	}

	@ConfigItem(keyName = "viewerObjectLimit", name = "Object limit", description = "The most recorded objects shown at once. Raise it if objects are missing; lower it if playback is slow.", position = 16, section = WATCH)
	@Range(min = 128, max = 8192)
	default int viewerObjectLimit()
	{
		return 4096;
	}

	@ConfigItem(keyName = "viewerLift", name = "Height above the floor", description = "How high a miniature sits above the floor. 128 is one tile. Life size stands on the floor.", position = 17, section = WATCH)
	@Range(max = 512)
	default int viewerLift()
	{
		return 96;
	}

	@ConfigItem(keyName = "viewerGround", name = "Show ground", description = "Draw the recorded ground.", position = 18, section = WATCH)
	default boolean viewerGround()
	{
		return true;
	}

	@ConfigItem(keyName = "viewerScenery", name = "Show scenery", description = "Draw the recorded scenery.", position = 19, section = WATCH)
	default boolean viewerScenery()
	{
		return true;
	}
}
