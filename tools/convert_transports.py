#!/usr/bin/env python3
"""
Builds the travel-guide data from the Shortest Path RuneLite plugin
(https://github.com/Skretzo/shortest-path, BSD 2-Clause).

Usage:
    git clone --depth 1 https://github.com/Skretzo/shortest-path sp
    python3 tools/convert_transports.py sp app/src/main/assets

Writes:
    transports.tsv     one line per usable link (teleports, fairy rings, doors, ships...)
    collision-map.zip  the plugin's walkability map, copied as-is

transports.tsv columns (tab separated):
    type  origin  destination  ticks  skills  quests  items  name  maxWild  spellbook  unlock  poh
  origin       "x,y,z", or "*" for teleports you can use from anywhere
  skills       "Magic:25;Agility:8"
  quests       "The Grand Tree;Tree Gnome Village"
  items        human readable, e.g. "1 Law rune, 3 Air rune, 1 Fire rune"
  maxWild      highest Wilderness level it works from (-1 = no limit given)
  spellbook    0 standard, 1 ancient, 2 lunar, 3 arceuus, -1 not a spell
  unlock       1 when it also needs something we can't check (a diary, an unlock, item charges)
  poh          for links into, out of or inside your house: arrive, outside:<location>, home:<location>, portal:<name>,
               box:<basic|fancy|ornate>, mount:<glory|mythical|xeric|digsite>, fairy, spirit
"""
import math
import os
import re
import shutil
import sys

# file stem -> (type key, permutation radius threshold)
FILES = {
    "transports": ("TRANSPORT", 0),
    "agility_shortcuts": ("AGILITY_SHORTCUT", 0),
    "boats": ("BOAT", 0),
    "canoes": ("CANOE", 0),
    "charter_ships": ("CHARTER_SHIP", 0),
    "ships": ("SHIP", 0),
    "fairy_rings": ("FAIRY_RING", 6),
    "gnome_gliders": ("GNOME_GLIDER", 6),
    "hot_air_balloons": ("HOT_AIR_BALLOON", 7),
    "magic_carpets": ("MAGIC_CARPET", 0),
    "magic_mushtrees": ("MAGIC_MUSHTREE", 5),
    "minecarts": ("MINECART", 0),
    "quetzals": ("QUETZAL", 5),
    "quetzal_whistle": ("QUETZAL_WHISTLE", 0),
    "spirit_trees": ("SPIRIT_TREE", 5),
    "teleportation_boxes": ("TELEPORT_ITEM", 0),
    "teleportation_items": ("TELEPORT_ITEM", 0),
    "teleportation_levers": ("LEVER", 0),
    "teleportation_minigames": ("MINIGAME", 0),
    "teleportation_portals": ("PORTAL", 0),
    "teleportation_portals_poh": ("POH_PORTAL", 0),
    "teleportation_spells": ("SPELL", 0),
    "teleportation_spells_home": ("HOME_TELEPORT", 0),
    "wilderness_obelisks": ("OBELISK", 0),
}

PERM = "PERM"   # column present but blank: links to every other entry of the file
ANY = "*"       # column absent: usable from anywhere (teleports)


def parse_point(v):
    p = v.strip().split()
    if len(p) != 3:
        return None
    try:
        return (int(p[0]), int(p[1]), int(p[2]))
    except ValueError:
        return None


def pretty_item(token):
    token = token.strip()
    if not token:
        return None
    if "=" in token:
        name, qty = token.split("=", 1)
    else:
        name, qty = token, "1"
    name = name.strip()
    if name.isdigit():
        return None  # bare item ids: the transport's own name already says which item
    qty = re.sub(r"[^0-9]", "", qty) or "1"
    if name.upper() == "COINS":
        return f"{qty} coins"
    words = name.replace("_", " ").lower()
    words = words[0].upper() + words[1:]
    return f"{qty} {words}"


def pretty_items(v):
    if not v or not v.strip():
        return ""
    alternatives = []
    for alt in v.split("||"):
        parts = [pretty_item(t) for t in alt.split("&&")]
        parts = [p for p in parts if p]
        if parts:
            alternatives.append(", ".join(parts))
    # Keep it short: the first way of paying, plus a hint that others exist.
    if not alternatives:
        return ""
    out = alternatives[0]
    if len(alternatives) > 1:
        out += " (or equivalent)"
    return out


def parse_skills(v):
    out = []
    for part in (v or "").split(";"):
        m = re.match(r"\s*(\d+)\s+([A-Za-z]+)", part)
        if m:
            out.append(f"{m.group(2).capitalize()}:{m.group(1)}")
    return ";".join(out)


def parse_quests(v):
    return ";".join(q.strip() for q in (v or "").split(";") if q.strip())


def spellbook(varbits):
    m = re.search(r"\b4070=(\d)", varbits or "")
    return int(m.group(1)) if m else -1


