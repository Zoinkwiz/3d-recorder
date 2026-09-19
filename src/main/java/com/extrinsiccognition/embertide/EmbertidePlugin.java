package com.extrinsiccognition.embertide;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.OverheadTextChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.DecorativeObjectDespawned;
import net.runelite.api.events.DecorativeObjectSpawned;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GroundObjectDespawned;
import net.runelite.api.events.GroundObjectSpawned;
import net.runelite.api.events.WallObjectDespawned;
import net.runelite.api.events.WallObjectSpawned;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "3D Replay Recorder",
	internalName = "embertide",
	legacyDataDirectory = "embertide",
	description = "Records your session as a 3D replay you can fly a camera through and cut into clips at embertide.gg. Nothing leaves your machine; other players appear as unnamed figures.",
	tags = {"3d", "replay", "recorder", "recording", "clips", "video", "camera", "embertide"}
)
public class EmbertidePlugin extends Plugin
{
	static final class SavedFile
	{
		final String name;
		final int part;
		final double seconds;
		final String problem;
		SavedFile(String name, int part, double seconds, String problem)
		{
			this.name = name;
			this.part = part;
			this.seconds = seconds;
			this.problem = problem;
		}
	}

	static final class PanelState
	{
		final String text;
		final String current;
		final java.util.List<SavedFile> saved;
		final boolean recording;
		final boolean canToggle;
		PanelState(String text, String current, java.util.List<SavedFile> saved, boolean recording, boolean canToggle)
		{
			this.text = text;
			this.current = current;
			this.saved = saved;
			this.recording = recording;
			this.canToggle = canToggle;
		}
	}

	@Inject
	private Client client;
	@Inject
	private ClientThread clientThread;
	@Inject
	private ClientToolbar clientToolbar;
	@Inject
	private EmbertideConfig config;
	@Inject
	private Gson gson;
	@Inject
	private java.util.concurrent.ScheduledExecutorService executor;

	private EmbertidePanel panel;
	private NavigationButton navigationButton;
	private volatile OsrsCapture capture;
	private volatile String notice = "Ready to record.";
	private boolean rediscover = true;
	private SceneIdentity sceneIdentity;
	private volatile net.runelite.client.util.Filepath folder;
	private volatile boolean enabled;
	private volatile boolean loggedIn;
	private volatile boolean preparingFolder;
	private volatile boolean manual;
	private volatile boolean continuing;
	private volatile int uiGeneration;
	private volatile OsrsCapture pendingSave;
	private volatile CompletableFuture<Void> save = CompletableFuture.completedFuture(null);
	private volatile boolean handingOff;
	/** Manual stop persists across logins until Start is pressed. */
	private volatile boolean stopped;
	private String series;
	private int nextPart;
	private final java.util.Map<String, String> pseudonyms = new java.util.HashMap<>();
	private final java.util.List<SavedFile> saved = new java.util.concurrent.CopyOnWriteArrayList<>();

