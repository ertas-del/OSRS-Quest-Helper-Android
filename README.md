# Quest Overlay for OSRS Mobile

A floating quest guide that sits on top of Old School RuneScape on Android. It covers every quest and miniquest in the RuneLite Quest Helper plugin (208 at the time of writing), one step at a time, with a compass, distances, chat options and item checklists.

It never reads, hooks or automates the game. You tick each step off yourself, so it works like having the wiki open beside you.

---

## Features

### The floating card
- **One step at a time.** The section you're in, what to do, and a big **Done ✓** button. **‹** goes back a step.
- **Compass arrow and distance.** Worked out from the real map tiles of each step, for example *"Head north-east · about 60 tiles"*. Special arrows show **Climb up**, **Climb down** and **Right around here**.
- **Chat options.** The dialogue to pick, in order, e.g. *Chat: What's wrong? › Can I help? › Yes.*
- **What to bring.** The first step of each section lists the items that section needs.
- **Map button.** Opens the exact tile on the community OSRS world map. The card shrinks to a bubble so the map isn't covered.
- **Progress bar** plus a *Step 5 of 21* counter.
- **Three tabs:**
  - **Step:** the current step, as above.
  - **Items:** tap-to-tick checklist split into *Required* and *Recommended*, with quantities and tips (for example *"Can be obtained during the quest"*). Below that are the quest's skill, quest-point and quest requirements.
  - **All steps:** the whole quest grouped by section, with finished steps crossed off. It opens scrolled to where you are, and tapping any step jumps there.
- **Drag** the card by its title to move it. **−** shrinks it to a gold bubble showing `5/21`; tap the bubble to open it again. **×** closes it.
- **Stays on screen** in portrait and landscape. Lists shrink to fit and the card can't be dragged off the edge.
- **Touches outside the card go straight to the game.** It never steals your taps or keyboard.

### The quest picker
- **Search** by name. Apostrophes and punctuation don't matter, so *"cooks"* finds Cook's Assistant.
- **Filters:** All · Free · Members · Mini · Started.
- **Smart order:** the quest you're showing comes first, then quests in progress, then everything else A–Z.
- Each card shows type, difficulty, step count, requirements (*Needs: Mining 15, Rune Mysteries*) and your status.
- **Reset** appears once you've started a quest.
- **Overlay opacity** slider (40–100%).
- **Open Old School RuneScape** button to jump straight into the game.

### Saving
- Progress, ticked items, card position and opacity are all saved.
- Your place is remembered by the step's text, not just its number. If a data update adds or reorders steps, you stay on the same step.
- Updates install over the old app and keep everything.

### Always up to date
- Every build pulls the newest Quest Helper data and converts it, so new quests appear without anyone typing them in.
- If that ever fails, or returns suspiciously few quests (under 150), the build keeps the quest list already in the project.

---

## Install it from your phone (no PC needed)

GitHub builds the app for you for free.

1. **Make a repo.** Sign in at github.com in Chrome, tap **+ → New repository**, give it a name, and tap **Create**.
2. **Upload the project.** In the repo, tap **Add file → Upload files**, pick `questoverlay.zip`, and commit it.
   (If the upload button is missing, open Chrome's ⋮ menu and turn on **Desktop site**.)
3. **Add the build recipe.** Tap **Add file → Create new file** and name it exactly
   `.github/workflows/build.yml`
   Paste in the contents of `build.yml`, then tap **Commit**.
4. **Let it publish releases (one time).** Go to **Settings → Actions → General → Workflow permissions**, choose **Read and write permissions**, and tap **Save**.
5. **Wait a few minutes.** The **Actions** tab shows the build. A green tick means it worked.
6. **Download the APK.** On the repo's main page, tap **Releases → Latest build → app-debug.apk**.
   *Backup:* open the finished run in **Actions** and download **QuestOverlay-debug-apk** under **Artifacts**. It's a zip with the APK inside.
7. **Install it.** Open the download and allow installs from Chrome when Android asks.
   Play Protect may warn you because the app isn't from the Play Store. Choose **Install anyway**.

### Updating
Upload the new `questoverlay.zip` over the old one, or edit any file in the repo. Each change starts a new build. To refresh the quest data without changing anything, open **Actions → Build APK → Run workflow**.

---

## Using it

1. Open **Quest Overlay** and tap **Grant permission**. Find Quest Overlay in the list and turn on **Allow display over other apps**.
2. Allow notifications if asked. The overlay runs as a small notification with a **Stop** button.
3. Search for a quest and tap **Start overlay**.
4. Tap **Open Old School RuneScape**.
5. Drag the card somewhere it doesn't cover anything you need. Tap **Done ✓** as you finish each step.
6. To close it, tap **×** on the card or **Stop** in the notification.

### Reading the compass

| Arrow | Meaning |
|-------|---------|
| Gold arrow + distance | Walk that way from the previous step's spot |
| ▲ / ▼ | Climb up or down (ladders, stairs) |
| Gold ring | You're already in the right area |
| Small dot, no label | No reliable direction, usually a dungeon, instance, teleport or cutscene. Use the text and the **Map** button |

Bearings point from where the previous step happened, so if you've wandered off, trust your minimap.

---

## Where the quests come from

Steps, items, requirements and map tiles are converted from the open-source
[RuneLite Quest Helper](https://github.com/Zoinkwiz/quest-helper) plugin by `tools/convert_questhelper.py`:

- the step order and section names come from each quest's sidebar panels
- step text, map tiles and chat options come from each step's definition
- required and recommended items, plus their tips, come from the quest's item lists
- skill, quest and quest-point requirements come from its general requirements
- compass bearings come from the tile of one step to the next. Underground areas are projected onto the surface map so going in and out of dungeons still points the right way

To regenerate the data yourself on a computer:

```bash
git clone --depth 1 https://github.com/Zoinkwiz/quest-helper qh
python3 tools/convert_questhelper.py qh app/src/main/assets/quests.json
```

## Known limits

- About 1 in 6 steps (puzzles, cutscenes, fights) has no map tile, so it shows no arrow and no Map button.
- A few puzzle-heavy quests show a section title as the step text where Quest Helper uses an in-game puzzle solver the overlay can't copy.
- It can't tell when you finish a step, by design. That's what keeps it within Jagex's rules.
- Android only. iPhones don't let apps draw over other apps.

## Project layout

```
app/src/main/java/com/questoverlay/
  MainActivity.kt     quest picker, search, filters, settings
  OverlayService.kt   the floating card (foreground service + overlay window)
  Quest.kt            quest data model and JSON loader
  ProgressStore.kt    saved progress, ticks, position, opacity
  Ui.kt               colours, compass, checkboxes, helpers
app/src/main/assets/quests.json   the quest data
tools/convert_questhelper.py      Quest Helper → quests.json converter
.github/workflows/build.yml       cloud build: refresh data, build APK, publish release
```

## Credits

Quest data © 2020 Zoinkwiz, BSD 2-Clause. See `NOTICE.md` for the full licence.
Map by [mejrs](https://mejrs.github.io/osrs).
Not affiliated with Jagex or RuneLite. *Old School RuneScape* is a trademark of Jagex Ltd.
