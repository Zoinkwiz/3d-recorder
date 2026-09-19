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

	@ConfigItem(
		keyName = "captureRadius",
		name = "Capture radius",
		description = "How much ground around you is recorded. People are recorded wherever the client draws them; this is the map itself. Larger areas fill a recording's budget sooner.",
		position = 1
	)
	@Range(min = 4, max = 26)
	default int captureRadius()
	{
		return 12;
	}

	@ConfigItem(
		keyName = "trackNpcs",
		name = "Record nearby NPCs",
		description = "Record where NPCs stand. Names come from the game cache; nothing else is kept.",
		position = 2
	)
	default boolean trackNpcs()
	{
		return true;
	}

	@ConfigItem(
		keyName = "trackPlayers",
		name = "Record nearby players",
		description = "Record where other players stand and what they wear, as unnamed figures, so the recording shows who was there. Their names and their chat are never recorded.",
		position = 3
	)
	default boolean trackPlayers()
	{
		return true;
	}

	@ConfigItem(
		keyName = "recordOverheadText",
		name = "Record what NPCs say overhead",
		description = "Record the lines the game draws above NPCs' heads. Other players' chat is never recorded, wherever it is drawn.",
		position = 4
	)
	default boolean recordOverheadText()
	{
		return true;
	}

	@ConfigItem(
		keyName = "recordOnLogin",
		name = "Start recording when you log in",
		description = "Begin recording as soon as you are in the world. Turn this off to record only when you press Start recording.",
		position = 5
	)
	default boolean recordOnLogin()
	{
		return true;
	}

	@ConfigItem(
		keyName = "chunkMinutes",
		name = "Minutes per file",
		description = "A session is saved as a chain of files, one every this many minutes, each written when it ends. Shorter files mean less is lost if the client crashes; Studio joins the chain into one timeline.",
		position = 7
	)
	@Range(min = 5, max = 20)
	default int chunkMinutes()
	{
		return 20;
	}

	@ConfigItem(
		keyName = "openStudioOnLogout",
		name = "Open Studio when you log out",
		description = "When you log out, show the recording's file and open embertide.gg/studio to drag it into.",
		position = 6
	)
	default boolean openStudioOnLogout()
	{
		return false;
	}

}
