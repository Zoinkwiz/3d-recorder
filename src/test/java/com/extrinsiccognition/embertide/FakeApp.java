package com.extrinsiccognition.embertide;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** The app's recorder on loopback: open, append and the lifetime socket, enough to drop, kill and restart it. */
final class FakeApp implements AutoCloseable
{
	static final String ID = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
	static final String TOKEN = "pairing-secret";

	/** What outlives a restart, as the app's store keeps it on disk. */
	static final class Disk
	{
		final List<String> rows = Collections.synchronizedList(new ArrayList<>());
		String header;
		int committed;
		String lastBatch = "";
	}

	private final Gson gson = new Gson();
	final Disk disk;
	volatile String capability = UUID.randomUUID().toString();
	final List<String> opens = Collections.synchronizedList(new ArrayList<>());
	final List<String> appends = Collections.synchronizedList(new ArrayList<>());
	final AtomicInteger lifetimeAuths = new AtomicInteger();
	final AtomicInteger lifetimesOpen = new AtomicInteger();
	volatile boolean dieAfterNextCommit;
	volatile boolean closed;
	private final ServerSocket server;
	private final List<Socket> sockets = Collections.synchronizedList(new ArrayList<>());
	private final List<Socket> lifetimes = Collections.synchronizedList(new ArrayList<>());

	FakeApp(Disk disk) throws IOException
	{
		this.disk = disk;
		server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
		Thread accept = new Thread(() ->
		{
			while (!server.isClosed())
			{
				try
				{
					Socket socket = server.accept();
					sockets.add(socket);
					Thread serve = new Thread(() -> serve(socket), "fake-app-connection");
					serve.setDaemon(true);
					serve.start();
				}
				catch (IOException e)
				{
					return;
				}
			}
		}, "fake-app-accept");
		accept.setDaemon(true);
		accept.start();
	}

	RecorderEndpoint endpoint()
	{
		return new RecorderEndpoint("http://127.0.0.1:" + server.getLocalPort(), TOKEN, 1);
	}

	/** Names this app in endpoint.json, as the app does when it starts. */
	FakeApp publish() throws IOException
	{
		Path file = RecorderEndpoint.candidateFiles().get(0);
		// Never the running app's own file: a test that forgets to move the data home stops here.
		if (!file.toAbsolutePath().startsWith(Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath()))
		{
			throw new IllegalStateException("A test would write the real endpoint.json: " + file);
		}
		Files.createDirectories(file.getParent());
		Path temporary = file.resolveSibling("endpoint.json." + UUID.randomUUID() + ".tmp");
		Files.write(temporary, ("{\"version\":1,\"url\":\"" + endpoint().getUrl() + "\",\"origin\":\"" + RecorderEndpoint.ORIGIN
			+ "\",\"token\":\"" + TOKEN + "\",\"pid\":1}").getBytes(StandardCharsets.UTF_8));
		Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		return this;
	}

	/** The lifetime sockets close; HTTP stays up. */
	void dropLifetimes() throws IOException
	{
		synchronized (lifetimes)
		{
			for (Socket socket : lifetimes)
			{
				socket.close();
			}
			lifetimes.clear();
		}
	}

	/** The app no longer knows the capture's capability, as when it is closing or was replaced on this port. */
	void forget()
	{
		capability = UUID.randomUUID().toString();
	}

	/** The app is gone: nothing listens and every connection resets. */
	@Override
	public void close() throws IOException
	{
		closed = true;
		server.close();
		synchronized (sockets)
		{
			for (Socket socket : sockets)
			{
				socket.close();
			}
		}
	}

