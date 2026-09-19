package com.extrinsiccognition.embertide;

import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;

/**
 * Where a memory's records go: a file on this computer, in RuneLite's own
 * folder. The Embertide app reads that folder, and embertide.gg/studio takes
 * the file dragged in, so the plugin itself never opens a connection to
 * anything. This seam is what the capture writes to and what a test can
 * stand in for.
 */
public interface Recorder
{
	enum State
	{
		IDLE, OPENING, READY, FINISHING, COMPLETE, FAILED
	}

	State state();

	String error();

	String captureId();

	/** Begin a memory with its header. Completes when the destination accepted it. */
	CompletableFuture<Void> open(JsonObject header);

	/** Queue one record. False means the queue refused it, which ends the capture on the caller's side. */
	boolean enqueue(JsonObject record, boolean control);

	CompletableFuture<Void> flush();

	/** Seal the memory: everything queued is written, and nothing more is taken. */
	CompletableFuture<Void> finish();

	/** Show the sealed memory to the person: the folder it is in, and the web Studio it can be dragged into. */
	CompletableFuture<Void> openStudio();

	/** A line for the panel about where this memory is going, or empty. */
	String destination();
}
