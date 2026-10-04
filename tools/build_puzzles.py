#!/usr/bin/env python3
"""
Writes app/src/main/assets/puzzles.json: help for quest puzzles.

Two kinds of entry:
  guide   the answer is the same for everyone: a checklist of exact steps
  solver  the answer changes per player: the app asks what you see and works it out
          (the "solver" name picks the Kotlin solver in puzzles/Solvers.kt)

Solutions and lookup tables come from reading the RuneLite Quest Helper plugin's puzzle code
(https://github.com/Zoinkwiz/quest-helper, BSD 2-Clause). Where the code left something
unclear, the text says so instead of guessing.

Usage: python3 tools/build_puzzles.py app/src/main/assets/puzzles.json
"""
import json
import sys

P = []


def guide(pid, quest, title, steps, match=None, intro="", notes=None, grid=None):
    P.append({
        "id": pid, "quest": quest, "title": title, "kind": "guide",
        "match": match or [], "intro": intro, "steps": steps,
        "notes": notes or [], "grid": grid or [],
    })


def solver(pid, quest, title, name, match=None, intro="", notes=None, config=None):
    P.append({
        "id": pid, "quest": quest, "title": title, "kind": "solver", "solver": name,
        "match": match or [], "intro": intro, "notes": notes or [], "config": config or {},
    })


# ---------------------------------------------------------------------------------- solvers

solver("sotf_door", "sins_of_the_father", "Mausoleum door grid", "kakurasu",
       match=[r"mausoleum'?s door", r"door puzzle"],
       intro="A 5×5 grid of switches with a number next to every row and under every column. "
             "Enter those 10 numbers and the app lights up the switches to press.",
       notes=["A row's number is the total of the column values of its pressed switches "
              "(columns are worth 1 to 5 from left to right). A column's number works the same way "
              "with rows worth 1 to 5 from top to bottom.",
              "Some grids have more than one answer; any answer opens the door."])

solver("arrav_metal_door", "the_curse_of_arrav", "Metal door code", "metaldoor",
       match=[r"metal doors?", r"code key", r"decoder strips"],
       intro="Read the code key: it says \"It reads ????\" with four letters from A to I. "
             "Pick them and the app gives the 4-digit door code.",
       notes=["On the door, set each digit with Up/Down, then press Enter. Press Back if a digit went in wrong."])

solver("lunar_dice", "lunar_diplomacy", "Chance challenge (dice)", "dice",
       match=[r"dice challenge", r"chance challenge"],
       intro="The Ethereal Fluke names a number. Pick it and the app shows which face each die must show. "
             "Each die only flips between two opposite faces.",
       notes=["You need to do this 5 times in a row."])

solver("fremmy_trials_door", "the_fremennik_trials", "Peer the Seer's door", "letterlock",
       match=[r"peer'?s door"],
       intro="Read the riddle on the door, pick it below, then (optionally) enter the letters the "
             "lock shows now to get the number of clicks for each wheel.",
       config={
           "alphabet": "ABCDEFGHIJKLMNOPQRSTUVWXYZ",
           "forward": "right arrow", "backward": "left arrow",
           "answers": [
               {"label": "My first is in mage…", "word": "MIND"},
               {"label": "My first is in tar…", "word": "TREE"},
               {"label": "My first is in well…", "word": "LIFE"},
               {"label": "My first is in fish…", "word": "FIRE"},
               {"label": "My first is in water…", "word": "TIME"},
               {"label": "My first is in wizard…", "word": "WIND"},
           ]})

solver("tribal_totem_door", "tribal_totem", "Combination door", "letterlock",
       match=[r"password for the door"],
       intro="The password is KURT. Enter the letters the lock shows now for the number of clicks.",
       config={
           "alphabet": "ABCDEFGHIJKLMNOPQRSTUVWXYZ",
           "forward": "right arrow", "backward": "left arrow",
           "answers": [{"label": "Password", "word": "KURT"}]})

solver("rd_sir_ren", "recruitment_drive", "Sir Ren Itchood's door", "letterlock",
       match=[r"sir ren itchood"],
       intro="Sir Ren's riddle always hides one of these six words. Work out which from his clue, "
             "pick it, then enter the lock's current letters for click counts.",
       notes=["The six possible answers are TIME, FISH, RAIN, BITE, MEAT and LAST."],
       config={
           "alphabet": "ABCDEFGHIJKLMNOPQRSTUVWXYZ",
           "forward": "right arrow", "backward": "left arrow",
           "answers": [{"label": w, "word": w} for w in ["TIME", "FISH", "RAIN", "BITE", "MEAT", "LAST"]]})

