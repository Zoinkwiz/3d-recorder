package gg.embertide.runelite;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import net.runelite.api.Client;
import net.runelite.api.NameableContainer;
import net.runelite.api.Nameable;
import net.runelite.api.Player;
import net.runelite.api.WidgetNode;
import net.runelite.api.WorldView;
import net.runelite.api.clan.ClanChannel;
import net.runelite.api.clan.ClanChannelMember;
import net.runelite.api.clan.ClanID;
import net.runelite.api.clan.ClanMember;
import net.runelite.api.clan.ClanSettings;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetType;

/**
 * Every open interface, the bank included, as osrs.widgets rows, with Record the game interface on.
 * A group's first row in a part is whole; after that a row carries only what scripts changed.
 * Positions are relative to the parent's content, as the client lays them out, so scrolling the bank writes one scroll, not every item.
 * Privacy: the PIN, welcome, name entry and report screens are never read; the chatbox and private chat are written
 * as open only (osrs.chatbox keeps the allowed lines); social screens keep their layout with no text; typed input is
 * never written; anywhere else the player's own name reads "You" and any other player the client knows of (nearby,
 * friends, ignores, chat channel, clans) reads as their "Adventurer N" stand-in, or is cut.
 */
final class WidgetRows
{
	static final String TYPE = "osrs.widgets";
	static final int PER_ROW = 256, ROWS_PER_TICK = 24, MAX_STATIC = 1500, MAX_DYNAMIC = 2500, MAX_TEXT = 160, MAX_GROUPS = 96;

	private static final Set<Integer> SECRET = Set.of(InterfaceID.BANKPIN_KEYPAD, InterfaceID.BANKPIN_SETTINGS, InterfaceID.WELCOME_SCREEN,
		InterfaceID.DISPLAYNAME, InterfaceID.TUTORIAL_DISPLAYNAME, InterfaceID.BUGREPORT, InterfaceID.REPORTABUSE);
	private static final Set<Integer> OPEN_ONLY = Set.of(InterfaceID.CHATBOX, InterfaceID.PM_CHAT);
	private static final Set<Integer> NO_TEXT = new HashSet<>(List.of(InterfaceID.FRIENDS, InterfaceID.IGNORE, InterfaceID.CHATCHANNEL_CURRENT,
		InterfaceID.CHATCHANNEL_SETUP, InterfaceID.SIDE_CHANNELS, InterfaceID.SIDE_CHANNELS_LARGE, InterfaceID.TRADEMAIN, InterfaceID.TRADECONFIRM,
		InterfaceID.TRADESIDE, InterfaceID.CASTLEWARS_TRADE, InterfaceID.GROUPING, InterfaceID.HISCORES, InterfaceID.GIM_RAFFLE,
		InterfaceID.GIM_SIDEPANEL, InterfaceID.GIM_LIMITED_SIDEPANEL, InterfaceID.GIM_LEAVING, InterfaceID.GIM_CREATION_SIDEPANEL,
		InterfaceID.GIM_SETTINGS, InterfaceID.GIM_OPTIONS, InterfaceID.TOB_PARTYDETAILS, InterfaceID.TOB_PARTYLIST, InterfaceID.TOB_HUD,
		InterfaceID.TOB_SCOREBOARD, InterfaceID.TOA_PARTYLIST, InterfaceID.TOA_LOBBY, InterfaceID.TOA_PARTYDETAILS, InterfaceID.TOA_HUD,
		InterfaceID.TOA_SCOREBOARD, InterfaceID.TOA_RAID_SUMMARY, InterfaceID.RAIDS_LOBBY_PARTYLIST, InterfaceID.RAIDS_LOBBY_PARTYDETAILS,
		InterfaceID.RAIDS_SIDEPANEL, InterfaceID.RAIDS_OVERLAY, InterfaceID.BARBASSAULT_OVER_RECRUIT_PLAYER_NAMES,
		InterfaceID.BARBASSAULT_PLAYERSTAT, InterfaceID.SOUL_WARS_LOBBY, InterfaceID.SOUL_WARS_CLAN_GAMES, InterfaceID.DEADMAN_TOURNAMENT_VIEWER,
		InterfaceID.DEADMAN_SPECTATOR, InterfaceID.BR_TOURNAMENT_VIEWER, InterfaceID.BR_LOBBYOVERLAY, InterfaceID.CLAN_CUP_HUD));

