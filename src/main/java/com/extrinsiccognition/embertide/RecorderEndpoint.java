package com.extrinsiccognition.embertide;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.Value;

/** Finds the local app's recorder endpoint and pairing secret (endpoint.json). */
@Value
public class RecorderEndpoint
{
	public static final String ORIGIN = "runelite://embertide";
	/** Packaged app first, then a dev run. */
	public static final String[] DATA_DIR_NAMES = {"Embertide", "Playroll"};
	public static final String RELATIVE_FILE = "place-memory/endpoint.json";

	String url;
	String token;
	int pid;

	public static List<Path> candidateFiles()
	{
		List<Path> files = new ArrayList<>();
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String home = System.getProperty("user.home", "");
		Path base;
		if (os.contains("win"))
		{
			String local = System.getenv("LOCALAPPDATA");
			base = local != null && !local.isEmpty() ? Paths.get(local) : Paths.get(home, "AppData", "Local");
		}
		else if (os.contains("mac"))
		{
			base = Paths.get(home, "Library", "Application Support");
		}
		else
		{
			String xdg = System.getenv("XDG_DATA_HOME");
			base = xdg != null && !xdg.isEmpty() ? Paths.get(xdg) : Paths.get(home, ".local", "share");
		}
		for (String name : DATA_DIR_NAMES)
		{
			files.add(base.resolve(name).resolve(RELATIVE_FILE));
		}
		return files;
	}

	/** Null when the app isn't running. */
	public static RecorderEndpoint locate()
	{
		for (Path file : candidateFiles())
		{
			RecorderEndpoint endpoint = read(file);
			if (endpoint != null)
			{
				return endpoint;
			}
		}
		return null;
	}

	static RecorderEndpoint read(Path file)
	{
		try
		{
			if (!Files.isRegularFile(file))
			{
				return null;
			}
			String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
			JsonObject object = new JsonParser().parse(text).getAsJsonObject();
			if (!object.has("url") || !object.has("token"))
			{
				return null;
			}
			int pid = object.has("pid") && object.get("pid").isJsonPrimitive() ? object.get("pid").getAsInt() : 0;
			return validated(new RecorderEndpoint(object.get("url").getAsString(), object.get("token").getAsString(), pid));
		}
		catch (IOException | RuntimeException e)
		{
			return null;
		}
	}

	static RecorderEndpoint validated(RecorderEndpoint endpoint)
	{
		try
		{
			URI uri = new URI(endpoint.url);
			boolean loopback = "http".equals(uri.getScheme())
				&& "127.0.0.1".equals(uri.getHost())
				&& uri.getPort() > 0
				&& uri.getUserInfo() == null
				&& (uri.getPath() == null || uri.getPath().isEmpty() || uri.getPath().equals("/"))
				&& uri.getQuery() == null
				&& uri.getFragment() == null;
			if (!loopback || endpoint.token.isEmpty() || endpoint.token.length() > 512)
			{
				return null;
			}
			return new RecorderEndpoint("http://127.0.0.1:" + uri.getPort(), endpoint.token, endpoint.pid);
		}
		catch (Exception e)
		{
			return null;
		}
	}
}
