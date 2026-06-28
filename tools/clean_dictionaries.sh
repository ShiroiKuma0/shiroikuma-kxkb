#!/usr/bin/env bash
# Regenerate per-language "removed" lists that strip cross-language pollution from the bundled
# FrequencyWords-derived .urik dictionaries (e.g. English "bad/bed/bag" inside the Czech dictionary).
#
# A word is removed for language L iff hunspell-L REJECTS it AND a reference language (English) ACCEPTS it —
# i.e. it is a recognised foreign word, not a Czech name/neologism/inflection hunspell merely doesn't know.
# This keeps legitimate loanwords (sport, hotel, start, taxi…) which hunspell-L accepts.
#
# Output: app/src/main/assets/dictionaries/<lang>.removed (lowercase, one word per line). The dictionary
# loader (SpellCheckManager.loadRemovedWords) auto-loads it and passes it to UrikDictionary as removedWords.
#
# Prereqs: hunspell + the per-language hunspell dict (e.g. `sudo apt-get install hunspell hunspell-cs`),
# and the en_US dict (reference). Run from the repo root.
set -euo pipefail
cd "$(dirname "$0")/.."

REF=en_US                       # reference language whose acceptance marks a word as "foreign"
LANGS=("cs:cs_CZ")              # add more as "asset_lang:hunspell_dict", e.g. "de:de_DE" "pl:pl_PL"

# 1) dump every dict's words to /tmp/urikdump/<lang>.words
JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64} ANDROID_HOME=${ANDROID_HOME:-$HOME/android-sdk} \
  ./gradlew :app:testDebugUnitTest --tests '*DictDumpTool' -Durik.dump=1 --console=plain < /dev/null

for entry in "${LANGS[@]}"; do
  lang="${entry%%:*}"; dic="${entry##*:}"
  src="/tmp/urikdump/${lang}.words"
  [ -f "$src" ] || { echo "missing $src — did the dump run?"; exit 1; }
  cut -f1 "$src" > "/tmp/urikdump/${lang}.list"
  hunspell -d "$dic" -l < "/tmp/urikdump/${lang}.list" > "/tmp/urikdump/${lang}.rejected"
  hunspell -d "$REF" -l < "/tmp/urikdump/${lang}.rejected" > "/tmp/urikdump/${lang}.refrej"
  # removed = rejected-by-L  AND  accepted-by-REF  (= rejected-by-L minus also-rejected-by-REF)
  comm -23 <(sort -u "/tmp/urikdump/${lang}.rejected") <(sort -u "/tmp/urikdump/${lang}.refrej") \
    | awk 'NF' | sort -u > "app/src/main/assets/dictionaries/${lang}.removed"
  echo "${lang}.removed: $(wc -l < "app/src/main/assets/dictionaries/${lang}.removed") words"
done