	static
	{
		for (int g = InterfaceID.CLANS_BANNED; g <= InterfaceID.CLANS_PERMISSIONS; g++) { NO_TEXT.add(g); }
		for (int g = InterfaceID.CLANWARS_HUD; g <= InterfaceID.CLANWARS_VIEW; g++) { NO_TEXT.add(g); }
		for (int g = InterfaceID.PVP_ARENA_LEGACYDUEL_OPTIONS; g <= InterfaceID.PVP_ARENA_1V1_INFO; g++) { NO_TEXT.add(g); }
	}

	private static final int KIND = 0, PARENT = 1, HIDDEN = 2, X = 3, Y = 4, W = 5, H = 6, COLOR = 7, ALPHA = 8, FILLED = 9, FONT = 10,
		ALIGN_X = 11, ALIGN_Y = 12, LINE_H = 13, SHADOW = 14, SPRITE = 15, TILE = 16, FLIP_H = 17, FLIP_V = 18, ITEM = 19, QTY = 20, QTY_MODE = 21,
		MODEL_TYPE = 22, MODEL = 23, ANIM = 24, ZOOM = 25, ROT_X = 26, ROT_Y = 27, ROT_Z = 28, SCROLL_X = 29, SCROLL_Y = 30, SCROLLABLE = 31,
		SLOTS = 32;
	private static final int TEXTS = 1 << WidgetType.TEXT | 1 << WidgetType.IF1_TOOLTIP, ALL = -1;

	/**
	 * A written field: its key, its slots (one is a number or flag, more an array), its first slot's empty value (the rest 0), the types it draws on;
	 * a gated field is written whole only where its gate slot is 1.
	 */
	private static final class Field
	{
		final String key;
		final int[] slots;
		final int empty, types;
		final boolean flag;
		int gate = -1;

		Field(String key, int empty, int types, boolean flag, int... slots)
		{
			this.key = key;
			this.empty = empty;
			this.types = types;
			this.flag = flag;
			this.slots = slots;
		}

		Field gate(int slot)
		{
			gate = slot;
			return this;
		}
	}

	private static final Field[] FIELDS = {
		new Field("parent", -1, ALL, false, PARENT),
		new Field("hidden", 0, ALL, true, HIDDEN),
		new Field("at", Integer.MIN_VALUE, ALL, false, X, Y, W, H),
		new Field("color", 0, TEXTS | 1 << WidgetType.RECTANGLE | 1 << WidgetType.LINE | 1 << WidgetType.TEXT_INVENTORY, false, COLOR),
		new Field("alpha", 0, ALL, false, ALPHA),
		new Field("filled", 0, 1 << WidgetType.RECTANGLE, true, FILLED),
		new Field("font", -1, TEXTS | 1 << WidgetType.TEXT_INVENTORY, false, FONT),
		new Field("align", 0, TEXTS, false, ALIGN_X, ALIGN_Y, LINE_H),
		new Field("shadow", 0, TEXTS | 1 << WidgetType.TEXT_INVENTORY, true, SHADOW),
		new Field("sprite", -1, 1 << WidgetType.GRAPHIC, false, SPRITE),
		new Field("tile", 0, 1 << WidgetType.GRAPHIC, true, TILE),
		new Field("flip", 0, 1 << WidgetType.GRAPHIC, false, FLIP_H, FLIP_V),
		new Field("item", -1, ALL, false, ITEM, QTY, QTY_MODE),
		new Field("model", -1, 1 << WidgetType.MODEL, false, MODEL, MODEL_TYPE),
		new Field("anim", -1, 1 << WidgetType.MODEL, false, ANIM),
		new Field("zoom", 0, 1 << WidgetType.MODEL, false, ZOOM),
		new Field("rot", 0, 1 << WidgetType.MODEL, false, ROT_X, ROT_Y, ROT_Z),
		new Field("scroll", 0, 1 << WidgetType.LAYER, false, SCROLL_X, SCROLL_Y).gate(SCROLLABLE),
	};