solver("hod_chest_letters", "the_heart_of_darkness", "Letter chest (book code)", "letterlock",
       intro="Read the book: four lines are highlighted in white. The first capital letter of each "
             "white line, in order, is the code. Pick each letter, then the chest's current letters.",
       config={
           "dials": ["LWABPEMDTR", "APORILCETN", "WERILANUTO", "EIDAOWKNRU"],
           "forward": "up arrow", "backward": "down arrow",
           "answers": []})

solver("hod_arrow_poem", "the_heart_of_darkness", "Arrow chest (poem)", "wordarrows",
       intro="The poem has four words highlighted in white. Pick them in order to get the arrow code.",
       config={
           "words": {"Makt": "Up", "Takam": "Right", "Uitt": "Down", "Silam": "Left"},
           "count": 4})

solver("ds2_crypt", "dragon_slayer_ii", "Crypt busts", "crypt",
       match=[r"crypt puzzle", r"inspect the tomb in the south room"],
       intro="Inspect the tomb to read the story on the stone plaque, then answer the three questions. "
             "The app tells you which bust goes on which plinth.",
       notes=["Busts: Tristan (north-east corner), Aivas (north-west), Robert (south-west), Camorra (south-east).",
              "Place them on the north, east, south and west plinths, then inspect the tomb again."])

solver("bcs_tomb", "beneath_cursed_sands", "Tomb riddle", "tomb",
       match=[r"tomb riddle"],
       intro="Read the north-western plaque, take the four emblems from the south-western one, "
             "then answer the four questions from the riddle.",
       notes=["Put one emblem in each urn, then pull the lever."])

solver("ft_potion", "the_forsaken_tower", "Refinery potion", "fluid",
       match=[r"refinery", r"fluid"],
       intro="Search the cupboard on the east wall of the 1st floor for the Old notes and read them. "
             "Which sentence mentions \"Cleansing fluid\", and where in it?",
       notes=["Take that fluid from the table, use it on the refinery, then activate the refinery."])

solver("akd_stone_code", "a_kingdom_divided", "Forthos wall panel code", "stonecode",
       match=[r"wall panel", r"stone pile"],
       intro="Check the four stone piles around the ruins. Each message names a letter (E, R, S or O) "
             "and a number. Enter the number from each.",
       notes=["Piles: E north-east, R south-east, S south-west, O north-west.",
              "Then chop and squeeze through the vines and enter the code on the wall panel."])

solver("akd_statues", "a_kingdom_divided", "Piscarilius statues", "sequence",
       match=[r"statue"],
       intro="Climb the pillar west of Martin Holt and check the wall panel. Tap the cities in the order "
             "the panel lists them; the app shows where each statue is.",
       config={
           "count": 5,
           "items": [
               {"name": "Arceuus", "detail": "Arceuus statue, north-west of the square"},
               {"name": "Piscarilius", "detail": "Piscarilius statue, north-east"},
               {"name": "Hosidius", "detail": "Hosidius statue, south-east"},
               {"name": "Lovakengj", "detail": "Lovakengj statue, on the west side"},
               {"name": "Shayzien", "detail": "Shayzien statue, south-west"},
           ],
           "action": "Inspect the {detail} and choose \"Press it in\"."})

solver("dt2_memory", "desert_treasure_ii", "Growth memory sequence", "sequence",
       intro="Tap the growths in the order you saw them light up, so you don't have to remember it.",
       config={
           "count": 4,
           "items": [
               {"name": "South-west", "detail": "south-west growth"},
               {"name": "North-west", "detail": "north-west growth"},
               {"name": "North-east", "detail": "north-east growth"},
               {"name": "South-east", "detail": "south-east growth"},
           ],
           "action": "Touch the {detail}.",
           "repeats": True})

solver("sote_baxtorian", "song_of_the_elves", "Baxtorian pillars", "baxtorian",
       intro="Inspect each marked pillar. For every hint, pick how it starts and which number it ends with. "
             "After five hints the app fills in the sixth and tells you what goes on each pillar.")

solver("eyes_discs", "the_eyes_of_glouphrie", "Disc machine", "discs",
       match=[r"machine"],
       intro="The machine asks for discs that add up to a target. Every disc is worth "
             "colour × shape. Enter the target and how many discs the slot takes.",
       notes=["Colours: red 1, orange 2, yellow 3, green 4, blue 5, indigo 6, violet 7.",
              "Shapes: circle ×1, triangle ×3, square ×4, pentagon ×5.",
              "Swap discs you don't need at the exchange machine next to it, or drop them and talk to "
              "Brimstail for more."],
       config={"mode": "eyes"})

