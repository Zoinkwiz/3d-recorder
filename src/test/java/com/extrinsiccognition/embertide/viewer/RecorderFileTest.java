package com.extrinsiccognition.embertide.viewer;

import com.extrinsiccognition.embertide.Fakes;
import com.extrinsiccognition.embertide.GroundRows;
import com.extrinsiccognition.embertide.MccrFileRecorder;
import com.extrinsiccognition.embertide.OsrsCapture;
import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.runelite.api.Tile;
import net.runelite.client.util.Filepath;
import org.junit.Test;
import static org.junit.Assert.*;

/** One plugin: the gzip file the recorder saves is listed and opened by the viewer from the plugin's own folder. */
public class RecorderFileTest
{
	@Test public void theViewerOpensWhatTheRecorderSaves() throws Exception
	{
		Path dir = Files.createTempDirectory("embertide-one");
		ExecutorService worker = Executors.newSingleThreadExecutor();
		try
		{
			Filepath folder = Filepath.Unchecked.getRooted(dir);
			int n = GroundRows.SIZE;
			Tile[][][] tiles = new Tile[4][n][n];
			Tile grass = Fakes.paint(100, 101, 102, 103, -1);
			for (Tile[] column : tiles[0]) { Arrays.fill(column, grass); }
			OsrsCapture capture = Fakes.capture(new MccrFileRecorder(new Gson(), folder, () -> "osrs-test", worker),
				Fakes.view(3176, 3168, tiles, new int[4][n + 1][n + 1], null), 4);
			capture.start();
			assertTrue(capture.tick(true));
			capture.finish("test", false).get(10, TimeUnit.SECONDS);
			Files.writeString(dir.resolve("unfinished.embertide.partial"), "");

			List<String> names = Viewer.recordings(folder);
			assertEquals(1, names.size());
			assertTrue(names.get(0), names.get(0).endsWith(".embertide"));
			assertEquals(0x1f, Files.readAllBytes(dir.resolve(names.get(0)))[0]);
			Recording recording = new RecordingReader().read(folder.joinSegment(names.get(0)));
			Recording.Scene scene = recording.scenes.get(0);
			assertNotNull(scene.origin(0));
			assertArrayEquals(new int[]{0, 100, 101, 102, 103, -1}, scene.terrain.tile(3176 + 50, 3168 + 50, 0).entry);
		}
		finally
		{
			worker.shutdownNow();
			try (var files = Files.walk(dir)) { files.sorted((a, b) -> b.compareTo(a)).forEach(path -> path.toFile().delete()); }
		}
	}
}
