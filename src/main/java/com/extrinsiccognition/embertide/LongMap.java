package com.extrinsiccognition.embertide;

import java.util.Arrays;

/** A map with long keys that are not boxed, probed linearly; no removal. */
final class LongMap<V>
{
	private long[] keys = new long[64];
	private Object[] values = new Object[64];
	private boolean[] used = new boolean[64];
	private int size;

	int size()
	{
		return size;
	}

	boolean containsKey(long key)
	{
		return used[slot(key)];
	}

	@SuppressWarnings("unchecked")
	V get(long key)
	{
		int slot = slot(key);
		return used[slot] ? (V) values[slot] : null;
	}

	void put(long key, V value)
	{
		int slot = slot(key);
		if (!used[slot])
		{
			if ((size + 1) * 2 > keys.length)
			{
				grow();
				slot = slot(key);
			}
			used[slot] = true;
			keys[slot] = key;
			size++;
		}
		values[slot] = value;
	}

	void clear()
	{
		Arrays.fill(used, false);
		Arrays.fill(values, null);
		size = 0;
	}

	/** The key's slot, or the free slot where it would go. */
	private int slot(long key)
	{
		int mask = keys.length - 1;
		int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> 40) & mask;
		while (used[slot] && keys[slot] != key)
		{
			slot = (slot + 1) & mask;
		}
		return slot;
	}

	private void grow()
	{
		long[] oldKeys = keys;
		Object[] oldValues = values;
		boolean[] oldUsed = used;
		keys = new long[oldKeys.length * 2];
		values = new Object[oldKeys.length * 2];
		used = new boolean[oldKeys.length * 2];
		for (int i = 0; i < oldKeys.length; i++)
		{
			if (oldUsed[i])
			{
				int slot = slot(oldKeys[i]);
				used[slot] = true;
				keys[slot] = oldKeys[i];
				values[slot] = oldValues[i];
			}
		}
	}
}