	/** One component as last written; text is the client's own string, so an unchanged one compares by reference. */
	private static final class Comp
	{
		final int[] v = new int[SLOTS];
		String raw;
		int stamp;

		boolean same(Comp o)
		{
			return Arrays.equals(v, o.v) && (raw == null ? o.raw == null : raw.equals(o.raw));
		}
	}

	private static final class Group
	{
		int parent, mode;
		final Map<Integer, Comp> comps = new HashMap<>();
	}

	private final Map<Integer, Group> groups = new HashMap<>();
	private final Set<Integer> behind = new LinkedHashSet<>();
	private final Map<Integer, Comp> changes = new HashMap<>();
	private Comp scratch = new Comp();
	private Map<String, String> names;
	private int stamp;
	private int[] canvas;

	/**
	 * The rows for this tick, at most ROWS_PER_TICK; a group that does not fit waits for the next tick and goes first then.
	 * standIn names a nearby player's "Adventurer N"; known gives a stand-in already made, or null.
	 */
	List<JsonObject> changed(Client client, Function<String, String> standIn, Function<String, String> known)
	{
		names = null;
		Map<Integer, int[]> open = new TreeMap<>();
		int top = client.getTopLevelInterfaceId();
		if (top >= 0) { open.put(top, new int[]{-1, 0}); }
		Iterable<WidgetNode> table = client.getComponentTable();
		if (table != null)
		{
			for (WidgetNode node : table)
			{
				if (open.size() >= MAX_GROUPS) { break; }
				open.put(node.getId(), new int[]{(int) node.getHash(), node.getModalMode()});
			}
		}
		open.keySet().removeIf(g -> secret(g, open, 0));
		List<JsonObject> rows = new ArrayList<>();
		int[] screen = {client.getCanvasWidth(), client.getCanvasHeight(), client.isResized() ? 1 : 0};
		if (!Arrays.equals(screen, canvas))
		{
			canvas = screen;
			JsonObject row = new JsonObject();
			row.add("canvas", ints(screen[0], screen[1]));
			row.addProperty("resizable", screen[2] == 1);
			row.addProperty("rel", true);
			rows.add(row);
		}
		for (Integer g : new ArrayList<>(groups.keySet()))
		{
			if (!open.containsKey(g))
			{
				groups.remove(g);
				JsonObject row = new JsonObject();
				row.addProperty("group", g);
				row.addProperty("closed", true);
				rows.add(row);
			}
		}
		behind.retainAll(open.keySet());
		List<Integer> order = new ArrayList<>(behind);
		for (Integer g : open.keySet()) { if (!behind.contains(g)) { order.add(g); } }
		for (Integer g : order)
		{
			int[] at = open.get(g);
			Group was = groups.get(g);
			boolean whole = was == null || was.parent != at[0] || was.mode != at[1];
			Group now = whole ? new Group() : was;
			List<JsonObject> found = new ArrayList<>();
			changes.clear();
			stamp++;
			boolean capped = !OPEN_ONLY.contains(g) && read(client, g, now.comps, found, whole, standIn, known);
			List<Integer> gone = new ArrayList<>();
			for (Map.Entry<Integer, Comp> e : now.comps.entrySet()) { if (e.getValue().stamp != stamp) { gone.add(e.getKey()); } }
			for (Integer id : gone) { found.add(key(id, true)); }
			if (!whole && found.isEmpty()) { behind.remove(g); continue; }
			int need = Math.max(1, (found.size() + PER_ROW - 1) / PER_ROW);
			if (!rows.isEmpty() && rows.size() + need > ROWS_PER_TICK) { behind.add(g); continue; }
			behind.remove(g);
			now.parent = at[0];
			now.mode = at[1];
			now.comps.keySet().removeAll(gone);
			now.comps.putAll(changes);
			groups.put(g, now);
			for (int from = 0; from < Math.max(1, found.size()); from += PER_ROW)
			{
				JsonObject row = new JsonObject();
				row.addProperty("group", g);
				if (whole && from == 0)
				{
					row.addProperty("whole", true);
					row.addProperty("parent", at[0]);
					row.addProperty("mode", at[1]);
					if (OPEN_ONLY.contains(g)) { row.addProperty("withheld", true); }
					if (NO_TEXT.contains(g)) { row.addProperty("no_text", true); }
					if (capped) { row.addProperty("capped", true); }
				}
				JsonArray list = new JsonArray();
				for (int k = from; k < Math.min(found.size(), from + PER_ROW); k++) { list.add(found.get(k)); }
				if (list.size() > 0) { row.add("c", list); }
				if (from + PER_ROW < found.size()) { row.addProperty("more", true); }
				rows.add(row);
			}
		}
		return rows;
	}