	private void serve(Socket socket)
	{
		try (Socket s = socket)
		{
			InputStream in = new BufferedInputStream(s.getInputStream());
			OutputStream out = s.getOutputStream();
			while (true)
			{
				String line = readLine(in);
				if (line == null || line.isEmpty())
				{
					return;
				}
				String path = line.split(" ")[1];
				Map<String, String> headers = new HashMap<>();
				for (String header = readLine(in); header != null && !header.isEmpty(); header = readLine(in))
				{
					int colon = header.indexOf(':');
					headers.put(header.substring(0, colon).trim().toLowerCase(Locale.ROOT), header.substring(colon + 1).trim());
				}
				if ("websocket".equalsIgnoreCase(headers.get("upgrade")))
				{
					lifetime(s, in, out, headers.get("sec-websocket-key"));
					return;
				}
				byte[] body = in.readNBytes(Integer.parseInt(headers.getOrDefault("content-length", "0")));
				String[] reply = route(path, headers.get("authorization"), new String(body, StandardCharsets.UTF_8));
				if (reply == null)
				{
					return;
				}
				byte[] bytes = reply[1].getBytes(StandardCharsets.UTF_8);
				out.write(("HTTP/1.1 " + reply[0] + " Fake\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length + "\r\n\r\n")
					.getBytes(StandardCharsets.UTF_8));
				out.write(bytes);
				out.flush();
			}
		}
		catch (IOException | RuntimeException ignored)
		{
			// the connection is gone
		}
	}

	/** Status and body, or null to drop the connection without a reply. */
	private String[] route(String path, String authorization, String text)
	{
		JsonObject body = new JsonParser().parse(text).getAsJsonObject();
		if (path.equals("/v1/mccr/open"))
		{
			opens.add(text);
			if (!("Bearer " + TOKEN).equals(authorization))
			{
				return new String[]{"403", "{\"error\":\"Recording authentication failed.\"}"};
			}
			String header = gson.toJson(body.get("header"));
			int next;
			synchronized (disk)
			{
				if (disk.header == null)
				{
					disk.header = header;
				}
				else if (!disk.header.equals(header))
				{
					return new String[]{"409", "{\"error\":\"Recording identity conflicts with its header.\"}"};
				}
				next = disk.committed;
			}
			return new String[]{"200", "{\"id\":\"" + ID + "\",\"nextSequence\":" + next + ",\"capability\":\"" + capability + "\"}"};
		}
		if (path.equals("/v1/mccr/" + ID + "/append"))
		{
			appends.add(text);
			if (!("Bearer " + capability).equals(authorization))
			{
				return new String[]{"403", "{\"error\":\"Recording authentication failed.\"}"};
			}
			int sequence = body.get("sequence").getAsInt();
			String records = gson.toJson(body.get("records"));
			synchronized (disk)
			{
				if (sequence == disk.committed - 1 && records.equals(disk.lastBatch))
				{
					return new String[]{"200", "{\"ack\":" + sequence + "}"};
				}
				if (sequence != disk.committed)
				{
					return new String[]{"409", "{\"error\":\"Recording sequence conflict or interrupted disk write.\"}"};
				}
				body.getAsJsonArray("records").forEach(row -> disk.rows.add(gson.toJson(row)));
				disk.lastBatch = records;
				disk.committed++;
			}
			if (dieAfterNextCommit)
			{
				// Kept on disk, then gone before the reply.
				try
				{
					close();
				}
				catch (IOException ignored)
				{
					// already closing
				}
				return null;
			}
			return new String[]{"200", "{\"ack\":" + sequence + "}"};
		}
		return new String[]{"404", "{}"};
	}

	private void lifetime(Socket socket, InputStream in, OutputStream out, String key) throws IOException
	{
		String accept;
		try
		{
			accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
				.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.UTF_8)));
		}
		catch (java.security.NoSuchAlgorithmException e)
		{
			throw new IOException(e);
		}
		out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n")
			.getBytes(StandardCharsets.UTF_8));
		out.flush();
		lifetimes.add(socket);
		String auth = readFrame(in);
		JsonObject presented = auth == null ? null : new JsonParser().parse(auth).getAsJsonObject();
		if (presented == null || !ID.equals(presented.get("id").getAsString()) || !capability.equals(presented.get("capability").getAsString()))
		{
			return;
		}
		lifetimeAuths.incrementAndGet();
		lifetimesOpen.incrementAndGet();
		try
		{
			byte[] ok = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
			out.write(new byte[]{(byte) 0x81, (byte) ok.length});
			out.write(ok);
			out.flush();
			while (readFrame(in) != null)
			{
				// held open until the client closes it or the app goes
			}
		}
		finally
		{
			lifetimesOpen.decrementAndGet();
		}
	}

	/** One client frame's text, or null on a close frame or end of stream. */
	private static String readFrame(InputStream in) throws IOException
	{
		int first = in.read();
		int second = in.read();
		if (first < 0 || second < 0 || (first & 0x0f) == 8)
		{
			return null;
		}
		long length = second & 0x7f;
		if (length == 126)
		{
			length = (in.read() << 8) | in.read();
		}
		else if (length == 127)
		{
			length = 0;
			for (int i = 0; i < 8; i++)
			{
				length = (length << 8) | in.read();
			}
		}
		byte[] mask = (second & 0x80) != 0 ? in.readNBytes(4) : null;
		byte[] payload = in.readNBytes((int) length);
		for (int i = 0; mask != null && i < payload.length; i++)
		{
			payload[i] ^= mask[i % 4];
		}
		return new String(payload, StandardCharsets.UTF_8);
	}

	private static String readLine(InputStream in) throws IOException
	{
		ByteArrayOutputStream line = new ByteArrayOutputStream();
		for (int b = in.read(); b >= 0; b = in.read())
		{
			if (b == '\n')
			{
				String text = line.toString(StandardCharsets.UTF_8);
				return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
			}
			line.write(b);
		}
		return line.size() == 0 ? null : line.toString(StandardCharsets.UTF_8);
	}
}
