package com.extrinsiccognition.embertide;

import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;

public interface Recorder
{
	enum State
	{
		IDLE, OPENING, READY, FINISHING, COMPLETE, FAILED
	}

	State state();

	String error();

	String captureId();

	CompletableFuture<Void> open(JsonObject header);

	/** Return false when admission fails; the caller must stop adding observations. */
	boolean enqueue(JsonObject record, boolean control);

	default boolean full() { return false; }

	CompletableFuture<Void> flush();

	CompletableFuture<Void> finish();

	CompletableFuture<Void> openStudio();

	String destination();
}
