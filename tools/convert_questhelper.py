#!/usr/bin/env python3
"""
Builds app/src/main/assets/quests.json from the RuneLite Quest Helper plugin source
(https://github.com/Zoinkwiz/quest-helper, BSD 2-Clause, Copyright (c) 2020 Zoinkwiz).

Usage:
    git clone --depth 1 https://github.com/Zoinkwiz/quest-helper qh
    python3 tools/convert_questhelper.py qh app/src/main/assets/quests.json

What it pulls out of each quest:
  * the ordered step list from getPanels(), grouped into sections
  * each step's text, map tile (WorldPoint) and chat options
  * required / recommended items from getItemRequirements() / getItemRecommended()
  * skill and quest requirements from getGeneralRequirements()
Compass bearings and distances are worked out from the tiles of consecutive steps.
"""
import json
import math
import os
import re
import sys
import urllib.parse

# --------------------------------------------------------------------------- tokenizer

TOKEN_RE = re.compile(
    r'''
    (?P<ws>\s+)
  | (?P<lc>//[^\n]*)
  | (?P<bc>/\*.*?\*/)
  | (?P<tb>"""(?:\\.|[^\\])*?""")
  | (?P<str>"(?:\\.|[^"\\\n])*")
  | (?P<chr>'(?:\\.|[^'\\\n])+')
  | (?P<num>\d[\d_]*(?:\.\d+)?[lLfFdD]?)
  | (?P<id>[A-Za-z_$][\w$]*)
  | (?P<op>::|->|==|!=|<=|>=|&&|\|\||\+\+|--|[-+*/%=<>!&|^~?:;,.(){}\[\]@])
    ''',
    re.S | re.X,
)


class Tok:
    __slots__ = ("kind", "val")

    def __init__(self, kind, val):
        self.kind = kind
        self.val = val

    def __repr__(self):
        return f"{self.kind}:{self.val}"


def unescape(s):
    out = []
    i = 0
    while i < len(s):
        c = s[i]
        if c == "\\" and i + 1 < len(s):
            n = s[i + 1]
            if n == "n":
                out.append("\n")
            elif n == "t":
                out.append(" ")
            elif n == "u" and i + 5 < len(s):
                try:
                    out.append(chr(int(s[i + 2:i + 6], 16)))
                    i += 6
                    continue
                except ValueError:
                    out.append(n)
            else:
                out.append(n)
            i += 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def tokenize(src):
    toks = []
    pos = 0
    n = len(src)
    while pos < n:
        m = TOKEN_RE.match(src, pos)
        if not m:
            pos += 1  # skip unknown char
            continue
        pos = m.end()
        kind = m.lastgroup
        if kind in ("ws", "lc", "bc"):
            continue
        val = m.group(kind)
        if kind == "str":
            val = unescape(val[1:-1])
        elif kind == "tb":
            kind = "str"
            body = val[3:-3]
            lines = [ln.strip() for ln in body.split("\n")]
            val = unescape(" ".join(ln for ln in lines if ln))
        toks.append(Tok(kind, val))
    return toks


def match_close(toks, i):
    """toks[i] is an opening bracket. Return index of its partner."""
    pairs = {"(": ")", "{": "}", "[": "]"}
    open_ = toks[i].val
    close = pairs[open_]
    depth = 0
    for j in range(i, len(toks)):
        t = toks[j]
        if t.kind == "op":
            if t.val == open_:
                depth += 1
            elif t.val == close:
                depth -= 1
                if depth == 0:
                    return j
    return len(toks) - 1


def split_args(toks, open_idx):
    """Split the argument list of the call whose '(' is at open_idx into token slices."""
    close = match_close(toks, open_idx)
    args = []
    cur = []
    depth = 0
    for j in range(open_idx + 1, close):
        t = toks[j]
        if t.kind == "op" and t.val in "([{":
            depth += 1
        elif t.kind == "op" and t.val in ")]}":
            depth -= 1
        if depth == 0 and t.kind == "op" and t.val == ",":
            args.append(cur)
            cur = []
        else:
            cur.append(t)
    if cur:
        args.append(cur)
    return args, close


