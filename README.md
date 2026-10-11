# 3D Replay Recorder for Old School RuneScape

Records your session as a 3D replay. Afterwards you can fly a camera round
it from any angle, change the look, and cut clips of your play, either in
your browser at [embertide.gg/studio](https://embertide.gg/studio/) or in
the Embertide app. No account needed for the browser version. You can also
watch a recording inside the game, in your player-owned house.

View your recordings:

<img width="1134" height="657" alt="A recorded session shown as a 3D scene in Embertide Studio" src="https://github.com/user-attachments/assets/5ab217b1-012d-44ad-8e63-86f171ed3f25" />

Change the lighting:

<img width="1128" height="570" alt="The same scene under a different sky" src="https://github.com/user-attachments/assets/c9a123aa-4b4a-4531-b6f3-8b5fc7671214" />

Change the renderer:

<img width="1128" height="646" alt="The same scene drawn with the game's own models" src="https://github.com/user-attachments/assets/0d2663d5-b48f-4237-8e62-e01580531c6c" />

Change your angle of the same scene:

<img width="1128" height="650" alt="The same moment from a different camera angle" src="https://github.com/user-attachments/assets/9345dea7-343d-4069-8482-b7407606533c" />

## How to make a clip

1. Install the plugin and log in. It starts recording on its own. The side
   panel shows the recording time and a Stop button.
2. Play as normal.
3. When you're done, log out or press Stop, then open
   [embertide.gg/studio](https://embertide.gg/studio/) and drag your
   recording in. **Show my recordings** in the side panel tells you where it
   is. Tick **Open Studio when you log out** and the site opens by itself.

Long sessions are saved as a few files. Studio and the app show them as one
recording.

## Watch it in your house

The side panel's **Watch** tab plays a saved recording back in your
player-owned house, as a small diorama (**Miniature**) or around you at full
size (**Life size**).

1. Press **Open recording…** and pick one, or press **Watch in your house**
   under a part saved this session in the **Record** tab.
2. Go into your house, ideally an open garden or an empty room.
3. Press **Place here**, or right-click a floor tile and choose **Place
   Embertide replay**. Then play, pause, scrub, change the speed or rotate it.

The size of the miniature, how much of the area it shows, the floors, the
object limit and its height above the floor are in the plugin's settings,
under **Watching in your house**.

Only you can see it. The recorded figures and scenery are pictures: they
can't be clicked, don't block anyone, and your house still decides where you
can walk. **Hide surroundings** (on by default) hides the house, other
players and pets while the replay is placed, and puts them back when you
press **Clear**, leave the house or log out. It works with RuneLite's GPU
plugin and 117 HD; with other renderers the panel says so, and you can untick
it to watch with the house showing.

Recordings made with this version save the ground as you saw it. Older ones
show the floor only near where you walked, or no floor at all, and the panel
says which.

## What is recorded

- Where you and the NPCs and players around you move, what they look like
  and what they do: animations, projectiles, hits and deaths.
- The prayer and skull icons over heads, and the health bar the game shows
  at the top of the screen.
- The sound effects the game plays, and your game volume settings.
- The scenery and ground items in the area the game has loaded, and when
  they change.
- The ground of the area the game has loaded, with its colours, shapes and
  heights as the game draws them, and the ground around you for the block view.
- Your camera, so a clip can start from the view you had.
- Your levels, XP, hitpoints, prayer, run energy and special attack.
- Your loot, with Grand Exchange prices, and milestone messages from the
  game: kill counts, personal bests, pets, collection log slots, clues,
  combat and Slayer tasks, quests and diaries. Only the kind of milestone and
  its numbers or names are kept, not the message.
- What NPCs say overhead, and your own public chat.

Other players are recorded as unnamed figures, "Adventurer 1", "Adventurer 2"
and so on. The plugin looks at their names only while you play, to keep each
one's number the same and to tell your own chat from theirs. It never records
their names, combat levels or chat, and never reads private, clan or friends
chat. It doesn't read your inventory or
bank, and it doesn't play for you.

You can turn off NPCs, other players, overhead text, your chat, and drops and
milestones in the plugin's settings.

## Privacy

Unless you turn on Show live in Embertide, the plugin doesn't connect to
anything. It writes your recording to a
`.partial` file in its folder as you play, and renames the file when each
part is saved. To watch, it reads only the recordings in that same folder,
and the game's own models, which the client already has. It reads no other
files. Your recordings stay on your computer unless you choose to share them.

If you turn on Show live in Embertide (off unless you do), the plugin also
sends the current recording, row by row, to the Embertide app running on this
same computer, over a local connection that never leaves your machine, and
keeps that connection open so the app can tell the session is still running.
What the app does with it is the app's own settings: it draws it, and it
uploads it only if you have signed in.

Studio runs in your browser and keeps the recording on your computer unless
you make a share link. You don't need an account.

The Embertide desktop app finds your recordings by itself. If you're signed
in to the app, it adds them to your Embertide account, which stores them on
Embertide's servers. Signed out, they stay on your computer.

## Where the recordings are

`.runelite/plugin-data/3d-recorder/`, one `.embertide` file per part of a
session, named with the date, time and your character's name. **Show my
recordings** in the side panel shows the full path.

Each file is gzipped JSON Lines. If saving a part fails, the side panel
offers to retry.

## Development

With JDK 11, `./gradlew test jar`, then `./gradlew run` (or double-click
`run-dev.command` on a Mac) starts a RuneLite development client with the
plugin. `./gradlew inspectRecording -Precording=<file or folder>` checks that
saved recordings open, and `scripts/size-budget.sh` counts the repository's
text against its 200k-token budget.