solver("pog_discs", "the_path_of_glouphrie", "Yewnock's machine", "discs",
       match=[r"disc puzzle"],
       intro="Puzzle 1 shows two discs: insert ONE disc worth their total. Puzzle 2 shows one disc: "
             "insert TWO discs that add up to it.",
       notes=["Every disc is worth colour × shape. Colours: red 1, orange 2, yellow 3, green 4, blue 5, "
              "indigo 6, violet 7. Shapes: circle ×1, triangle ×3, square ×4, pentagon ×5.",
              "Missing a disc? Put one you don't need in Yewnock's exchanger and click exchange until "
              "one of the outputs is the disc you want, then confirm."],
       config={"mode": "yewnock"})

solver("ilh_tiles", "icthlarins_little_helper", "Door tile puzzle", "rowtoggle",
       match=[r"western door", r"door puzzle"],
       intro="A 4×5 grid of tiles. Clicking a tile flips it and its left and right neighbours. "
             "Work row by row: tap the tiles in one row that are NOT yet the goal look, and the app "
             "shows which ones to click.",
       notes=["You do this puzzle twice in the quest.",
              "The goal look is the one every tile shares once the door is solved. If a row has no "
              "answer, you've picked the wrong look as the goal: invert your taps."])

# ---------------------------------------------------------------------------------- fixed guides

guide("bmr_clocks", "the_blood_moon_rises", "Castle Drakan clocks",
      match=[r"clock"],
      intro="Put the hands on both grandfather clocks in the dining room, then open each and set the time.",
      steps=["Western clock: turn the big hand to 11, then the small hand to 9 (about 9:55).",
             "Eastern clock: turn the big hand to 12, then the small hand to 4 (4:00).",
             "Do the big hand first each time. Turn whichever way is fewer clicks.",
             "Close each clock, then search the fireplace between them for the emblem."],
      notes=["Assumes the dial reads like a normal clock (12 at the top)."])

guide("bmr_chest", "the_blood_moon_rises", "Altar house arrow chest",
      match=[r"chest puzzle"],
      steps=["Enter the arrows: Right, Down, Right, Left, Left.", "Press Confirm."],
      notes=["If you mis-tap, press Reset and start again."])

guide("bmr_forest", "the_blood_moon_rises", "Darkwood forest trees",
      match=[r"tangle of trees", r"darkwood trees"],
      intro="Each tree can be chopped once from each side. \"From the south\" means stand on the south side.",
      steps=["Take the axe from the stump.",
             "Tree 1: chop from the south, chop from the east, climb over from the south.",
             "Tree 2: chop from the south, chop the next part from the east, climb over from the east.",
             "Tree 3: chop from the south, chop from the east, climb over from the south.",
             "Tree 4: chop from the west, chop from the south, climb over from the west.",
             "Tree 5: chop from the south, chop from the west, climb over from the west.",
             "Tree 6: chop from the south, chop from the west, climb over from the west.",
             "Climb over tree 6 from the west.",
             "Cut tree 7 from the south.",
             "Climb back over tree 6 from the east.",
             "Climb over tree 6 from the south.",
             "Cut tree 7 from the west.",
             "Climb over tree 7 from the west.",
             "Cut tree 8 from the south.",
             "Climb back over tree 7 from the east.",
             "Climb back over tree 6 from the north.",
             "Climb over tree 6 from the west.",
             "Climb over tree 7 from the south.",
             "Cut tree 8 from the west.",
             "Climb over tree 8 from the west.",
             "Turn run off and enter the darkwood doorway to the north-east."])

guide("bmr_books", "the_blood_moon_rises", "Gilded bookcase",
      match=[r"bookcase", r"gilded"],
      steps=["Put the 8 books in their correct order by swapping pairs.",
             "Work left to right: find the book that belongs in the first wrong slot and swap it in.",
             "You never need more than 7 swaps."],
      notes=["The app can't tell which book is which from here, so this is the method rather than the answer."])

