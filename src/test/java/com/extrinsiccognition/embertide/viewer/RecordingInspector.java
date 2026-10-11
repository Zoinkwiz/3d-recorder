package com.extrinsiccognition.embertide.viewer;

import com.extrinsiccognition.embertide.GroundRows;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

public final class RecordingInspector
{
	public static void main(String[] args) throws Exception
	{
		if (args.length == 0 || args[0].isBlank()) { throw new IllegalArgumentException("Pass -Precording=/path/to/file-or-directory"); }
		Path path = Path.of(args[0]);
		List<Path> files;
		if (Files.isDirectory(path))
		{
			try (java.util.stream.Stream<Path> paths = Files.list(path))
			{ files = paths.filter(p -> p.toString().endsWith(".embertide") || p.toString().endsWith(".mccr")).sorted().collect(Collectors.toList()); }
		}
		else { files = List.of(path); }
		int failures = 0;
		for (Path file : files)
		{
			try
			{
				long start = System.nanoTime(); Recording r = new RecordingReader().read(Files.newInputStream(file));
				if (Boolean.getBoolean("embertide.inspect.terrain"))
				{
					int withGround = 0, shaped = 0, textured = 0, hidden = 0, snapshotScenes = 0, snapshotCells = 0;
					int[] floorTiles = new int[4];
					for (Recording.Scene scene : r.scenes)
					{
						if (!scene.terrain.recorded())
						{
							// Colours need the client's definitions; this counts the cells a floor can be built from.
							long cells = scene.ground.values().stream().filter(g -> SnapshotTerrain.names(g.material)).count();
							if (cells > 0) { snapshotScenes++; snapshotCells += cells; }
							continue;
						}
						withGround++;
						Recording.Sample origin = scene.origin(scene.start);
						if (origin == null) { continue; }
						for (int p = 0; p < 4; p++)
						{ for (int x = (int) origin.x - 24; x <= (int) origin.x + 24; x++)
						{ for (int y = (int) origin.y - 24; y <= (int) origin.y + 24; y++)
						{
							Terrain.Tile tile = scene.terrain.floorTile(x, y, p);
							if (tile == null) { continue; }
							floorTiles[p]++;
							if (!tile.paint()) { shaped++; }
							for (int i = 5; i < tile.entry.length; i += tile.paint() ? 1 : 4) { if (tile.entry[i] >= 0) { textured++; break; } }
							if (tile.entry[1] == GroundRows.HIDDEN || (!tile.paint() && tile.entry[2] == GroundRows.HIDDEN)) { hidden++; }
						} } }
					}
					System.out.printf("GROUND scenes=%d/%d shaped=%d textured=%d hidden=%d%n", withGround, r.scenes.size(), shaped, textured, hidden);
					System.out.println("PHYSICAL_FLOORS tiles=" + java.util.Arrays.toString(floorTiles));
					if (snapshotScenes > 0) { System.out.printf("SNAPSHOT_FLOOR scenes=%d cells=%d%n", snapshotScenes, snapshotCells); }
				}
				int checks = 0;
				for (double fraction : new double[]{0, .25, .5, .99, .1, 1})
				{
					double t = r.duration * fraction; Recording.Scene scene = r.sceneAt(t);
					for (Recording.Actor actor : scene.actors.values())
					{
						Recording.Sample s = actor.sample(t);
						if (s != null && (!Double.isFinite(s.x) || !Double.isFinite(s.y))) { throw new AssertionError("Non-finite playback position"); }
						checks++;
					}
				}
				System.out.printf("PASS %s: %.1fs, %d rows, %d scenes, %d actor seek checks, %.0fms%n", file.getFileName(), r.duration, r.rows, r.scenes.size(), checks, (System.nanoTime() - start) / 1e6);
			}
			catch (Exception e) { failures++; System.out.println("FAIL " + file.getFileName() + ": " + e.getMessage()); }
		}
		if (files.isEmpty() || failures > 0) { throw new IllegalStateException(failures + " failures in " + files.size() + " recordings"); }
	}
}
