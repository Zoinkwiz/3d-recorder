package com.extrinsiccognition.embertide;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Gson's text for a row, written into a reused buffer instead of a fresh StringWriter. */
final class RowWriter extends Writer
{
	private static final int START = 8192;
	private static final int KEEP = 1 << 18;
	private char[] buffer = new char[START];
	private int count;

	/** The UTF-8 bytes of gson.toJson(row) and a newline. */
	synchronized byte[] line(Gson gson, JsonElement row)
	{
		count = 0;
		gson.toJson(row, this);
		write('\n');
		byte[] line = ascii();
		if (line == null) { line = new String(buffer, 0, count).getBytes(StandardCharsets.UTF_8); }
		if (buffer.length > KEEP) { buffer = new char[START]; }
		return line;
	}

	/** The text as bytes when it is all ASCII, where UTF-8 is one byte a char; otherwise null. */
	private byte[] ascii()
	{
		byte[] bytes = new byte[count];
		for (int i = 0; i < count; i++)
		{
			char c = buffer[i];
			if (c >= 0x80) { return null; }
			bytes[i] = (byte) c;
		}
		return bytes;
	}

	private void room(int more)
	{
		if (count + more > buffer.length) { buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, count + more)); }
	}

	@Override
	public void write(int c)
	{
		room(1);
		buffer[count++] = (char) c;
	}

	@Override
	public void write(char[] chars, int offset, int length)
	{
		room(length);
		System.arraycopy(chars, offset, buffer, count, length);
		count += length;
	}

	@Override
	public void write(String text, int offset, int length)
	{
		room(length);
		text.getChars(offset, offset + length, buffer, count);
		count += length;
	}

	@Override
	public void flush()
	{
	}

	@Override
	public void close()
	{
	}
}
