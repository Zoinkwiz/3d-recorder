package com.extrinsiccognition.embertide;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/** Developer-mode RuneLite with the plugin loaded: `./gradlew run`. */
public class EmbertidePluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(EmbertidePlugin.class);
		RuneLite.main(args);
	}
}
