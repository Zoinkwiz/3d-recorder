package com.extrinsiccognition.embertide;

import com.extrinsiccognition.embertide.viewer.Viewer;
import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.api.events.SoundEffectPlayed;
import net.runelite.api.events.AreaSoundEffectPlayed;
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
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.PlayerDespawned;
import net.runelite.api.events.PlayerSpawned;
import net.runelite.api.events.WallObjectDespawned;
import net.runelite.api.events.WallObjectSpawned;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.Filepath;
import net.runelite.client.util.HotkeyListener;
import net.runelite.client.util.Text;
import net.runelite.http.api.loottracker.LootRecordType;

@Slf4j
@PluginDescriptor(
	name = "3D Replay Recorder",
	internalName = "3d-recorder",
	legacyDataDirectory = "embertide",
	description = "Records your session as a 3D replay you can fly a camera through and cut into clips at embertide.gg, or watch in your POH. Nothing leaves your machine; other players appear as unnamed figures.",
	tags = {"3d", "replay", "recorder", "recording", "clips", "video", "camera", "embertide", "viewer", "poh"}
)
public class EmbertidePlugin extends Plugin
{
	static final class SavedFile
	{
		final String name;
		final double seconds;
		SavedFile(String name, double seconds)
		{
			this.name = name;
			this.seconds = seconds;
		}
	}

	static final class PanelState
	{
		final String headline;
		final String text;
		final String current;
		final List<SavedFile> saved;
		final boolean recording;
		final boolean canToggle;
		PanelState(String headline, String text, String current, List<SavedFile> saved, boolean recording, boolean canToggle)
		{
			this.headline = headline;
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
	private EventBus eventBus;
	@Inject
	private Viewer viewer;
	@Inject
	private EmbertideConfig config;
	@Inject
	private Gson gson;
	@Inject
	private ConfigManager configManager;
	@Inject
	private ScheduledExecutorService executor;
	@Inject
	private ItemManager itemManager;
	@Inject
	private KeyManager keyManager;

	private EmbertidePanel panel;
	private NavigationButton navigationButton;
	private volatile OsrsCapture capture;
	private volatile String notice = "Ready to record.";
	private boolean rediscover = true;
	private SceneIdentity sceneIdentity;
	private volatile Filepath folder;
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
	/**
	 * How long a hop or a lost connection keeps the series for the same account. The server keeps a
	 * dropped player in the world for about a minute; five also covers logging back in by hand after
	 * the client gives up reconnecting. A longer break is a new sitting.
	 */
	static final long KEEP_SERIES_NANOS = TimeUnit.MINUTES.toNanos(5);
	private String series;
	private int nextPart;
	/** When a hop or lost connection held the series, or -1. */
	private long heldSince = -1;
	private long heldAccount;
	LongSupplier nanos = System::nanoTime;
	/** Seconds in this series' finished parts. */
	private volatile double earlier;
	private final Map<String, String> pseudonyms = new HashMap<>();
	private final List<SavedFile> saved = new CopyOnWriteArrayList<>();
	private final HotkeyListener hotkey = new HotkeyListener(() -> config.recordHotkey())
	{
		@Override
		public void hotkeyPressed()
		{
			toggleRecording();
		}
	};

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
		viewer.startUp();
		eventBus.register(viewer);
		keyManager.registerKeyListener(hotkey);
		prepareFolder();
		int generation = ++uiGeneration;
		SwingUtilities.invokeLater(() ->
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
			clientThread.invokeLater(this::beginCapture);
		}
	}

	@Override
	protected void shutDown()
	{
		enabled = false;
		continuing = false;
		keyManager.unregisterKeyListener(hotkey);
		++uiGeneration;
		EmbertidePanel closingPanel = panel;
		NavigationButton closingButton = navigationButton;
		SwingUtilities.invokeLater(() ->
		{
			if (closingButton != null) { clientToolbar.removeNavigation(closingButton); }
			if (closingPanel != null) { closingPanel.dispose(); }
		});
		clientThread.invoke(() -> endCapture("plugin stopped", false));
		eventBus.unregister(viewer);
		viewer.shutDown();
	}