guide("ft_jugs", "the_forsaken_tower", "Furnace jugs",
      match=[r"jug", r"furnace"],
      intro="Measure exactly 4 gallons of coolant with a 5-gallon and an 8-gallon jug. "
            "Numbers show (5-gallon, 8-gallon).",
      steps=["Take a tinderbox from the cupboard on the north wall, and both jugs from the cupboard in the "
             "south-west corner of the north room (\"Take both\").",
             "Fill the 5-gallon jug at the coolant dispenser → (5, 0)",
             "Use the 5-gallon on the 8-gallon → (0, 5)",
             "Fill the 5-gallon → (5, 5)",
             "Use the 5-gallon on the 8-gallon → (2, 8)",
             "Empty the 8-gallon jug → (2, 0)",
             "Use the 5-gallon on the 8-gallon → (0, 2)",
             "Fill the 5-gallon → (5, 2)",
             "Use the 5-gallon on the 8-gallon → (0, 7)",
             "Fill the 5-gallon → (5, 7)",
             "Use the 5-gallon on the 8-gallon → (4, 8)",
             "Use the 5-gallon jug (4 gallons) on the furnace coolant.",
             "Light the furnace with the tinderbox."],
      notes=["Lost track? Empty both jugs and start again."])

guide("ft_altar", "the_forsaken_tower", "Altar pylons",
      match=[r"pylon", r"altar"],
      intro="Move all four energy discs from the west pylon to the middle pylon. A move is "
            "\"Rebalance\" on the pylon you take from, then on the pylon you put it on. "
            "Disc 1 is the smallest.",
      steps=["West → East (disc 1)", "West → Middle (disc 2)", "East → Middle (disc 1)",
             "West → East (disc 3)", "Middle → West (disc 1)", "Middle → East (disc 2)",
             "West → East (disc 1)", "West → Middle (disc 4)", "East → Middle (disc 1)",
             "East → West (disc 2)", "Middle → West (disc 1)", "East → Middle (disc 3)",
             "West → East (disc 1)", "West → Middle (disc 2)", "East → Middle (disc 1)"],
      notes=["Off track? Restart the puzzle and follow the list from the top."])

guide("ft_power", "the_forsaken_tower", "Power grid",
      match=[r"power puzzle", r"power grid"],
      steps=["Click tiles to rotate them until the power line runs unbroken through the grid.",
             "Straight pieces have two correct positions (either way round)."],
      notes=["The finished layout is a picture rather than something the app can describe in words. "
             "Use the Wiki button for a solved grid."])

guide("ew2_pipes", "elemental_workshop_ii", "Junction box pipes",
      match=[r"junction box", r"sort the pipes"],
      steps=["Connect inlet B to inlet C.", "Connect inlet A to inlet 3.", "Connect inlet 1 to inlet 2."])

guide("ds2_golem", "desert_treasure_ii", "Golem charges",
      match=[r"inspect the golem again"],
      intro="Drag the eight charges onto the grid, one per row and one per column, then press the power button.",
      steps=["Row 1: column 6", "Row 2: column 3", "Row 3: column 1", "Row 4: column 4",
             "Row 5: column 2", "Row 6: column 8", "Row 7: column 5", "Row 8: column 7",
             "Press the power-on button."],
      grid=[".....X..", "..X.....", "X.......", "...X....", ".X......", ".......X", "....X...", "......X."],
      notes=["Rows count from the top, columns from the left."])

guide("dt2_chest", "desert_treasure_ii", "Sucellus arrow chest",
      match=[r"sucellus"],
      steps=["Enter the arrows: Up, Right, Left, Down, Right, Up.", "Press Confirm."])

guide("dt2_growth", "desert_treasure_ii", "Rune rifts order",
      match=[r"rift"],
      steps=["Activate the six runes in the hidden order by trial and error.",
             "When a rune is wrong the sequence resets: remember the ones that worked and try a different "
             "rune for the next spot. At most 15 wrong tries in total."],
      notes=["The order is different for every player, so there's nothing to look up."])

guide("ffg_chest", "fallen_from_grace", "North-west chest",
      match=[r"unlock the chest"],
      steps=["Enter the arrows: Right, Left, Down, Down.", "Press Confirm."])

guide("sotn_codes", "secrets_of_the_north", "Gates and chests",
      match=[r"\bgate\b"],
      steps=["North gate arrows: Left, Up, Left, Down.",
             "Centre gate word: BLOOD.",
             "North chest code: 7402."])

guide("rd_spishyus", "recruitment_drive", "Sir Spishyus: fox, chicken and grain",
      match=[r"spishyus"],
      intro="All three start on the east side. You can carry one at a time.",
      steps=["Take the chicken across to the west and drop it.",
             "Go back east. Take the fox west and drop it.",
             "Take the chicken back east and drop it.",
             "Take the grain west and drop it.",
             "Go back east. Take the chicken west and drop it.",
             "Leave through the portal."])