def needs_unlock(varbits, varplayers):
    """True when the link depends on game state we can't see (diaries, unlocks, charges).
    The spellbook check (varbit 4070) is handled separately."""
    # 4070 = spellbook, 4744 = "teleport inside your house" setting, 2187 = house location:
    # all three are handled by the app's own settings.
    rest = [v for v in re.split(r"[;&|]+", varbits or "")
            if v.strip() and not v.strip().startswith(("4070", "4744", "2187"))]
    return 1 if rest or (varplayers or "").strip() else 0


def in_house(p):
    """Player-owned house instance: nothing there is reachable without POH portals."""
    return p not in (ANY, PERM) and p[0] // 64 == 29 and p[1] // 64 == 110


HOUSE_TILE = (1858, 7051, 0)


def poh_tag(r):
    """How a link involving the player-owned house is unlocked, decided by the app's house settings.
    Empty for links that don't touch the house."""
    o, d = r["origin"], r["dest"]
    t = r["type"]
    loc = re.search(r"\b2187=(\d+)", r.get("varbits", ""))
    if t in ("SPELL", "TELEPORT_ITEM") and loc and not in_house(d):
        # Teleport to House's "Outside" option lands at the house portal, which depends on where
        # the player's house is. Without this tag these looked usable by everyone, everywhere.
        return "outside:" + loc.group(1)
    if not (in_house(o) or in_house(d)):
        return ""
    obj = r.get("obj", "")
    if t in ("SPELL", "TELEPORT_ITEM") and in_house(d):
        return "arrive"
    if t == "PORTAL" and "Home Portal" in obj:
        return f"home:{loc.group(1)}" if loc else ""
    if t == "POH_PORTAL":
        return "portal:" + r["name"]
    if t == "TELEPORT_ITEM" and "Jewellery Box" in obj:
        tier = re.search(r"(Basic|Fancy|Ornate) Jewellery Box", obj)
        return "box:" + tier.group(1).lower() if tier else ""
    if t == "TELEPORT_ITEM":
        for key, label in (("Amulet of Glory", "glory"), ("Mythical cape", "mythical"),
                           ("Xeric's Talisman", "xeric"), ("Digsite Pendant", "digsite")):
            if key in obj:
                return "mount:" + label
        return ""
    if t == "FAIRY_RING":
        return "fairy"
    if t == "SPIRIT_TREE":
        return "spirit"
    return ""  # e.g. the house obelisk: left out


def clean(s):
    return (s or "").replace("\t", " ").replace("\n", " ").strip()


def load_file(path, type_key):
    rows = []
    header = None
    section = ""
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.rstrip("\n").rstrip("\r")
            if not line.strip():
                continue
            if line.startswith("#"):
                cols = [h.strip() for h in line.lstrip("#").split("\t")]
                if header is None and "Destination" in cols:
                    header = cols
                else:
                    section = line.lstrip("#").strip()
                continue
            if header is None:
                raise SystemExit(f"{path}: no header line with a Destination column")
            cols = line.split("\t")
            rec = {h: (cols[i] if i < len(cols) else "") for i, h in enumerate(header)}
            origin = ANY
            if "Origin" in header:
                origin = parse_point(rec["Origin"]) if rec["Origin"].strip() else PERM
            dest = ANY
            if "Destination" in header:
                dest = parse_point(rec["Destination"]) if rec["Destination"].strip() else PERM
            if origin is None or dest is None:
                continue
            try:
                ticks = int(rec.get("Duration", "") or 0)
            except ValueError:
                ticks = 0
            wild = rec.get("Wilderness level", "").strip()
            name = clean(rec.get("Display info", ""))
            obj = clean(rec.get("menuOption menuTarget objectID", ""))
            if not name and obj:
                name = re.sub(r"\s+\d+$", "", obj)  # "Open Door 9398" -> "Open Door"
            rows.append({
                "type": type_key,
                "origin": origin,
                "dest": dest,
                "ticks": ticks,
                "skills": parse_skills(rec.get("Skills", "")),
                "quests": parse_quests(rec.get("Quests", "")),
                "items": pretty_items(rec.get("Items", "")),
                "name": name,
                "wild": int(wild) if wild.lstrip("-").isdigit() else -1,
                "book": spellbook(rec.get("Varbits", "")),
                "unlock": needs_unlock(rec.get("Varbits", ""), rec.get("VarPlayers", "")),
                "section": section,
                "obj": obj,
                "varbits": rec.get("Varbits", ""),
            })
    return rows


def merge_req(a, b, sep=";"):
    parts = [p for p in (a.split(sep) if a else []) + (b.split(sep) if b else []) if p]
    seen = []
    for p in parts:
        if p not in seen:
            seen.append(p)
    return sep.join(seen)


def group_name(type_key, origin_row, dest_row):
    d = dest_row["name"] or dest_row["section"]
    if type_key == "FAIRY_RING":
        code = d.replace(" ", "")
        return "Fairy ring " + (code if code != "ZANARIS" else "to Zanaris")
    if type_key == "SPIRIT_TREE":
        return "Spirit tree to " + re.sub(r"^\d+:\s*", "", d or dest_row["section"])
    if type_key == "GNOME_GLIDER":
        return "Gnome glider to " + re.sub(r"^\d+:\s*", "", d or dest_row["section"])
    if type_key == "QUETZAL":
        return "Quetzal to " + re.sub(r"^\d+:\s*", "", d or dest_row["section"])
    if type_key == "HOT_AIR_BALLOON":
        return "Balloon to " + re.sub(r"^\d+:\s*", "", d or dest_row["section"])
    if type_key == "MAGIC_MUSHTREE":
        return "Magic mushtree to " + re.sub(r"^\d+:\s*", "", d or dest_row["section"])
    return d or origin_row["name"]


