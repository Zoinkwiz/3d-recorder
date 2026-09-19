# 3D Replay Recorder for Old School RuneScape

A RuneLite plugin that turns a play session into a local **Embertide
recording**: where you stood, who was near you, what the ground and the
objects around you looked like, and what appeared or vanished while you were there.
It writes a file in RuneLite's own folder. 


Open it at [embertide.gg/studio](https://embertide.gg/studio/) in your browser, with no account and nothing to install, or in the Embertide app, and cut clips of your play from any angle, in any look.

View your recordings:

<img width="1134" height="657" alt="Screenshot 2026-09-16 at 00 12 44" src="https://github.com/user-attachments/assets/5ab217b1-012d-44ad-8e63-86f171ed3f25" />

Change the lighting:

<img width="1128" height="570" alt="Screenshot 2026-09-16 at 00 13 11" src="https://github.com/user-attachments/assets/c9a123aa-4b4a-4531-b6f3-8b5fc7671214" />

Change the renderer:

<img width="1128" height="646" alt="Screenshot 2026-09-16 at 00 14 03" src="https://github.com/user-attachments/assets/0d2663d5-b48f-4237-8e62-e01580531c6c" />

Change your angle of the same scene:

<img width="1128" height="650" alt="Screenshot 2026-09-16 at 00 15 01" src="https://github.com/user-attachments/assets/9345dea7-343d-4069-8482-b7407606533c" />

**Nothing leaves the machine, and the plugin opens no connection to
anything.** It never automates play and never reads inventories or account
data. **Nothing is on disk until a recording is sealed**: the records are held
in memory while you play and written in one go when you stop, finish, log
out or close the client, so there is never a file growing line by line that
could be read as a live feed of where you and the people around you stand.

**Other players are unnamed.** They appear in a recording as figures wearing
what they wore, with positions, animations, observed health ratios and combat
events. Their display names, combat levels and chat are excluded. Each is "Adventurer 1", "Adventurer 2" in
order of first sighting, a number that means nothing outside that login’s chain of files.
Optional settings include your own public lines and NPC overhead speech.
Disabling an actor category excludes it from every channel, including attacker
references; these settings take effect immediately.
Another player's chat is never recorded, wherever the game draws it.

## What it records

The plugin is a second producer of the app's MCCR format (`mccr/0.1`,
profile `ec.mccr.spatial/1`), the same container the Embertide Cubes page
streams, so the app's recorder and the Studio importer needed no new format.

| Record | From RuneLite | Meaning |
| --- | --- | --- |
| `player.position` | you, once per game tick (everyone else rides in `osrs.poses`) | Where each actor stood, on which plane, and the local player's facing. Nothing is interpolated across the 0.6 s between ticks. |
| `mccr.spatial_snapshot` | the scene's tiles within the capture radius on your plane | A ground cell per tile, named by overlay or underlay id, and one cell above it for the top-most named object on the tile, or explicit `air`. |
| `world.event` | object spawned and despawned events | An exact `before` and `after` for one object cell. `actors` is empty and `observed_cause` is null: the client never learns why. |
| `presence` | first sighting of an NPC or another player | Names the actor for the Studio importer: an NPC by its cache name, another player by a number. Kind and colour ride along. |
| `mccr.capture_start` / `mccr.capture_end` | plugin lifecycle | Recording boundaries only, never world arrival or departure. |
| `world.event` (`combat.fight`, `combat.kill`) | hitsplats and deaths | What mattered, so an edit can cut on it: the start of a fight with someone (once per opponent per 10 s) and a kill of someone you were fighting. |
| `player.near_death` / `player.death` | your hitpoints and death | Below a quarter of your hitpoints (once per 30 s), and dying, with who was hitting you. |
| `osrs.hit` | every hitsplat the client shows | One hit in the words every game shares: `target`, `amount`, `source` when known, and the target's life as the client reports it (`health_ratio` out of `health_scale` for others; your own hitpoints out of your level). The Studio importer turns these into `hit` events and life readings, and every lane draws a bar and a number from them. |
| `osrs.death` | an opponent dying | The same death as `combat.kill`, in the shared words: `target` is who died, so it is drawn over the body rather than only cut on. |
| `advancement` | a real skill level going up | `Attack level 50`. The first sighting of a skill is the baseline, never a level-up. |
| `osrs.camera` | your own camera, on the client tick, only when it moved | Where you were looking: position in fractional tiles, height up-positive, and the client's 0..2047 yaw and pitch with its zoom, and the viewport's width and height in pixels, which with the zoom is the field of view. This is what lets an edit replay a moment the way you actually saw it. |
| `chat.message` | your own public chat, and what NPCs say overhead | Your own lines, plus what NPCs say above their heads when that setting is on. Never another player's line. At most 600 lines a recording, and a repeated line is kept once. |

