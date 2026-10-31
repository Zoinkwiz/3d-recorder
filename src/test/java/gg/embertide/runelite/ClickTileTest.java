package gg.embertide.runelite;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import net.runelite.api.MenuAction;
import org.junit.Test;

public class ClickTileTest
{
	@Test
	public void objectAndGroundItemClicksNameTheirSceneTile()
	{
		assertArrayEquals(new int[]{40, 61}, OsrsCapture.clickedSceneTile(MenuAction.GAME_OBJECT_FIRST_OPTION, 40, 61, 104, 104));
		assertArrayEquals(new int[]{3, 7}, OsrsCapture.clickedSceneTile(MenuAction.GAME_OBJECT_FIFTH_OPTION, 3, 7, 104, 104));
		assertArrayEquals(new int[]{12, 90}, OsrsCapture.clickedSceneTile(MenuAction.GROUND_ITEM_THIRD_OPTION, 12, 90, 104, 104));
		assertArrayEquals(new int[]{0, 0}, OsrsCapture.clickedSceneTile(MenuAction.EXAMINE_OBJECT, 0, 0, 104, 104));
		assertArrayEquals(new int[]{5, 6}, OsrsCapture.clickedSceneTile(MenuAction.EXAMINE_ITEM_GROUND, 5, 6, 104, 104));
		assertArrayEquals(new int[]{8, 9}, OsrsCapture.clickedSceneTile(MenuAction.WIDGET_TARGET_ON_GAME_OBJECT, 8, 9, 104, 104));
		assertArrayEquals(new int[]{10, 11}, OsrsCapture.clickedSceneTile(MenuAction.WIDGET_TARGET_ON_GROUND_ITEM, 10, 11, 104, 104));
	}

	@Test
	public void otherClicksNameNoTile()
	{
		// A walk's params are the pointer; its tile comes from the client's destination instead.
		assertNull(OsrsCapture.clickedSceneTile(MenuAction.WALK, 250, 160, 104, 104));
		assertNull(OsrsCapture.clickedSceneTile(MenuAction.NPC_FIRST_OPTION, 4, 5, 104, 104));
		assertNull(OsrsCapture.clickedSceneTile(MenuAction.CC_OP, 1, 9764864, 104, 104));
		assertNull(OsrsCapture.clickedSceneTile(null, 4, 5, 104, 104));
	}

	@Test
	public void aTileOutsideTheSceneIsLeftOut()
	{
		assertNull(OsrsCapture.clickedSceneTile(MenuAction.GAME_OBJECT_FIRST_OPTION, 104, 5, 104, 104));
		assertNull(OsrsCapture.clickedSceneTile(MenuAction.GAME_OBJECT_FIRST_OPTION, 5, -1, 104, 104));
		assertArrayEquals(new int[]{180, 5}, OsrsCapture.clickedSceneTile(MenuAction.GAME_OBJECT_FIRST_OPTION, 180, 5, 184, 184));
	}
}