	Viewer viewer()
	{
		return viewer;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		loggedIn = event.getGameState() == GameState.LOGGED_IN;
		if (capture != null) { capture.gameState(event.getGameState().name()); }
		switch (event.getGameState())
		{
			case LOADING:
				if (capture != null) { capture.loading(true); }
				rediscover = true;
				break;
			case LOGGED_IN:
				if (capture != null) { capture.loading(false); }
				rediscover = true;
				if (heldSince >= 0)
				{
					long account = client.getAccountHash();
					if (account == -1 || account != heldAccount || nanos.getAsLong() - heldSince > KEEP_SERIES_NANOS)
					{
						series = null;
					}
					heldSince = -1;
				}
				if (capture == null)
				{
					beginCapture();
				}
				else if (!SceneIdentity.of(client.getTopLevelWorldView()).equals(sceneIdentity))
				{
					changeScene();
				}
				break;
			case LOGIN_SCREEN:
				continuing = false;
				endCapture("logged out", config.openStudioOnLogout());
				// A logout ends the sitting; a drop that fell back to this screen waits for the next login.
				if (heldSince < 0)
				{
					series = null;
				}
				break;
			case HOPPING:
			case CONNECTION_LOST:
				// The next part starts on arrival; LOGGED_IN decides whether it continues this series.
				endCapture("logged out", false);
				if (series != null && heldSince < 0)
				{
					heldSince = nanos.getAsLong();
					heldAccount = client.getAccountHash();
				}
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
				beginCapture();
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
			endCapture(current.reason(), false);
			beginCapture();
			current = capture;
			if (current == null) { return; }
		}
		if (!current.recording())
		{
			return;
		}
		boolean opening = rediscover;
		if (current.tick(rediscover)) { rediscover = false; }
		if (opening)
		{
			current.clientTick();
		}
	}

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		OsrsCapture current = capture;
		if (current != null && current.recording() && !rediscover && client.getGameState() == GameState.LOGGED_IN)
		{
			current.clientTick();
		}
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		if (capture != null) { capture.actorSpawned(event.getNpc()); }
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		if (capture != null) { capture.actorDespawned(event.getNpc()); }
	}

	@Subscribe
	public void onPlayerSpawned(PlayerSpawned event)
	{
		if (capture != null) { capture.actorSpawned(event.getPlayer()); }
	}

	@Subscribe
	public void onPlayerDespawned(PlayerDespawned event)
	{
		if (capture != null) { capture.actorDespawned(event.getPlayer()); }
	}

