package com.extrinsiccognition.embertide;

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

	private RowTypes()
	{
	}
}