def string_value(arg):
    """Concatenate the string literals of an argument like "a" + x + "b". None if none."""
    parts = [t.val for t in arg if t.kind == "str"]
    if not parts:
        return None
    # Only treat it as text if the argument is mostly a string expression.
    if arg[0].kind not in ("str",) and not (arg[0].kind == "op" and arg[0].val == "("):
        if not any(t.kind == "op" and t.val == "+" for t in arg):
            return None
    return clean_text("".join(parts))


def clean_text(s):
    s = re.sub(r"<br\s*/?>", " ", s, flags=re.I)
    s = re.sub(r"<[^>]+>", "", s)
    s = re.sub(r"\s+", " ", s).strip()
    return s


def int_value(arg):
    if len(arg) == 1 and arg[0].kind == "num":
        try:
            return int(arg[0].val.rstrip("lL").replace("_", ""))
        except ValueError:
            return None
    if len(arg) == 2 and arg[0].kind == "op" and arg[0].val == "-" and arg[1].kind == "num":
        return None
    return None


def find_world_point(arg_tokens):
    for k in range(len(arg_tokens) - 2):
        t = arg_tokens[k]
        if t.kind == "id" and t.val == "WorldPoint" and k > 0 and arg_tokens[k - 1].val == "new":
            if arg_tokens[k + 1].val == "(":
                args, _ = split_args(arg_tokens, k + 1)
                nums = [int_value(a) for a in args]
                if len(nums) == 3 and all(v is not None for v in nums):
                    return nums
    return None


# --------------------------------------------------------------------------- quest model


class QuestSource:
    def __init__(self, files):
        self.toks = []
        self.file_starts = []
        for f in files:
            with open(f, encoding="utf-8", errors="replace") as fh:
                self.toks.extend(tokenize(fh.read()))
        self.defs = {}       # name -> list of (start, end) token ranges of the RHS expression
        self.calls = {}      # name -> list of (method, args)
        self._index()

    def _index(self):
        t = self.toks
        n = len(t)
        i = 0
        while i < n - 2:
            # ident = <expr> ;
            if t[i].kind == "id" and t[i + 1].kind == "op" and t[i + 1].val == "=":
                prev = t[i - 1] if i > 0 else None
                if prev is None or not (prev.kind == "op" and prev.val == "."):
                    start = i + 2
                    j = start
                    depth = 0
                    while j < n:
                        tv = t[j]
                        if tv.kind == "op":
                            if tv.val in "([{":
                                depth += 1
                            elif tv.val in ")]}":
                                if depth == 0:
                                    break
                                depth -= 1
                            elif tv.val == ";" and depth == 0:
                                break
                        j += 1
                    self.defs.setdefault(t[i].val, []).append((start, j))
                    i = j
                    continue
            # ident.method( args )
            if (t[i].kind == "id" and t[i + 1].kind == "op" and t[i + 1].val == "."
                    and i + 3 < n and t[i + 2].kind == "id" and t[i + 3].val == "("):
                prev = t[i - 1] if i > 0 else None
                if prev is None or not (prev.kind == "op" and prev.val == "."):
                    args, close = split_args(t, i + 3)
                    self.calls.setdefault(t[i].val, []).append((t[i + 2].val, args))
            i += 1

    def method_body_in_class(self, cls, name):
        t = self.toks
        for i in range(len(t) - 2):
            if t[i].val == "class" and t[i + 1].val == cls:
                k = i + 2
                while k < len(t) and t[k].val != "{":
                    k += 1
                end = match_close(t, k)
                for j in range(k, end - 2):
                    if t[j].kind == "id" and t[j].val == name and t[j + 1].val == "(":
                        close = match_close(t, j + 1)
                        if close + 1 < end and t[close + 1].val == "{":
                            return close + 1, match_close(t, close + 1)
                return None
        return None

    def method_body(self, name):
        """Token range of the body of method `name` (first match)."""
        t = self.toks
        for i in range(len(t) - 3):
            if t[i].kind == "id" and t[i].val == name and t[i + 1].val == "(":
                close = match_close(t, i + 1)
                if close + 1 < len(t) and t[close + 1].val == "{":
                    end = match_close(t, close + 1)
                    return close + 1, end
                # skip 'throws X' etc.
                k = close + 1
                while k < len(t) and t[k].val not in ("{", ";"):
                    k += 1
                if k < len(t) and t[k].val == "{":
                    return k, match_close(t, k)
        return None

    def rhs_before(self, name, pos):
        """Definition closest before token position pos (falls back to rhs)."""
        best = None
        for st, en in self.defs.get(name, []):
            if st < pos and (best is None or st > best[0]):
                best = (st, en)
        if best:
            return self.toks[best[0]:best[1]]
        return self.rhs(name)

    def rhs(self, name):
        """Best definition of a variable: the first `new ...` assignment, else any."""
        ranges = self.defs.get(name)
        if not ranges:
            return None
        for s, e in ranges:
            if e > s and self.toks[s].val == "new":
                return self.toks[s:e]
        s, e = ranges[0]
        return self.toks[s:e]


