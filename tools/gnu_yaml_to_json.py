#!/usr/bin/env python3
"""Convert a FUTO-style (futokxkb v2) compass-keyboard YAML into a shiroikuma-kxkb (Urik) layout JSON.

The YAML is 白い熊's own layout *design data*; this tool translates the arrangement into our extended
flick JSON, the exact shape `KeyboardJsonCodec.parseKey()` accepts. It does NOT copy any FUTO/AOSP
engine source — this is a pure data translation.

WHAT IT EMITS (per-key `type`s the codec understands):
  - "character"            plain glyph keys (alt pages, plain letters)
  - "action"               native Urik action keys ($shift/$space/$delete, key_shift/key_space/…)
  - "spacer"               an empty slot (futokxkb `gap`)
  - "compass" | "cluster"  a directional / horizontal-band FlickKey (`flick:{8 dirs + center}`)
  - "column"               a vertical-band FlickKey (band ends ride up/down — authored only if non-end)
  - "case"                 a multi-state key: {normal, shifted, shiftedManually, shiftLocked, symbols,
                           symbolsShifted}, each branch a full key object (recursed)
  - "macro"                commits its `text` literally on tap
  - "chord"                fires a modifier chord {keys, label} on tap
  - "cycle"                steps through {taps} on repeated taps

Per key, futokxkb stows BOTH visual and behavioural settings under one `attributes:` map. We SPLIT:
  - visual  (color/fontScale/hintScale/backgroundColor/borderColor/*Offset) -> our `appearance` block
  - behaviour (width/style/moreKeyMode/heightRows/showPopup/longPressEnabled/repeatableEnabled/
               anchored/useKeySpecShortcut/shiftable/fastMoreKeys) -> our `attributes` block
The futokxkb named `width:` token becomes `attributes.widthClass` (lossless) AND is resolved to a
numeric per-key `width` in cells (the runtime width driver) using the layout's `overrideWidths` map.

Usage: python3 tools/gnu_yaml_to_json.py "<input.yaml>" <out.json> [locale] [script]
"""
import os
import sys

import yaml

try:
    import json
except ImportError:  # pragma: no cover
    json = None

DIRS = ["up", "down", "left", "right", "upLeft", "upRight", "downLeft", "downRight"]

# !code/<X> -> our generic editor action name (resolved at runtime in handleGnuAction).
CODE_ACTION = {
    "key_escape": "escape", "key_tab": "tab", "key_enter": "enter", "key_space": "space",
    "key_ctrl": "ctrl", "action_undo": "undo", "action_redo": "redo",
    "action_hide_keyboard": "hide", "action_voice_input": "voice",
    "action_up": "arrow_up", "action_down": "arrow_down",
    "action_left": "arrow_left", "action_right": "arrow_right",
    "action_next_language_layout": "next_language",
    "action_cut": "cut", "action_copy": "copy", "action_paste": "paste",
    "action_select_all": "select_all",
    "key_settings": "settings", "action_settings": "settings",
}
# !code/<X> -> a layer/page target (mapped to a keyboard mode at runtime).
CODE_LAYER = {
    "key_to_alt_0_layout": "alt0", "key_to_alt_1_layout": "alt1",
    "key_to_alpha_0_layout": "alpha0", "key_to_alpha_1_layout": "alpha1",
}
# Cells a cluster key spans in a cluster-FAMILY layout (en/cs/ru "Cluster" boards), set per run from argv.
# 1.0 (the default) means cluster keys are ordinary one-cell keys — so a stray cluster key in a compass or
# column layout (e.g. GNU's "." cluster) is NOT widened. Wider widths are always an explicit per-key spec.
CLUSTER_CELLS = 1.0

