#!/usr/bin/env python3
"""Inject the authorized long-press extra-key strips (tools/xk_strips.json) onto the COMPASS keys of the
on-screen GNU layouts (GNU 13c, GNU 15c), matched by each key's center char.

The strips were curated by merging the `XK:` sets across 白い熊's Multiling O Keyboard layouts (see the
session that produced tools/xk_strips.json). Each compass key whose `char` is a letter with a strip gets a
`longPressKeys` array (own-case-first order); the cluster punctuation keys and chord/action keys are left
alone. Idempotent and re-runnable: it also clears a stale strip from a key that no longer matches.
"""
import json, os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
STRIPS = json.load(open(os.path.join(ROOT, 'tools', 'xk_strips.json'), encoding='utf-8'))
TARGETS = ['gnu_5r13c', 'gnu_5r15c']

for name in TARGETS:
    path = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'layouts', name + '.json')
    d = json.load(open(path, encoding='utf-8'))
    injected = cleared = 0
    hits = []
    for mode in d.get('modes', {}).values():
        for row in mode.get('rows', []):
            for k in row:
                if k.get('type') != 'compass':
                    continue
                c = k.get('char', '')
                entry = STRIPS.get(c.lower()) if (len(c) == 1 and c.isalpha()) else None
                strip = entry['upper' if c.isupper() else 'lower'] if entry else None
                if strip:
                    if k.get('longPressKeys') != strip:
                        injected += 1
                    k['longPressKeys'] = strip
                    hits.append(c)
                elif 'longPressKeys' in k:
                    del k['longPressKeys']
                    cleared += 1
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(d, f, ensure_ascii=False, indent=2)
        f.write('\n')
    print('%-12s set=%2d cleared=%d  keys=%s' % (name, injected, cleared, ' '.join(sorted(set(hits)))))
