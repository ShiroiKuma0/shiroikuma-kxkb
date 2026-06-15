#!/usr/bin/env python3
"""Convert a FUTO-style compass keyboard YAML into a shiroikuma-kxkb (Urik) layout JSON.

The YAML is 白い熊's own layout *design data*; this tool converts the arrangement into our
extended flick JSON. It does NOT copy any FUTO engine source.

Mapping summary:
  - compass key      -> {"type":"flick","char":<primary label>,"flick":{...8 dirs...}}
  - case key         -> its "normal" compass variant (shiftedManually is deferred)
  - cluster ";.:"    -> flick with left/centre/right taken from the triple, plus its own flicks
  - macro {text}     -> a key/direction that commits the literal text
  - chord {keys}     -> {"chord":"C-c","label":...} on a direction (sent as a modifier key event)
  - base spec        -> a functional key: $shift/$delete/$space -> native action keys; others
                        (escape, tab, enter, layer switches, arrows, settings...) -> flick centre
                        bindings; a base key's moreKeys become its 8 flick directions.
  - rows / altPages  -> Urik modes: main=letters, altPages[0]=numbers, altPages[1]=symbols.

Usage: python3 tools/gnu_yaml_to_json.py "<input.yaml>" app/src/main/assets/layouts/gnu.json
"""
import json
import sys

import yaml

DIRS = ["up", "down", "left", "right", "upLeft", "upRight", "downLeft", "downRight"]

# !code/<X> -> our action name (resolved at runtime in handleGnuAction)
CODE_ACTION = {
    "key_escape": "escape", "key_tab": "tab", "key_enter": "enter", "key_space": "space",
    "key_ctrl": "ctrl", "action_undo": "undo", "action_redo": "redo",
    "action_hide_keyboard": "hide", "action_voice_input": "voice",
    "action_up": "arrow_up", "action_down": "arrow_down",
    "action_left": "arrow_left", "action_right": "arrow_right",
    "action_next_language_layout": "next_language",
}
# !code/<X> -> our layer target (mapped to a keyboard mode at runtime)
CODE_LAYER = {
    "key_to_alt_0_layout": "alt0", "key_to_alt_1_layout": "alt1",
    "key_to_alpha_0_layout": "alpha0", "key_to_alpha_1_layout": "alpha1",
}
# !icon/<X> -> a text glyph we can render (Urik lacks most of these drawables)
ICON_LABEL = {
    "space_key": "␣", "tab_key": "⇥", "enter_key": "⏎", "settings_key": "⚙",
    "action_undo": "↶", "action_redo": "↷", "action_hide_keyboard": "⌄",
    "action_voice_input": "🎙", "action_up": "↑", "action_down": "↓",
    "action_left": "←", "action_right": "→",
}
DOLLAR = {"$shift": "shift", "$delete": "backspace", "$space": "space"}


def split_spec(s):
    """Parse a FUTO key spec ("label|!code/Y", "!icon/X|!code/Y", "!icon/X") -> (label, code)."""
    label = code = icon = None
    for part in s.split("|"):
        if part.startswith("!code/"):
            code = part[len("!code/"):]
        elif part.startswith("!icon/"):
            icon = part[len("!icon/"):]
        else:
            label = part
    if label is None and icon is not None:
        label = ICON_LABEL.get(icon, icon)
    return (label or ""), code


def conv_spec_string(s):
    """A string value (plain char or a spec) -> a plain string (text) or a binding dict."""
    if not isinstance(s, str):
        return None
    if "!code/" in s or s.startswith("!icon/"):
        label, code = split_spec(s)
        if code in CODE_LAYER:
            return {"layer": CODE_LAYER[code], "label": label}
        if code in CODE_ACTION:
            return {"action": CODE_ACTION[code], "label": label}
        if code:
            return {"action": code, "label": label}
        return label  # icon-only, no code: render the glyph as text
    return s  # plain text/char


def conv_position(v):
    """A flick direction value -> a string (text) or a binding dict, or None to omit."""
    if isinstance(v, str):
        return conv_spec_string(v)
    if isinstance(v, dict):
        t = v.get("type")
        if t == "macro":
            return v.get("text")
        if t == "chord":
            return {"chord": v["keys"], "label": v.get("label", v["keys"])}
        if t == "compass":
            return conv_primary(v.get("primary", ""))[0]
    return None


