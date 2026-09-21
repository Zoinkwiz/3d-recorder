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

1. Install the plugin and log in. It starts recording on its own. The
   side panel shows `Recording …` and a Stop button if you want to pause
   it.
2. Play as normal.
3. When you're done, log out or press Stop, then:
   - **Browser:** open [embertide.gg/studio](https://embertide.gg/studio/)
     and drag your recording in. **Show my recordings** in the panel tells
     you where it is. Tick **Open Studio when you log out** and the site
     opens itself.
   - **Embertide app:** it picks up your recordings by itself and shows
     each session as one card. Sign in and you can share a link to a
     recording that anyone can watch on their phone, no account needed.

Long sessions get saved as a few files along the way so a crash doesn't
lose everything. You won't notice: Studio and the app show a session as
one recording.

If a save ever fails, the panel gives you a Retry button. Use it before you
close RuneLite, because a crash loses anything that hasn't been saved yet.

## What stays out

Nothing leaves your machine. By default the plugin doesn't connect to
anything: your recording is a file in RuneLite's folder, and it only goes
anywhere if you put it in Studio yourself or sign in to the app and save
it. If you turn on Show live in Embertide (off unless you do), the plugin
also sends the current recording, row by row, to the Embertide app running
on this same computer, over a local connection that never goes past your
own machine, and keeps that connection open so the app can tell the
session is still running. What the app then does with it is the app's own
settings, not the plugin's: it draws it, and it uploads it only if you
have signed in and chosen to save.

Other players show up as figures wearing what they wore, moving how they
moved, and that's it. The plugin doesn't read their names, combat levels
or chat, so none of that is in the file. In your recording they're just
"Adventurer 1", "Adventurer 2" and so on.

Your own public chat and what NPCs say overhead are recorded by default.
You can turn either off in the settings, along with NPCs and other players
altogether.

Each part of a session is written in one go when it ends, whether that's
the regular save every few minutes, pressing Stop, logging out or closing
the client. In between, it sits in memory. So there's no file slowly
filling up that could be read as a live feed of where everyone is; the
only live feed is the one you switch on, and it goes to the app on your
machine and nowhere else.

The plugin doesn't play for you and doesn't look at your inventory, bank
or account. It only records what's on your screen.

## Where the recordings are

`~/.runelite/plugin-data/embertide/`, one `.embertide` file per part of a
session, named with the date, time and your character's name. Older
versions saved to `~/.runelite/embertide/`; Studio and the app check both.
