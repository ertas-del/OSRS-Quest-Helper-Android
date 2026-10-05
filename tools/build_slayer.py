#!/usr/bin/env python3
"""Builds app/src/main/assets/slayer.json from tools/slayer_parts/*.json (monster cards researched
from the OSRS Wiki) plus the Konar and Mortimer task tables below (also from the wiki).
Run:  python3 tools/build_slayer.py
"""
import glob, json, os

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "app", "src", "main", "assets", "slayer.json")

# Values the wiki pages disagreed on or didn't give: better blank than wrong.
NULL_MAXHIT = {"banshees", "waterfiends"}

# (id, name, slayer, combat, kills_lo, kills_hi, weight, needs, locations "|"-separated)
# needs: quests / "Ability: x" = a Slayer reward you buy with points.
KONAR = [
 ("aberrant_spectres","Aberrant spectres",60,65,120,170,6,"","Catacombs of Kourend|Slayer Tower|Stronghold Slayer Cave"),
 ("abyssal_demons","Abyssal demons",85,85,120,170,9,"Priest in Peril or Fairytale II","Catacombs of Kourend|Abyss|Slayer Tower"),
 ("ankou","Ankou",0,40,50,50,5,"","Stronghold of Security|Stronghold Slayer Dungeon|Catacombs of Kourend"),
 ("aviansie","Aviansie",0,0,120,170,6,"Ability: Watch the birdie","God Wars Dungeon"),
 ("basilisks","Basilisks",40,0,110,170,5,"Ability: Basilocked","Fremennik Slayer Dungeon|Jormungand's Prison"),
 ("black_demons","Black demons",0,80,120,170,9,"","Catacombs of Kourend|Chasm of Fire|Taverley Dungeon|Brimhaven Dungeon"),
 ("black_dragons","Black dragons",0,80,10,15,6,"Dragon Slayer I (partial)","Catacombs of Kourend|Myths' Guild Dungeon|Evil Chicken's Lair|Taverley Dungeon"),
 ("bloodveld","Bloodveld",50,50,120,170,9,"","Catacombs of Kourend|God Wars Dungeon|Iorwerth Dungeon|Meiyerditch Laboratories|Slayer Tower|Stronghold Slayer Dungeon"),
 ("blue_dragons","Blue dragons",0,65,120,170,4,"Dragon Slayer I (partial)","Catacombs of Kourend|Isle of Souls Dungeon|Myths' Guild Dungeon|Ogre Enclave|Ruins of Tapoyauik|Taverley Dungeon"),
 ("bosses","Bosses",0,0,3,35,8,"Ability: Like a boss","Varies with the boss"),
 ("brine_rats","Brine rats",47,45,120,170,2,"Olaf's Quest","Brine Rat Cavern"),
 ("cave_kraken","Cave kraken",87,80,80,100,9,"","Kraken Cove"),
 ("dagannoth","Dagannoth",0,75,120,170,8,"Horror from the Deep","Catacombs of Kourend|Lighthouse|Waterbirth Island|Jormungand's Prison"),
 ("dark_beasts","Dark beasts",90,90,10,15,5,"Mourning's End Part II (partial)","Mourner Tunnels|Iorwerth Dungeon"),
 ("drakes","Drakes",84,0,75,140,10,"","Karuulm Slayer Dungeon"),
 ("dust_devils","Dust devils",65,70,120,170,6,"Desert Treasure I (partial)","Catacombs of Kourend|Smoke Dungeon"),
 ("fire_giants","Fire giants",0,65,120,175,9,"","Brimhaven Dungeon|Catacombs of Kourend|Isle of Souls Dungeon|Giants' Den|Karuulm Slayer Dungeon|Stronghold Slayer Dungeon|Waterfall Dungeon"),
 ("fossil_island_wyverns","Fossil Island wyverns",66,60,15,30,5,"Bone Voyage, Elemental Workshop I","Wyvern Cave"),
 ("gargoyles","Gargoyles",75,80,120,170,6,"Priest in Peril","Slayer Tower"),
 ("greater_demons","Greater demons",0,75,120,170,7,"","Catacombs of Kourend|Chasm of Fire|Isle of Souls Dungeon|Karuulm Slayer Dungeon|Brimhaven Dungeon"),
 ("hellhounds","Hellhounds",0,75,120,170,8,"","Karuulm Slayer Dungeon|Catacombs of Kourend|Stronghold Slayer Dungeon|Taverley Dungeon|Witchaven Dungeon"),
 ("hydras","Hydras",95,0,125,190,10,"","Karuulm Slayer Dungeon"),
 ("jellies","Jellies",52,57,120,170,6,"","Fremennik Slayer Dungeon|Catacombs of Kourend|Ruins of Tapoyauik"),
 ("kalphite","Kalphite",0,15,120,170,9,"","Kalphite Lair|Kalphite Cave"),
 ("kurask","Kurask",70,65,120,170,3,"Song of the Elves (for Iorwerth Dungeon)","Fremennik Slayer Dungeon|Iorwerth Dungeon"),
 ("lesser_nagua","Lesser nagua",48,0,55,120,2,"Perilous Moons","Neypotzli|Ruins of Tapoyauik|Crypt of Tonali"),
 ("lizardmen","Lizardmen",0,0,90,110,8,"Ability: Reptile got ripped","Battlefront|Lizardman Canyon|Lizardman Settlement|Kebos Swamp|Molch"),
 ("metal_dragons","Metal dragons",0,85,30,40,15,"Dragon Slayer I (partial)","Brimhaven Dungeon|Catacombs of Kourend|Isle of Souls Dungeon|Lithkren Vault"),
 ("mutated_zygomites","Mutated zygomites",57,60,10,25,2,"Lost City","Fossil Island|Zanaris"),
 ("nechryael","Nechryael",80,85,110,110,7,"","Catacombs of Kourend|Iorwerth Dungeon|Slayer Tower"),
 ("red_dragons","Red dragons",0,68,30,50,5,"Dragon Slayer I (partial); Ability: Seeing red","Brimhaven Dungeon|Catacombs of Kourend|Forthos Dungeon|Myths' Guild Dungeon"),
 ("skeletal_wyverns","Skeletal wyverns",72,70,5,12,5,"Elemental Workshop I","Asgarnian Ice Dungeon"),
 ("smoke_devils","Smoke devils",93,85,120,170,7,"","Smoke Devil Dungeon"),
 ("trolls","Trolls",0,60,120,170,6,"","Troll Stronghold|Keldagrim|Death Plateau|South of Mount Quidamortem|Fremennik Isles|Wyrmscraig Cavern"),
 ("turoth","Turoth",55,60,120,170,3,"","Fremennik Slayer Dungeon"),
 ("vampyres","Vampyres",0,0,100,160,4,"Ability: Actual Vampyre Slayer","Darkmeyer|Meiyerditch|Slepe|Vampyrium"),
 ("warped_creatures","Warped creatures",0,0,110,170,4,"Ability: Warped Reality","Poison Waste Dungeon"),
 ("waterfiends","Waterfiends",0,75,120,170,2,"Barbarian Training (Pyre ships)","Ancient Cavern|Iorwerth Dungeon|Kraken Cove"),
 ("wyrms","Wyrms",62,0,125,190,10,"","Karuulm Slayer Dungeon|Neypotzli|Charred Dungeon|Wyrmscraig"),
]

