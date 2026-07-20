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
# All bundled Latin-script languages (en is the reference, so excluded; non-Latin ar/bg/el/fa/ja/ru/uk
# can't be polluted by Latin English words via their cluster bands). hunspell accepts comma-separated dicts,
# so es/pt use a union of their major variants to avoid removing valid regional spellings.
LANGS=(
  "ca:ca_ES" "cs:cs_CZ" "de:de_DE" "es:es_ES,es_MX" "fr:fr_FR" "it:it_IT"
  "nl:nl_NL" "pl:pl_PL" "pt:pt_BR,pt_PT" "sk:sk_SK" "sv:sv_SE"
)

# 1) dump every dict's words to /tmp/urikdump/<lang>.words
JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64} ANDROID_HOME=${ANDROID_HOME:-$HOME/android-sdk} \
  ./gradlew :app:testDebugUnitTest --tests '*DictDumpTool' -Durik.dump=1 --console=plain < /dev/null

for entry in "${LANGS[@]}"; do
  lang="${entry%%:*}"; dic="${entry##*:}"
  src="/tmp/urikdump/${lang}.words"
  [ -f "$src" ] || { echo "missing $src — did the dump run?"; exit 1; }
  cut -f1 "$src" > "/tmp/urikdump/${lang}.list"
  # -i utf-8: the .urik dump is UTF-8 while some hunspell dicts are ISO-8859-*; tell hunspell the input
  # encoding so words are decoded correctly (otherwise it guesses the dict's encoding and mis-reads them).
  hunspell -d "$dic" -i utf-8 -l < "/tmp/urikdump/${lang}.list" > "/tmp/urikdump/${lang}.rejected"
  hunspell -d "$REF" -i utf-8 -l < "/tmp/urikdump/${lang}.rejected" > "/tmp/urikdump/${lang}.refrej"
  # removed = rejected-by-L  AND  accepted-by-REF  (= rejected-by-L minus also-rejected-by-REF)
  comm -23 <(sort -u "/tmp/urikdump/${lang}.rejected") <(sort -u "/tmp/urikdump/${lang}.refrej") \
    | awk 'NF' | sort -u > "app/src/main/assets/dictionaries/${lang}.removed"
  # Merge the hand-curated list (tools/removed_manual/<lang>.txt) — pollution the heuristic can't
  # catch: names hunspell-L itself accepts (Danny) or junk both spellers reject (donno), which
  # can't be automated without also nuking valid colloquials (dobrej) and real words (davy, dogy).
  manual="tools/removed_manual/${lang}.txt"
  if [ -f "$manual" ]; then
    sort -u -o "app/src/main/assets/dictionaries/${lang}.removed" \
      "app/src/main/assets/dictionaries/${lang}.removed" <(grep -v '^#' "$manual" | awk 'NF')
  fi
  echo "${lang}.removed: $(wc -l < "app/src/main/assets/dictionaries/${lang}.removed") words"
done