guide("rd_tinley", "recruitment_drive", "Sir Tinley",
      match=[r"sir tinley"],
      steps=["Talk to Sir Tinley and press Continue.",
             "Do nothing at all (no clicking, no moving) until he talks to you again."])

guide("rd_kuam", "recruitment_drive", "Sir Kuam Ferentse",
      match=[r"kuam", r"sir leye"],
      steps=["Talk to Sir Kuam to spawn Sir Leye.",
             "Defeat Sir Leye. The final hit must be with a steel warhammer or your bare hands."])

guide("rd_hynn", "recruitment_drive", "Ms Hynn Terprett's riddle",
      match=[r"hynn"],
      steps=["She asks one of five riddles. The answers are:",
             "\"The wolves.\"", "\"Bucket A (32 degrees)\"",
             "\"The number of false statements here is three.\"", "\"Zero.\"", "10"],
      notes=["Pick the one that answers the riddle you're given."])

guide("rd_lady_table", "recruitment_drive", "Lady Table's statues",
      match=[r"lady table"],
      steps=["Remember the statues before one disappears.",
             "They stand in a 4×3 grid: from west to east 2-handed sword, halberd, axe, mace; "
             "from south to north bronze, silver, gold.",
             "When the missing one comes back, click it."])

guide("rd_cheevers", "recruitment_drive", "Miss Cheevers' lab",
      match=[r"cheevers"],
      steps=["Magnet from the old bookshelf.",
             "From the shelves: \"Take both vials\".",
             "From the shelves (\"Yes\" each time): Cupric Sulfate, Gypsum, Sodium Chloride.",
             "Bronze wire from the small crates; tin from the large crate; shears from the chest; "
             "chisel from the large crates.",
             "From the shelves (\"Yes\"): Nitrous Oxide, Tin Ore Powder, Cupric Ore Powder.",
             "From the shelves: \"Take all three vials\".",
             "Knife from the other old bookshelf; metal spade from the table.",
             "Use the spade on the bunsen burner (spade head + ashes).",
             "Use the spade head on the stone door, then Cupric Sulfate, then a vial of liquid. Open the door.",
             "Use a vial of liquid on the tin, then Gypsum on the tin.",
             "Use the gypsum tin on the chained key on the ground.",
             "Use cupric ore powder, then tin ore powder, on the tin.",
             "Use the tin on the bunsen burner.",
             "Use the chisel, knife or bronze wire on the tin to get the bronze key.",
             "Leave through the second door."])

guide("lunar_numbers", "lunar_diplomacy", "Number challenge",
      match=[r"numbers? challenge"],
      steps=["Work out the pattern in the numbers shown and press the next two digits.",
             "You need 6 correct answers."],
      notes=["The sequences aren't stored in the app yet; use the Wiki button for the full list."])

guide("lunar_mimic", "lunar_diplomacy", "Mimic challenge",
      match=[r"mimic"],
      steps=["Copy each emote the Ethereal Mimic performs (Cry, Bow, Dance, Wave or Think).",
             "Do it 5 times."])

guide("lunar_memory", "lunar_diplomacy", "Memory challenge (cloud floor)",
      match=[r"memory challenge"],
      steps=["Cross the cloud tiles from north to south. Only some are safe.",
             "It's trial and error: if you fall, remember which tiles were safe and try another next time.",
             "You only ever need to move south, east or west, never back north."],
      notes=["The safe tiles are hidden, so the app can't work them out."])

guide("kr_lockpick", "kings_ransom", "Door lockpick",
      match=[r"door's lock", r"\blockpick"],
      steps=["Select each tumbler in turn and move it up or down until it sets, then press Try lock."],
      notes=["The right positions are hidden and different for every player, so there's no answer to look up."])

guide("slug_fragments", "the_slug_menace", "Torn page fragments",
      match=[r"combine the fragments", r"fragment"],
      steps=["Select only one fragment at a time.",
             "Flip it to the correct side and rotate it upright.",
             "Move fragments 2 and 3 on top of fragment 1 (fragment 1 stays where it is)."])

guide("bar_schematic", "between_a_rock", "Schematic",
      match=[r"assemble the schematics", r"schematic"],
      steps=["Select only one piece.",
             "Rotate it until it matches the base schematic, then move it into place.",
             "Repeat for the other two pieces."])

guide("scrambled_egg", "scrambled", "Egg jigsaw",
      match=[r"broken-egg"],
      steps=["Click each shell piece until it's upright, then drag it into place.",
             "Start from the outer edge pieces and work inwards."],
      notes=["The app can't tell the 26 pieces apart, so there's no step-by-step answer."])

