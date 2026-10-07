package com.extrinsiccognition.embertide;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class LongMapTest
{
	@Test
	public void answersAsAHashMapDoes()
	{
		Random random = new Random(3);
		LongMap<String> map = new LongMap<>();
		Map<Long, String> expected = new HashMap<>();
		for (int round = 0; round < 3; round++)
		{
			for (int i = 0; i < 20_000; i++)
			{
				// Cell keys near the player, and anything else a long can hold.
				long key = i % 3 == 0 ? random.nextLong() : SceneMapper.key(3200 + random.nextInt(200), random.nextInt(32), -(5400 + random.nextInt(200)));
				if (random.nextBoolean())
				{
					String value = random.nextInt(10) == 0 ? null : "m" + random.nextInt(50);
					map.put(key, value);
					expected.put(key, value);
				}
				assertEquals(expected.containsKey(key), map.containsKey(key));
				assertEquals(expected.get(key), map.get(key));
				assertEquals(expected.size(), map.size());
			}
			for (Map.Entry<Long, String> entry : expected.entrySet())
			{
				assertEquals(entry.getValue(), map.get(entry.getKey()));
			}
			map.clear();
			expected.clear();
			assertEquals(0, map.size());
		}
	}
}