# !icon/<X> -> a text glyph we can render (Urik lacks most of these drawables).
ICON_LABEL = {
    "space_key": "␣", "tab_key": "⇥", "enter_key": "⏎", "settings_key": "⚙",
    "shift_key": "⇧", "delete_key": "⌫",
    "action_undo": "↶", "action_redo": "↷", "action_hide_keyboard": "⌄",
    "action_voice_input": "🎙", "action_up": "↑", "action_down": "↓",
    "action_left": "←", "action_right": "→",
    "action_cut": "✂", "action_copy": "⧉", "action_paste": "⎘",
    "action_select_all": "全",
}
# $X dollar shortcut -> a native Urik action key.
DOLLAR = {"$shift": "shift", "$delete": "backspace", "$space": "space"}
# !code/<X> -> a native Urik action key (keeps shift-state / spacebar-slide / backspace-accel / Tab etc.)
CODE_NATIVE = {
    "key_shift": "shift", "key_delete": "backspace", "key_space": "space",
    "key_enter": "enter", "key_tab": "tab", "key_language_switch": "language_switch",
    "key_settings": "settings",
}
# The action names emitted as a native {"type":"action"} key (others ride a flick centre binding).
NATIVE_ACTIONS = set(CODE_NATIVE.values())

# File language code (kxkb_<code>_...) -> Urik layout locale + script.
LOCALE_MAP = {"cz": "cs", "en": "en", "ru": "ru", "ja": "ja", "gnu": "gnu"}
SCRIPT_MAP = {"cs": "Latn", "en": "Latn", "gnu": "Latn", "ru": "Cyrl", "ja": "Hira"}

# Visual vs behavioural keys inside a futokxkb per-key `attributes:` map.
APPEARANCE_KEYS = {
    "color", "fontScale", "hintScale", "backgroundColor", "borderColor",
    "labelOffsetX", "labelOffsetY", "clusterLeftOffset", "clusterRightOffset",
    "flickTopOffset", "flickBottomOffset", "flickLeftOffset", "flickRightOffset",
}
ATTRIBUTE_KEYS = {
    "style", "moreKeyMode", "heightRows", "rowSpan", "showPopup", "longPressEnabled",
    "repeatableEnabled", "anchored", "useKeySpecShortcut", "shiftable", "fastMoreKeys",
}

# Named width tokens whose cell count is a constant (independent of overrideWidths fractions).
# Regular = one cell. Grow/FunctionalKey are handled specially (fill / spacer-sized).
NAMED_CONST_CELLS = {"Regular": 1.0}

# Per-locale spacebar cell count (the bottom-row $space / key_space weight). gnu's wider compass
# bars want a 3-cell space; every other locale (en/cs/ru/ja) gets a 2-cell space. This OVERRIDES
# whatever Grow/overrideWidths sizing the row would otherwise give the spacebar (surrounding keys
# stay 1 cell). Applied as a fixed weight in the bottom-row width step.
SPACE_CELLS = {"gnu": 3.0}
SPACE_CELLS_DEFAULT = 2.0


# ---------------------------------------------------------------------------
# Spec parsing ( "label|!code/Y", "!icon/X|!code/Y", "!icon/X", plain char )
# ---------------------------------------------------------------------------
def split_spec(s):
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
    """A YAML string (plain char or a spec) -> plain text, or a binding dict for a flick position."""
    if not isinstance(s, str):
        return None
    if "!code/" in s or s.startswith("!icon/"):
        label, code = split_spec(s)
        if code in CODE_NATIVE:
            return {"action": CODE_NATIVE[code], "label": label}
        if code in CODE_LAYER:
            return {"layer": CODE_LAYER[code], "label": label}
        if code in CODE_ACTION:
            return {"action": CODE_ACTION[code], "label": label}
        if code:
            return {"action": code, "label": label}
        return label  # icon-only, no code: render the glyph as text
    return s


def conv_position(v):
    """A flick-direction value -> a string (text), a binding dict, or None to omit."""
    if isinstance(v, str):
        return conv_spec_string(v)
    if isinstance(v, dict):
        t = v.get("type")
        if "macro" in v and t is None:
            # futokxkb inline `{ macro: "1ˢᵗ" }` shorthand on a slide -> commit that literal text.
            return v["macro"]
        if t == "macro":
            return v.get("text")
        if t == "chord":
            return {"chord": v["keys"], "label": v.get("label", v["keys"])}
        if t == "compass":
            return conv_primary(v.get("primary", ""))[0]
        if "chord" in v:
            return {"chord": v["chord"], "label": v.get("label", v["chord"])}
    return None


