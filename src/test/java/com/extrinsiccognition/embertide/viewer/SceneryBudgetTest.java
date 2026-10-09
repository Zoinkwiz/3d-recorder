package com.extrinsiccognition.embertide.viewer;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.RuneLiteObjectController;
import net.runelite.api.coords.LocalPoint;
import org.junit.Test;
import static com.extrinsiccognition.embertide.viewer.RecordingReaderTest.*;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class SceneryBudgetTest
{
	private final Client client = ProjectionTest.client();
	private final Set<RuneLiteObjectController> registered = new HashSet<>();

	private Recording recording() throws Exception
	{
		when(client.getIndexConfig().loadData(6, 43)).thenReturn(new byte[]{0});
		when(client.isRuneLiteObjectRegistered(any())).thenAnswer(call -> registered.contains(call.getArgument(0)));
		doAnswer(call -> { assertTrue(registered.add(call.getArgument(0))); return null; }).when(client).registerRuneLiteObject(any());
		doAnswer(call -> { assertTrue(registered.remove(call.getArgument(0))); return null; }).when(client).removeRuneLiteObject(any());
		Recording recording = parse(HEADER + pose(0, 3200, 0));
		recording.sceneAt(0).actors.clear(); return recording;
	}

	private void objects(Recording.Scene scene, int count, int id, int offset)
	{
		for (int i = offset; i < offset + count; i++)
		{
			Recording.Scenery object = new Recording.Scenery(0, 3176 + i % 48, 3176 + i / 48 % 48,
				i / (48 * 48), id, 10, 0, 0, true);
			scene.objects.put(object.key(), List.of(object));
		}
	}

	private ReplayRenderer renderer(Recording recording, boolean map)
	{
		ReplayRenderer renderer = new ReplayRenderer(client);
		Recording.Sample origin = new Recording.Sample(0, 3200, 3200, 0, 0, -1, -1, -1, -1);
		renderer.place(recording.sceneAt(0), origin, new LocalPoint(6400, 6400, -1), 0, 0,
			false, 4, 24, 0, 0, map, 0, true);
		return renderer;
	}

	@Test public void denseHouseLoadsFullyAndChangingLimitAddsAndRemovesOnlyItsOwnObjects() throws Exception
	{
		Recording recording = recording(); objects(recording.sceneAt(0), 3232, 42, 0);
		ReplayRenderer renderer = renderer(recording, false);
		renderer.draw(recording, 0, false, true);
		assertEquals(3232, registered.size()); assertFalse(renderer.status(), renderer.status().contains("not shown"));
		Placement anchor = renderer.placement();
		renderer.objectLimit(384); renderer.draw(recording, 0, false, true);
		assertEquals(384, registered.size()); assertTrue(renderer.status(), renderer.status().contains("2848 objects not shown"));
		renderer.objectLimit(4096); renderer.draw(recording, 0, false, true);
		assertEquals(3232, registered.size()); assertSame(anchor, renderer.placement());
		renderer.draw(recording, 0, false, true); assertEquals(3232, registered.size());
		renderer.clear(); renderer.clear(); assertTrue(registered.isEmpty());
		verify(client, times(1)).loadModelData(1);
	}

	@Test public void modelLessPlaceholdersDoNotConsumeTheBudgetOrStayPending() throws Exception
	{
		Recording recording = recording(); objects(recording.sceneAt(0), 1000, 43, 0);
		objects(recording.sceneAt(0), 128, 42, 1000);
		ReplayRenderer renderer = renderer(recording, false); renderer.objectLimit(128);
		// The first pass learns the invisible asset; a second pass uses that classification.
		renderer.draw(recording, 0, false, true); renderer.draw(recording, 0, false, true);
		assertEquals(128, registered.size()); assertFalse(renderer.status(), renderer.status().contains("pending"));
		assertFalse(renderer.status(), renderer.status().contains("not shown"));
		renderer.clear(); assertTrue(registered.isEmpty());
	}
}
