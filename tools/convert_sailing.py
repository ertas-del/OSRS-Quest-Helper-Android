#!/usr/bin/env python3
"""Builds assets/sailing.json from two BSD 2-Clause RuneLite plugins:
  - Sailing (LlemonDuck): sea charting tasks and where they are
  - Port Tasks (nucleon): ports, the Sailing level each needs, and sailing routes between them

Usage: convert_sailing.py <runelite-sailing-plugin> <port-tasks> <out.json>
"""
import json, re, sys

AREA_FIX = {"Of": "of", "The": "the", "And": "and"}


def nice(words):
    out = []
    for i, w in enumerate(words):
        t = w.capitalize()
        if i > 0 and t in AREA_FIX:
            t = AREA_FIX[t]
        out.append(t)
    s = " ".join(out)
    return s.replace("Wizards Tower", "Wizards' Tower")


def chart_label(varbit):
    body = varbit.removeprefix("SAILING_CHARTING_").removesuffix("_COMPLETE")
    parts = body.split("_")
    kinds = {
        "GENERIC": "Chart",
        "SPYGLASS": "Spyglass",
        "CURRENT": "Current duck",
        "DRINK": "Drink crate",
        "MERMAID": "Mermaid guide",
        "WEATHER": "Weather",
    }
    kind = kinds.get(parts[0], "Chart")
    rest = parts[1:]
    if parts[0] == "CURRENT" and rest[:1] == ["DUCK"]:
        rest = rest[1:]
    if parts[0] == "DRINK" and rest[:1] == ["CRATE"]:
        rest = rest[1:]
    if parts[0] == "MERMAID" and rest[:1] == ["GUIDE"]:
        rest = rest[1:]
    if parts[0] not in kinds:
        rest = parts
    return kind, nice(rest)


def main():
    sail, ports_root, out = sys.argv[1], sys.argv[2], sys.argv[3]
    src = open(sail + "/src/main/java/com/duckblade/osrs/sailing/features/charting/SeaChartTask.java").read()
    charts = []
    for m in re.finditer(r"TASK_(\d+)\(\d+,\s*VarbitID\.(\w+),.*?new WorldPoint\((\d+),\s*(\d+),\s*(\d+)\)\s*,\s*(null|new WorldPoint\((\d+),\s*(\d+),\s*(\d+)\))", src):
        tid, varbit, x, y, z = m.group(1), m.group(2), int(m.group(3)), int(m.group(4)), int(m.group(5))
        kind, label = chart_label(varbit)
        c = {"id": int(tid), "kind": kind, "label": label, "x": x, "y": y, "z": z}
        if m.group(7):
            c["to"] = [int(m.group(7)), int(m.group(8))]
        charts.append(c)

    base = ports_root + "/src/main/java/com/nucleon/porttasks/enums/"
    psrc = open(base + "PortLocation.java").read()
    ports = {}
    for m in re.finditer(r"^\s*(\w+)\(\d+,\s*\"([^\"]+)\",\s*(null|\d+),.*?new WorldPoint\((\d+),\s*(\d+),\s*(\d+)\)\)", psrc, re.M):
        if m.group(1) == "EMPTY":
            continue
        ports[m.group(1)] = {"name": m.group(2), "level": None if m.group(3) == "null" else int(m.group(3)),
                             "x": int(m.group(4)), "y": int(m.group(5))}
    rsrc = open(base + "PortPaths.java").read()
    routes = []
    for m in re.finditer(r"^\t(\w+)\(\s*PortLocation\.(\w+),\s*PortLocation\.(\w+)((?:,\s*new RelativeMove\(-?\d+,\s*-?\d+\))*)\s*\)", rsrc, re.M):
        name, a, b, moves = m.groups()
        if a not in ports or b not in ports:
            continue
        x, y = ports[a]["x"], ports[a]["y"]
        pts = [[x, y]]
        for dx, dy in re.findall(r"RelativeMove\((-?\d+),\s*(-?\d+)\)", moves):
            x += int(dx); y += int(dy)
            pts.append([x, y])
        pts.append([ports[b]["x"], ports[b]["y"]])
        routes.append({"from": ports[a]["name"], "to": ports[b]["name"], "points": pts})

    data = {
        "source": "Sea charting from the Sailing RuneLite plugin (BSD 2-Clause, (c) 2025 LlemonDuck); "
                  "ports and routes from the Port Tasks RuneLite plugin (BSD 2-Clause, (c) 2025 nucleon).",
        "ports": sorted(ports.values(), key=lambda p: (p["level"] or 999, p["name"])),
        "routes": routes,
        "charts": charts,
    }
    json.dump(data, open(out, "w"), separators=(",", ":"))
    print(f"{len(ports)} ports, {len(routes)} routes, {len(charts)} charts -> {out}")


if __name__ == "__main__":
    main()
