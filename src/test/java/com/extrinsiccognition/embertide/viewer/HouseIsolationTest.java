package com.extrinsiccognition.embertide.viewer;

import net.runelite.api.*;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.callback.RenderCallbackManager;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class HouseIsolationTest
{
	private final Client client = mock(Client.class);
	// Any zone renderer: RuneLite's GPU plugin and 117 HD both implement DrawCallbacks.
	private final DrawCallbacks gpu = mock(DrawCallbacks.class);
	private final Scene scene = mock(Scene.class);
	private final WorldView view = mock(WorldView.class);
	private final Player self = mock(Player.class);
	private final RenderCallbackManager callbacks = new RenderCallbackManager();
	private final HouseIsolation isolation = new HouseIsolation(client, callbacks);

	public HouseIsolationTest()
	{
		when(client.getDrawCallbacks()).thenReturn(gpu); when(client.getTopLevelWorldView()).thenReturn(view);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN); when(client.getLocalPlayer()).thenReturn(self);
		when(view.getScene()).thenReturn(scene);
		Tile[][][] tiles = new Tile[4][16][16];
		tiles[0][0][0] = mock(Tile.class); tiles[0][1][0] = mock(Tile.class); tiles[1][0][0] = mock(Tile.class);
		tiles[0][8][9] = mock(Tile.class); when(scene.getExtendedTiles()).thenReturn(tiles);
	}
	@Test public void hidesAllHouseGeometryAndInvalidatesEachOccupiedZoneOnce()
	{
		isolation.hide(scene);
		assertFalse(callbacks.drawTile(scene, mock(Tile.class)));
		assertFalse(callbacks.drawObject(scene, mock(WallObject.class)));
		assertFalse(callbacks.drawObject(scene, mock(GroundObject.class)));
		assertFalse(callbacks.drawObject(scene, mock(DecorativeObject.class)));
		assertFalse(callbacks.drawObject(scene, mock(ItemLayer.class)));
		GameObject furniture = mock(GameObject.class); when(furniture.getHash()).thenReturn(2L << 16);
		assertFalse(callbacks.drawObject(scene, furniture));
		verify(gpu).invalidateZone(scene, 0, 0); verify(gpu).invalidateZone(scene, 1, 1); verifyNoMoreInteractions(gpu);
		verify(scene, never()).removeTile(any()); verify(scene, never()).removeGameObject(any());
	}
	@Test public void preservesSelfAndReplayRenderablesWhileHidingLiveVisitorsAndPets()
	{
		isolation.hide(scene);
		for (boolean ui : new boolean[]{true, false})
		{
			assertTrue(callbacks.addEntity(self, ui)); assertFalse(callbacks.addEntity(mock(Player.class), ui));
			assertFalse(callbacks.addEntity(mock(NPC.class), ui)); assertFalse(callbacks.addEntity(mock(Projectile.class), ui));
		}
		assertFalse(callbacks.addEntity(mock(GraphicsObject.class), false));
		GameObject projected = mock(GameObject.class); when(projected.getRenderable()).thenReturn(mock(Renderable.class));
		assertTrue(callbacks.drawObject(scene, projected));
		when(projected.getHash()).thenReturn(-1L); assertTrue(callbacks.drawObject(scene, projected));
		when(projected.getRenderable()).thenReturn(self); assertTrue(callbacks.drawObject(scene, projected));
		when(projected.getRenderable()).thenReturn(mock(Player.class)); assertFalse(callbacks.drawObject(scene, projected));
	}
	@Test public void restoreUnregistersFilterAndReuploadsTheUnmodifiedHouse()
	{
		isolation.hide(scene); isolation.close(); isolation.close();
		assertFalse(isolation.active()); assertTrue(callbacks.drawTile(scene, mock(Tile.class)));
		assertTrue(callbacks.drawObject(scene, mock(WallObject.class))); assertTrue(callbacks.addEntity(mock(NPC.class), false));
		verify(gpu, times(2)).invalidateZone(scene, 0, 0); verify(gpu, times(2)).invalidateZone(scene, 1, 1);
	}
	@Test public void rePlacementInSameHouseDoesNotFlickerOrReuploadEveryTick()
	{
		isolation.hide(scene); isolation.hide(scene); isolation.hide(scene);
		verify(gpu).invalidateZone(scene, 0, 0); verify(gpu).invalidateZone(scene, 1, 1);
		isolation.close(); assertTrue(callbacks.drawTile(scene, mock(Tile.class)));
	}
	@Test public void incomingScenesAreNeverHiddenOrRestoredFromOldReferences()
	{
		isolation.hide(scene); Scene next = mock(Scene.class);
		assertTrue(callbacks.drawTile(next, mock(Tile.class))); assertTrue(callbacks.drawObject(next, mock(WallObject.class)));
		when(view.getScene()).thenReturn(next); isolation.close();
		verify(gpu).invalidateZone(scene, 0, 0); verify(gpu, never()).invalidateZone(eq(next), anyInt(), anyInt());
	}
	@Test public void logoutDisablesTheFilterWithoutTouchingDiscardedGpuBuffers()
	{
		isolation.hide(scene); when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN); isolation.close();
		assertTrue(callbacks.addEntity(mock(Player.class), false));
		verify(gpu).invalidateZone(scene, 0, 0); verify(gpu).invalidateZone(scene, 1, 1);
	}
	@Test public void rendererSwitchRestoresWithoutTakingOverTheNewRenderer()
	{
		isolation.hide(scene); DrawCallbacks next = mock(DrawCallbacks.class); when(client.getDrawCallbacks()).thenReturn(next);
		assertTrue(isolation.rendererChanged()); isolation.close();
		assertFalse(isolation.active()); assertTrue(callbacks.drawTile(scene, mock(Tile.class)));
		verify(client, never()).setDrawCallbacks(any()); verify(next, never()).invalidateZone(any(), anyInt(), anyInt());
	}
	@Test public void unsupportedRendererCannotSilentlyClaimToHideTheFloor()
	{
		when(client.getDrawCallbacks()).thenReturn(null);
		try { isolation.hide(scene); fail("Expected renderer requirement"); }
		catch (IllegalStateException expected) { assertEquals(HouseIsolation.NEEDS_GPU, expected.getMessage()); }
		assertFalse(isolation.active()); assertTrue(callbacks.drawTile(scene, mock(Tile.class)));
	}
	@Test public void failedGpuInvalidationRollsBackItsRegisteredFilter()
	{
		doThrow(new IllegalStateException("GPU unavailable")).when(gpu).invalidateZone(scene, 0, 0);
		try { isolation.hide(scene); fail("Expected GPU error"); } catch (IllegalStateException expected) { }
		assertFalse(isolation.active()); assertTrue(callbacks.drawTile(scene, mock(Tile.class)));
	}
	@Test public void aRendererThatAsksAboutTheHouseIsConfirmedAndKeepsItHidden()
	{
		isolation.hide(scene); assertFalse(isolation.confirmed());
		assertFalse(callbacks.drawTile(scene, mock(Tile.class))); assertTrue(isolation.confirmed());
		for (int i = 0; i < 100; i++) { assertFalse(isolation.frame()); }
		assertTrue(isolation.active()); assertNull(isolation.problem());
	}
	@Test public void aRendererThatNeverAsksIsNamedAfterItsFramesAndNotTriedAgain()
	{
		isolation.hide(scene);
		for (int i = 1; i < HouseIsolation.CONFIRM_FRAMES; i++) { assertFalse(isolation.frame()); }
		assertTrue(isolation.frame()); isolation.close();
		assertEquals(HouseIsolation.DID_NOT_HIDE, isolation.problem());
		try { isolation.hide(scene); fail("Expected the renderer to be named"); }
		catch (IllegalStateException expected) { assertEquals(HouseIsolation.DID_NOT_HIDE, expected.getMessage()); }
		when(client.getDrawCallbacks()).thenReturn(mock(DrawCallbacks.class)); assertNull(isolation.problem());
	}
	@Test public void framesBeforeHidingOrAfterRestoringCountForNothing()
	{
		assertFalse(isolation.frame()); isolation.hide(scene); isolation.close();
		for (int i = 0; i < 100; i++) { assertFalse(isolation.frame()); }
		assertNull(isolation.problem());
	}
}