STEP_SUFFIXES = ("Step",)


def describe_step(src, name, seen=None):
    """Return dict(text, wp, dialog) for a step variable, following ConditionalStep defaults."""
    if seen is None:
        seen = set()
    if name in seen:
        return None
    seen.add(name)
    expr = src.rhs(name)
    text = None
    wp = None
    dialog = []
    cls = None
    if expr and len(expr) > 2 and expr[0].kind in ("id",) and expr[0].val != "this":
        if expr[0].val == "new":
            cls = expr[1].val
            k = 2
        else:
            cls = expr[0].val
            k = 1
        # find the constructor's / factory's '(' 
        while k < len(expr) and expr[k].val != "(":
            k += 1
        if k < len(expr):
            args, _ = split_args(expr, k)
            for a in args:
                if text is None:
                    sv = string_value(a)
                    if sv:
                        text = sv
            wp = find_world_point(expr)
            if cls in ("ConditionalStep", "ReorderableConditionalStep") and len(args) >= 2:
                child = args[1]
                if len(child) == 1 and child[0].kind == "id":
                    sub = describe_step(src, child[0].val, seen)
                    if sub:
                        if text is None:
                            text = sub["text"]
                        if wp is None:
                            wp = sub["wp"]
                        if not dialog:
                            dialog = sub["dialog"]
    # Later calls on the variable can change things.
    for method, args in src.calls.get(name, []):
        if method == "setText" and args:
            sv = string_value(args[0])
            if sv:
                text = sv
            elif args[0] and args[0][0].val == "List":
                vals = [t.val for t in args[0] if t.kind == "str"]
                if vals:
                    text = clean_text(" ".join(vals))
        elif method == "setWorldPoint" and args:
            p = find_world_point(args[0])
            if p:
                wp = p
            else:
                nums = [int_value(a) for a in args]
                if len(nums) == 3 and all(v is not None for v in nums):
                    wp = nums
        elif method in ("addDialogStep", "addDialogSteps", "addDialogStepWithExclusion",
                        "addDialogStepWithExclusions"):
            for a in args if method != "addDialogStepWithExclusion" else args[:1]:
                sv = string_value(a)
                if sv and sv not in dialog:
                    dialog.append(sv)
    if text is None:
        return None
    return {"text": text, "wp": wp, "dialog": dialog}


def list_identifiers(arg):
    """Identifiers listed in List.of(a, b) / Arrays.asList(a, b) / singletonList(a) / a."""
    ids = []
    # Find the first '(' after a list factory; otherwise treat the arg as a single item.
    for k, t in enumerate(arg):
        if t.kind == "id" and t.val in ("of", "asList", "singletonList", "List", "Arrays",
                                        "Collections", "ImmutableList"):
            continue
        if t.kind == "op" and t.val == ".":
            continue
        if t.kind == "op" and t.val == "(":
            items, _ = split_args(arg, k)
            for it in items:
                ident = leading_identifier(it)
                if ident:
                    ids.append(ident)
            return ids
        break
    ident = leading_identifier(arg)
    return [ident] if ident else []


def leading_identifier(arg):
    if not arg:
        return None
    if arg[0].kind == "id" and arg[0].val not in ("new", "this", "null"):
        if len(arg) == 1 or arg[1].val == ".":
            return arg[0].val
    return None