def expand(rows, radius):
    out = []
    origins = [r for r in rows if r["origin"] not in (PERM, ANY) and r["dest"] == PERM]
    dests = [r for r in rows if r["origin"] == PERM and r["dest"] not in (PERM, ANY)]
    for r in rows:
        o, d = r["origin"], r["dest"]
        if o == PERM or d == PERM or d == ANY:
            continue
        if o != ANY and o == d:
            continue
        out.append(r)
    for o in origins:
        for d in dests:
            (ox, oy, oz), (dx, dy, dz) = o["origin"], d["dest"]
            if math.hypot(ox - dx, oy - dy) <= radius:
                continue
            out.append({
                "type": o["type"],
                "origin": o["origin"],
                "dest": d["dest"],
                "ticks": max(o["ticks"], d["ticks"]),
                "skills": merge_req(o["skills"], d["skills"]),
                "quests": merge_req(o["quests"], d["quests"]),
                "items": o["items"] or d["items"],
                "name": group_name(o["type"], o, d),
                "wild": -1,
                "book": -1,
                "unlock": max(o.get("unlock", 0), d.get("unlock", 0)),
                "obj": o.get("obj", ""),
                "varbits": o.get("varbits", ""),
            })
    return out


def fmt_point(p):
    return "*" if p == ANY else f"{p[0]},{p[1]},{p[2]}"


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(2)
    sp_root, out_dir = sys.argv[1], sys.argv[2]
    res = os.path.join(sp_root, "src/main/resources")
    lines = []
    counts = {}
    seen_outside = set()
    for stem, (type_key, radius) in FILES.items():
        path = os.path.join(res, "transports", stem + ".tsv")
        if not os.path.exists(path):
            raise SystemExit(f"missing {path}: the upstream layout changed")
        rows = expand(load_file(path, type_key), radius)
        counts[type_key] = counts.get(type_key, 0) + len(rows)
        for r in rows:
            poh = poh_tag(r)
            if (in_house(r["origin"]) or in_house(r["dest"])) and not poh:
                continue
            # One way in is enough: skip the "(Inside)" duplicates and the max cape (needs every skill maxed).
            if poh == "arrive" and ("(Inside)" in r["name"] or "Max cape" in r["name"]):
                continue
            if poh.startswith("outside:"):
                if "Max cape" in r["name"]:
                    continue
                # "Teleport to House" in outside mode and its "(Outside)" option land on the same tile.
                r["name"] = re.sub(r"\s*\(Outside\)$", "", r["name"])
                key = (r["type"], r["dest"], r["name"])
                if key in seen_outside:
                    continue
                seen_outside.add(key)
            if poh:
                r["unlock"] = 0 if poh != "box:basic" or not r.get("unlock") else r["unlock"]
                if r["type"] == "POH_PORTAL" or poh.startswith(("box:", "mount:")):
                    r["type"] = "POH"
            name = clean(r["name"]) or type_key.replace("_", " ").title()
            ticks = r["ticks"] if r["ticks"] > 0 else (4 if r["origin"] == ANY else 1)
            lines.append("\t".join([
                r["type"], fmt_point(r["origin"]), fmt_point(r["dest"]), str(ticks),
                r["skills"], r["quests"], clean(r["items"]), name, str(r["wild"]), str(r["book"]),
                str(r.get("unlock", 0)), poh,
            ]))
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "transports.tsv"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")
    shutil.copyfile(os.path.join(res, "collision-map.zip"), os.path.join(out_dir, "collision-map.zip"))

    # Starting points for the route planner: one tile per named bank, plus Lumbridge.
    places = {"Lumbridge (spawn)": (3222, 3218, 0)}
    bank = os.path.join(res, "destinations/game_features/bank.tsv")
    if os.path.exists(bank):
        with open(bank, encoding="utf-8") as fh:
            for line in fh:
                if line.startswith("#") or not line.strip():
                    continue
                cols = line.rstrip("\n").split("\t")
                p = parse_point(cols[0])
                name = clean(cols[1]) if len(cols) > 1 else ""
                if not p or not name or in_house(p) or name in places:
                    continue
                places[name + " bank"] = p
    with open(os.path.join(out_dir, "places.tsv"), "w", encoding="utf-8") as fh:
        for name, (x, y, z) in places.items():
            fh.write(f"{name}\t{x},{y},{z}\n")
    print(f"{len(places)} starting places written")
    for k, v in sorted(counts.items()):
        print(f"{k:18} {v}")
    empty = [k for k, v in counts.items() if v == 0]
    if empty:
        raise SystemExit("no links converted for: " + ", ".join(empty))
    print(f"{len(lines)} transport links written")


if __name__ == "__main__":
    main()