	/** Whether a never-read screen (the bank PIN, name entry, reports) is open now. */
	static boolean secretOpen(Client client)
	{
		if (SECRET.contains(client.getTopLevelInterfaceId())) { return true; }
		Iterable<WidgetNode> table = client.getComponentTable();
		if (table == null) { return false; }
		for (WidgetNode node : table) { if (SECRET.contains(node.getId())) { return true; } }
		return false;
	}

	/** Whether a group's text may name players the client does not know of: the chatbox, private chat and social screens. */
	static boolean namesUnknownPlayers(int group)
	{
		return OPEN_ONLY.contains(group) || NO_TEXT.contains(group) || SECRET.contains(group);
	}

	/** True for a never-read group or one mounted, at any depth, inside one. */
	private static boolean secret(int group, Map<Integer, int[]> open, int depth)
	{
		if (SECRET.contains(group)) { return true; }
		int[] at = open.get(group);
		int parent = at == null || at[0] < 0 ? -1 : at[0] >>> 16;
		return parent >= 0 && parent != group && depth < 16 && secret(parent, open, depth + 1);
	}

	/** Reads a group's components, adding an entry for each new or changed one; true when a cap cut it short. */
	private boolean read(Client client, int g, Map<Integer, Comp> was, List<JsonObject> found, boolean whole,
		Function<String, String> standIn, Function<String, String> known)
	{
		int dynamic = 0;
		for (int k = 0; k < MAX_STATIC; k++)
		{
			Widget w = client.getWidget(g, k);
			if (w == null) { return false; }
			int id = k << 12;
			compare(client, g, w, id, was.get(id), found, whole, false, standIn, known);
			Widget[] children = w.getDynamicChildren();
			if (children == null) { continue; }
			for (int d = 0; d < children.length && d < 4095; d++)
			{
				if (children[d] == null) { continue; }
				if (++dynamic > MAX_DYNAMIC) { return true; }
				compare(client, g, children[d], id | (d + 1), was.get(id | (d + 1)), found, whole, true, standIn, known);
			}
		}
		return true;
	}

	private void compare(Client client, int g, Widget w, int id, Comp old, List<JsonObject> found, boolean whole,
		boolean dynamic, Function<String, String> standIn, Function<String, String> known)
	{
		Comp c = scratch;
		int[] v = c.v;
		int type = w.getType();
		v[KIND] = type;
		int parent = dynamic ? -1 : w.getParentId();
		v[PARENT] = parent >= 0 && parent >>> 16 == g ? parent & 0xffff : -1;
		v[HIDDEN] = w.isHidden() ? 1 : 0;
		if (v[HIDDEN] == 1 && old != null && old.v[KIND] == type)
		{
			// Not drawn: keep what was written and read the rest once it shows again.
			System.arraycopy(old.v, HIDDEN + 1, v, HIDDEN + 1, SLOTS - HIDDEN - 1);
			c.raw = old.raw;
		}
		else
		{
			read(w, type, v);
			c.raw = (TEXTS >> type & 1) == 1 ? w.getText() : null;
		}
		c.stamp = stamp;
		if (old != null) { old.stamp = stamp; }
		boolean all = whole || old == null || old.v[KIND] != type;
		if (!all && c.same(old)) { return; }
		scratch = new Comp();
		changes.put(id, c);
		JsonObject e = key(id, false);
		int before = e.size();
		if (all) { e.addProperty("type", type); }
		for (Field f : FIELDS)
		{
			if ((f.types >> type & 1) == 0) { continue; }
			boolean write = false;
			for (int i = 0; i < f.slots.length; i++)
			{
				int slot = f.slots[i];
				write |= !all ? v[slot] != old.v[slot] : f.gate >= 0 ? v[f.gate] == 1 : f.empty == Integer.MIN_VALUE || v[slot] != (i == 0 ? f.empty : 0);
			}
			if (!write) { continue; }
			if (f.flag) { e.addProperty(f.key, v[f.slots[0]] == 1); }
			else if (f.slots.length == 1) { e.addProperty(f.key, v[f.slots[0]]); }
			else
			{
				JsonArray a = new JsonArray();
				for (int slot : f.slots) { a.add(v[slot]); }
				e.add(f.key, a);
			}
		}
		if (!NO_TEXT.contains(g) && c.raw != null && (all ? !c.raw.isEmpty() : !c.raw.equals(old.raw)))
		{
			e.addProperty("text", scrub(client, c.raw, standIn, known));
		}
		if (e.size() > before) { found.add(e); }
	}