def conv_primary(p):
    """A compass primary -> (centre label, centre binding dict or None)."""
    c = conv_spec_string(p)
    if isinstance(c, dict):
        return c.get("label", ""), c
    return (c or ""), None


def conv_compass(k):
    out = {"type": "flick"}
    flick = {}
    char, center_binding = conv_primary(k.get("primary", ""))
    out["char"] = char
    if center_binding is not None:
        flick["center"] = center_binding
    for d in DIRS:
        if d in k:
            cv = conv_position(k[d])
            if cv is not None and cv != "":
                flick[d] = cv
    if flick:
        out["flick"] = flick
    return out


def conv_cluster(k):
    out = {"type": "flick"}
    flick = {}
    main = k.get("main", "")
    if len(main) >= 3:
        out["char"], flick["left"], flick["right"] = main[1], main[0], main[2]
    elif len(main) == 2:
        out["char"], flick["right"] = main[0], main[1]
    else:
        out["char"] = main
    for d in DIRS:
        if d in k and d not in flick:
            cv = conv_position(k[d])
            if cv is not None and cv != "":
                flick[d] = cv
    if flick:
        out["flick"] = flick
    return out


def conv_string_key(s):
    if s in DOLLAR:
        return {"type": "action", "action": DOLLAR[s]}
    c = conv_spec_string(s)
    if isinstance(c, str):
        return {"type": "flick", "char": c}
    # native action keys keep Urik's special handling (shift state, spacebar cursor, backspace accel)
    if c.get("action") in ("shift", "backspace", "space"):
        return {"type": "action", "action": c["action"]}
    return {"type": "flick", "char": c.get("label", ""), "flick": {"center": c}}


def conv_base(k):
    base = conv_string_key(k.get("spec", ""))
    more = k.get("moreKeys") or []
    if more:
        if base.get("type") != "flick":
            base = {"type": "flick", "char": base.get("action", ""),
                    "flick": {"center": {"action": base.get("action")}}}
        flick = base.setdefault("flick", {})
        for i, mk in enumerate(more[:8]):
            cv = conv_position(mk)
            if cv is not None and cv != "":
                flick[DIRS[i]] = cv
    return base


def conv_key(k):
    if isinstance(k, str):
        return conv_string_key(k)
    t = k.get("type")
    if t == "compass":
        return conv_compass(k)
    if t == "case":
        return conv_compass(k["normal"])  # shiftedManually deferred
    if t == "cluster":
        return conv_cluster(k)
    if t == "macro":
        return {"type": "flick", "char": k.get("text", "")}
    if t == "base":
        return conv_base(k)
    if t == "chord":
        return {"type": "flick", "char": k.get("label", k["keys"]),
                "flick": {"center": {"chord": k["keys"], "label": k.get("label", k["keys"])}}}
    return None


def conv_row(row):
    keys = row.get("letters") or row.get("bottom") or []
    out = []
    for k in keys:
        ck = conv_key(k)
        if ck is not None:
            out.append(ck)
    return out


def conv_page(page):
    return {"rows": [conv_row(r) for r in page]}


def main():
    in_path, out_path = sys.argv[1], sys.argv[2]
    data = yaml.safe_load(open(in_path, encoding="utf-8"))
    modes = {"letters": {"rows": [conv_row(r) for r in data["rows"]]}}
    alt = data.get("altPages", [])
    if len(alt) >= 1:
        modes["numbers"] = conv_page(alt[0])
    if len(alt) >= 2:
        modes["symbols"] = conv_page(alt[1])
    out = {"locale": "gnu", "script": "Latn", "isRTL": False,
           "showFlickHints": True, "modes": modes}
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
        f.write("\n")
    rows = sum(len(m["rows"]) for m in modes.values())
    keys = sum(len(r) for m in modes.values() for r in m["rows"])
    print(f"wrote {out_path}: {len(modes)} modes, {rows} rows, {keys} keys")


if __name__ == "__main__":
    main()