guide("ds2_map", "dragon_slayer_ii", "Fossil Island map",
      match=[r"incomplete map"],
      steps=["Swap tiles until every piece is in its place, then click tiles to rotate them upright.",
             "Start with the coast and corner pieces."],
      notes=["The pieces are scrambled differently for everyone. Use the Wiki button for a picture of the finished map."])

guide("arrav_tiles", "the_curse_of_arrav", "Floor tile puzzle",
      steps=["Cross the 12×12 floor from the east edge to the west edge.",
             "Green and blue tiles go together; red and yellow tiles go together.",
             "Pick your first tile, then only ever step onto tiles of the same pair of colours "
             "(north, south, east or west, never diagonally).",
             "Once across, pull the lever."])

guide("wgs_statues", "while_guthix_sleeps", "Herblore statues",
      match=[r"statue"],
      intro="Each of the 8 statues stands for a potion. Use that potion's ingredients on it. Get "
            "ingredients by using your druid pouch on the druid spirits.",
      steps=["Agility: Toadflax + Toad's legs", "Energy: Harralander + Chocolate dust",
             "Restoration: Harralander + Red spider's eggs", "Attack: Guam leaf + Eye of newt",
             "Strength: Tarromin + Limpwurt root", "Defence: Ranarr weed + White berries",
             "Combat: Harralander + Goat horn dust", "Ranged: Dwarf weed + Wine of Zamorak",
             "Prayer: Ranarr weed + Snape grass", "Hunter: Avantoe + Kebbit teeth dust",
             "Fishing: Avantoe + Snape grass", "Magic: Lantadyme + Potato cactus",
             "Balance: Harralander + Red spider's eggs + Garlic + Silver dust",
             "When all 8 are done, use all the dolmens on the stone table."],
      notes=["Which potion each statue wants is different per player: check each statue first."])

guide("pog_monolith", "the_path_of_glouphrie", "Storeroom monoliths",
      match=[r"storeroom", r"monolith"],
      steps=["Push the southern monolith north once (only if it blocks you).",
             "Open the chest for 3 shapes.",
             "Push the south-west monolith north once.",
             "Push the north-west monolith east once.",
             "Open the next chest for 3 more shapes (6 total).",
             "Picklock the chest for a key.",
             "Push the small monolith south once.",
             "Push the north-west monolith west once.",
             "Search the golden chest for the strongroom key and a crystal chime seed.",
             "Open the last chest for 3 more shapes (9 total).",
             "Inspect the singing bowl, then use it again to make the crystal chime.",
             "Push the south-east monolith west once.",
             "Unlock the gate to Yewnock's machine room."],
      notes=["If a chest gives no shapes: drop the shapes you have, click the chest, then pick them all up."])

# Song of the Elves light puzzles. Pillars are on a grid: columns A-I west to east, rows 0-8 south to north.
SOTE_RESET = "If the last puzzle's light is still on, pull the lever in the dispenser in the central room first."
SOTE_COLLECT = "Collect everything from the dispenser in the central room (\"Take everything\")."


def sote(pid, seal, steps, extra=None):
    guide(pid, "song_of_the_elves", f"Light puzzle: {seal}", match=[seal.lower() + " seal"],
          intro="Add mirrors and crystals to the pillars in this order, rotating each mirror as shown. "
                "Pillar names: columns A–I run west to east, rows 0–8 south to north.",
          steps=steps + [f"Touch the Seal of {seal}."],
          notes=extra or [])


sote("sote_cadarn", "Cadarn", [
    "Collect 7 mirrors and a red crystal from the dispenser in the central room.",
    "Middle floor D5 (north): mirror, point the light east.",
    "Middle floor E5 (east): mirror, point the light down.",
    "Go downstairs.",
    "Ground floor E5 (by the stairs): mirror, point south.",
    "Ground floor E3 (south): mirror, point west.",
    "Ground floor D3 (west): mirror, point south.",
    "Ground floor D2 (south): red crystal.",
    "Ground floor D1 (south): mirror, point east.",
    "Ground floor E1 (east): mirror, point south at the Seal of Cadarn."])