	/**
	 * What the component draws, read only for the types that draw it; typed input (INPUT_FIELD) is never read.
	 * The position is the client's laid-out one, relative to the parent's content origin, so a scroll moves no child.
	 */
	private static void read(Widget w, int type, int[] v)
	{
		v[X] = w.getRelativeX();
		v[Y] = w.getRelativeY();
		v[W] = w.getWidth();
		v[H] = w.getHeight();
		boolean layer = type == WidgetType.LAYER;
		v[SCROLL_X] = layer ? w.getScrollX() : 0;
		v[SCROLL_Y] = layer ? w.getScrollY() : 0;
		v[SCROLLABLE] = layer && (w.getScrollWidth() > v[W] || w.getScrollHeight() > v[H] || v[SCROLL_X] != 0 || v[SCROLL_Y] != 0) ? 1 : 0;
		boolean text = (TEXTS >> type & 1) == 1, inventory = type == WidgetType.TEXT_INVENTORY;
		boolean graphic = type == WidgetType.GRAPHIC, model = type == WidgetType.MODEL, rect = type == WidgetType.RECTANGLE;
		v[COLOR] = text || inventory || rect || type == WidgetType.LINE ? w.getTextColor() : 0;
		v[ALPHA] = w.getOpacity();
		v[FILLED] = rect && w.isFilled() ? 1 : 0;
		v[FONT] = text || inventory ? w.getFontId() : -1;
		v[ALIGN_X] = text ? w.getXTextAlignment() : 0;
		v[ALIGN_Y] = text ? w.getYTextAlignment() : 0;
		v[LINE_H] = text ? w.getLineHeight() : 0;
		v[SHADOW] = (text || inventory) && w.getTextShadowed() ? 1 : 0;
		v[SPRITE] = graphic ? w.getSpriteId() : -1;
		v[TILE] = graphic && w.getSpriteTiling() ? 1 : 0;
		v[FLIP_H] = graphic && w.isFlippedHorizontally() ? 1 : 0;
		v[FLIP_V] = graphic && w.isFlippedVertically() ? 1 : 0;
		v[ITEM] = graphic || model || inventory ? w.getItemId() : -1;
		v[QTY] = v[ITEM] < 0 ? 0 : w.getItemQuantity();
		v[QTY_MODE] = v[ITEM] < 0 ? 0 : w.getItemQuantityMode();
		v[MODEL_TYPE] = model ? w.getModelType() : 0;
		v[MODEL] = model ? w.getModelId() : -1;
		v[ANIM] = model ? w.getAnimationId() : -1;
		v[ZOOM] = model ? w.getModelZoom() : 0;
		v[ROT_X] = model ? w.getRotationX() : 0;
		v[ROT_Y] = model ? w.getRotationY() : 0;
		v[ROT_Z] = model ? w.getRotationZ() : 0;
	}

	private static JsonObject key(int id, boolean gone)
	{
		JsonObject e = new JsonObject();
		e.addProperty("k", id >>> 12);
		if ((id & 0xfff) != 0) { e.addProperty("d", (id & 0xfff) - 1); }
		if (gone) { e.addProperty("gone", true); }
		return e;
	}

	private static JsonArray ints(int... values)
	{
		JsonArray out = new JsonArray();
		for (int v : values) { out.add(v); }
		return out;
	}