def owner_substeps(src, owner):
    """Steps of owner.getSteps(): a helper class's own getSteps() list, or the steps
    added to a ConditionalStep-like owner with owner.addStep(cond, step)."""
    rhs0 = src.rhs(owner)
    if rhs0 and len(rhs0) > 2 and rhs0[0].val == "new":
        body = src.method_body_in_class(rhs0[1].val, "getSteps")
        if body:
            s, e = body
            seg = src.toks[s:e]
            for k, tk in enumerate(seg):
                if tk.val in ("of", "asList") and k + 1 < len(seg) and seg[k + 1].val == "(":
                    items, _ = split_args(seg, k + 1)
                    ids = [leading_identifier(it) for it in items]
                    return [x for x in ids if x]
    out = []
    for method, args in src.calls.get(owner, []):
        if method == "addStep" and args:
            ident = leading_identifier(args[-1])
            if ident and ident not in out:
                out.append(ident)
    # addStep order is priority order (latest stage first), so reverse it.
    out.reverse()
    rhs = src.rhs(owner)
    if rhs and len(rhs) > 4 and rhs[0].val == "new":
        k = 2
        while k < len(rhs) and rhs[k].val != "(":
            k += 1
        a, _ = split_args(rhs, k)
        if len(a) >= 2:
            first = leading_identifier(a[1])
            if first and first not in out:
                out.insert(0, first)
    return out


def last_stage_step(src):
    """The step registered for the highest quest stage in loadSteps(), if it is a plain step."""
    body = src.method_body("loadSteps")
    if not body:
        return None
    s, e = body
    t = src.toks
    best = None
    for k in range(s, e - 3):
        if t[k].val == "put" and t[k - 1].val == "." and t[k + 1].val == "(":
            args, _ = split_args(t, k + 1)
            if len(args) == 2:
                stage = int_value(args[0])
                ident = leading_identifier(args[1])
                if stage is not None and ident and (best is None or stage >= best[0]):
                    best = (stage, ident)
    if not best:
        return None
    rhs = src.rhs(best[1])
    if not rhs or len(rhs) < 2:
        return None
    if rhs[0].val == "new" and rhs[1].val in ("ConditionalStep", "ReorderableConditionalStep"):
        return None
    return best[1]


def extract_panels(src):
    body = src.method_body("getPanels")
    if not body:
        return []
    s, e = body
    t = src.toks
    if not any(t[k].val == "PanelDetails" and (t[k - 1].val == "new" or t[k + 1].val == ".") for k in range(s, e)):
        s, e = 0, len(t)
    t = src.toks
    sections = []
    i = s
    while i < e - 2:
        locked = (t[i].val == "PanelDetails" and t[i + 1].val == "." and i + 3 < e
                  and t[i + 2].val == "lockedPanel" and t[i + 3].val == "(")
        if locked or (t[i].val == "new" and t[i + 1].val == "PanelDetails" and t[i + 2].val == "("):
            args, close = split_args(t, i + 3 if locked else i + 2)
            if locked:
                # Drop the display condition and locking step: (title, steps, items...) like the constructor.
                args = args[:1] + args[3:]
            title = string_value(args[0]) if args else None
            step_ids = []
            if len(args) >= 2:
                a1 = args[1]
                if len(a1) == 1 and a1[0].kind == "id" and a1[0].val not in src.defs:
                    step_ids = []
                elif len(a1) == 1 and a1[0].kind == "id":
                    # a variable holding the list, or a single step
                    rhs = src.rhs_before(a1[0].val, i)
                    if rhs and any(x.val in ("of", "asList", "singletonList") for x in rhs[:6]):
                        step_ids = list_identifiers(rhs)
                    else:
                        step_ids = [a1[0].val]
                elif (len(a1) >= 4 and a1[0].kind == "id" and a1[1].val == "."
                      and a1[2].val == "getSteps"):
                    step_ids = owner_substeps(src, a1[0].val)
                else:
                    step_ids = list_identifiers(a1)
                if len(args) >= 3:
                    section_items = []
                    for extra in args[2:]:
                        if extra and extra[0].val in ("List", "Arrays", "Collections"):
                            for ident in list_identifiers(extra):
                                section_items.append([Tok("id", ident)])
                        else:
                            section_items.append(extra)
                else:
                    section_items = []
                # Old style: new PanelDetails("x", Arrays.asList(a, b), item, item)
            sections.append((title or "", step_ids, section_items))
            i = close
        i += 1
    return sections


SKILL_NAMES = {
    "RUNECRAFT": "Runecraft", "HITPOINTS": "Hitpoints",
}