	@Provides
	EmbertideConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(EmbertideConfig.class);
	}

	@Override
	protected void startUp()
	{
		enabled = true;
		loggedIn = client.getGameState() == GameState.LOGGED_IN;
		prepareFolder();
		int generation = ++uiGeneration;
		javax.swing.SwingUtilities.invokeLater(() ->
		{
			if (!enabled || generation != uiGeneration) { return; }
			panel = new EmbertidePanel(this);
			navigationButton = NavigationButton.builder()
				.tooltip("Embertide")
				.icon(icon())
				.priority(7)
				.panel(panel)
				.build();
			clientToolbar.addNavigation(navigationButton);
		});
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			clientThread.invokeLater(() -> beginCapture(true));
		}
	}

	@Override
	protected void shutDown()
	{
		enabled = false;
		continuing = false;
		++uiGeneration;
		EmbertidePanel closingPanel = panel;
		NavigationButton closingButton = navigationButton;
		javax.swing.SwingUtilities.invokeLater(() ->
		{
			if (closingButton != null) { clientToolbar.removeNavigation(closingButton); }
			if (closingPanel != null) { closingPanel.dispose(); }
		});
		clientThread.invoke(() -> endCapture("plugin stopped", false, true));
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		loggedIn = event.getGameState() == GameState.LOGGED_IN;
		switch (event.getGameState())
		{
			case LOADING:
				if (capture != null) { capture.loading(true); }
				rediscover = true;
				break;
			case LOGGED_IN:
				if (capture != null) { capture.loading(false); }
				rediscover = true;
				if (capture == null)
				{
					beginCapture(true);
				}
				else if (!SceneIdentity.of(client.getTopLevelWorldView()).equals(sceneIdentity))
				{
					changeScene();
				}
				break;
			case LOGIN_SCREEN:
				continuing = false;
			case HOPPING:
			case CONNECTION_LOST:
				endCapture("logged out", event.getGameState() == GameState.LOGIN_SCREEN && config.openStudioOnLogout(), true);
				series = null;
				break;
			default:
				break;
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		loggedIn = client.getGameState() == GameState.LOGGED_IN;
		OsrsCapture current = capture;
		if (current == null)
		{
			if (wantsRecording())
			{
				beginCapture(true);
			}
			current = capture;
			if (current == null) { return; }
		}
		if (!SceneIdentity.of(client.getTopLevelWorldView()).equals(sceneIdentity))
		{
			changeScene();
			current = capture;
			if (current == null) { return; }
		}
		if (current.exhausted())
		{
			endCapture(current.reason(), false, true);
			beginCapture(true);
			current = capture;
			if (current == null) { return; }
		}
		if (!current.recording())
		{
			return;
		}
		long began = System.nanoTime();
		boolean opening = rediscover;
		current.tick(rediscover);
		rediscover = false;
		long spent = System.nanoTime() - began;
		long tookMs = spent / 1_000_000L;
		gameTickNanos += spent;
		if (opening || tookMs > 50)
		{
			log.debug("embertide game tick took {} ms ({}; {} client ticks since the last)", tookMs, opening ? "opening" : "steady", clientTicks);
		}
		clientTicks = 0;
		tally();
		if (opening)
		{
			current.clientTick();
		}
	}

	private int clientTicks;
	private long gameTickNanos;
	private long clientTickNanos;
	private long tallySince = System.nanoTime();

	private void tally()
	{
		long now = System.nanoTime();
		long wall = now - tallySince;
		if (wall < 10_000_000_000L)
		{
			return;
		}
		OsrsCapture current = capture;
		log.debug("embertide cost over {} s: game ticks {} ms, client ticks {} ms, {}% of one core{}",
			wall / 1_000_000_000L, gameTickNanos / 1_000_000L, clientTickNanos / 1_000_000L,
			(gameTickNanos + clientTickNanos) * 100L / wall,
			current == null ? "" : "; " + current.status());
		gameTickNanos = 0;
		clientTickNanos = 0;
		tallySince = now;
	}

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		OsrsCapture current = capture;
		clientTicks++;
		if (current != null && current.recording() && !rediscover && client.getGameState() == GameState.LOGGED_IN)
		{
			long began = System.nanoTime();
			current.clientTick();
			clientTickNanos += System.nanoTime() - began;
		}
	}

	@Subscribe
	public void onNpcSpawned(net.runelite.api.events.NpcSpawned event)
	{
		if (capture != null) { capture.actorSpawned(event.getNpc()); }
	}

	@Subscribe
	public void onNpcDespawned(net.runelite.api.events.NpcDespawned event)
	{
		if (capture != null) { capture.actorDespawned(event.getNpc()); }
	}

	@Subscribe
	public void onPlayerSpawned(net.runelite.api.events.PlayerSpawned event)
	{
		if (capture != null) { capture.actorSpawned(event.getPlayer()); }
	}

	@Subscribe
	public void onPlayerDespawned(net.runelite.api.events.PlayerDespawned event)
	{
		if (capture != null) { capture.actorDespawned(event.getPlayer()); }
	}

	@Subscribe
	public void onOverheadTextChanged(OverheadTextChanged event)
	{
		OsrsCapture current = capture;
		Actor actor = event.getActor();
		if (current == null || rediscover || !config.recordOverheadText() || actor == null)
		{
			return;
		}
		if (!(actor instanceof NPC) || !config.trackNpcs())
		{
			return;
		}
		current.overhead(actor, event.getOverheadText(), client.getLocalPlayer());
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		OsrsCapture current = capture;
		if (current == null || rediscover)
		{
			return;
		}
		current.hitsplat(event.getActor(), event.getHitsplat(), client.getLocalPlayer());
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		OsrsCapture current = capture;
		if (current == null || rediscover)
		{
			return;
		}
		current.actorDied(event.getActor(), client.getLocalPlayer());
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		OsrsCapture current = capture;
		if (current == null)
		{
			return;
		}
		current.statChanged(event.getSkill(), event.getLevel());
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		OsrsCapture current = capture;
		Player me = client.getLocalPlayer();
		if (current == null || me == null || !config.recordOwnChat() || event.getType() != ChatMessageType.PUBLICCHAT)
		{
			return;
		}
		if (!Text.removeTags(event.getName()).equalsIgnoreCase(Text.removeTags(me.getName())))
		{
			return;
		}
		current.ownChat(Text.removeTags(event.getMessage()), me.getName());
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		objectChanged(event.getTile(), event.getGameObject(), true);
	}

	@Subscribe
	public void onGameObjectDespawned(GameObjectDespawned event)
	{
		objectChanged(event.getTile(), event.getGameObject(), false);
	}

	@Subscribe
	public void onWallObjectSpawned(WallObjectSpawned event)
	{
		objectChanged(event.getTile(), event.getWallObject(), true);
	}

	@Subscribe
	public void onWallObjectDespawned(WallObjectDespawned event)
	{
		objectChanged(event.getTile(), event.getWallObject(), false);
	}

	@Subscribe
	public void onGroundObjectSpawned(GroundObjectSpawned event)
	{
		objectChanged(event.getTile(), event.getGroundObject(), true);
	}

	@Subscribe
	public void onGroundObjectDespawned(GroundObjectDespawned event)
	{
		objectChanged(event.getTile(), event.getGroundObject(), false);
	}

	@Subscribe
	public void onDecorativeObjectSpawned(DecorativeObjectSpawned event)
	{
		objectChanged(event.getTile(), event.getDecorativeObject(), true);
	}

	@Subscribe
	public void onDecorativeObjectDespawned(DecorativeObjectDespawned event)
	{
		objectChanged(event.getTile(), event.getDecorativeObject(), false);
	}

	private void objectChanged(net.runelite.api.Tile tile, net.runelite.api.TileObject object, boolean spawned)
	{
		OsrsCapture current = capture;
		// Scene-load spawns are included in the next baseline rather than emitted as transitions.
		if (current == null || rediscover || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		current.objectChanged(tile, object, spawned);
	}

	@Subscribe
	public void onClientShutdown(ClientShutdown event)
	{
		enabled = false;
		event.waitFor(endCapture("client closed", false, true));
	}

	/** Folder migration can touch disk, so it runs on RuneLite's worker. */
	private synchronized void prepareFolder()
	{
		if (preparingFolder || folder != null) { return; }
		preparingFolder = true;
		notice = "Preparing the recording folder…";
		executor.execute(() ->
		{
			try
			{
				folder = getPluginDirectory();
				notice = "Ready to record.";
			}
			catch (java.io.IOException | RuntimeException e)
			{
				notice = "Could not prepare the recording folder: " + rootMessage(e)
					+ "\nPress Start recording to retry.";
			}
			finally { preparingFolder = false; }
		});
	}

	String folderPath()
	{
		return folder == null ? "The recording folder is not ready yet." : folder.toString();
	}

	boolean canRecover()
	{
		OsrsCapture pending = pendingSave;
		return pending != null && pending.recorder().state() == Recorder.State.FAILED;
	}

	void retrySave()
	{
		clientThread.invoke(() ->
		{
			if (canRecover())
			{
				handingOff = true;
				watchSave(pendingSave, pendingSave.recorder().finish());
			}
		});
	}

	void discardSave()
	{
		clientThread.invoke(() ->
		{
			if (canRecover())
			{
				((MccrFileRecorder) pendingSave.recorder()).discard();
				pendingSave = null;
				save = CompletableFuture.completedFuture(null);
				notice = "Unsaved recording discarded.";
			}
		});
	}

	void startRecording()
	{
		stopped = false;
		manual = true;
		if (folder == null) { prepareFolder(); }
		clientThread.invoke(() ->
		{
			if (capture == null && client.getGameState() == GameState.LOGGED_IN)
			{
				beginCapture(true);
			}
		});
	}

	void stopRecording()
	{
		stopped = true;
		manual = false;
		continuing = false;
		clientThread.invoke(() -> endCapture("stopped by you", false, true));
	}

	PanelState panelState()
	{
		OsrsCapture current = capture;
		java.util.List<SavedFile> files = new java.util.ArrayList<>(saved);
		StringBuilder text = new StringBuilder();
		if (current == null)
		{
			OsrsCapture pending = pendingSave;
			boolean recover = pending != null && pending.recorder().state() == Recorder.State.FAILED;
			boolean resume = enabled && !stopped && (manual || continuing || config.recordOnLogin());
			text.append(handingOff ? "Saving your recording." + (resume ? " Recording resumes after it is saved." : "")
				: recover ? "Recording paused. The unsaved recording is still in memory.\n\n"
					+ pending.recorder().error() + "\n\nRetry saving, or discard it. Closing RuneLite loses this unsaved recording."
				: stopped ? "Not recording." : notice);
			boolean in = loggedIn;
			if (!in)
			{
				text.append("\n\nLog in to start recording.");
			}
			else if (stopped && pending == null)
			{
				text.append("\n\nWhat you played so far is kept. Press Start recording to begin a new one.");
			}
			return new PanelState(text.toString(), "", files, handingOff && resume,
				in && !recover && (pending == null || handingOff));
		}
		text.append(current.status());
		Recorder recorder = current.recorder();
		if (recorder.state() == Recorder.State.FAILED)
		{
			text.append("\n\nCannot save: ").append(recorder.error());
		}
		else
		{
			text.append("\n\nEach recording is written to its file when it ends. Other players are recorded as unnamed figures.");
		}
		boolean busy = !current.recording();
		String name = recorder instanceof MccrFileRecorder && ((MccrFileRecorder) recorder).path() != null
			? ((MccrFileRecorder) recorder).path().getFileName().toString()
			: "";
		return new PanelState(text.toString(), name, files, !busy, !busy);
	}

	private boolean wantsRecording()
	{
		return enabled && !stopped && (manual || continuing || config.recordOnLogin())
			&& client.getGameState() == GameState.LOGGED_IN;
	}

	static String fileStem(String playerName)
	{
		String who = playerName == null ? "" : playerName.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
		String when = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"));
		return "osrs-" + when + (who.isEmpty() ? "" : "-" + who);
	}

	private void changeScene()
	{
		WorldView view = client.getTopLevelWorldView();
		sceneIdentity = SceneIdentity.of(view);
		OsrsCapture current = capture;
		if (current != null) { current.sceneChanged(view); }
		rediscover = true;
	}

	private void beginCapture(boolean freshBaseline)
	{
		if (!wantsRecording() || capture != null || pendingSave != null || handingOff || folder == null)
		{
			return;
		}
		Player me = client.getLocalPlayer();
		WorldView view = client.getTopLevelWorldView();
		if (view == null || me == null) { return; }
		sceneIdentity = SceneIdentity.of(view);
		String dimension = sceneIdentity.dimension();
		if (series == null)
		{
			series = java.util.UUID.randomUUID().toString();
			nextPart = 1;
			pseudonyms.clear();
		}
		// Resolve the filename on the first scene tick, when the character name is available.
		Recorder recorder = new MccrFileRecorder(gson, folder, () ->
		{
			Player who = client.getLocalPlayer();
			return fileStem(who == null ? null : who.getName());
		}, executor);
		OsrsCapture next = new OsrsCapture(client, recorder, config, dimension, me == null ? null : me.getName(), client.getWorld(),
			series, nextPart++, pseudonyms, config.chunkMinutes() * 60.0);
		capture = next;
		continuing = true;
		rediscover = freshBaseline;
		notice = "Recording. The file is saved when this part ends.";
		next.start().whenComplete((ignored, throwable) ->
		{
			if (throwable != null)
			{
				notice = "Could not start the recording file: " + rootMessage(throwable);
				log.debug("capture open failed", throwable);
			}
		});
	}

	private CompletableFuture<Void> endCapture(String reason, boolean openStudio)
	{
		return endCapture(reason, openStudio, false);
	}

	private CompletableFuture<Void> endCapture(String reason, boolean openStudio, boolean planned)
	{
		OsrsCapture current = capture;
		capture = null;
		if (current == null)
		{
			return save;
		}
		pendingSave = current;
		handingOff = true;
		return watchSave(current, current.finish(reason, openStudio, planned));
	}

	private CompletableFuture<Void> watchSave(OsrsCapture current, CompletableFuture<Void> result)
	{
		// Publication is updated before the returned future completes, including on
		// client shutdown when another client-thread callback cannot be relied on.
		save = result.whenComplete((ignored, throwable) ->
		{
			if (throwable == null)
			{
				if (current.recorder().state() == Recorder.State.COMPLETE) { remember(current, null); }
				pendingSave = null;
				notice = current.recorder().state() == Recorder.State.IDLE
					? "Recording cancelled before the first scene." : "Recording saved.";
			}
			else
			{
				notice = "Recording paused: retry saving or discard the unsaved recording.";
				log.warn("Could not save Embertide recording; retained in memory for retry", throwable);
			}
			handingOff = false;
		});
		return save;
	}

	private void remember(OsrsCapture ended, Throwable throwable)
	{
		Recorder recorder = ended.recorder();
		String name = recorder instanceof MccrFileRecorder && ((MccrFileRecorder) recorder).path() != null
			? ((MccrFileRecorder) recorder).path().getFileName().toString()
			: "recording " + ended.part();
		String problem = throwable == null ? "" : rootMessage(throwable);
		if (throwable == null && recorder.state() == Recorder.State.FAILED)
		{
			problem = recorder.error();
		}
		saved.add(new SavedFile(name, ended.part(), ended.seconds(), problem));
	}

	private static String rootMessage(Throwable throwable)
	{
		Throwable cause = throwable;
		while (cause.getCause() != null && cause.getCause() != cause)
		{
			cause = cause.getCause();
		}
		return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
	}

	private static BufferedImage icon()
	{
		BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0xE8, 0x86, 0x3A));
		g.fillOval(2, 2, 12, 12);
		g.setColor(new Color(0xFF, 0xE0, 0xAD));
		g.fillOval(6, 5, 4, 4);
		g.dispose();
		return image;
	}
}
