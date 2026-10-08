package com.extrinsiccognition.embertide.viewer;

import java.lang.reflect.Field;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.callback.RenderCallbackManager;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Exercise the real plugin's cleanup entry points, not just the filter itself. */
public class IsolationLifecycleTest
{
	private final Viewer plugin = new Viewer();
	private final Client client = mock(Client.class);
	private final WorldView view = mock(WorldView.class);
	private final Scene scene = mock(Scene.class);
	private final RenderCallbackManager callbacks = new RenderCallbackManager();
	private final HouseIsolation isolation = new HouseIsolation(client, callbacks);
	private final ReplayRenderer renderer = new ReplayRenderer(client);

	public IsolationLifecycleTest() throws Exception
	{
		when(client.getDrawCallbacks()).thenReturn(mock(DrawCallbacks.class)); when(client.getTopLevelWorldView()).thenReturn(view);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN); when(view.getScene()).thenReturn(scene);
		ClientThread thread = mock(ClientThread.class);
		doAnswer(invocation -> { invocation.<Runnable>getArgument(0).run(); return null; }).when(thread).invokeLater(any(Runnable.class));
		inject("client", client); inject("clientThread", thread); inject("renderer", renderer); inject("isolation", isolation); inject("enabled", true);
		renderer.place(new Recording.Scene(0, "osrs:surface"), new Recording.Sample(0, 3200.5, 3200.5, 0, 0, -1, 0, -1, -1),
			new LocalPoint(6400, 6400, -1), 0, 0, false, 4, 12, 0, 0, false, 0, false);
		isolation.hide(scene);
	}
	private void inject(String name, Object value) throws Exception
	{ Field field = Viewer.class.getDeclaredField(name); field.setAccessible(true); field.set(plugin, value); }
	private void restored()
	{
		assertFalse(renderer.placed()); assertFalse(isolation.active());
		assertTrue(callbacks.drawTile(scene, mock(Tile.class)));
	}
	@Test public void clearButtonRestoresTheHouse() { plugin.clear(); restored(); }
	@Test public void logoutRestoresTheHouse()
	{
		GameStateChanged event = mock(GameStateChanged.class); when(event.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
		plugin.onGameStateChanged(event); restored();
	}
	@Test public void leavingTheHouseRestoresIt() { plugin.onGameTick(new GameTick()); restored(); }
	@Test public void changingRendererRestoresTheHouse()
	{ when(client.getDrawCallbacks()).thenReturn(null); plugin.onClientTick(new ClientTick()); restored(); }
	@Test public void disablingPluginRestoresTheHouse() { plugin.shutDown(); restored(); }
	@Test public void aRendererThatNeverHidesTheHouseRestoresItAndSaysWhichWorks() throws Exception
	{
		for (int i = 0; i < HouseIsolation.CONFIRM_FRAMES; i++) { plugin.onBeforeRender(new BeforeRender()); }
		restored();
		Field message = Viewer.class.getDeclaredField("message"); message.setAccessible(true);
		assertEquals(HouseIsolation.DID_NOT_HIDE, message.get(plugin));
	}
	@Test public void aRendererThatHidesTheHouseKeepsTheReplay()
	{
		callbacks.drawTile(scene, mock(Tile.class));
		for (int i = 0; i < 100; i++) { plugin.onBeforeRender(new BeforeRender()); }
		assertTrue(renderer.placed()); assertTrue(isolation.active());
	}
}
