# Quest Overlay for OSRS Mobile

A floating quest helper card that sits on top of Old School RuneScape on Android.

- One step at a time, with a compass arrow pointing the way and the location in big letters
- **Done ✓** moves to the next step, **‹ Back** undoes a step
- **Items** tab: tap-to-tick checklist with quantities and where to get each item
- **All steps** tab: the whole quest, ticked off as you go. Tap any step to jump there
- Drag the card by its title, and use **−** to shrink it to a small bubble showing `3/7`
- Progress, position and opacity are saved
- It never reads or touches the game. You tick the steps off yourself, so it doesn't break Jagex's rules

Starter quests: Cook's Assistant, Sheep Shearer, Witch's Potion, Rune Mysteries, The Restless Ghost, Doric's Quest.

---

## Build it from your phone (no PC needed)

GitHub builds the app for you for free.

1. **Make a repo.** Sign in at github.com in Chrome, tap **+ → New repository**, name it `quest-overlay`, and tap **Create**.
2. **Upload the project.** In the new repo, tap **Add file → Upload files** and pick `questoverlay.zip`. Commit it.
   (If the upload button is missing, open Chrome's ⋮ menu and turn on **Desktop site**.)
3. **Add the build recipe.** Tap **Add file → Create new file**. For the name, type exactly
   `.github/workflows/build.yml`
   Paste in the contents of the `build.yml` file I sent you, then tap **Commit**.
4. **Wait about 5 minutes.** The **Actions** tab shows the build running. A green tick means it worked.
5. **Download the APK.** On the repo's main page, tap **Releases → Latest build → app-debug.apk**.
6. **Install it.** Open the download. Android asks you to allow installs from Chrome, so allow it.
   Google Play Protect may warn you because the app isn't from the Play Store. Choose **Install anyway**.

Every time you change a file in the repo, a new APK gets built. It installs over the old one and keeps your progress.

## Using it

1. Open **Quest Overlay** and tap **Grant permission**. Find Quest Overlay in the list and turn on **Allow display over other apps**.
2. Pick a quest and tap **Start overlay**.
3. Tap **Open Old School RuneScape**.
4. Drag the card wherever it doesn't cover anything important. Touches outside the card go straight to the game.
5. To close it, tap **×** on the card or **Stop** in the notification.

## Adding more quests

All quests live in `app/src/main/assets/quests.json`. Copy one quest block and change the text. Each step has:

| Field  | What it does |
|--------|--------------|
| `text`  | What to do |
| `where` | Shown in capitals above the step |
| `dir`   | Compass arrow: `N NE E SE S SW W NW`, or `UP`, `DOWN`, `HERE`, `NONE` |
| `hint`  | Small grey note: dialogue options, warnings, shortcuts |

Directions are rough guides relative to the previous step. Check them against your world map the first time you do a quest.

Quest text is adapted from the Old School RuneScape Wiki (CC BY-NC-SA 3.0). This app is not affiliated with Jagex.