	@Subscribe
	public void onOverheadTextChanged(OverheadTextChanged event)
	{
		OsrsCapture current = capture;
		Actor actor = event.getActor();
		if (current == null || rediscover || !config.recordOverheadText() || !(actor instanceof NPC) || !config.trackNpcs())
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
	public void onProjectileMoved(ProjectileMoved event)
	{
		OsrsCapture current = capture;
		if (current == null || rediscover) { return; }
		current.projectile(event.getProjectile(), client.getLocalPlayer());
	}

	@Subscribe
	public void onSoundEffectPlayed(SoundEffectPlayed event)
	{
		OsrsCapture current = capture;
		if (current == null || rediscover) { return; }
		current.sound(event.getSoundId(), event.getDelay(), event.getSource(), null, null, null, client.getLocalPlayer());
	}

	@Subscribe
	public void onAreaSoundEffectPlayed(AreaSoundEffectPlayed event)
	{
		OsrsCapture current = capture;
		if (current == null || rediscover) { return; }
		current.sound(event.getSoundId(), event.getDelay(), event.getSource(), event.getSceneX(), event.getSceneY(), event.getRange(), client.getLocalPlayer());
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
		current.statChanged(event.getSkill(), event.getLevel(), event.getXp(), event.getBoostedLevel());
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		OsrsCapture current = capture;
		Player me = client.getLocalPlayer();
		if (current == null || me == null)
		{
			return;
		}
		if (event.getType() == ChatMessageType.GAMEMESSAGE || event.getType() == ChatMessageType.SPAM)
		{
			// Allowlisted kinds only.
			if (config.recordMoments())
			{
				current.gameMessage(GameMessages.match(event.getType(), event.getMessage()));
			}
			return;
		}
		if (!config.recordOwnChat() || event.getType() != ChatMessageType.PUBLICCHAT)
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
	public void onNpcLootReceived(NpcLootReceived event)
	{
		OsrsCapture current = capture;
		if (current == null || !config.recordMoments())
		{
			return;
		}
		NPC npc = event.getNpc();
		current.loot("npc", npc == null ? null : npc.getName(), npc, priced(event.getItems()));
	}

	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		OsrsCapture current = capture;
		// NPC kills arrive via NpcLootReceived.
		if (current == null || !config.recordMoments() || event.getType() == null
			|| event.getType() == LootRecordType.NPC)
		{
			return;
		}
		String kind = event.getType().name().toLowerCase(Locale.ROOT);
		// Never record another player's name.
		current.loot(kind, "player".equals(kind) ? null : event.getName(), null, priced(event.getItems()));
	}

	/** [item id, quantity, GE price each]; client thread. */
	private List<int[]> priced(Collection<ItemStack> items)
	{
		List<int[]> out = new ArrayList<>();
		if (items == null)
		{
			return out;
		}
		for (ItemStack stack : items)
		{
			long each = itemManager.getItemPrice(itemManager.canonicalize(stack.getId()));
			out.add(new int[]{stack.getId(), stack.getQuantity(), (int) Math.min(each, Integer.MAX_VALUE)});
		}
		return out;
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

	@Subscribe
	public void onItemSpawned(ItemSpawned event)
	{
		OsrsCapture current = capture;
		if (current == null || rediscover || client.getGameState() != GameState.LOGGED_IN) { return; }
		current.itemChanged(event.getTile(), event.getItem(), true);
	}

	@Subscribe
	public void onItemDespawned(ItemDespawned event)
	{
		OsrsCapture current = capture;
		if (current == null || rediscover || client.getGameState() != GameState.LOGGED_IN) { return; }
		current.itemChanged(event.getTile(), event.getItem(), false);
	}

	private void objectChanged(Tile tile, TileObject object, boolean spawned)
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
		event.waitFor(endCapture("client closed", false));
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
				viewer.folder(folder);
				notice = "Ready to record.";
			}
			catch (IOException | RuntimeException e)
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
		return saveFailed(pendingSave);
	}

