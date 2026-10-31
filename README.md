# 3D Replay Recorder for Old School RuneScape

Records your session as a 3D replay. Afterwards you can fly a camera round
it from any angle, change the look, and cut clips of your play, either in
your browser at [embertide.gg/studio](https://embertide.gg/studio/) or in
the [Embertide](https://embertide.gg/) app. No account needed for the browser
version. You can also watch a recording inside the game, in your player-owned
house. See [what Embertide does for Old School RuneScape](https://embertide.gg/games/old-school-runescape).

View your recordings:

<img width="1134" height="657" alt="A recorded session shown as a 3D scene in Embertide Studio" src="https://github.com/user-attachments/assets/5ab217b1-012d-44ad-8e63-86f171ed3f25" />

Change the lighting:

<img width="1128" height="570" alt="The same scene under a different sky" src="https://github.com/user-attachments/assets/c9a123aa-4b4a-4531-b6f3-8b5fc7671214" />

Change the renderer:

<img width="1128" height="646" alt="The same scene drawn with the game's own models" src="https://github.com/user-attachments/assets/0d2663d5-b48f-4237-8e62-e01580531c6c" />

Change your angle of the same scene:

<img width="1128" height="650" alt="The same moment from a different camera angle" src="https://github.com/user-attachments/assets/9345dea7-343d-4069-8482-b7407606533c" />

## How to make a clip

1. Install the plugin and log in. Your deaths are clipped from the start
   ([Always clip](#always-clip)). To record whole sessions, press **Start
   recording** in the side panel's **Record** tab; it asks once whether to
   record every time you log in instead, which is off until you choose it. The
   tab shows whether it is recording and for how long, with a Stop button. While it records, a small red light and
   REC show on the game screen (**Show a recording light** in the settings;
   hold Alt to drag it). Stop then Start while you stay logged in carries on
   with the same recording. To start and stop without opening the
   panel, pick a key for **Start or stop hotkey** in the plugin's settings.
2. Play as normal. Did something worth keeping? Press your **Clip that
   hotkey** (set it in the plugin's settings): the last 30 seconds, or as
   many as **Clip reaches back** says, are marked as a clip, the light says
   CLIPPED, and Studio puts that clip first. With Always clip on, the key and
   the side panel's **Save a clip** also save a clip of their own, recording
   or not.
3. When you're done, log out or press Stop, then open
   [embertide.gg/studio](https://embertide.gg/studio/) and drag your
   recording in. **Show my recordings** in the side panel tells you where it
   is. The **Record** tab lists your clips by name and this session's last
   few parts by the time they began, each with **Watch** to play it in your
   house, and **Open Studio** at the bottom. Tick **Open Studio when you log
   out** and the site opens by itself.

Long sessions are saved as a few files. Studio and the app show them as one
recording. A world hop, or a lost connection you're back from within five
minutes, saves a new file in the same recording. Logging out ends it.

## Boss fights

List your bosses under **Boss fights** in the plugin's settings, by name and
separated by commas (for example Zulrah, Vorkath). Each fight with one of them
is marked in the recording, from when it comes near until 30 seconds after it
is gone, so Studio can jump straight to it.

Tick **Record only these fights** to record nothing else: each attempt is its
own recording, named after the boss, and stops after 30 minutes at most. The
side panel says when it is waiting for one of your bosses.

## Watch it in your house

The side panel's **Watch** tab plays a saved recording back in your
player-owned house, as a small diorama (**Miniature**) or around you at full
size (**Life size**). It is the basic version: it plays the recording, and
you look round it with the game's camera. For clips, a different look and
share links, **Open Studio** at the bottom of the tab opens
[embertide.gg/studio](https://embertide.gg/studio/) in your browser.

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
can walk. **Hide surroundings** (on by default) hides your house's floor and
objects while the replay is placed, so the replay is clear, and puts them back
when you press **Clear**, leave the house or log out. Other players, pets and
NPCs stay as they are. It works with RuneLite's GPU
plugin and 117 HD; with other renderers the panel says so, and you can untick
it to watch with the house showing.

A recording saves the ground as you saw it. If one holds the floor only near
where you walked, or no floor at all, the panel says which.

## What the plugin shows in game

While you play, the plugin draws only its recording light. It shows nothing
about other players or NPCs: no prayers, health or anything else it records.
A recording is for watching back afterwards, in Studio in your browser or the
Embertide app, outside the game.

In your house, the Watch tab shows a recording's figures, scenery and ground,
nothing more. It never shows the prayers, hits, health bars, interfaces, chat
or clicks a recording holds.

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
- On a Sailing boat, the boat itself as it moves and turns: its deck, its
  objects and what they are doing, and its crew.
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
chat. It reads your bank only with Record the game interface on (below), and it
doesn't play for you.

You can turn off NPCs, other players, overhead text, your chat, and drops and
milestones in the plugin's settings.

**Record the game interface** is off until you turn it on. With it on, the
plugin also records what your own interface shows, so Studio can draw it:

- Your inventory and the items you wear.
- Your active prayers and your spellbook.
- How your interface is laid out: fixed or resizable, which side tab is open,
  and the minimap zoom.
- The chatbox's game messages, NPC dialogue and examine text, and your own
  public chat if you record it.
- Every interface you have open, your bank included: shops, dialogues, skill
  menus and the rest, as the game lays them out.

Even with it on, other players' names and chat and private, clan and friends
chat are never recorded. Friends, ignore, chat channel, clan, trade, duel,
group and party screens keep their layout without any text; anywhere else
another player's name is replaced by their "Adventurer" number or left out,
and your own name reads "You". What you type, the bank PIN screen and the
login and welcome screens are never recorded.

**Record your clicks** is off until you turn it on. With it on, the plugin
also records each menu option you choose (like Chop down Tree), where you
clicked on the game screen, the tile you clicked or walked to, and the game
tick, so Studio can show your clicks.
It never records what you type, never names another player (a click on one
points at their unnamed figure), leaves out what you click in the chatbox,
private chat and social screens, and records nothing while the bank PIN is
open.

## Always clip

**Always clip** saves a short clip each time something you ticked happens,
whether or not you are recording: your deaths, drops worth at least an amount
you choose, level-ups, pets and collection log slots, and boss kills. Deaths are on
unless you turn them off; the others are off until you turn them on.

With any of them on, the plugin keeps the last few seconds of play in memory,
recorded the same way as a recording with your settings. When one of them
happens, it saves a clip of the seconds before and after (20 and 5 unless you
change them) to its folder, named after what happened. Studio opens it like any
recording. The game's chat says when a clip is saved and what its file is called;
turn off **Tell me in chat** to keep it quiet. Nothing is written while nothing happens, and what is kept in memory
is let go every minute or so. With Always clip on, the Clip that hotkey saves a
clip too.

Drops, pets, collection log slots and boss kills come from the drops and
milestones the plugin records, so they need **Record drops and milestones** on.

## Privacy

The plugin doesn't connect to anything. It writes your recording to a
`.partial` file in its folder as you play, and renames the file when each
part is saved. To watch, it reads only the recordings in that same folder,
and the game's own models, which the client already has. It reads no other
files. Your recordings stay on your computer unless you choose to share them.

With **Record the game interface** on, a recording also holds your bank and
every interface you open, so anyone you share it with sees them. Other
players' names, what you type and the bank PIN are never in it.

Studio runs in your browser and keeps the recording on your computer unless
you make a share link. You don't need an account.

The Embertide desktop app finds your recordings by itself. Signing in to it
uploads nothing. It saves your recordings to your Embertide account, on
Embertide's servers, only while **Keep my recordings in my account** is on,
and you can turn that off in its Settings, under **Your data**.

The plugin records no audio and never uses your microphone. It notes which of
the game's sound effects played, so a replay can play them again. Only the
desktop app can record your voice, and only if you turn on **Record my voice
while I play**.

## Where the recordings are

`.runelite/plugin-data/3d-recorder/`, one `.embertide` file per part of a
session, named with the date, time and your character's name, and the boss's
name for a fight recorded on its own. **Show my recordings** in the side panel
shows the full path.

A `.embertide` file is an Embertide recording: drag it into
[embertide.gg/studio](https://embertide.gg/studio/), or the Embertide app finds
it by itself. While a part is still recording it ends in `.embertide.partial`.
If saving a part fails, the side panel offers to retry.

## Questions and help

Ask questions, report bugs or show what you made in the
[Embertide Discord](https://discord.gg/XbGqAEubSR). **Join the Embertide
Discord** at the bottom of the side panel opens the same invite. It only opens
your browser when you click it.
