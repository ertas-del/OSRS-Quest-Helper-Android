# Quest Overlay for OSRS Mobile

A floating quest guide that sits on top of Old School RuneScape on Android. It covers every quest and miniquest in the RuneLite Quest Helper plugin (208 at the time of writing), one step at a time, with a compass, distances, chat options and item checklists.

It never reads, hooks or automates the game. You tick each step off yourself, so it works like having the wiki open beside you.

It's dressed like the classic in-game interface: bevelled brown stone panels, pixel-font titles, orange and yellow text with crisp black shadows (body text uses your phone's own font so it stays easy to read), a compass with a red north needle, and quest names coloured red, yellow and green like the in-game quest list.

---

## Features

### The floating card
- **One step at a time.** The section you're in, what to do, and a big **Done ✓** button. **‹** goes back a step.
- **Compass arrow and distance.** Worked out from the real map tiles of each step, for example *"Head north-east · about 60 tiles"*. Special arrows show **Climb up**, **Climb down** and **Right around here**.
- **Chat options.** The dialogue to pick, in order, e.g. *Chat: What's wrong? › Can I help? › Yes.*
- **What to bring.** The first step of each section lists the items that section needs.
- **Map button.** Opens the exact tile on the community OSRS world map. The card shrinks to a bubble so the map isn't covered.
- **Progress bar** plus a *Step 5 of 21* counter.
- **Up to five tabs:**
  - **Step:** the current step, as above.
  - **Route:** the travel guide for this step (see below).
  - **Puzzle:** answers and solvers for the quest's puzzles (see below). Only shown for quests that have puzzles.
  - **Items:** tap-to-tick checklist split into *Required* and *Recommended*, with quantities and tips (for example *"Can be obtained during the quest"*). Below that are the quest's skill, quest-point and quest requirements.
  - **All steps:** the whole quest grouped by section, with finished steps crossed off. It opens scrolled to where you are, and tapping any step jumps there.
- **Drag** the card by its title to move it. **−** shrinks it to a gold bubble showing `5/21`; tap the bubble to open it again. **×** closes it.
- **Stays on screen** in portrait and landscape. Lists shrink to fit and the card can't be dragged off the edge.
- **Touches outside the card go straight to the game.** It never steals your taps or keyboard.

### Travel guide
- **How to get there, for every step.** A real route finder works out the fastest way from where the last step happened to the next one, using:
  - teleport spells on all four spellbooks, plus the home teleport
  - teleport jewellery and items
  - fairy rings, spirit trees, gnome gliders, magic carpets, quetzals and mushtrees
  - ships, charter ships, canoes, minecarts and balloons
  - portals and levers, agility shortcuts, minigame teleports
  - your player-owned house (see below)
- **One-line summary on the Step tab**, e.g. *Travel: Cast Varrock Teleport → Walk 51 tiles south-east*. Tap it for the full route.
- **Route tab:** each leg with what it needs, e.g. *Magic 25 · 3 Air rune, 1 Fire rune, 1 Law rune* or *needs Tree Gnome Village*, plus total walking distance and time.
- **Climbs and caves are spelled out:** *Climb down the ladder*, *Climb up the ladder (×2)*.
- **Travel settings:** members or free-to-play, spellbook, which ways to travel you've unlocked, avoid the Wilderness, use diary teleports, and where to plan the first step from (any bank).
- **Your account (optional):** type your RuneScape name to
  - load your levels from the official hiscores, so it skips teleports and shortcuts you can't use yet
  - import finished quests from WikiSync, if you've played with RuneLite's WikiSync plugin
- **Travel through your house.** Switch on *Your house* in Travel settings and tick what you've built. Routes can then:
  - get you home with the Teleport to House spell, house tablets or a Construction cape, including the *Outside* option
  - walk out of the exit portal at your house location (Rimmington, Taverley, Pollnivneach, Rellekka, Brimhaven, Yanille, Hosidius, Prifddinas or Aldarin)
  - use your portal chamber or portal nexus destinations, e.g. *Break a Teleport to House tablet → In your house: Kharyrll Portal*
  - use your jewellery box (basic, fancy or ornate), mounted glory, mythical cape, Xeric's talisman and digsite pendant
  - use the fairy ring and spirit tree in your garden
- **Mark done** on any quest card, and finishing a quest in the overlay marks it automatically, so quest-locked transport such as spirit trees and gliders unlocks in your routes.
- Planned on the phone in the background, usually in well under a second.

### Puzzles
55 puzzles across 25 quests. A **Puzzle help** link appears on the steps where a puzzle comes up, and every
puzzle in the quest is on the Puzzle tab.

- **Answers (37):** puzzles that are the same for everyone, written out as tick-off checklists:
  - all seven Song of the Elves light puzzles
  - Forsaken Tower jugs and altar
  - Blood Moon Rises clocks and forest
  - the arrow chests
  - the Recruitment Drive rooms
  - the DT2 golem grid (with a diagram)
  - Elemental Workshop II pipes
  - Path of Glouphrie storeroom
  - While Guthix Sleeps potions
  - and more
- **Solvers (18):** puzzles that change per player. You tap in what you see and the app works out the answer:

| Quest | Puzzle | You enter |
|-------|--------|-----------|
| Sins of the Father | Mausoleum door grid | the 10 row and column numbers |
| The Curse of Arrav | Metal door | the 4 letters on the code key |
| Lunar Diplomacy | Dice | the number asked for |
| The Fremennik Trials | Peer's door | which riddle you got |
| Tribal Totem / Recruitment Drive | Letter locks | the lock's current letters |
| The Heart of Darkness | Letter chest and arrow chest | the highlighted letters and words |
| Dragon Slayer II | Crypt busts | three clues from the plaque |
| Beneath Cursed Sands | Tomb riddle | the gods and offerings named |
| The Forsaken Tower | Refinery potion | where "Cleansing fluid" appears in the notes |
| A Kingdom Divided | Wall panel code and statues | the stone numbers, the city order |
| Song of the Elves | Baxtorian pillars | the five pillar hints |
| Eyes / Path of Glouphrie | Disc machines | the target discs |
| Icthlarin's Little Helper | Door tiles | which tiles in a row are wrong |
| Desert Treasure II | Growth sequence | the order you saw (a memory aid) |

- **Tips** for the few puzzles whose answer is hidden from the player, such as the King's Ransom lockpick, the Lunar cloud floor and the DT2 rune rifts.
- Everything works by tapping, so the game never loses focus to a keyboard.

### The quest picker
- **Search** by name. Apostrophes and punctuation don't matter, so *"cooks"* finds Cook's Assistant.
- **Filters:** All · Free · Members · Mini · Started.
- **Smart order:** the quest you're showing comes first, then quests in progress, then everything else A–Z.
- Each card shows type, difficulty, step count, requirements (*Needs: Mining 15, Rune Mysteries*) and your status. Quest names are red (not started), yellow (in progress) or green (done), like the in-game list.
- **Reset** appears once you've started a quest.
- **Mark done** for quests you finished before installing the app.
- **Overlay opacity** slider (40–100%).
- **Travel settings** button.
- **Open Old School RuneScape** button to jump straight into the game.

### Saving
- Progress, ticked items, card position and opacity are all saved.
- Your place is remembered by the step's text, not just its number. If a data update adds or reorders steps, you stay on the same step.
- Updates install over the old app and keep everything.

### Always up to date
- Every build pulls the newest Quest Helper quests and the newest Shortest Path teleports and walking map, so new content appears without anyone typing it in.
- If either refresh fails, or returns suspiciously little data, the build keeps what's already in the project.

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

## Where the travel routes come from

Teleports, transports, doors, stairs and the walkability of every tile come from the open-source
[Shortest Path](https://github.com/Skretzo/shortest-path) RuneLite plugin, converted by `tools/convert_transports.py`.
The app runs its own route finder on your phone: a shortest-path search over every walkable tile,
plus every teleport and transport your settings allow. Teleports that cost runes, charges or a cooldown
get a small penalty, so it won't burn a law rune to save five steps.

The app never sees your bank, inventory or unlocks, so the route lists what each leg needs.
Switch off anything you don't have in **Travel settings**.

## Known limits

- About 1 in 6 steps (puzzles, cutscenes, fights) has no map tile, so it shows no arrow and no Map button.
- A few puzzle-heavy quests show a section title as the step text where Quest Helper uses an in-game puzzle solver the overlay can't copy.
- About 1 in 5 steps are inside quest instances or puzzle areas the walking map doesn't cover. Those show no route, only the step text.
- Routes assume you have the runes, jewellery and unlocks for anything switched on in Travel settings.
- The app can't see your house, so house routes only use what you've ticked under *Your house*. Leave a portal unticked if you're not sure.
- Free-to-play mode doesn't fence off members-only areas while walking. It only limits teleports and boats.
- It can't tell when you finish a step, by design. That's what keeps it within Jagex's rules.
- Android only. iPhones don't let apps draw over other apps.

## Where the puzzle answers come from

The puzzle answers, rules and lookup tables come from reading the puzzle code in the RuneLite Quest Helper plugin, which
solves them by reading the game. The app reworks each one to use what you can see instead. The content is in
`tools/build_puzzles.py`; run `python3 tools/build_puzzles.py` to rebuild `puzzles.json` after editing it.

## Project layout

```
app/src/main/java/com/questoverlay/
  MainActivity.kt     quest picker, search, filters, settings
  OverlayService.kt   the floating card (foreground service + overlay window)
  Quest.kt            quest data model and JSON loader
  ProgressStore.kt    saved progress, ticks, position, opacity
  TravelSettingsActivity.kt   travel guide settings and account lookups
  travel/             route finder, transport data, travel settings, hiscores/WikiSync
  puzzles/            puzzle solvers (Solvers.kt), puzzle data loader and the Puzzle tab screens
  Ui.kt               colours, compass, checkboxes, helpers
app/src/main/assets/quests.json   the quest data
tools/convert_questhelper.py      Quest Helper → quests.json converter
tools/convert_transports.py       Shortest Path → transports.tsv, places.tsv, collision-map.zip
tools/build_puzzles.py            puzzle answers and solver settings → puzzles.json
app/src/main/assets/transports.tsv, collision-map.zip, places.tsv   travel data
.github/workflows/build.yml       cloud build: refresh quests and travel data, build APK, publish release
```

## Credits

Quest data © 2020 Zoinkwiz, BSD 2-Clause. Travel data © Skretzo and contributors (Shortest Path), BSD 2-Clause. See `NOTICE.md` for both licences.
Map by [mejrs](https://mejrs.github.io/osrs). Pixel font: [Pixelify Sans](https://github.com/eifetx/Pixelify-Sans) (SIL Open Font License).
Not affiliated with Jagex or RuneLite. *Old School RuneScape* is a trademark of Jagex Ltd.