	private static boolean saveFailed(OsrsCapture pending)
	{
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

	/** The side panel's button and the hotkey: nothing while the button is greyed out. */
	void toggleRecording()
	{
		PanelState state = panelState();
		if (!state.canToggle)
		{
			return;
		}
		if (state.recording)
		{
			stopRecording();
		}
		else
		{
			startRecording();
		}
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
				beginCapture();
			}
		});
	}

	void stopRecording()
	{
		stopped = true;
		manual = false;
		continuing = false;
		clientThread.invoke(() -> endCapture("stopped by you", false));
	}

	PanelState panelState()
	{
		OsrsCapture current = capture;
		List<SavedFile> files = new ArrayList<>(saved);
		StringBuilder text = new StringBuilder();
		if (current == null)
		{
			OsrsCapture pending = pendingSave;
			boolean recover = saveFailed(pending);
			boolean resume = resumes();
			text.append(handingOff ? "Saving your recording." + (resume ? " Recording resumes after it is saved." : "")
				: recover ? "Recording paused. The unsaved part is kept as a .partial file.\n\n"
					+ pending.recorder().error() + "\n\nRetry saving, or discard it."
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
			return new PanelState(handingOff ? "Saving…" : "Not recording", text.toString(), "", files, handingOff && resume,
				in && !recover && (pending == null || handingOff));
		}
		// The headline carries the time, so only the capture's other states are repeated here.
		String line = current.status();
		if (!line.startsWith("Recording"))
		{
			text.append(line).append("\n\n");
		}
		Recorder recorder = current.recorder();
		if (recorder.state() == Recorder.State.FAILED)
		{
			text.append("Cannot save: ").append(recorder.error());
		}
		else
		{
			text.append("Other players are recorded as unnamed figures.");
		}
		boolean recording = current.recording();
		String headline = recording ? "Recording · " + clock(earlier + current.seconds()) : "Not recording";
		return new PanelState(headline, text.toString(), fileName(recorder, ""), files, recording, recording);
	}

	static String clock(double seconds)
	{
		int s = (int) Math.max(0, seconds);
		return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
			: String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
	}

	/** Whether recording should go on once the game allows it. */
	private boolean resumes()
	{
		return enabled && !stopped && (manual || continuing || config.recordOnLogin());
	}

	private boolean wantsRecording()
	{
		return resumes() && client.getGameState() == GameState.LOGGED_IN;
	}

	static String fileStem(String playerName)
	{
		String who = playerName == null ? "" : playerName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
		String when = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"));
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

	private void beginCapture()
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
			series = UUID.randomUUID().toString();
			nextPart = 1;
			earlier = 0;
			pseudonyms.clear();
		}
		// Resolve the filename on the first scene tick, when the character name is available.
		Recorder recorder = new MccrFileRecorder(gson, folder, () ->
		{
			Player who = client.getLocalPlayer();
			return fileStem(who == null ? null : who.getName());
		}, executor);
		OsrsCapture next = new OsrsCapture(client, recorder, config, dimension, me.getName(), client.getWorld(),
			series, nextPart++, pseudonyms, config.chunkMinutes() * 60.0);
		next.account(accountKey());
		capture = next;
		continuing = true;
		rediscover = true;
		notice = "Recording.";
		next.start().whenComplete((ignored, throwable) ->
		{
			if (throwable != null)
			{
				notice = "Could not start the recording file: " + rootMessage(throwable);
				log.debug("capture open failed", throwable);
			}
		});
	}

	/** Opaque account key; the salt lives in plugin settings. */
	private String accountKey()
	{
		String salt = configManager.getConfiguration(EmbertideConfig.GROUP, AccountKey.SALT_KEY);
		if (salt == null || salt.isEmpty())
		{
			salt = AccountKey.newSalt();
			configManager.setConfiguration(EmbertideConfig.GROUP, AccountKey.SALT_KEY, salt);
		}
		return AccountKey.of(salt, client.getAccountHash());
	}

	private CompletableFuture<Void> endCapture(String reason, boolean openStudio)
	{
		OsrsCapture current = capture;
		capture = null;
		if (current == null)
		{
			return save;
		}
		pendingSave = current;
		handingOff = true;
		CompletableFuture<Void> finishing = current.finish(reason, openStudio, true);
		earlier += current.seconds();
		return watchSave(current, finishing);
	}

	private CompletableFuture<Void> watchSave(OsrsCapture current, CompletableFuture<Void> result)
	{
		// Publication is updated before the returned future completes, including on
		// client shutdown when another client-thread callback cannot be relied on.
		save = result.whenComplete((ignored, throwable) ->
		{
			if (throwable == null)
			{
				if (current.recorder().state() == Recorder.State.COMPLETE)
				{
					saved.add(new SavedFile(fileName(current.recorder(), "recording " + current.part()), current.seconds()));
				}
				pendingSave = null;
				notice = current.recorder().state() == Recorder.State.IDLE
					? "Recording cancelled before the first scene." : "Recording saved.";
			}
			else
			{
				notice = "Recording paused: retry saving or discard the unsaved recording.";
				log.warn("Could not save recording", throwable);
			}
			handingOff = false;
		});
		return save;
	}

	private static String fileName(Recorder recorder, String otherwise)
	{
		Filepath path = recorder instanceof MccrFileRecorder ? ((MccrFileRecorder) recorder).path() : null;
		return path == null ? otherwise : path.getFileName().toString();
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
