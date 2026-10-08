package com.extrinsiccognition.embertide.viewer;

import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.GraphicsObject;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Projectile;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.callback.RenderCallback;
import net.runelite.client.callback.RenderCallbackManager;

/**
 * Hides scene submissions, leaving the house and its collision data intact. Works with any renderer that rebuilds
 * zones on invalidateZone and asks RenderCallbackManager about each tile: RuneLite's GPU plugin and 117 HD's default
 * renderer do. Whether this one does is confirmed by it asking about the hidden house, not by its class.
 */
final class HouseIsolation implements RenderCallback, AutoCloseable
{
	static final String NEEDS_GPU = "Hide surroundings needs a GPU renderer. Turn on RuneLite's GPU plugin or 117 HD, or untick Hide surroundings.";
	static final String DID_NOT_HIDE = "Your renderer does not hide the house. Hide surroundings works with RuneLite's GPU plugin and 117 HD, "
		+ "but not 117 HD's legacy renderer. Untick Hide surroundings to place the replay with your house showing.";
	/** Drawn frames to wait for the renderer to rebuild the house before concluding it never asks. */
	static final int CONFIRM_FRAMES = 30;
	private final Client client;
	private final RenderCallbackManager callbacks;
	// GPU uploads can run on the map-loader thread. Publish the scene identity,
	// not a flag that might accidentally hide an incoming non-house scene.
	private volatile Scene hiddenScene;
	private volatile Player localPlayer;
	private volatile boolean confirmed;
	private DrawCallbacks renderer, failedRenderer;
	private int frames;

	HouseIsolation(Client client, RenderCallbackManager callbacks)
	{ this.client = client; this.callbacks = callbacks; }

	/** Why hiding cannot work with the current renderer, or null when it can be tried. */
	String problem()
	{
		DrawCallbacks current = client.getDrawCallbacks();
		return current == null ? NEEDS_GPU : current == failedRenderer ? DID_NOT_HIDE : null;
	}
	boolean supported() { return problem() == null; }
	boolean active() { return hiddenScene != null; }
	boolean matches(Scene scene) { return hiddenScene == scene; }
	boolean confirmed() { return confirmed; }
	/** The renderer hidden with is no longer the client's. */
	boolean rendererChanged() { return hiddenScene != null && client.getDrawCallbacks() != renderer; }

	/** Count a drawn frame; true once the renderer has had CONFIRM_FRAMES to ask about the house and has not. */
	boolean frame()
	{
		if (hiddenScene == null || confirmed || ++frames < CONFIRM_FRAMES) { return false; }
		failedRenderer = renderer;
		return true;
	}

	void hide(Scene scene)
	{
		String problem = problem();
		if (problem != null) { throw new IllegalStateException(problem); }
		if (scene == null) { throw new IllegalArgumentException("No house scene is loaded."); }
		if (hiddenScene == scene) { return; }
		close();
		renderer = client.getDrawCallbacks(); confirmed = false; frames = 0;
		localPlayer = client.getLocalPlayer(); hiddenScene = scene;
		callbacks.register(this);
		try { invalidate(scene, renderer); }
		catch (RuntimeException e)
		{
			hiddenScene = null; localPlayer = null; renderer = null; callbacks.unregister(this);
			throw e;
		}
	}

	@Override public boolean drawTile(Scene scene, Tile tile)
	{
		if (scene != hiddenScene) { return true; }
		confirmed = true;
		return false;
	}

	@Override public boolean drawObject(Scene scene, TileObject object)
	{
		if (scene != hiddenScene) { return true; }
		confirmed = true;
		if (!(object instanceof GameObject)) { return false; }
		// TileObject's documented hash layout puts entity type in bits 16..18.
		// Static house objects are type 2. RuneLite projections are temporary
		// objects; their zero/-1 tags are not map objects. Keep their draw path.
		int type = (int) (object.getHash() >>> 16 & 7);
		if (type >= 1 && type <= 4) { return false; }
		return keepEntity(((GameObject) object).getRenderable());
	}

	@Override public boolean addEntity(Renderable entity, boolean ui)
	{ return hiddenScene == null || keepEntity(entity); }

	private boolean keepEntity(Renderable entity)
	{
		if (entity instanceof Player) { return entity == localPlayer; }
		// Replay controllers use their own renderable, not live Actor objects.
		return !(entity instanceof NPC || entity instanceof Projectile || entity instanceof GraphicsObject || entity instanceof Scene);
	}

	@Override public void close()
	{
		Scene previous = hiddenScene;
		DrawCallbacks previousRenderer = renderer;
		if (previous == null) { return; }
		hiddenScene = null; localPlayer = null; renderer = null;
		callbacks.unregister(this);
		WorldView view = client.getTopLevelWorldView();
		// A scene reload already rebuilds the incoming scene, and a new renderer
		// uploads its own. Never invalidate a discarded scene or another renderer.
		if (view != null && view.getScene() == previous && client.getGameState() == GameState.LOGGED_IN
			&& previousRenderer != null && client.getDrawCallbacks() == previousRenderer)
		{ invalidate(previous, previousRenderer); }
	}

	private static void invalidate(Scene scene, DrawCallbacks renderer)
	{
		// Zone renderers cache static geometry by 8x8 extended-scene zone. Changing
		// a callback alone cannot hide an already uploaded floor or restore it.
		Tile[][][] tiles = scene.getExtendedTiles();
		if (tiles == null) { return; }
		int zones = Constants.EXTENDED_SCENE_SIZE >> 3;
		boolean[][] touched = new boolean[zones][zones];
		for (Tile[][] plane : tiles)
		{
			if (plane == null) { continue; }
			for (int x = 0; x < Math.min(plane.length, zones * 8); x++)
			{
				Tile[] column = plane[x]; if (column == null) { continue; }
				for (int y = 0; y < Math.min(column.length, zones * 8); y++)
				{
					if (column[y] != null && !touched[x >> 3][y >> 3])
					{
						touched[x >> 3][y >> 3] = true;
						renderer.invalidateZone(scene, x >> 3, y >> 3);
					}
				}
			}
		}
	}
}