### The OSRS layer, for rendering from the cache

The generic records above describe the world as blocks, which any Embertide
viewer can draw. On top of them the recording keeps a second set of records,
profile `ec.mccr.osrs/5`, that hold the game's own ids: which map regions
were loaded, which object stood on which tile, what each player wore, and
what animation each figure was in. A viewer that has the player's game cache
on the same machine can look those ids up and draw the real models from any
angle. A viewer without the cache ignores these records; the app stores them
unchanged. The file header records the cache revision, so the file can still
be drawn against the right version of the game after an update.

| Record | Content |
| --- | --- |
| `osrs.region` | On each scene load: the loaded map regions, the scene base, the plane, instance status and template chunks. The static world is the cache's own map at that revision; recorded objects override it where they differ. |
| `osrs.instance_heights` | Once per instance part, one row per plane: the loaded scene's actual corner heights, including the outer border. These cover all loaded planes; actor recording remains limited to your plane. |
| `osrs.environment` | The client's RGB sky colour, initially and whenever it changes on a client tick. Zero means black. |
| `osrs.skybox` | The separate 3D sky returned by the public Scene API, checked each game tick and written only when changed. Since it exposes no cache id, the record holds its displayed vertices, faces, lit corner colours, alpha and texture projection indices; texture images remain in the local cache. A null mesh clears the sky. Bounded at 16,384 vertices/faces and 2,000 changes per part; an oversized or invalid mesh is marked unsupported. |
| `osrs.graphics` | Standalone scene effects on your plane, sampled on client ticks. `spawn` entries are `[key, graphic id, x, y, plane, height, animation frame]`, with destination-world fractional tiles and absolute up-positive height. `end` lists keys that finished or left the scene. Each appearance has a distinct key; future-start effects wait for their client cycle. Bounded at 10,000 appearances and 4,096 inspected effects per tick. |
| `osrs.object` | Baseline objects within the capture radius, and every spawn or despawn: object id, kind (game, wall, ground, decorative), packed type and orientation from the object config, the varbit-selected impostor id when there is one, and the anchor tile of a multi-tile object. |
| `osrs.appearance` | A player's equipment ids, colours and gender, once and again whenever they change. |
| `osrs.poses` | Once per game tick, one entry for every eligible figure in the loaded scene on your plane, nearest first: `[id, x, y, plane, height, orientation, animation, pose animation, frame, graphic, npc id or -1, graphic height, client slot]`. The world tile, the ground height the client placed it at, the animation it was in and the effect over its head. |
| `osrs.motion` | On the 20 ms client tick, for you and the eligible figures selected on the last game tick: `[id, x, y, plane, orientation, animation, frame, pose animation]` with x and y in fractional tiles, written only when something changed. A run across two tiles with a turn halfway is here; the game tick alone cannot carry it. Bounded at 600,000 samples per recording. |

Coordinates map a world tile `(x, y, plane)` to the cell
`(x, plane * 8 + elevation, -y)`, declared in every header as
`x-east,y-up,z-south`, so north points into the screen of a y-up viewer and
the four planes stack eight blocks apart. Elevation is the tile's rendered
height at one block per tile width, clamped so a plane never climbs into the
next. See `SceneMapper` for the exact arithmetic.

The matching Studio source supports both classic frame animations and the newer
Maya skeletal clips, including NPCs such as Lowerniel Drakan. Existing recordings
already contain animation ids, so this playback correction applies to them.
Sky meshes and standalone effect lifetimes require a new recording with profile
5. In Studio, **Recorded sky** follows the captured background and 3D sky;
choosing a named sky preset overrides it. These are development changes, not a
published Hub or desktop release. Compare a fresh recording with live gameplay
before claiming encounter fidelity.

An instanced area is its own coordinate space, so entering or leaving one
seals the current recording and starts another with dimension
`osrs:instance:<layout-fingerprint>`. A change to instance template chunks,
map regions or scene base seals the part and resets its caches. Region records
preserve `instance_template_chunks[plane][chunkX][chunkY]`, including `-1` for
empty chunks. Everything else is `osrs:surface`.

The matching Studio changes reconstruct instance terrain and static objects
from the template chunks in destination scene coordinates. Rotation, repeated
chunks, rectangular object footprints and bridge planes are preserved. Actual
recorded corner heights keep floors and actors aligned when chunks come from
different source planes. Older instance files without the layout or heights
show an explanation in the detailed view; they need to be recorded again.

Ordinary region loads and teleports retain world coordinates. Large actor
jumps and plane changes cut between observations instead of interpolating a
journey across the map. Long surface journeys roll into linked parts before
exceeding 64 distinct loaded map squares; `session.series` and `session.part`
keep their order. Instance changes also create parts. These are separate files,
and the save interval remains a coverage gap.

