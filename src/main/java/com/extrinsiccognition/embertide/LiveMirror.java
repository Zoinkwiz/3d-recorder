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
	/** The file, when it and the client share a Gson: each row is then written once for both. */
	private final MccrFileRecorder shared;
	private volatile boolean mirroring = true;
	private volatile String stoppedBecause = "";

	public LiveMirror(Recorder file, MccrRecorderClient live)
	{
		this.file = file;
		this.live = live;
		this.shared = file instanceof MccrFileRecorder && ((MccrFileRecorder) file).gson() == live.gson() ? (MccrFileRecorder) file : null;
	}

	public Recorder file()
	{
		return file;
	}

	MccrRecorderClient live()
	{
		return live;
	}

	public String liveNote()
	{
		if (!stoppedBecause.isEmpty())
		{
			return "Live view stopped: " + stoppedBecause + " The recording is unaffected.";
		}
		if (live.reconnecting())
		{
			return "Reconnecting the live view to Embertide…";
		}
		if (live.lifetime() == MccrRecorderClient.Lifetime.CONNECTED)
		{
			return "Showing live in Embertide on this computer.";
		}
		return live.state() == State.OPENING || live.lifetime() == MccrRecorderClient.Lifetime.CONNECTING
			? "Connecting the live view to Embertide…" : "";
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
				live.stop(because);
			}
			catch (RuntimeException e)
			{
				log.debug("stopping the live link failed", e);
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
				stopMirroring(live.error().isEmpty() ? "Embertide would not start it." : live.error());
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
		byte[] line = shared == null ? null : shared.line(record);
		if (mirroring)
		{
			try
			{
				// The client writes the row's text before it returns, so the row needs no copy.
				if (!(line == null ? live.enqueue(record, control) : live.enqueue(line, control)))
				{
					stopMirroring(live.error().isEmpty() ? "Embertide stopped taking rows." : live.error());
				}
			}
			catch (RuntimeException e)
			{
				stopMirroring("Embertide stopped taking rows.");
			}
		}
		return line == null ? file.enqueue(record, control) : shared.enqueue(record, line, control);
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
				// Finished, not vanished: close it so the app doesn't count a lost client.
				live.finish().whenComplete((ignored, error) ->
				{
					if (error != null)
					{
						return;
					}
					try
					{
						live.closeLifetime();
					}
					catch (RuntimeException e)
					{
						log.debug("closing the live lifetime after the seal failed", e);
					}
				}).exceptionally(error -> null);
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