def conv_primary(p):
    """A compass primary -> (centre label, centre binding dict or None)."""
    c = conv_spec_string(p)
    if isinstance(c, dict):
        return c.get("label", ""), c
    return (c or ""), None


# ---------------------------------------------------------------------------
# appearance / attributes split
# ---------------------------------------------------------------------------
def split_attributes(attrs):
    """A futokxkb `attributes:` map -> (appearance dict|None, attributes dict|None, width token|None)."""
    if not isinstance(attrs, dict):
        return None, None, None
    appearance = {}
    attributes = {}
    width_token = None
    for k, v in attrs.items():
        if k == "width":
            width_token = v
            attributes["widthClass"] = v
        elif k in APPEARANCE_KEYS:
            appearance[k] = v
        elif k in ATTRIBUTE_KEYS:
            attributes[k] = v
        else:
            # Unknown -> keep it under attributes so nothing is silently dropped.
            attributes[k] = v
    return (appearance or None), (attributes or None), width_token


def apply_blocks(out, appearance, attributes):
    if appearance:
        out["appearance"] = appearance
    if attributes:
        out["attributes"] = attributes


# ---------------------------------------------------------------------------
# Key-type converters (each returns a JSON key dict, sans width resolution)
# ---------------------------------------------------------------------------
def conv_compass(k):
    out = {"type": "compass"}
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
    """A horizontal cluster band. The whole `main` string rides the centre; neighbours of the centre
    glyph fall on left/right; authored slides win over those derived neighbours."""
    out = {"type": "cluster"}
    flick = {}
    main = k.get("main", "")
    if main:
        out["cluster"] = main
        center = len(main) // 2
        out["char"] = main[center]
        if center - 1 >= 0:
            flick["left"] = main[center - 1]
        if center + 1 < len(main):
            flick["right"] = main[center + 1]
    else:
        out["char"] = main
    for d in DIRS:
        if d in k:
            cv = conv_position(k[d])
            if cv is not None and cv != "":
                flick[d] = cv  # authored slide overrides a derived neighbour
    if flick:
        out["flick"] = flick
    return out