def resolve_item(src, arg_tokens, recommended, depth=0):
    """arg_tokens is an entry of the items list. Returns item dict or None."""
    if depth > 4 or not arg_tokens:
        return None
    qty_override = None
    # x.quantity(n) / x.highlighted() / x.copy()
    for k in range(len(arg_tokens) - 2):
        if arg_tokens[k].val == "quantity" and arg_tokens[k + 1].val == "(":
            a, _ = split_args(arg_tokens, k + 1)
            if a:
                qty_override = int_value(a[0])
    if arg_tokens[0].val == "new":
        expr = arg_tokens
        name_hint = None
    else:
        ident = leading_identifier(arg_tokens)
        if not ident:
            return None
        expr = src.rhs(ident)
        name_hint = ident
        if not expr:
            return None
        if expr[0].kind == "id" and expr[0].val != "new":
            # alias like milk = bucketOfMilk.quantity(2)
            sub = resolve_item(src, expr, recommended, depth + 1)
            if sub and qty_override:
                sub["qty"] = qty_override
            if sub and name_hint:
                apply_item_calls(src, name_hint, sub)
            return sub
    if len(expr) < 3 or expr[1].kind != "id":
        return None
    cls = expr[1].val
    if cls not in ("ItemRequirement", "ItemRequirements", "FollowerItemRequirement",
                   "KeyringRequirement", "TeleportItemRequirement", "ItemOnTileRequirement",
                   "FreeInventorySlots", "NoItemRequirement", "WeightRequirement",
                   "QuestItemRequirement", "ComplexRequirement"):
        if not cls.endswith("ItemRequirement") and not cls.endswith("ItemRequirements"):
            return None
    if cls in ("FreeInventorySlots", "NoItemRequirement", "WeightRequirement", "ItemOnTileRequirement"):
        return None
    k = 2
    while k < len(expr) and expr[k].val != "(":
        k += 1
    if k >= len(expr):
        return None
    args, close = split_args(expr, k)
    name = None
    qty = 1
    for idx, a in enumerate(args):
        sv = string_value(a)
        if sv and name is None:
            name = sv
            # ItemRequirement("Name", id, qty)
            if cls != "ItemRequirements" and idx + 2 < len(args):
                q = int_value(args[idx + 2])
                if q is not None and 0 < q < 100000:
                    qty = q
            break
    if name is None:
        return None
    # chained .quantity() on the definition itself
    for kk in range(close, len(expr) - 2):
        if expr[kk].val == "quantity" and expr[kk + 1].val == "(":
            a, _ = split_args(expr, kk + 1)
            if a:
                q = int_value(a[0])
                if q:
                    qty = q
    if qty_override:
        qty = qty_override
    item = {"name": name, "qty": qty, "note": "", "rec": recommended}
    if name_hint:
        apply_item_calls(src, name_hint, item)
    return item


def apply_item_calls(src, ident, item):
    notes = []
    for method, args in src.calls.get(ident, []):
        if method == "setTooltip" and args:
            sv = string_value(args[0])
            if sv:
                notes.append(sv)
        elif method == "canBeObtainedDuringQuest":
            notes.append("Can be obtained during the quest.")
        elif method == "setQuantity" and args:
            q = int_value(args[0])
            if q:
                item["qty"] = q
    if notes:
        seen = []
        for n in notes:
            if n not in seen:
                seen.append(n)
        item["note"] = " ".join(seen)


def method_list_entries(src, method):
    body = src.method_body(method)
    if not body:
        return []
    s, e = body
    t = src.toks
    entries = []
    i = s
    while i < e:
        tv = t[i]
        # List.of( ... ) / Arrays.asList( ... )
        if tv.kind == "id" and tv.val in ("of", "asList", "singletonList") and i + 1 < e and t[i + 1].val == "(":
            args, close = split_args(t, i + 1)
            entries.extend(args)
            i = close
        # x.add( ... )
        elif tv.kind == "id" and tv.val == "add" and i + 1 < e and t[i + 1].val == "(" and t[i - 1].val == ".":
            args, close = split_args(t, i + 1)
            if args:
                entries.append(args[0])
            i = close
        i += 1
    return entries


def extract_items(src):
    items = []
    seen = set()
    for method, rec in (("getItemRequirements", False), ("getItemRecommended", True)):
        for entry in method_list_entries(src, method):
            it = resolve_item(src, entry, rec)
            if it and it["name"].lower() not in seen:
                seen.add(it["name"].lower())
                items.append(it)
    return items


