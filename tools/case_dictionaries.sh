#!/usr/bin/env bash
# Generate per-language PROPER-CASING lists for the bundled .urik dictionaries.
#
# The FrequencyWords-derived dictionaries carry no case at all — every entry is lowercase, so every proper
# noun and nationality adjective ("english", "czech", "monday", "london", "john") is offered lowercase.
# This script derives the words that must be shown capitalised and writes them, one cased surface per line,
# to app/src/main/assets/dictionaries/<lang>.cased. SpellCheckManager loads the file next to the dictionary
# (loadCasedWords) and SuggestionPipeline swaps a candidate for its cased surface (preserve-case) at the
# bar funnel, so the bar shows and commits "Czech" for a dictionary "czech".
#
# Heuristic (hunspell): a dictionary word that hunspell REJECTS lowercase but ACCEPTS capitalised is a
# proper noun — hunspell accepts a sentence-start capital of any common word, so only words that exist
# ONLY capitalised pass. Two hand lists refine it (tools/cased_manual/<lang>.add / <lang>.exclude):
#   .add     — words hunspell knows in BOTH cases where the capitalised sense is the one people type
#              ("english" is a billiards term, "french" a verb, "john" a toilet, "china" porcelain).
#   .exclude — heuristic hits that must stay lowercase ("ur" the city vs the texting "ur").
#
# Only English is generated. The same heuristic is UNUSABLE for Czech as-is: hunspell cs_CZ carries some
# 100 000 surnames, so every colloquial spelling collides with one (teda→Teda, mlíko→Mlíko, todle→Todle,
# budem, vidim, páč) — a Czech list needs a curated subset (place names / nationality nouns / holidays by
# hunspell flag class), see the notes in docs/PLAN.md.
#
# Prereqs: hunspell + hunspell-en-us. Run from the repo root; reuses the DictDumpTool dump.
set -euo pipefail
cd "$(dirname "$0")/.."

LANGS=("en:en_US")

DUMP=/tmp/urikdump
if [ ! -f "$DUMP/en.words" ]; then
  JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64} ANDROID_HOME=${ANDROID_HOME:-$HOME/android-sdk} \
    ./gradlew :app:testDebugUnitTest --tests '*DictDumpTool' -Durik.dump=1 --console=plain < /dev/null
fi

for entry in "${LANGS[@]}"; do
  lang="${entry%%:*}"; dic="${entry##*:}"
  src="$DUMP/${lang}.words"
  [ -f "$src" ] || { echo "missing $src — did the dump run?"; exit 1; }
  cut -f1 "$src" > "$DUMP/${lang}.list"
  # 1) words hunspell rejects as typed (lowercase)
  hunspell -d "$dic" -i utf-8 -l < "$DUMP/${lang}.list" | sort -u > "$DUMP/${lang}.rej"
  # 2) the same words capitalised; those hunspell now ACCEPTS are the proper nouns
  python3 - "$DUMP/${lang}.rej" "$DUMP/${lang}.cap" <<'PY'
import sys
src, dst = sys.argv[1], sys.argv[2]
with open(src, encoding='utf-8') as f, open(dst, 'w', encoding='utf-8') as o:
    for w in f:
        w = w.strip()
        if w:
            o.write(w[:1].upper() + w[1:] + '\n')
PY
  hunspell -d "$dic" -i utf-8 -l < "$DUMP/${lang}.cap" | sort -u > "$DUMP/${lang}.caprej"
  out="app/src/main/assets/dictionaries/${lang}.cased"
  python3 - "$DUMP/${lang}.rej" "$DUMP/${lang}.caprej" "tools/cased_manual/${lang}.add" \
    "tools/cased_manual/${lang}.exclude" "$src" "$out" <<'PY'
import sys
rej_path, caprej_path, add_path, excl_path, words_path, out_path = sys.argv[1:7]
def lines(path):
    try:
        with open(path, encoding='utf-8') as f:
            return [l.strip() for l in f if l.strip() and not l.startswith('#')]
    except FileNotFoundError:
        return []
in_dict = set()
with open(words_path, encoding='utf-8') as f:
    for l in f:
        in_dict.add(l.split('\t')[0])
caprej = set(lines(caprej_path))
exclude = {w.lower() for w in lines(excl_path)}
cased = {}
for w in lines(rej_path):
    c = w[:1].upper() + w[1:]
    if c != w and c not in caprej and w.lower() not in exclude:
        cased[w.lower()] = c
for c in lines(add_path):
    if c.lower() in in_dict:
        cased[c.lower()] = c
    else:
        print(f"  add-list word not in the dictionary, skipped: {c}")
with open(out_path, 'w', encoding='utf-8') as o:
    for k in sorted(cased):
        o.write(cased[k] + '\n')
print(f"{out_path}: {len(cased)} words")
PY
done
