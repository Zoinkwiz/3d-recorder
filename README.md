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
what they wore, standing where they stood, and nothing more: no display
name, no combat level, no chat. Each is "Adventurer 1", "Adventurer 2" in
order of first sighting, a number that means nothing outside that one file.
Of chat, the recording keeps your own public lines and what NPCs say overhead.
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
profile `ec.mccr.osrs/4`, that hold the game's own ids: which map regions
were loaded, which object stood on which tile, what each player wore, and
what animation each figure was in. A viewer that has the player's game cache
on the same machine can look those ids up and draw the real models from any
angle. A viewer without the cache ignores these records; the app stores them
unchanged. The file header records the cache revision, so the file can still
be drawn against the right version of the game after an update.

| Record | Content |
| --- | --- |
| `osrs.region` | On each scene load: the loaded map regions, the scene base, the plane, and whether it is an instance. The static world is the cache's own map at that revision; recorded objects override it where they differ. |
| `osrs.object` | Baseline objects within the capture radius, and every spawn or despawn: object id, kind (game, wall, ground, decorative), packed type and orientation from the object config, the varbit-selected impostor id when there is one, and the anchor tile of a multi-tile object. |
| `osrs.appearance` | A player's equipment ids, colours and gender, once and again whenever they change. |
| `osrs.poses` | Once per game tick, one entry for every figure the client draws, nearest first: `[id, x, y, plane, height, orientation, animation, pose animation, frame, graphic, npc id or -1, graphic height, client slot]`. The world tile, the ground height the client placed it at, the animation it was in and the effect over its head. |
| `osrs.motion` | On the 20 ms client tick, for you and every tracked figure within the radius: `[id, x, y, plane, orientation, animation, frame, pose animation]` with x and y in fractional tiles, written only when something changed. A run across two tiles with a turn halfway is here; the game tick alone cannot carry it. Bounded at 600,000 samples per recording. |

Coordinates map a world tile `(x, y, plane)` to the cell
`(x, plane * 8 + elevation, -y)`, declared in every header as
`x-east,y-up,z-south`, so north points into the screen of a y-up viewer and
the four planes stack eight blocks apart. Elevation is the tile's rendered
height at one block per tile width, clamped so a plane never climbs into the
next. See `SceneMapper` for the exact arithmetic.

An instanced area is its own coordinate space, so entering or leaving one
seals the current recording and starts another with dimension
`osrs:instance:<region>`. Everything else is `osrs:surface`.

### Budgets

Each recording is bounded, then the next one begins where it stopped:

| Budget | Limit |
| --- | --- |
| duration | 20 minutes |
| discovered cells | 40,000 |
| poses | 800,000 |
| object transitions | 10,000 |
| figures posed each tick, nearest first | 1,024 (everyone the client draws) |
| named figures in one file | 8,192 players and 8,192 NPCs (a figure costs a few hundred bytes; the pose and motion budgets are the real bound) |

A single scene is 104 by 104 tiles, so a long walk fills the cell budget
before the clock does. The header says so in its description, and Studio
shows the caveat.

## Where the file goes

Every recording is a file in RuneLite's own folder, beside every other plugin's
files:

- `~/.runelite/embertide/osrs-<date>-<time>-<name>.embertide` (`<name>` is your
  own character's), written when **Stop recording**, the minutes-per-file split, logging out
  or closing the client seals the recording. The write goes through
  `.embertide.partial` and an atomic rename, so a reader never sees half a
  file. The name says what the file is; inside it is the `mccr/0.x`
  container every Embertide reader checks.
- The Embertide app, when it is installed, reads that folder and lists the
  recordings on its shelf; nothing has to be paired or configured.
- Without the app, **Show the folder** opens the folder, and the panel's
  link opens [embertide.gg/studio](https://embertide.gg/studio/), which takes
  a file dragged in.

**A session is a chain of files.** Every **Minutes per file** (twenty by
default, five at the least) the recording in progress is sealed and the next
one begins where it stopped; every file of one login carries the same
series and a rising part number, and the same figure keeps the same
"Adventurer N" along the chain. The Embertide app shows the chain as one
card and opens it as one timeline; embertide.gg/studio does the same when
the files are dragged in together. Six hours is eighteen small files, each
usable the moment it is sealed.

A hard crash loses only the file in progress: at most the minutes-per-file
setting, and the price of never having a live file on disk.

## Using it

1. Enable the plugin and log in. The side panel shows `Recording …` once the
   first tick with a scene has opened the file. **Stop recording** seals it
   and records nothing more until you press **Start recording**; the panel's
   first button always says which of the two you are about to do. Turn off
   **Start recording when you log in** to begin every session stopped.
2. Play. Every **Minutes per file** the recording so far is written and the
   next begins; every file this session has written is listed on the panel
   under **This session**, with its length, and the one being written on top.
3. When you are done, **Show the folder** opens the folder the files are in,
   and the link at the top of the panel opens the web Studio to drag one
   into. Closing the client or logging out seals the recording, and with **Open
   Studio when you log out** on, shows the folder and opens Studio for you.

## Plugin Hub

`runelite-plugin.properties` carries the Hub manifest (`build=standard`, so
the Hub builds it with its own Gradle). What a reviewer will want to know is
above: the plugin opens no connection, writes only under RuneLite's own
folder and only when a recording is sealed (never a live file), records other
players as unnamed figures with no chat, and uses the client's own
`LinkBrowser` to show the folder and open the web Studio.