	/** The text with every known player's name replaced, the player's own by "You"; cut to MAX_TEXT characters. */
	String scrub(Client client, String text, Function<String, String> standIn, Function<String, String> known)
	{
		String out = unname(client, text, standIn, known);
		return out.length() > MAX_TEXT ? out.substring(0, MAX_TEXT) : out;
	}

	/** A chatbox line with names replaced as scrub does, from a fresh look at who the client knows of, and not cut. */
	String scrubLine(Client client, String text, Function<String, String> standIn, Function<String, String> known)
	{
		names = null;
		return unname(client, text, standIn, known);
	}

	private String unname(Client client, String text, Function<String, String> standIn, Function<String, String> known)
	{
		if (names == null) { names = names(client, standIn, known); }
		StringBuilder out = new StringBuilder(text.length());
		String norm = normal(text);
		int i = 0;
		while (i < text.length())
		{
			int end = -1;
			String to = null;
			if (!word(norm, i - 1) && word(norm, i))
			{
				for (int j = Math.min(norm.length(), i + 12); j > i; j--)
				{
					if (word(norm, j) || !word(norm, j - 1)) { continue; }
					String hit = names.get(norm.substring(i, j));
					if (hit != null) { end = j; to = hit; break; }
				}
			}
			if (end < 0) { out.append(text.charAt(i++)); }
			else { out.append(to); i = end; }
		}
		return out.toString();
	}

	private static boolean word(String s, int i)
	{
		return i >= 0 && i < s.length() && Character.isLetterOrDigit(s.charAt(i));
	}

	/** Same length as the text: lower case, with the name separators the game treats alike as spaces. */
	private static String normal(String s)
	{
		char[] c = s.toCharArray();
		for (int i = 0; i < c.length; i++) { c[i] = c[i] == '\u00a0' || c[i] == '_' || c[i] == '-' ? ' ' : Character.toLowerCase(c[i]); }
		return new String(c);
	}

	private Map<String, String> names(Client client, Function<String, String> standIn, Function<String, String> known)
	{
		Map<String, String> out = new HashMap<>();
		List<String> social = new ArrayList<>();
		add(social, client.getFriendContainer());
		add(social, client.getIgnoreContainer());
		add(social, client.getFriendsChatManager());
		if (client.getFriendsChatManager() != null) { social.add(client.getFriendsChatManager().getOwner()); }
		for (ClanChannel channel : new ClanChannel[]{client.getClanChannel(), client.getGuestClanChannel(), client.getClanChannel(ClanID.GROUP_IRONMAN)})
		{
			if (channel != null && channel.getMembers() != null) { for (ClanChannelMember m : channel.getMembers()) { social.add(m.getName()); } }
		}
		for (ClanSettings settings : new ClanSettings[]{client.getClanSettings(), client.getGuestClanSettings(), client.getClanSettings(ClanID.GROUP_IRONMAN)})
		{
			if (settings != null && settings.getMembers() != null) { for (ClanMember m : settings.getMembers()) { social.add(m.getName()); } }
		}
		for (String name : social)
		{
			String to = name == null ? null : known.apply(name);
			put(out, name, to == null ? "" : to);
		}
		WorldView view = client.getTopLevelWorldView();
		Player me = client.getLocalPlayer();
		if (view != null)
		{
			for (Player p : view.players()) { if (p != null && p != me && p.getName() != null) { put(out, p.getName(), standIn.apply(p.getName())); } }
		}
		put(out, me == null ? null : me.getName(), "You");
		return out;
	}

	private static void add(List<String> into, NameableContainer<? extends Nameable> container)
	{
		Nameable[] members = container == null ? null : container.getMembers();
		if (members == null) { return; }
		for (Nameable n : members)
		{
			if (n == null) { continue; }
			into.add(n.getName());
			into.add(n.getPrevName());
		}
	}

	private static void put(Map<String, String> into, String name, String to)
	{
		if (name == null) { return; }
		String key = normal(net.runelite.client.util.Text.removeTags(name)).trim();
		if (!key.isEmpty() && key.length() <= 12) { into.put(key, to); }
	}
}
