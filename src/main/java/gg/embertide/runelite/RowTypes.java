package gg.embertide.runelite;

import java.util.Set;

/** Row types the recorder writes and the viewer reads back; the rest are written as literals where they are made. */
public final class RowTypes
{
	public static final String HEADER = "mccr.header", SCENE = "mccr.scene", LIFECYCLE = "mccr.actor_lifecycle";
	public static final String SNAPSHOT = "mccr.spatial_snapshot", PRESENCE = "presence", POSITION = "player.position";
	public static final String POSES = "osrs.poses", MOTION = "osrs.motion", APPEARANCE = "osrs.appearance";
	public static final String OBJECT = "osrs.object", REGION = "osrs.region", INSTANCE_HEIGHTS = "osrs.instance_heights";
	public static final String GROUND = "osrs.ground";
	/** A Sailing boat's own scene, in its own tiles: objects and ground when they change, "gone" when it unloads. */
	public static final String BOAT = "osrs.boat";
	public static final String HEAD_ICONS = "osrs.head_icons", PRAYERS = "osrs.prayers";

	/**
	 * Rows that rebuild a session afterwards in Embertide Studio, outside the client, and are never shown in game:
	 * the in-game viewer skips them, so recorded prayers, hits, bars, interfaces, chat and clicks are not displayed
	 * in RuneLite at any time.
	 */
	public static final Set<String> NOT_SHOWN_IN_GAME = Set.of(HEAD_ICONS, PRAYERS, "osrs.boss_hp", "osrs.hit", "osrs.vitals",
		"osrs.inventory", "osrs.equipment", "osrs.spellbook", "osrs.interface", "osrs.widgets", "osrs.chatbox", "osrs.click");

	private RowTypes()
	{
	}
}
