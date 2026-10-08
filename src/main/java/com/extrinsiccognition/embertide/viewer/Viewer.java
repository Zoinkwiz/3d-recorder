package com.extrinsiccognition.embertide.viewer;

import java.awt.Component;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.Perspective;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOpened;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.callback.RenderCallbackManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Filepath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Plays a saved recording back in your POH. The plugin starts and stops it and registers it for events. */
public class Viewer
{
	private static final Logger LOG = LoggerFactory.getLogger(Viewer.class);
	@Inject private Client client;
	@Inject private ClientThread clientThread;
	@Inject private RenderCallbackManager renderCallbacks;
	private volatile boolean enabled;
	private volatile WatchPanel panel;
	private volatile Filepath folder;
	private ExecutorService worker;
	private Future<?> loadTask;
	private final AtomicLong generation = new AtomicLong();
	private final AtomicLong lifecycle = new AtomicLong();
	private final PlaybackClock clock = new PlaybackClock();
	private ReplayRenderer renderer;
	private HouseIsolation isolation;
	private Recording recording;
	private Recording.Scene selectedScene;
	private Recording.Sample sourceOrigin;
	private boolean house, loading;
	private boolean miniature = true, ground = true, scenery = true;
	private boolean hideSurroundings = true;
	private boolean surroundingMap;
	private boolean allFloors = true;
	private int extraArea = 12;
	private int size = 4, radius = 12, lift = 96, rotation;
	private int baseX, baseY, world;
	private long nextUi;
	private String filename = "Choose a saved recording to begin.";
	private String message = "Enter your POH to place a replay.";

	public void startUp()
	{
		lifecycle.incrementAndGet(); enabled = true;
		miniature = ground = scenery = hideSurroundings = true; size = 4; radius = 12; lift = 96; rotation = 0;
		surroundingMap = false; extraArea = 12; allFloors = true;
		clock.speed(1, System.nanoTime()); clock.loop(true);
		filename = "Choose a saved recording to begin."; message = "Enter your POH to place a replay.";
		worker = Executors.newSingleThreadExecutor(task -> { Thread t = new Thread(task, "Embertide recording reader"); t.setDaemon(true); return t; });
		renderer = new ReplayRenderer(client);
		isolation = new HouseIsolation(client, renderCallbacks);
		onClient(() -> { house = HouseGate.containsHouse(client.getTopLevelWorldView()); publish(); });
	}
	public void shutDown()
	{
		enabled = false; generation.incrementAndGet(); lifecycle.incrementAndGet();
		if (loadTask != null) { loadTask.cancel(true); }
		if (worker != null) { worker.shutdownNow(); }
		ReplayRenderer old = renderer;
		HouseIsolation oldIsolation = isolation;
		clientThread.invokeLater(() ->
		{
			try { if (old != null) { old.close(); } }
			finally { if (oldIsolation != null) { oldIsolation.close(); } }
		});
		recording = null; selectedScene = null; sourceOrigin = null;
		house = loading = false; clock.load(0, System.nanoTime());
		panel = null;
	}

	/** The Watch section; the sidebar panel adds it on the Swing thread. */
	public JComponent panel()
	{
		WatchPanel created = new WatchPanel(this); panel = created; onClient(this::publish); return created;
	}

	/** The plugin's own folder, the only place recordings are read from. */
	public void folder(Filepath folder) { this.folder = folder; }

	/** Opens a recording in the plugin's folder by file name. */
	public void load(String name)
	{
		Filepath where = folder;
		if (where == null) { onClient(() -> { message = "The recording folder is not ready yet."; publish(); }); return; }
		try { load(name, where.joinSegment(name)); }
		catch (RuntimeException e) { onClient(() -> { message = "Could not open recording: " + e.getMessage(); publish(); }); }
	}

