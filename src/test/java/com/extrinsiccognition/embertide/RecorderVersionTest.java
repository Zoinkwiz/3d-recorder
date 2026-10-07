package com.extrinsiccognition.embertide;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** A recording names the plugin version that wrote it: the one the build and the Plugin Hub ship. */
public class RecorderVersionTest
{
	@Test
	public void theHeaderNamesTheVersionTheBuildShips() throws IOException
	{
		String build = version("build.gradle", "^version = '([^']+)'$");
		String hub = version("runelite-plugin.properties", "^version=(\\S+)$");
		assertEquals("build.gradle and runelite-plugin.properties name different versions", build, hub);
		OsrsCapture capture = new RaidScene(1, 1).capture(null);
		assertEquals("embertide-runelite/" + build, capture.header().get("recorder").getAsString());
	}

	private static String version(String file, String pattern) throws IOException
	{
		String text = new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8);
		Matcher match = Pattern.compile(pattern, Pattern.MULTILINE).matcher(text);
		assertTrue("no version in " + file, match.find());
		return match.group(1);
	}
}
