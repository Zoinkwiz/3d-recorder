package com.extrinsiccognition.embertide;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;

public class RowWriterTest
{
	@Test
	public void writesWhatGsonWrites()
	{
		RowWriter writer = new RowWriter();
		for (Gson gson : new Gson[]{new Gson(), new GsonBuilder().serializeNulls().disableHtmlEscaping().create()})
		{
			for (JsonObject row : rows())
			{
				assertArrayEquals((gson.toJson(row) + '\n').getBytes(StandardCharsets.UTF_8), writer.line(gson, row));
			}
		}
	}

	private static List<JsonObject> rows()
	{
		List<JsonObject> rows = new ArrayList<>();
		JsonObject plain = new JsonObject();
		plain.addProperty("type", "osrs.motion");
		plain.addProperty("elapsed_s", 12.3456);
		plain.add("character_id", JsonNull.INSTANCE);
		JsonArray sample = new JsonArray();
		sample.add("npc:shade#9010");
		sample.add(3305.5);
		sample.add(-1);
		sample.add(JsonNull.INSTANCE);
		sample.add(true);
		plain.add("samples", sample);
		rows.add(plain);
		JsonObject text = new JsonObject();
		text.addProperty("text", "Zéro <b>&</b> = 'quoted' \"too\" \\ \t\n  🔥 and a lone \uD800");
		text.addProperty("sender", "Grâce");
		rows.add(text);
		JsonObject big = new JsonObject();
		JsonArray cells = new JsonArray();
		for (int i = 0; i < 40_000; i++)
		{
			JsonArray cell = new JsonArray();
			cell.add(3200 + i);
			cell.add(i % 31);
			cell.add(-5400 - i);
			cell.add("osrs:underlay:" + i % 7);
			cells.add(cell);
		}
		big.add("cells", cells);
		big.addProperty("max", Double.MAX_VALUE);
		big.addProperty("min", Long.MIN_VALUE);
		rows.add(big);
		JsonObject nested = new JsonObject();
		nested.add("payload", plain.deepCopy());
		nested.add("empty", new JsonObject());
		nested.add("list", new JsonArray());
		nested.addProperty("heights", Arrays.toString(new int[]{-60, 0, 120}));
		rows.add(nested);
		return rows;
	}
}