sote("sote_crwys", "Crwys", [
    SOTE_RESET, SOTE_COLLECT,
    "Middle floor D4 (north): mirror, point west.",
    "Middle floor C4 (west): mirror, point north.",
    "Middle floor C5 (north): mirror, point west.",
    "Middle floor B5 (west): fractured crystal.",
    "Middle floor A5 (west): mirror, point north.",
    "Middle floor A6 (north): green crystal.",
    "Middle floor A8 (run around to the north): mirror, point down.",
    "Go down the north-west stairs to the ground floor.",
    "Ground floor A8 (north-west): mirror, point east.",
    "Ground floor B8 (east): mirror, point up.",
    "Go up to the middle floor. B8 (next to the stairs): cyan crystal.",
    "Go up to the top floor (north-west stairs).",
    "Top floor B8 (next to the stairs): mirror, point south.",
    "Top floor B7 (south): mirror, point down.",
    "Go down to the middle floor. B7 (south): mirror, point south."])

sote("sote_amlodd", "Amlodd", [
    SOTE_RESET, SOTE_COLLECT,
    "Middle floor D4 (north): fractured crystal.",
    "Middle floor C4 (west): mirror, point north.",
    "Middle floor C5 (north): red crystal.",
    "Middle floor C6 (north): mirror, point east.",
    "Middle floor D6 (east): mirror, point north.",
    "Middle floor D7 (north): mirror, point west.",
    "Middle floor C7 (west): mirror, point down.",
    "Go down the north-west stairs.",
    "Ground floor C7 (the pillar with light coming down into it): mirror, point south.",
    "Go back up to the middle floor.",
    "Middle floor D5 (north of the fractured crystal): mirror, point east.",
    "Middle floor E5 (east): mirror, point down.",
    "Go down the middle stairs.",
    "Ground floor E5 (by the stairs): mirror, point south.",
    "Ground floor E3: mirror, point west.",
    "Ground floor D3: mirror, point south.",
    "Ground floor D2 (south): mirror, point west.",
    "Ground floor C2 (west): mirror, point north.",
    "Ground floor C3 (north): green crystal.",
    "Ground floor C4 (north): yellow crystal.",
    "Ground floor C5 (north): mirror, point east.",
    "Ground floor C6 (north): mirror, point east.",
    "Ground floor D6 (east): mirror, point south."])

sote("sote_meilyr", "Meilyr", [
    SOTE_RESET, SOTE_COLLECT,
    "Middle floor D5: mirror, point east.",
    "Middle floor E5: mirror, point down.",
    "Go down the middle stairs.",
    "Ground floor E5: mirror, point south.",
    "Ground floor E3: mirror, point west.",
    "Ground floor D3: mirror, point south.",
    "Ground floor D2: mirror, point west.",
    "Ground floor C2: mirror, point north.",
    "Ground floor C3 (north): mirror, point west.",
    "Go back up to the middle floor, then down the south-west stairs.",
    "Ground floor B3 (the marked pillar): mirror, point north.",
    "Ground floor B4 (north): mirror, point up.",
    "Go up to the middle floor (south-west stairs). B4 (north): red crystal.",
    "Go up to the top floor (south-west stairs). B4 (north): mirror, point north.",
    "Go down to the middle floor. F4 (east): mirror, point up.",
    "Go up to the top floor by the east stairs.",
    "Top floor F4 (west): mirror, point north.",
    "Top floor F5 (north): mirror, point west.",
    "Go back to the middle floor, then up to the top floor by the north-west stairs.",
    "Climb across the floating books to the south-east.",
    "Top floor D5 (the marked pillar, across the books): mirror, point north.",
    "Top floor D6 (north): mirror, point west.",
    "Top floor C6 (west): mirror, point south.",
    "Top floor C5 (south): mirror, point west."])

sote("sote_hefin", "Hefin", [
    SOTE_RESET, SOTE_COLLECT,
    "Middle floor D5: mirror, point east.",
    "Middle floor E5: mirror, point down.",
    "Go down the middle stairs.",
    "Ground floor E5: mirror, point south.",
    "Ground floor E3: mirror, point west.",
    "Ground floor D3: mirror, point south.",
    "Ground floor D2: mirror, point west.",
    "Ground floor C2: mirror, point north.",
    "Ground floor C3: mirror, point west.",
    "Go up to the middle floor.",
    "Middle floor C1 (south-west): mirror, point south.",
    "Middle floor C0 (south): mirror, point west.",
    "Middle floor A0 (west): mirror, point north.",
    "Middle floor A2 (run around to the north pillar): mirror, point down.",
    "Go down the south-west stairs to the ground floor.",
    "Ground floor A2 (south-west): mirror, point east.",
    "Ground floor B2 (east): mirror, point up.",
    "Ground floor B3 (the marked pillar): mirror, point north.",
    "Ground floor B4 (north): mirror, point west.",
    "Ground floor A4 (west): mirror, point up.",
    "Go up to the top floor by the south-west stairs.",
    "Top floor B2 (south): mirror, point north.",
    "Top floor B3 (north): mirror, point west.",
    "Top floor A4 (north-west): mirror, point south."])