# (id, name, slayer, combat, lo, hi, weight, needs, points modifier, xp bonus %, quantity modifier)
MORTIMER = [
 ("crawling_hands","Crawling hands",5,0,35,50,10,"Priest in Peril","5-15","25-100","-15..-30"),
 ("cave_crawlers","Cave crawlers",10,10,35,50,10,"","5-15","25-100","-15..-30"),
 ("banshees","Banshees",15,20,35,50,10,"Priest in Peril","5-15","25-100","-15..-30"),
 ("rockslugs","Rockslugs",20,20,35,50,10,"","5-15","25-100","-15..-30"),
 ("cockatrice","Cockatrice",25,25,35,50,10,"Defence 20","5-15","25-100","-15..-30"),
 ("pyrefiends","Pyrefiends",30,25,35,50,10,"","5-15","25-100","-15..-30"),
 ("basilisks","Basilisks",40,40,40,60,10,"Defence 20","25-40","35-75","50-100"),
 ("infernal_mages","Infernal mages",45,40,35,50,10,"Priest in Peril","5-15","25-100","-15..-30"),
 ("bloodveld","Bloodveld",50,50,120,180,8,"Priest in Peril","10-15","5-15","50-100"),
 ("gryphons","Gryphons",51,0,80,120,10,"Sailing 45, Troubled Tortugans","10-25","20-50","50-100"),
 ("jellies","Jellies",52,57,80,120,10,"","10-15","10-25","50-100"),
 ("custodian_stalkers","Custodian stalkers",54,0,80,120,8,"Shadows of Custodia","10-15","10-25","50-100"),
 ("turoth","Turoth",55,60,80,120,10,"","15-30","35-75","30-80"),
 ("warped_creatures","Warped creatures",56,0,80,120,10,"The Path of Glouphrie","15-30","35-75","50-100"),
 ("cave_horrors","Cave horrors",58,85,80,120,10,"Cabin Fever","15-30","35-75","30-80"),
 ("aberrant_spectres","Aberrant spectres",60,65,80,120,10,"Priest in Peril","15-30","35-75","30-80"),
 ("wyrms","Wyrms",62,0,80,120,10,"","25-40","35-75","50-100"),
 ("dust_devils","Dust devils",65,70,120,180,8,"Desert Treasure I (partial)","10-15","10-20","50-150"),
 ("kurask","Kurask",70,65,40,60,10,"","25-40","35-75","30-80"),
 ("venators","Venators",74,60,120,180,10,"The Blood Moon Rises","10-25","35-75","50-100"),
 ("gargoyles","Gargoyles",75,80,120,180,10,"Priest in Peril","15-30","35-75","50-100"),
 ("aquanites","Aquanites",78,0,40,60,10,"Sailing 73","25-40","35-75","50-100"),
 ("nechryael","Nechryael",80,85,150,200,8,"Priest in Peril","10-20","15-30","50-100"),
 ("drakes","Drakes",84,0,40,60,10,"","25-40","25-75","30-80"),
 ("abyssal_demons","Abyssal demons",85,85,120,180,8,"Priest in Peril or Fairytale II","10-25","10-25","50-100"),
 ("dark_beasts","Dark beasts",90,90,40,60,10,"Mourning's End Part II (started)","10-25","35-75","30-80"),
 ("araxytes","Araxytes",92,0,120,180,8,"Priest in Peril","10-20","5-15","50-100"),
 ("smoke_devils","Smoke devils",93,85,80,120,8,"","10-20","5-15","50-100"),
 ("hydras","Hydras",95,0,150,200,10,"","15-30","10-30","50-100"),
]

