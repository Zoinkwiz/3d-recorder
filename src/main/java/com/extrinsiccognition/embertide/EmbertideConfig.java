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
		description = "How far around you the ground and objects are recorded. People are always recorded wherever the game shows them. Bigger areas make bigger files.",
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
		description = "Include NPCs in the replay: where they were, what they looked like, the fights. Off means no NPCs at all.",
		position = 2
	)
	default boolean trackNpcs()
	{
		return true;
	}

	@ConfigItem(
		keyName = "trackPlayers",
		name = "Record nearby players",
		description = "Include other players in the replay: where they were, what they wore, the fights. They appear as unnamed figures, and the plugin doesn't read their names or chat. Off means no other players at all.",
		position = 3
	)
	default boolean trackPlayers()
	{
		return true;
	}

	@ConfigItem(
		keyName = "recordOverheadText",
		name = "Record what NPCs say overhead",
		description = "Include what NPCs say above their heads. This doesn't cover other players, whose chat the plugin doesn't read.",
		position = 4
	)
	default boolean recordOverheadText()
	{
		return true;
	}

	@ConfigItem(
		keyName = "recordOwnChat",
		name = "Record your public chat",
		description = "Include your own public chat in the replay. Only yours, and only public: the plugin doesn't read private, clan or friends chat, or anything other players say.",
		position = 8
	)
	default boolean recordOwnChat()
	{
		return true;
	}

	@ConfigItem(
		keyName = "recordOnLogin",
		name = "Start recording when you log in",
		description = "Start recording as soon as you're in the world. Turn it off if you'd rather press Start yourself.",
		position = 5
	)
	default boolean recordOnLogin()
	{
		return true;
	}

	@ConfigItem(
		keyName = "chunkMinutes",
		name = "Minutes per file",
		description = "How often your session is saved. Lower means less is lost if the client crashes. Studio shows the whole session as one recording either way.",
		position = 7
	)
	@Range(min = 1, max = 20)
	default int chunkMinutes()
	{
		return 20;
	}

	@ConfigItem(
		keyName = "openStudioOnLogout",
		name = "Open Studio when you log out",
		description = "Open Studio in your browser when you log out, ready to drag your recording into.",
		position = 6
	)
	default boolean openStudioOnLogout()
	{
		return false;
	}

}
