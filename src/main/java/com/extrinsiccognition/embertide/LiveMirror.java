package com.extrinsiccognition.embertide;

import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;

/** Writes the file and mirrors each row to the local app; the file stays the source of truth. */
@Slf4j
public final class LiveMirror implements Recorder
{
	private final Recorder file;
	private final MccrRecorderClient live;
	private volatile boolean mirroring = true;
	private volatile String stoppedBecause = "";

	public LiveMirror(Recorder file, MccrRecorderClient live)
	{
		this.file = file;
		this.live = live;
	}

	public Recorder file()
	{
		return file;
	}

	public String liveNote()
	{
		if (!stoppedBecause.isEmpty())
		{
			return "Live view stopped: " + stoppedBecause + " The recording is unaffected.";
		}
		switch (live.lifetime())
		{
			case CONNECTED:
				return "Showing live in Embertide on this computer.";
			case CONNECTING:
				return "Connecting the live view to Embertide…";
			default:
				return "";
		}
	}

	private void stopMirroring(String because)
	{
		if (mirroring)
		{
			mirroring = false;
			stoppedBecause = because;
			log.debug("live mirror stopped: {}", because);
			try
			{
				live.closeLifetime();
			}
			catch (RuntimeException e)
			{
				log.debug("closing the live lifetime failed", e);
			}
		}
	}

	@Override
	public CompletableFuture<Void> open(JsonObject header)
	{
		// Deep copy: the client serialises on its own thread.
		JsonObject forLive = header.deepCopy();
		try
		{
			live.open(forLive).exceptionally(error ->
			{
				stopMirroring("Embertide would not start it.");
				return null;
			});
		}
		catch (RuntimeException e)
		{
			stopMirroring("Embertide would not start it.");
		}
		return file.open(header);
	}

	@Override
	public boolean enqueue(JsonObject record, boolean control)
	{
		if (mirroring)
		{
			try
			{
				if (!live.enqueue(record.deepCopy(), control))
				{
					stopMirroring(live.error().isEmpty() ? "Embertide stopped taking rows." : live.error());
				}
			}
			catch (RuntimeException e)
			{
				stopMirroring("Embertide stopped taking rows.");
			}
		}
		return file.enqueue(record, control);
	}

	@Override
	public CompletableFuture<Void> flush()
	{
		if (mirroring)
		{
			live.flush().exceptionally(error -> null);
		}
		return file.flush();
	}

	@Override
	public CompletableFuture<Void> finish()
	{
		if (mirroring)
		{
			try
			{
				live.finish().exceptionally(error -> null);
			}
			catch (RuntimeException e)
			{
				log.debug("sealing the live copy failed", e);
			}
		}
		return file.finish();
	}

	@Override
	public CompletableFuture<Void> openStudio()
	{
		return file.openStudio();
	}

	@Override
	public String destination()
	{
		return file.destination();
	}

	@Override
	public State state()
	{
		return file.state();
	}

	@Override
	public String error()
	{
		return file.error();
	}

	@Override
	public String captureId()
	{
		return file.captureId();
	}

	@Override
	public boolean full()
	{
		return file.full();
	}
}