def pretty_enum(name):
    return name.replace("_", " ").title()


# "Talk to the Cook in ...", "Speak with Martin Holt ...", "Return to King Arthur ..." -> the NPC's name,
# so screen reading can recognise the conversation. Only capitalised names are taken.
_NAME = r"(?:the |a |an )?([A-Z][\w'\-]*(?:(?: of| the| de| du)? [A-Z][\w'\-]*)*)"
TALK_RE = re.compile(r"\b(?:[Tt]alk|[Ss]peak)(?: back)? (?:to|with) " + _NAME)
# "Return to ..." can name a place ("Return to Camelot and talk to King Arthur"), so it's only a fallback.
RETURN_RE = re.compile(r"\b(?:[Rr]eturn|[Rr]eport)(?: back)? to " + _NAME)


# Places that "Return to ..." sometimes names instead of a person.
NOT_NPCS = {
    "Camelot", "Varrock", "Falador", "Lumbridge", "Ardougne", "East Ardougne", "West Ardougne", "Draynor",
    "Draynor Village", "Port Sarim", "Rimmington", "Edgeville", "Taverley", "Catherby", "Yanille",
    "Canifis", "Rellekka", "Burthorpe", "Karamja", "Entrana", "Al Kharid", "Seers' Village", "Prifddinas",
    "Zanaris", "Keldagrim", "Shilo Village", "Brimhaven", "Hosidius", "Port Phasmatys", "Morytania",
}


def npc_name(text):
    m = TALK_RE.search(text) or RETURN_RE.search(text)
    if not m or m.group(1) in NOT_NPCS:
        return ""
    return m.group(1)


def extract_quest_points(src):
    """Quest points the quest awards (getQuestPointReward), or 0."""
    body = src.method_body("getQuestPointReward")
    if not body:
        return 0
    s, e = body
    t = src.toks
    for i in range(s, e - 2):
        if t[i].val == "QuestPointReward" and t[i + 1].val == "(" and t[i + 2].kind == "num":
            return int(t[i + 2].val)
    return 0


def extract_requirements(src, quest_names):
    reqs = []
    for entry in method_list_entries(src, "getGeneralRequirements"):
        expr = entry
        if entry and entry[0].kind == "id" and entry[0].val != "new":
            r = src.rhs(entry[0].val)
            if r:
                expr = r
        vals = [t.val for t in expr]
        joined = " ".join(vals)
        m = re.search(r"SkillRequirement \( Skill \. (\w+) , (\d+)", joined)
        if m:
            skill = SKILL_NAMES.get(m.group(1), m.group(1).title())
            boost = " (boostable)" if "true" in vals[vals.index(m.group(2)):] else ""
            reqs.append(f"{skill} {m.group(2)}{boost}")
            continue
        m = re.search(r"QuestRequirement \( QuestHelperQuest \. (\w+)", joined)
        if m:
            qn = quest_names.get(m.group(1), pretty_enum(m.group(1)))
            started = " (started)" if "IN_PROGRESS" in joined else ""
            reqs.append(f"{qn}{started}")
            continue
        m = re.search(r"QuestPointRequirement \( (\d+)", joined)
        if m:
            reqs.append(f"{m.group(1)} quest points")
            continue
        m = re.search(r"CombatLevelRequirement \( (\d+)", joined)
        if m:
            reqs.append(f"Combat level {m.group(1)}")
            continue
    out = []
    for r in reqs:
        if r not in out:
            out.append(r)
    return out


# --------------------------------------------------------------------------- directions

COMPASS = ["N", "NE", "E", "SE", "S", "SW", "W", "NW"]


def area(p):
    """Which map layer a tile is on, and its position projected onto the surface map.
    OSRS dungeons sit 6400 tiles north of the surface they are under; tiles between
    the surface and the dungeons (y 4160-6400) or far beyond are instances/special areas."""
    x, y, z = p
    if y < 4160:
        return "surface", x, y
    if 6400 <= y < 10560:
        return "under", x, y - 6400
    return f"other:{x // 512}:{y // 512}", x, y


UP_RE = re.compile(r"^(climb|go|walk|head|run) (back )?up\b|^climb-up|^go upstairs", re.I)
DOWN_RE = re.compile(r"^(climb|go|walk|head|run) (back )?down\b|^climb-down|^go downstairs", re.I)


