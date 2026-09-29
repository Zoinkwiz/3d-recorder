# 3D Replay Recorder for Old School RuneScape

Records your session as a 3D replay. Afterwards you can fly a camera round
it from any angle, change the look, and cut clips of your play, either in
your browser at [embertide.gg/studio](https://embertide.gg/studio/) or in
the Embertide app. No account needed for the browser version.

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

## What is recorded

- Where you and the NPCs and players around you move, what they look like
  and what they do: animations, hits and deaths.
- The scenery and ground items in the area the game has loaded, and when
  they change.
- The ground around you, for the block view.
- Your camera, so a clip can start from the view you had.
- Your levels, XP, hitpoints, prayer, run energy and special attack.
- Your loot, with Grand Exchange prices, and milestone messages from the
  game: kill counts, personal bests, pets, collection log slots, clues,
  combat and Slayer tasks, quests and diaries. Only the kind of milestone and
  its numbers or names are kept, not the message.
- What NPCs say overhead, and your own public chat.

Other players are recorded as unnamed figures, "Adventurer 1", "Adventurer 2"
and so on. The plugin never reads their names, combat levels or chat, and
never reads private, clan or friends chat. It doesn't read your inventory or
bank, and it doesn't play for you.

You can turn off NPCs, other players, overhead text, your chat, and drops and
milestones in the plugin's settings.

## Privacy

The plugin doesn't connect to anything. It writes your recording to a
`.partial` file in its folder as you play, and renames the file when each
part is saved. Your recordings stay on your computer unless you choose to
share them.

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