# Other masters, for the points maths. base = points per task; diary = with the relevant diary.
MASTERS = [
 {"id":"turael","name":"Turael","where":"Burthorpe","slayer":0,"combat":0,"points":0,"diary":0},
 {"id":"mazchna","name":"Mazchna","where":"Canifis","slayer":0,"combat":20,"points":6,"diary":6},
 {"id":"vannaka","name":"Vannaka","where":"Edgeville Dungeon","slayer":0,"combat":40,"points":8,"diary":8},
 {"id":"chaeldar","name":"Chaeldar","where":"Zanaris","slayer":0,"combat":70,"points":10,"diary":10},
 {"id":"konar","name":"Konar quo Maten","where":"Mount Karuulm","slayer":0,"combat":75,"points":18,"diary":20,
  "note":"Gives a place with every task, and you must kill there to earn brimstone keys. Needs Kourend elite diary for 20 points."},
 {"id":"nieve","name":"Nieve / Steve","where":"Tree Gnome Stronghold","slayer":0,"combat":85,"points":12,"diary":15},
 {"id":"duradel","name":"Duradel / Kuradal","where":"Shilo Village","slayer":50,"combat":100,"points":15,"diary":15},
 {"id":"krystilia","name":"Krystilia","where":"Edgeville","slayer":0,"combat":0,"points":25,"diary":25,"note":"Wilderness tasks only."},
 {"id":"mortimer","name":"Mortimer","where":"Wyrmscraig Cavern","slayer":70,"combat":100,"points":0,"diary":0,
  "note":"Pays by modifiers, not a flat rate; 2 tasks to choose from (3 after 50). Needs Fallen From Grace partly done. Slayer 99 skips the combat need."},
]

def main():
    monsters = []
    for f in sorted(glob.glob(os.path.join(HERE, "slayer_parts", "part_*.json"))):
        monsters += json.load(open(f))
    import re
    for m in monsters:
        if m["id"] in NULL_MAXHIT:
            m["maxHit"] = None
        # The helpers tagged guesses as "judgement"; the card already says food and gear are suggestions.
        f = m.get("food")
        if f and f.get("note"):
            note = re.sub(r"[;,.]?\s*(?:and )?judgement(?: on food| call)?\.?", "", f["note"], flags=re.I).strip(" ;,.")
            f["note"] = (note + ".") if note else None
        g = m.get("gear")
        if g and g.get("style"):
            g["style"] = re.sub(r"\s*\(judgement\)", "", g["style"], flags=re.I).strip()
    ids = {m["id"] for m in monsters}

    def konar(r):
        return {"id": r[0], "name": r[1], "slayer": r[2], "combat": r[3], "lo": r[4], "hi": r[5], "weight": r[6],
                "needs": r[7], "places": r[8].split("|")}
    def mort(r):
        return {"id": r[0], "name": r[1], "slayer": r[2], "combat": r[3], "lo": r[4], "hi": r[5], "weight": r[6],
                "needs": r[7], "points": r[8], "xp": r[9], "qty": r[10]}
    tables = {"konar": [konar(r) for r in KONAR], "mortimer": [mort(r) for r in MORTIMER]}
    for t in tables.values():
        for r in t:
            r["card"] = r["id"] in ids
    out = {
        "bonusEvery": [[10, 5], [50, 15], [100, 25], [250, 35], [1000, 50]],  # every Nth task pays base x this
        "masters": MASTERS, "tables": tables, "monsters": monsters,
    }
    with open(OUT, "w") as fh:
        json.dump(out, fh, separators=(",", ":"), ensure_ascii=False)
    nocard = sorted({r["id"] for t in tables.values() for r in t if not r["card"]})
    print("monsters", len(monsters), "konar", len(KONAR), "mortimer", len(MORTIMER), "bytes", os.path.getsize(OUT))
    print("table rows without a card:", nocard)

main()