sote("sote_trahaearn", "Trahaearn", [
    SOTE_RESET, SOTE_COLLECT,
    "Middle floor C1 (south-west): mirror, point south.",
    "Middle floor C0: mirror, point west.",
    "Middle floor A0: mirror, point north.",
    "Middle floor A2 (run around to the north pillar): mirror, point down.",
    "Go down the south-west stairs.",
    "Ground floor A2: mirror, point east.",
    "Ground floor B2: mirror, point up.",
    "Go up to the top floor by the south-west stairs.",
    "Top floor B2: mirror, point north.",
    "Top floor B3 (north): mirror, point east.",
    "Go back to the middle floor, then up to the top floor by the south-east stairs.",
    "Climb across the books to the west.",
    "Top floor D3 (north-west): mirror, point south.",
    "Top floor D1 (south): mirror, point east.",
    "Top floor F1 (east): blue crystal.",
    "Top floor G1 (east): mirror, point south.",
    "Top floor G0 (south): mirror, point east.",
    "Top floor H0 (east): mirror, point north.",
    "Top floor H2 (north): mirror, point west.",
    "Top floor G2 (west): mirror, point north.",
    "Go down to the middle floor.",
    "Middle floor G3 (the marked pillar): mirror, point north.",
    "Middle floor G4 (north): mirror, point east.",
    "Middle floor H4 (east): mirror, point up.",
    "Go up to the top floor by the east stairs.",
    "Top floor H4 (west): mirror, point east.",
    "Top floor I4 (east): mirror, point north.",
    "Top floor I5 (north): mirror, point west.",
    "Top floor G5 (west): mirror, point south."], extra=["Needs 14 mirrors."])

sote("sote_iorwerth", "Iorwerth", [
    SOTE_RESET, SOTE_COLLECT,
    "Middle floor F4 (north-east): cyan crystal.",
    "Middle floor F5 (north): blue crystal.",
    "Middle floor F6 (north-east room): mirror, point west.",
    "Middle floor E6 (west): mirror, point down.",
    "Go down the north-east stairs to the ground floor.",
    "Ground floor E6 (west): mirror, point north.",
    "Ground floor E7 (north): fractured crystal.",
    "Ground floor E8 (north): mirror, point east.",
    "Ground floor F8 (east): mirror, point up.",
    "Ground floor F7 (south): mirror, point south.",
    "Ground floor F6 (south): mirror, point east.",
    "Ground floor G6 (east): yellow crystal.",
    "Ground floor H6 (east): mirror, point north.",
    "Ground floor H7 (north): mirror, point west.",
    "Ground floor G7 (west): mirror, point north.",
    "Ground floor G8 (north): mirror, point up.",
    "Go up to the middle floor.",
    "Middle floor G8 (north-east room): mirror, point south.",
    "Middle floor F8 (north-east room): magenta crystal.",
    "Middle floor G3 (east room): mirror, point the yellow light north.",
    "Middle floor G4 (north): mirror, point east.",
    "Middle floor H4 (east): red crystal.",
    "Middle floor I4 (east): mirror, point north.",
    "Middle floor I5 (north): mirror, point west.",
    "Middle floor H5 (west): mirror, point north.",
    "Middle floor H6 (north): mirror, point east.",
    "Middle floor I6 (east): mirror, point north.",
    "Middle floor I7 (north): mirror, point up.",
    "Go up to the top floor by the north-east stairs.",
    "Top floor I7 (north): mirror, point north.",
    "Top floor I8 (north): mirror, point west.",
    "Top floor H8 (west): mirror, point south.",
    "Top floor H7 (south): mirror, point down.",
    "Top floor F8 (west): mirror, point south.",
    "Top floor F7 (south): mirror, point down."], extra=["Needs 14 mirrors."])


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/puzzles.json"
    ids = [p["id"] for p in P]
    assert len(ids) == len(set(ids)), "duplicate puzzle id"
    with open(out, "w", encoding="utf-8") as fh:
        json.dump({"version": 1, "puzzles": P}, fh, ensure_ascii=False, indent=1)
    print(f"{len(P)} puzzles written to {out}")


if __name__ == "__main__":
    main()