def direction(prev, cur, text=""):
    if UP_RE.search(text):
        return "UP", 0
    if DOWN_RE.search(text):
        return "DOWN", 0
    if cur is None or prev is None:
        return "NONE", 0
    pa, px, py = area(prev)
    ca, cx, cy = area(cur)
    if pa != ca and {pa, ca} != {"surface", "under"}:
        return "NONE", 0  # changing map layer: a bearing would point somewhere misleading
    dx, dy = cx - px, cy - py
    dist = int(round(math.hypot(dx, dy)))
    if dist <= 8:
        return "HERE", 0
    if pa != ca and dist > 600:
        return "NONE", 0
    if dist > 2500 or (ca.startswith("other") and dist > 400):
        return "NONE", 0
    ang = math.degrees(math.atan2(dx, dy)) % 360
    return COMPASS[int((ang + 22.5) // 45) % 8], dist


# --------------------------------------------------------------------------- main


def load_enum(qh_root):
    """Quests, miniquests and achievement diary tiers from Quest Helper's quest list.

    Each entry is read as a whole (diary entries span two lines). Returns tuples of
    (enum name, helper class, type, difficulty, display name or None)."""
    info = os.path.join(qh_root, "src/main/java/com/questhelper/questinfo")
    entries = []
    with open(os.path.join(info, "QuestHelperQuest.java"), encoding="utf-8") as fh:
        text = fh.read()
    for m in re.finditer(r"^\s*(\w+)\(new (\w+)\(\)", text, re.M):
        # The entry runs until the brackets balance again.
        depth = 0
        end = m.start()
        for k in range(text.index("(", m.start()), len(text)):
            c = text[k]
            if c == "(":
                depth += 1
            elif c == ")":
                depth -= 1
                if depth == 0:
                    end = k
                    break
        body = text[m.start():end + 1]
        t = re.search(r"QuestDetails\.Type\.(F2P|P2P|MINIQUEST|ACHIEVEMENT_DIARY)\s*,\s*QuestDetails\.Difficulty\.(\w+)", body)
        if not t:
            continue
        label = re.search(r'new \w+\(\)\s*,\s*"([^"]+)"', body)
        entries.append((m.group(1), m.group(2), t.group(1), t.group(2), label.group(1) if label else None))
    urls = {}
    with open(os.path.join(info, "ExternalQuestResources.java"), encoding="utf-8") as fh:
        for line in fh:
            m = re.match(r'\s*(\w+)\("(https://[^"]+)"\)', line)
            if m:
                urls[m.group(1)] = m.group(2)
    return entries, urls


DIARY_TIERS = ("EASY", "MEDIUM", "HARD", "ELITE")


def diary_info(enum_name, label):
    """("Ardougne", "Easy") from ARDOUGNE_EASY / "Ardougne Easy Diary"."""
    tier = enum_name.rsplit("_", 1)[-1].title()
    region = (label or "").replace(" Diary", "")
    if region.endswith(" " + tier):
        region = region[: -len(tier) - 1]
    return region, tier


def name_from_url(url):
    if not url:
        return None
    part = url.split("/w/", 1)[-1].split("#")[0]
    return urllib.parse.unquote(part).replace("_", " ")


def find_class_dir(qh_root, cls):
    base = os.path.join(qh_root, "src/main/java/com/questhelper/helpers")
    for root, _, files in os.walk(base):
        if cls + ".java" in files:
            return root, os.path.join(root, cls + ".java")
    return None, None


def convert(qh_root):
    entries, urls = load_enum(qh_root)
    quest_names = {}
    for enum_name, cls, typ, diff, label in entries:
        if typ == "ACHIEVEMENT_DIARY":
            quest_names[enum_name] = label or pretty_enum(enum_name)
        else:
            quest_names[enum_name] = name_from_url(urls.get(enum_name)) or pretty_enum(enum_name)

    quests = []
    report = []
    for enum_name, cls, typ, diff, label in entries:
        if enum_name.startswith("BALLOON_TRANSPORT_"):
            continue  # balloon route unlocks, not quests
        qdir, main_file = find_class_dir(qh_root, cls)
        if not main_file:
            report.append(f"MISSING {enum_name} {cls}")
            continue
        files = [main_file]
        if typ != "ACHIEVEMENT_DIARY" and os.path.basename(qdir) not in ("quests", "miniquests", "helpers"):
            for root, _, fnames in os.walk(qdir):
                for f in sorted(fnames):
                    fp = os.path.join(root, f)
                    if f.endswith(".java") and fp != main_file:
                        files.append(fp)
        src = QuestSource(files)
        sections = extract_panels(src)
        steps = []
        prev_wp = None
        last_text = None
        # A finishing step that the author left out of the panels (e.g. "Drink from the cauldron").
        panel_ids = {i for _, ids, _ in sections for i in ids}
        final_id = last_stage_step(src)
        if sections and final_id and final_id not in panel_ids:
            title, ids, its = sections[-1]
            sections[-1] = (title, ids + [final_id], its)

        for title, ids, section_items in sections:
            first_in_section = True
            bring = []
            for entry in section_items:
                it = resolve_item(src, entry, False)
                if it:
                    bring.append(f'{it["qty"]}× {it["name"]}' if it["qty"] > 1 else it["name"])
            described = [(ident, describe_step(src, ident)) for ident in ids]
            if title and not any(d and d["text"] for _, d in described):
                # Puzzle or custom steps with no text of their own: fall back to the section title.
                described = [(None, {"text": title, "wp": None, "dialog": []})]
            for ident, d in described:
                if not d or not d["text"]:
                    continue
                if d["text"] == last_text:
                    continue
                last_text = d["text"]
                dir_key, dist = direction(prev_wp, d["wp"], d["text"])
                step = {
                    "text": d["text"],
                    "section": title if first_in_section else "",
                    "dir": dir_key,
                }
                if first_in_section and bring:
                    step["bring"] = bring
                if dist:
                    step["dist"] = dist
                if d["dialog"]:
                    step["chat"] = d["dialog"]
                npc = npc_name(d["text"])
                if npc:
                    step["npc"] = npc
                if d["wp"]:
                    step["map"] = d["wp"]
                    prev_wp = d["wp"]
                steps.append(step)
                first_in_section = False
        if not steps:
            report.append(f"NO STEPS {enum_name}")
            continue
        items = extract_items(src)
        reqs = extract_requirements(src, quest_names)
        entry = {
            "id": enum_name.lower(),
            "name": quest_names[enum_name],
            "type": {"F2P": "Free", "P2P": "Members", "MINIQUEST": "Miniquest", "ACHIEVEMENT_DIARY": "Diary"}[typ],
            "difficulty": diff.replace("_", " ").title() if diff not in ("MINIQUEST", "ACHIEVEMENT_DIARY") else
                          ("Miniquest" if diff == "MINIQUEST" else diary_info(enum_name, label)[1]),
            "qp": extract_quest_points(src) if typ in ("F2P", "P2P") else 0,
            "requirements": reqs,
            "wikiUrl": urls.get(enum_name, ""),
            "items": items,
            "steps": steps,
        }
        if typ == "ACHIEVEMENT_DIARY":
            region, tier = diary_info(enum_name, label)
            entry["region"] = region
            entry["tier"] = tier
            if not entry["wikiUrl"]:
                entry["wikiUrl"] = "https://oldschool.runescape.wiki/w/" + urllib.parse.quote(region.replace(" ", "_")) + "_Diary"
        quests.append(entry)
        report.append(f"ok {enum_name}: {len(steps)} steps, {len(items)} items, {len(reqs)} reqs")
    def order(q):
        if q["type"] == "Diary":
            return (1, q["region"].lower(), ["Easy", "Medium", "Hard", "Elite"].index(q["tier"]))
        return (0, q["name"].lower().removeprefix("the "), 0)
    quests.sort(key=order)
    return quests, report


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(2)
    qh_root, out = sys.argv[1], sys.argv[2]
    quests, report = convert(qh_root)
    data = {
        "version": 2,
        "source": "Converted from the RuneLite Quest Helper plugin (BSD 2-Clause, Copyright (c) 2020 Zoinkwiz).",
        "quests": quests,
    }
    with open(out, "w", encoding="utf-8") as fh:
        json.dump(data, fh, ensure_ascii=False, separators=(",", ":"))
    for line in report:
        if not line.startswith("ok"):
            print(line)
    print(f"{len(quests)} quests written to {out}")


if __name__ == "__main__":
    main()