	/** Lists the recordings in the plugin's folder, newest first, and opens the one picked. */
	void choose(Component parent)
	{
		Filepath where = folder;
		if (where == null || worker == null || worker.isShutdown()) { return; }
		worker.execute(() ->
		{
			List<String> names;
			try { names = recordings(where); }
			catch (IOException | RuntimeException e) { onClient(() -> { message = "Could not list your recordings: " + e.getMessage(); publish(); }); return; }
			SwingUtilities.invokeLater(() ->
			{
				if (names.isEmpty()) { JOptionPane.showMessageDialog(parent, "No saved recordings yet. Play, then stop recording or log out."); return; }
				JComboBox<String> list = new JComboBox<>(names.toArray(new String[0]));
				if (JOptionPane.showConfirmDialog(parent, list, "Watch a recording", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
				{ load((String) list.getSelectedItem()); }
			});
		});
	}

	static List<String> recordings(Filepath folder) throws IOException
	{
		try (Stream<Filepath> files = folder.walk(1))
		{
			return files.filter(f -> f.getFileName().endsWith(".embertide") || f.getFileName().endsWith(".mccr"))
				.sorted(Comparator.comparing(Viewer::modified).reversed()).map(Filepath::getFileName)
				.collect(Collectors.toList());
		}
	}
	private static long modified(Filepath file)
	{
		try { return file.getLastModifiedTime().toMillis(); } catch (IOException e) { return 0; }
	}

	private void load(String name, Filepath file)
	{
		if (!enabled || worker == null || worker.isShutdown()) { return; }
		long token = generation.incrementAndGet();
		if (loadTask != null) { loadTask.cancel(true); }
		onClient(() -> { loading = true; clock.play(false, System.nanoTime()); message = "Reading recording…"; publish(); });
		loadTask = worker.submit(() ->
		{
			try
			{
				Recording result = new RecordingReader().read(file);
				boolean ground = result.scenes.stream().anyMatch(scene -> scene.terrain.recorded());
				// Snapshot cells naming an underlay or overlay become a floor near where the player walked.
				boolean cells = result.scenes.stream().anyMatch(scene -> scene.ground.values().stream().anyMatch(g -> SnapshotTerrain.names(g.material)));
				onClient(() ->
				{
					if (token != generation.get()) { return; }
					clearProjection("Recording replaced."); renderer.close(); recording = result;
					clock.load(result.duration, System.nanoTime()); filename = name; loading = false;
					message = ground ? "Loaded. Choose a view and place it in your house."
						: "Loaded. " + (cells ? ReplayRenderer.PARTIAL_GROUND : ReplayRenderer.NO_GROUND); publish();
				});
			}
			catch (Exception e)
			{
				onClient(() -> { if (token == generation.get()) { loading = false; message = "Could not open recording: " + e.getMessage(); publish(); } });
			}
		});
	}
	void toggle() { onClient(() -> { if (renderer.placed() && !loading) { clock.play(!clock.playing(), System.nanoTime()); publish(); } }); }
	void seek(double time) { onClient(() -> { clock.seek(time, System.nanoTime()); draw(); publish(); }); }
	void speed(double speed) { onClient(() -> clock.speed(speed, System.nanoTime())); }
	void loop(boolean loop) { onClient(() -> clock.loop(loop)); }
	void clear() { onClient(() -> { clearProjection("Projection cleared."); publish(); }); }
	void rotate() { onClient(() -> { rotation = (rotation + 1) & 3; replace(); }); }
	void hideSurroundings(boolean hide)
	{
		onClient(() ->
		{
			hideSurroundings = hide;
			if (!hide) { isolation.close(); message = "House visible. Clear removes the replay."; }
			else if (!isolation.supported()) { message = isolation.problem(); }
			else if (renderer.placed())
			{
				isolation.hide(client.getTopLevelWorldView().getScene());
				message = "Surroundings hidden. Clear restores the house.";
			}
			publish();
		});
	}
	void settings(boolean miniature, int size, int radius, int lift, boolean ground, boolean scenery, boolean surroundingMap, int extraArea, boolean allFloors)
	{
		onClient(() ->
		{
			boolean geometryChanged = this.miniature != miniature || this.size != size || this.radius != radius || this.lift != lift
				|| this.surroundingMap != surroundingMap || (surroundingMap && this.extraArea != extraArea) || this.allFloors != allFloors;
			this.miniature = miniature; this.size = size; this.radius = radius; this.lift = lift;
			this.ground = ground; this.scenery = scenery;
			this.surroundingMap = surroundingMap; this.extraArea = Math.max(0, Math.min(24, extraArea));
			this.allFloors = allFloors;
			if (geometryChanged) { replace(); }
			publish();
		});
	}
	void placeHere()
	{ onClient(() -> { if (client.getLocalPlayer() != null) { place(client.getLocalPlayer().getLocalLocation(), true); } }); }

	void objectLimit(int limit)
	{ onClient(() -> { renderer.objectLimit(limit); draw(); publish(); }); }

	private void place(LocalPoint anchor, boolean newOrigin)
	{
		if (recording == null || loading || client.getGameState() != GameState.LOGGED_IN) { return; }
		WorldView view = client.getTopLevelWorldView();
		house = HouseGate.containsHouse(view);
		if (!house || anchor == null || view == null || anchor.getWorldView() != view.getId())
		{ message = "Enter your POH and place the replay near its exit portal."; publish(); return; }
		if (hideSurroundings && !isolation.supported()) { message = isolation.problem(); publish(); return; }
		Recording.Scene scene = recording.sceneAt(clock.time());
		if (newOrigin || sourceOrigin == null || scene != selectedScene) { sourceOrigin = scene.origin(clock.time()); }
		if (sourceOrigin == null) { clearProjection("This part has no recorded actors. Seek to another moment."); publish(); return; }
		renderer.place(scene, sourceOrigin, anchor, view.getPlane(), Perspective.getTileHeight(client, anchor, view.getPlane()),
			miniature, size, radius, rotation, lift, surroundingMap, extraArea, allFloors);
		if (hideSurroundings) { isolation.hide(view.getScene()); }
		selectedScene = scene; baseX = view.getBaseX(); baseY = view.getBaseY(); world = client.getWorld();
		message = miniature ? "Miniature placed. Press Play or scrub the timeline." : "Life-size scene placed around you. Press Play or scrub.";
		draw(); publish();
	}
	private void replace()
	{
		if (renderer.placed())
		{ Placement p = renderer.placement(); place(new LocalPoint(p.anchorX, p.anchorY, p.worldView), false); }
	}

	@Subscribe public void onClientTick(ClientTick event)
	{
		if (!enabled) { return; }
		try
		{
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				WorldView view = client.getTopLevelWorldView();
				if (isolation.active() && (isolation.rendererChanged() || view == null || !isolation.matches(view.getScene())))
				{ clearProjection("Renderer or scene changed. House restored; place the replay again."); }
				if (renderer.placed() && (view == null || view.getBaseX() != baseX || view.getBaseY() != baseY
					|| client.getWorld() != world || view.getPlane() != renderer.placement().plane))
				{ clearProjection("House scene changed. Place the replay again."); }
				clock.tick(System.nanoTime()); draw();
			}
			if (System.nanoTime() >= nextUi) { publish(); nextUi = System.nanoTime() + 200_000_000L; }
		}
		catch (RuntimeException e)
		{
			LOG.warn("Embertide projection stopped", e);
			clearProjection("Projection stopped: " + e.getClass().getSimpleName() + ". See the RuneLite log."); publish();
		}
	}
	@Subscribe public void onBeforeRender(BeforeRender event)
	{
		if (enabled && isolation != null && isolation.frame()) { clearProjection(HouseIsolation.DID_NOT_HIDE); publish(); }
	}
	private void draw()
	{
		if (recording == null || !renderer.placed() || !house) { return; }
		Recording.Scene now = recording.sceneAt(clock.time());
		Recording.Sample player = now.origin(clock.time());
		if (now != selectedScene || (!allFloors && player != null && sourceOrigin != null && player.plane != sourceOrigin.plane))
		{
			Placement p = renderer.placement(); place(new LocalPoint(p.anchorX, p.anchorY, p.worldView), true); return;
		}
		renderer.draw(recording, clock.time(), ground, scenery);
	}
	@Subscribe public void onGameTick(GameTick event)
	{
		house = HouseGate.containsHouse(client.getTopLevelWorldView());
		if (!house && renderer.placed()) { clearProjection("House no longer visible. Place the replay again inside your POH."); }
	}
	@Subscribe public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() != GameState.LOGGED_IN)
		{ house = false; clearProjection("Enter your POH and place the replay again."); publish(); }
	}
	@Subscribe public void onMenuOpened(MenuOpened event)
	{
		if (!house || recording == null || loading) { return; }
		WorldView view = client.getTopLevelWorldView();
		Tile tile = view == null ? null : view.getSelectedSceneTile();
		if (tile == null) { return; }
		LocalPoint at = tile.getLocalLocation();
		client.createMenuEntry(-1).setOption("Place Embertide replay").setTarget("").setType(MenuAction.RUNELITE)
			.onClick(entry -> place(at, true));
	}
	private void clearProjection(String notice)
	{
		try { if (renderer != null) { renderer.clear(); } }
		finally
		{
			if (isolation != null) { isolation.close(); }
			clock.play(false, System.nanoTime()); selectedScene = null; sourceOrigin = null; message = notice;
		}
	}
	private void onClient(Runnable task)
	{
		long epoch = lifecycle.get();
		if (!enabled) { return; }
		clientThread.invokeLater(() ->
		{
			if (!enabled || epoch != lifecycle.get()) { return; }
			try { task.run(); }
			catch (RuntimeException e)
			{
				LOG.warn("Embertide viewer action failed", e);
				clearProjection("Viewer action failed: " + e.getClass().getSimpleName() + ". See the RuneLite log."); publish();
			}
		});
	}
	private void publish()
	{
		WatchPanel current = panel; if (current == null) { return; }
		String file = filename, notice = message;
		String detail = recording == null ? "" : recording.scenes.size() + " recorded scene(s)"
			+ (recording.revision != client.getRevision() ? " · recorded cache " + recording.revision + ", current " + client.getRevision() : "")
			+ (renderer.placed() ? "\n" + renderer.status() : "");
		double elapsed = clock.time(), total = clock.duration();
		boolean playing = clock.playing(), ready = recording != null, placed = renderer.placed(), inHouse = house, busy = loading;
		SwingUtilities.invokeLater(() -> { if (enabled && current == panel) { current.update(file, notice, detail, elapsed, total, playing, ready, placed, inHouse, busy); } });
	}
}