def conv_column(k):
    """A vertical column band. The band ends ride up/down (the codec derives them), so author up/down
    only when the YAML gives an explicit non-end value; left/right/diagonals pass through."""
    out = {"type": "column"}
    flick = {}
    main = k.get("main", "")
    if main:
        out["main"] = main
        out["char"] = main[len(main) // 2]
    else:
        out["char"] = main
    for d in DIRS:
        if d in k:
            cv = conv_position(k[d])
            if cv is not None and cv != "":
                flick[d] = cv
    if flick:
        out["flick"] = flick
    return out


def conv_macro(k):
    out = {"type": "macro", "text": k.get("text", "")}
    return out


def conv_chord(k):
    return {"type": "chord", "keys": k["keys"], "label": k.get("label", k["keys"])}


def conv_cycle(k):
    taps = k.get("taps", "")
    if isinstance(taps, str):
        taps_list = list(taps)
    else:
        taps_list = list(taps)
    return {"type": "cycle", "taps": taps_list, "label": k.get("label", taps_list[0] if taps_list else "")}


def conv_string_key(s):
    """A bare YAML string in a row -> the right key dict."""
    if s in DOLLAR:
        action = DOLLAR[s]
        out = {"type": "action", "action": action}
        if action == "space":
            out["attributes"] = {"style": "Spacebar"}
        return out
    c = conv_spec_string(s)
    if isinstance(c, str):
        # A plain glyph in a letter row is a flat compass key (consistent with the source's compass grid).
        return {"type": "compass", "char": c}
    # A spec dict: native action key, layer/page switch, or a generic action -> flick centre.
    if c.get("action") in NATIVE_ACTIONS:
        return {"type": "action", "action": c["action"]}
    return {"type": "compass", "char": c.get("label", ""), "flick": {"center": c}}


def conv_base(k):
    """A futokxkb `base` key: a functional/spec key whose moreKeys (if any) become its flick directions."""
    base = conv_string_key(k.get("spec", ""))
    more = k.get("moreKeys") or []
    if more:
        if base.get("type") == "action":
            # An action key with explicit more-keys: keep it an action but hang the extras as a popup is
            # not modelled; carry them as flick slides on a compass instead (centre = the action binding).
            label, code = split_spec(k.get("spec", ""))
            binding = conv_spec_string(k.get("spec", ""))
            base = {"type": "compass", "char": label,
                    "flick": {"center": binding} if isinstance(binding, dict) else {}}
        flick = base.setdefault("flick", {})
        for i, mk in enumerate(more[:8]):
            cv = conv_position(mk)
            if cv is not None and cv != "":
                flick[DIRS[i]] = cv
    return base


def conv_key(k):
    """Convert one YAML key (string or dict) to a JSON key dict, recursing through `case` branches.
    Returns (key_dict, width_token) — width_token is the futokxkb named width to resolve to cells."""
    if isinstance(k, str):
        return conv_string_key(k), None

    if not isinstance(k, dict):
        return None, None

    t = k.get("type")
    out = None
    if t == "gap":
        return {"type": "spacer"}, None
    elif t == "compass":
        out = conv_compass(k)
    elif t == "cluster":
        out = conv_cluster(k)
    elif t == "column":
        out = conv_column(k)
    elif t == "macro":
        out = conv_macro(k)
    elif t == "chord":
        out = conv_chord(k)
    elif t == "cycle":
        out = conv_cycle(k)
    elif t == "base":
        out = conv_base(k)
    elif t == "case":
        return conv_case(k)
    else:
        return None, None

    appearance, attributes, width_token = split_attributes(k.get("attributes"))
    apply_blocks(out, appearance, attributes)

    # Change 1: a column key must always be as tall as its band. heightRows = max(authored, len(main)),
    # so the "compact" column layouts get keys as tall as the wide ones (whose YAML already sets it).
    if out.get("type") == "column":
        main = out.get("main", "")
        if main:
            attrs = out.setdefault("attributes", {})
            try:
                authored = int(attrs.get("heightRows", 1) or 1)
            except (TypeError, ValueError):
                authored = 1
            attrs["heightRows"] = max(authored, len(main))

    # Width is NOT special-cased by key type: every key defaults to one cell, and a wider width is an
    # explicit per-key specifier in the JSON (set from the YAML width token, or stamped onto cluster keys
    # for the cluster-family layouts via CLUSTER_CELLS below). A cluster key in a compass/column layout
    # (e.g. GNU's "." cluster) therefore stays a normal one-cell key.
    if CLUSTER_CELLS != 1.0 and out.get("type") == "cluster":
        out["width"] = CLUSTER_CELLS

    return out, width_token


# The shift-state branch names of a futokxkb `case` key, in canonical order.
CASE_BRANCHES = ["normal", "shifted", "shiftedManually", "shiftLocked", "symbols", "symbolsShifted"]


def conv_case(k):
    """A futokxkb CaseSelector -> a multi-state `case` key with every branch the YAML provides."""
    out = {"type": "case"}
    width_token = None
    for branch in CASE_BRANCHES:
        if branch not in k or k[branch] is None:
            continue
        bv = k[branch]
        if isinstance(bv, dict) and "type" in bv:
            bk, bw = conv_key(bv)
        elif isinstance(bv, dict):
            # A bare compass-shaped dict (no `type`) — treat as a compass face.
            bk, bw = conv_compass(bv), None
        else:
            bk, bw = conv_string_key(bv), None
        if bk is None:
            continue
        out[branch] = bk
        if branch == "normal":
            width_token = bw or width_token
        elif width_token is None:
            width_token = bw
    if "normal" not in out:
        return None, None
    # A `case`'s own attributes (rare) split like any key's.
    appearance, attributes, own_width = split_attributes(k.get("attributes"))
    apply_blocks(out, appearance, attributes)
    if own_width:
        width_token = own_width
    return out, width_token


# ---------------------------------------------------------------------------
# Row conversion + per-row width resolution (overrideWidths -> numeric cells)
# ---------------------------------------------------------------------------
def is_space_action(key):
    return isinstance(key, dict) and key.get("type") == "action" and key.get("action") == "space"


def width_token_of(key, explicit_token):
    """The named width token governing a key: the explicit one from its attributes, else the row default."""
    if explicit_token:
        return explicit_token
    # A key may carry widthClass via its own (already-split) attributes.
    attrs = key.get("attributes") if isinstance(key, dict) else None
    if attrs and attrs.get("widthClass"):
        return attrs["widthClass"]
    return None


def resolve_row_widths(keys, tokens, override_widths, row_attr_token, locale):
    """Give every key in a row an explicit numeric `width` (cells) so the row fills proportionally.

    Strategy: express each key's fraction-of-keyboard-width, scale the whole row so the smallest unit
    is ~1 cell, then round. Named tokens resolve via `overrideWidths` (Custom1–4 are fractions of the
    keyboard width); Regular = the base 1-cell key; an un-fractioned spacebar (futokxkb Grow) fills the
    row's leftover cells. We only stamp widths when the row actually needs them (a Custom token or a
    spacebar present), so uniform letter rows keep the renderer's clean defaults.

    The spacebar is special-cased: it always gets a fixed per-locale cell count (`SPACE_CELLS`),
    independent of the Grow/overrideWidths math, so every layout in a locale has the same space size.
    """
    n = len(keys)
    if n == 0:
        return

    eff_tokens = []
    has_custom = False
    space_idx = []
    for i, (key, tok) in enumerate(zip(keys, tokens)):
        t = width_token_of(key, tok) or row_attr_token
        eff_tokens.append(t)
        if t and isinstance(t, str) and t.startswith("Custom"):
            has_custom = True
        if is_space_action(key):
            space_idx.append(i)

    space_cells = SPACE_CELLS.get(locale, SPACE_CELLS_DEFAULT)

    # Uniform row with no custom widths and no spacebar -> let the renderer size it (return early).
    if not has_custom and not space_idx:
        return

    # Resolve each key's fraction of the keyboard width (None = base/Regular cell, filled below).
    fractions = []
    for tok in eff_tokens:
        if tok and tok in override_widths:
            fractions.append(float(override_widths[tok]))
        else:
            fractions.append(None)  # Regular / unsized

    # If a spacebar has no explicit fraction, mark it Grow so it fills the remainder (not a 1-cell key).
    grow_space = [i for i in space_idx if fractions[i] is None]

    # The base (Regular) fraction: 1 cell. Express it as a fraction so it sits on the same scale as the
    # Custom fractions — use the smallest Custom fraction present as the "1 cell" unit, else 1/n.
    custom_fracs = [f for f in fractions if f is not None]
    unit = min(custom_fracs) if custom_fracs else (1.0 / n)
    for i, f in enumerate(fractions):
        if f is None and i not in grow_space:
            fractions[i] = unit

    # Grow spacebars fill whatever fraction is left (so the bar fills to 1.0 like futokxkb). When the
    # row carries Custom fractions there's real slack to fill; with no overrideWidths there's none, so a
    # Grow space falls back to 2 base cells (futokxkb's "eight 1-cell keys + one 2-cell $space" bottom).
    if grow_space:
        used = sum(f for i, f in enumerate(fractions) if i not in grow_space)
        slack = max(0.0, 1.0 - used)
        each = slack / len(grow_space) if slack > 0 else 0.0
        for i in grow_space:
            fractions[i] = each if each > unit * 1.5 else 2.0 * unit

    # Normalise so the smallest fraction maps to ~1 cell, then round to 4 dp.
    smallest = min(f for f in fractions if f and f > 0) or unit
    for i, (key, frac) in enumerate(zip(keys, fractions)):
        cells = round((frac or smallest) / smallest, 4)
        if isinstance(key, dict):
            # A cluster key stamped with an explicit width (the cluster-family layouts) keeps it.
            if key.get("type") == "cluster" and key.get("width"):
                continue
            # The spacebar always gets the fixed per-locale cell count (overrides the proportional
            # math), so e.g. every gnu space is 3.0 and every en/cs/ru/ja space is 2.0.
            key["width"] = space_cells if i in space_idx else cells


def conv_row(row, override_widths, locale):
    """A futokxkb row dict ({letters|numbers|bottom: [...], attributes: {...}}) -> a JSON row (key list)."""
    keys_src = row.get("letters")
    if keys_src is None:
        keys_src = row.get("numbers")
    if keys_src is None:
        keys_src = row.get("bottom")
    keys_src = keys_src or []

    # A row-level `attributes:` applies to every key in the row (key-level wins).
    row_app, row_attr, row_width_token = split_attributes(row.get("attributes"))

    out_keys = []
    tokens = []
    for k in keys_src:
        ck, wtok = conv_key(k)
        if ck is None:
            continue
        # Merge the row-level appearance/attributes UNDER the key's own (key wins).
        if row_app:
            merged = dict(row_app)
            merged.update(ck.get("appearance") or {})
            ck["appearance"] = merged
        if row_attr:
            merged = dict(row_attr)
            merged.update(ck.get("attributes") or {})
            ck["attributes"] = merged
        out_keys.append(ck)
        tokens.append(wtok)

    resolve_row_widths(out_keys, tokens, override_widths, row_width_token, locale)
    return out_keys


def conv_page(page, override_widths, locale):
    return {"rows": [conv_row(r, override_widths, locale) for r in page]}


# ---------------------------------------------------------------------------
# Locale / driver
# ---------------------------------------------------------------------------
def derive_locale(in_path):
    """kxkb_<code>_... or `kxkb <code> ...` filename -> (locale, script)."""
    base = os.path.basename(in_path)
    stem = base.replace(".yaml", "")
    # Tokens split on space or underscore; the first non-"kxkb" token is the language code, except for
    # the 日本語 files whose code is a CJK word -> detect 'ja' from the filename instead.
    lower = stem.lower()
    if "日本語" in stem or "_ja_" in lower or lower.startswith("kxkb_ja") or " ja " in lower:
        code = "ja"
    else:
        parts = base.replace(" ", "_").split("_")
        code = parts[1] if len(parts) > 1 else "en"
    locale = LOCALE_MAP.get(code, code)
    return locale, SCRIPT_MAP.get(locale, "Latn")


def main():
    if len(sys.argv) < 3:
        print("usage: gnu_yaml_to_json.py <input.yaml> <out.json> [locale] [script]", file=sys.stderr)
        sys.exit(2)
    global CLUSTER_CELLS
    in_path, out_path = sys.argv[1], sys.argv[2]
    locale, script = derive_locale(in_path)
    if len(sys.argv) > 3:
        locale = sys.argv[3]
    if len(sys.argv) > 4:
        script = sys.argv[4]
    if len(sys.argv) > 5:
        CLUSTER_CELLS = float(sys.argv[5])

    data = yaml.safe_load(open(in_path, encoding="utf-8"))
    override_widths = data.get("overrideWidths", {}) or {}

    modes = {"letters": {"rows": [conv_row(r, override_widths, locale) for r in data["rows"]]}}
    alt = data.get("altPages", []) or []
    if len(alt) >= 1:
        modes["numbers"] = conv_page(alt[0], override_widths, locale)
    if len(alt) >= 2:
        modes["symbols"] = conv_page(alt[1], override_widths, locale)
    if len(alt) >= 3:
        modes["symbols_secondary"] = conv_page(alt[2], override_widths, locale)

    out = {
        "locale": locale,
        "script": script,
        "isRTL": False,
        "showFlickHints": True,
        "modes": modes,
    }
    # Carry through layout-level metadata (preserved even where the runtime ignores it).
    if "numberRowMode" in data:
        out["numberRowMode"] = data["numberRowMode"]
    if override_widths:
        out["overrideWidths"] = override_widths
    if "topBar" in data:
        out["topBar"] = data["topBar"]
    if "name" in data:
        out["name"] = data["name"]

    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
        f.write("\n")

    rows = sum(len(m["rows"]) for m in modes.values())
    keys = sum(len(r) for m in modes.values() for r in m["rows"])
    print(f"wrote {out_path}: locale={locale} script={script} "
          f"{len(modes)} modes, {rows} rows, {keys} keys")


if __name__ == "__main__":
    main()
