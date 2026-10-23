package gg.embertide.runelite;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Prayer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarbitID;

/**
 * The player's own interface, recorded only with Record the game interface on (3d-recorder#24): each row at its change,
 * and whole when a part opens, since a part starts with nothing written. Never anyone else's; the bank and other open interfaces are WidgetRows.
 */
final class InterfaceRows
{
	static final String INVENTORY = "osrs.inventory", EQUIPMENT = "osrs.equipment", PRAYERS = "osrs.prayers";
	static final String SPELLBOOK = "osrs.spellbook", LAYOUT = "osrs.interface", CHATBOX = "osrs.chatbox";

	/** The chatbox lines kept, by kind; anything else (other players, private, clan, friends, trades, broadcasts) is never read. */
	private static final Set<ChatMessageType> GAME = EnumSet.of(ChatMessageType.GAMEMESSAGE, ChatMessageType.SPAM, ChatMessageType.ENGINE,
		ChatMessageType.WELCOME, ChatMessageType.LEVELUPMESSAGE, ChatMessageType.DIDYOUKNOW);
	private static final Set<ChatMessageType> NPC = EnumSet.of(ChatMessageType.NPC_SAY, ChatMessageType.DIALOG, ChatMessageType.MESBOX);
	private static final Set<ChatMessageType> EXAMINE = EnumSet.of(ChatMessageType.ITEM_EXAMINE, ChatMessageType.NPC_EXAMINE, ChatMessageType.OBJECT_EXAMINE);

	static final class Row
	{
		final String type;
		final JsonObject payload;

		Row(String type, JsonObject payload)
		{
			this.type = type;
			this.payload = payload;
		}
	}

	private String inventory, equipment, prayers, layout;
	private int spellbook = Integer.MIN_VALUE;

	/** The rows whose state changed since the last look; everything the first time. */
	List<Row> changed(Client client)
	{
		List<Row> rows = new ArrayList<>();
		JsonArray items = items(client.getItemContainer(InventoryID.INV), 28);
		if (!Objects.equals(items.toString(), inventory))
		{
			inventory = items.toString();
			JsonObject payload = new JsonObject();
			payload.add("items", items);
			rows.add(new Row(INVENTORY, payload));
		}
		JsonArray worn = items(client.getItemContainer(InventoryID.WORN), 14);
		if (!Objects.equals(worn.toString(), equipment))
		{
			equipment = worn.toString();
			JsonObject payload = new JsonObject();
			payload.add("slots", worn);
			rows.add(new Row(EQUIPMENT, payload));
		}
		JsonArray active = new JsonArray();
		for (Prayer prayer : Prayer.values())
		{
			if (client.isPrayerActive(prayer)) { active.add(prayer.name().toLowerCase(Locale.ROOT)); }
		}
		boolean quick = client.getVarbitValue(VarbitID.QUICKPRAYER_ACTIVE) == 1;
		String prayerState = active + "|" + quick;
		if (!prayerState.equals(prayers))
		{
			prayers = prayerState;
			JsonObject payload = new JsonObject();
			payload.add("active", active);
			payload.addProperty("quick", quick);
			rows.add(new Row(PRAYERS, payload));
		}
		int book = client.getVarbitValue(VarbitID.SPELLBOOK);
		if (book != spellbook)
		{
			spellbook = book;
			JsonObject payload = new JsonObject();
			payload.addProperty("book", book);
			rows.add(new Row(SPELLBOOK, payload));
		}
		JsonObject shape = new JsonObject();
		shape.addProperty("resizable", client.isResized());
		shape.addProperty("stones", client.getVarbitValue(VarbitID.RESIZABLE_STONE_ARRANGEMENT));
		shape.addProperty("panel", client.getVarcIntValue(VarClientID.TOPLEVEL_PANEL));
		shape.addProperty("minimap_zoom", Math.round(client.getMinimapZoom() * 100) / 100.0);
		if (!shape.toString().equals(layout))
		{
			layout = shape.toString();
			rows.add(new Row(LAYOUT, shape));
		}
		return rows;
	}

	/**
	 * A chatbox line as the game showed it, or null for a line that is not kept. Own public chat only when the player's
	 * own chat is recorded; it carries no name, only that it is the player's.
	 */
	static JsonObject chatLine(ChatMessageType type, String name, String message, boolean own, boolean ownChatOn)
	{
		String kind;
		if (GAME.contains(type)) { kind = "game"; }
		else if (NPC.contains(type)) { kind = "npc"; }
		else if (EXAMINE.contains(type)) { kind = "examine"; }
		else if (type == ChatMessageType.PUBLICCHAT && own && ownChatOn) { kind = "own"; }
		else { return null; }
		if (message == null || message.isEmpty()) { return null; }
		JsonObject line = new JsonObject();
		line.addProperty("kind", kind);
		line.addProperty("type", type.name().toLowerCase(Locale.ROOT));
		if ("npc".equals(kind) && name != null && !name.isEmpty()) { line.addProperty("name", name); }
		line.addProperty("text", message);
		return line;
	}

	/** Slots in order, [item id, quantity], [-1, 0] for an empty slot, padded to the container's size. */
	private static JsonArray items(ItemContainer container, int size)
	{
		JsonArray out = new JsonArray();
		Item[] items = container == null ? new Item[0] : container.getItems();
		for (int slot = 0; slot < size; slot++)
		{
			Item item = slot < items.length ? items[slot] : null;
			JsonArray pair = new JsonArray();
			pair.add(item == null || item.getId() < 0 ? -1 : item.getId());
			pair.add(item == null || item.getId() < 0 ? 0 : item.getQuantity());
			out.add(pair);
		}
		return out;
	}
}
