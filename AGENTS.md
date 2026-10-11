# 3D Replay Recorder: agent notes

One RuneLite plugin, Java 11. `EmbertidePlugin` records (package
`com.extrinsiccognition.embertide`) and starts, stops and registers the
in-game viewer (`...embertide.viewer`, entry point `Viewer`). Read `README.md`
before changing behaviour; it is the Plugin Hub's description of the plugin.

- Build and test with an arm64 JDK 11: `./gradlew test jar`. Gradle 8.10 will
  not start on a JDK above 23. `./gradlew run` starts a development client.
- Keep the whole repository under 200k tokens: `scripts/size-budget.sh`. The
  Plugin Hub's review bot counts `src/main/java` without comments and skips a
  plugin above 200k.
- Versions: bump `build.gradle`, `runelite-plugin.properties` and
  `MccrSession.VERSION` together, in a commit of their own ("Bump version to
  X"); `RecorderVersionTest` checks they agree.
- No new runtime dependencies. Recordings are written and read only through
  `Filepath`. The only network is the opt-in live link (Show live in
  Embertide, off by default): `LiveMirror` copies rows to the Embertide app on
  loopback through the injected `OkHttpClient`, and `RecorderEndpoint` reads
  the app's `endpoint.json` from the app's own folder. Both are Plugin Hub
  review items; keep them behind that setting.
- One format, one reader: row type names are in `RowTypes`, the header in
  `MccrSession` and `MccrFileRecorder.MOTION_XY`, the ground layout in
  `GroundRows` (writer and decoder side by side). Change a row and its reader
  in the same commit; viewer tests write ground through `GroundRows` itself.
- The viewer plays back only inside the player-owned house. Never automate
  gameplay, send game actions, replace collision maps or change server state.
- Client API and scene changes run on the client thread, Swing on the EDT,
  file parsing on the viewer's worker with explicit bounds. Tests use
  synthetic recordings; never commit a real one.
- Copy models before changing them; never alter the client's cached models.
  Leaving the house, a scene reload, logout and shutdown remove every projection.
- Do not claim an in-game visual pass from unit tests or compilation.

## Viewer pitfalls

- Read-only Swing text still has a caret: keep display carets NEVER_UPDATE and
  change a document only when its text differs, or refreshes scroll the sidebar.
- Hide surroundings works through `RenderCallbackManager` with any renderer
  that asks about tiles when it rebuilds a zone (RuneLite GPU, 117 HD's
  default). Invalidate occupied zones on hide and restore; drop the filter on
  every cleanup path. A renderer that has not asked within 30 frames is named
  and not tried again; the CPU renderer and 117 HD legacy never ask.
- Definitions are decoded from `Client.getIndexConfig()` bytes in
  `Definitions`. Ours refuse unknown opcodes; check them against RuneLite's
  loaders after a game update.
- `osrs.ground` stores map planes. A bridge tile's floors draw one plane down;
  colours and heights are differences from the first value; a later scene
  load replaces the tiles it covers. Older recordings get a floor from
  snapshot cells (`SnapshotTerrain`): id plus one, 0 is none, saved instance
  heights win over the cells' rounded ones.
- Compact motion is a per-actor milli-tile delta whose accumulator spans scene
  and lifecycle boundaries. Never reset it there.
- All-floors terrain uses physical map planes; keep terrain patches per floor
  and register every object on the house anchor's plane.
- Classic `Animation.getDuration()` counts keyframes, not ticks: sum frame
  lengths and loop only the frame-step suffix (checked against
  `AnimationController.tick(1)`).
- A type-2 corner wall is two sections, at orientation + 4 and
  (orientation + 1) & 3.

## Verification so far (viewer, before the merge)

Checked off screen only, against the 1.13.1 client and local recordings read
in place: all 114,118 object, NPC, item and kit definitions and all 251
underlays and 643 overlays decode as RuneLite's loaders do (0 differ); the
recorder's fixture scene's 12,520 ground tiles read back exactly; all 37
local recordings open and seek; animation clocks match 9,006 native frames;
81 wall variants match the client's builder. Never checked in game: the
projection in a real house, Hide surroundings under 117 HD, the floor of
older recordings, frame rate at the display limits (128 actors, 4,096
objects, radius 48). RuneLite lists content simulation among rejected
features, so the viewer needs a Plugin Hub ruling before it ships:
<https://github.com/runelite/runelite/wiki/Rejected-or-Rolled-Back-Features>.