### Budgets

Each recording is bounded. A normal limit seals the part; recording resumes
on the next game tick after saving succeeds. The save interval is a coverage
gap, during which observations are not recorded.

| Budget | Limit |
| --- | --- |
| duration | 20 minutes |
| distinct surface map squares | 64; the next scene load starts a new part |
| discovered cells | 40,000 |
| baseline objects | 40,000 |
| encoded file | 60 MiB, reserving space for the end record and leaving room for importer checkpoints within its 64 MiB archive limit |
| records | 120,000 plus the header |
| client-tick actor samples | 600,000 individual actors, not batches |
| camera samples | 40,000 |
| own/NPC speech lines combined | 600; consecutive duplicates per actor suppressed |
| hits / significant moments | 200,000 / 2,000 |
| poses | 800,000 |
| object transitions | 10,000 |
| figures posed each tick, nearest first | 1,024 (eligible actors in the loaded scene) |
| named figures in one file | 8,192 players and 8,192 NPCs (a figure costs a few hundred bytes; the pose and motion budgets are the real bound) |

A single scene is 104 by 104 tiles, so a long walk fills the cell budget
before the clock does. The header says so in its description, and Studio
shows the caveat.

## Where the file goes

Every recording is a file in RuneLite's own folder, beside every other plugin's
files:

- `~/.runelite/plugin-data/embertide/osrs-<date>-<time>-<name>-<capture-uuid>.embertide` (`<name>` is your
  own character's), written when **Stop recording**, the minutes-per-file split, logging out
  or closing the client seals the recording. The write goes through
  a uniquely named `.embertide.partial`, then a same-directory rename after
  closing the writer. Existing files are never replaced. The importer ignores partials. The name says what the file is; inside it is the `mccr/0.x`
  container every Embertide reader checks.
- RuneLite migrates the old `~/.runelite/embertide` folder through `Filepath`.
  The Embertide app scans both directories.
- The Embertide app, when it is installed, reads that folder and lists the
  recordings on its shelf; nothing has to be paired or configured.
- Without the app, **Recording folder path** shows a selectable path to copy
  into your file manager, and the panel's
  link opens [embertide.gg/studio](https://embertide.gg/studio/), which takes
  a file dragged in.

**A session is a chain of files.** Every **Minutes per file** (twenty by
default, five at the least) the recording in progress is sealed and the next
one begins after saving succeeds; every file of one login carries the same
series and a rising part number, and the same figure keeps the same
"Adventurer N" along the chain. The Embertide app shows the chain as one
card and opens it as one timeline; embertide.gg/studio does the same when
the files are dragged in together. Six hours is eighteen small files, each
usable the moment it is sealed.

The worker holds at most one pending recording. If saving fails, recording
pauses and accepted rows remain in memory. Use **Retry saving** or **Discard
unsaved recording**; discard requires confirmation. A successful retry resumes
only if recording is still requested. Stop persists across scene changes,
world hops and logins until Start is pressed.

A hard crash loses the active or unsaved recording. Retry failed saves before
closing RuneLite; recovery does not survive process termination.

## Using it

1. Enable the plugin and log in. The side panel shows `Recording …` once the
   first tick with a scene has opened the file. **Stop recording** seals it
   and records nothing more until you press **Start recording**; the panel's
   first button always says which of the two you are about to do. Turn off
   **Start recording when you log in** to begin every session stopped.
2. Play. Every **Minutes per file** the recording so far is written and the
   next begins; every file this session has written is listed on the panel
   under **This session**, with its length, and the one being written on top.
3. **Recording folder path** shows where to find the file. Copy it into your
   file manager; the panel’s website link opens Studio to drag it into.
   Closing the client or logging out seals the recording. **Open Studio when
   you log out** opens the website after a successful save.

Before the first eligible scene tick, recording is armed and events are ignored.
Stopping then cancels cleanly and creates no file. Actor selection covers the
loaded scene on your plane, which is not the same as screen visibility.

## Plugin Hub

This branch uses RuneLite's `Filepath` utility with
`internalName = "embertide"` and legacy-folder migration. The only website opener
is `LinkBrowser.browse`; folder opening, subprocesses, reflection and runtime
networking are absent from the plugin's main sources. Tests use temporary paths
and reflection solely to provide synthetic RuneLite interfaces.

**Release gate, checked 16 September 2026:** Plugin Hub currently pins 1.12.38.
The required Filepath API is available in the 1.12.39 development snapshot,
so this branch cannot compile on the Hub until its published version advances.
A SNAPSHOT build is not an approval result. See
[Hub readiness and acceptance](docs/plugin-hub-readiness.md).
