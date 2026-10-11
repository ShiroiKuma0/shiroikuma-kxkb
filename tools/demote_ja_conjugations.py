#!/usr/bin/env python3
"""Generate the Japanese INFLECTED-FORM demotion list for the kana-kanji converter.

    app/src/main/assets/dictionaries/ja_readings.txt   ->   .../ja.demoted

Why this exists
---------------
`ja_readings.txt` is flattened from Mozc, and Mozc stores every conjugated form of a verb as its
own entry carrying the verb's cost.  In real Mozc that number is never used alone: it is a word
cost fed into a Viterbi search over the whole sentence with a POS connection matrix, and that
matrix is what stops a bare volitional from winning a one-word conversion.  Flattened into the
per-reading unigram table `KanaKanjiConverter` looks words up in, the correction is gone and the
inflections of common verbs outrank genuine nouns:

    すもう  住もう 16606   <- the volitional of 住む
    すもう  相撲   15222
    じゅうし 住し  17120   <- a bare サ変 fragment, not a word at all
    じゅうし 重視  16286
    えん    得ん   20000
    えん    円     18315

This script lists every (reading, surface) pair that is an inflected form, and the converter
subtracts a fixed penalty from those at index load (KanaKanjiConverter.INFLECTION_PENALTY), so a
noun of comparable standing wins a bare one-shot conversion while a very common inflection still
beats a rare homophone.  The penalty lives in code, not here: this file states a FACT about each
pair ("this is an inflected form"), the penalty is the policy applied to it.

How a paradigm is found
-----------------------
Mozc's POS ids are not in the flattened export, so the paradigms are re-derived from the table
itself.  Reading and surface share their okurigana (it is kana in both), so a stem is just an
entry with its trailing kana removed, and a paradigm is confirmed by its SIBLING SLOTS: 住/す is a
む-row godan verb because 住ま, 住む, 住め, 住も, 住ん are all present.  No terminal form is
required — Mozc's export is incomplete for some compounds (会いにいく has no 未然形) — but an
ANCHOR slot is: the 未然形 or the 意志形, the two slots a noun stem never has.  Without that anchor
白う + 白い + 白っ look like a godan verb "白う", which would protect 白う from demotion instead of
demoting it (it is the ウ音便 of the adjective 白い).

What is deliberately NOT demoted
--------------------------------
  * dictionary forms            読む, 食べる, 白い, 暗い, 欲しい, 住する
  * the bare 連用形 and 仮定形   productively nouns — 読み, 残し, 話し, 晴れ, 届け
  * ichidan bare stems          likewise — 眺め, 調べ, 届け
  * adjective nominalisations   白さ, 白み, 白げ
A godan 〜す verb's 連用形 is nominalisable (残し, 返し, 話し) but a サ変 verb's is a bare fragment
(住し), and the two are spelled alike — so the protection is withheld when stem+する is attested.

Orthographic variants
---------------------
Mozc spells the same inflection several ways (曲がっ / 曲っ, 見積もっ / 見積っ) and the rarer
spelling's own paradigm is too sparse to be attested, so demoting only the detected one would just
hand the lead to the worse spelling.  Any surface sharing a kana tail with its reading is the same
inflected shape and follows the demotion; a surface ending in a kanji (相撲, 調子, 円) shares no
tail and is never touched — which is exactly the noun the demotion exists to promote.

Hand-curated lists (tools/demoted_manual/)
------------------------------------------
  ja.demote — pairs to demote that the heuristic cannot reach, nearly all of them Mozc READING
              errors: 高そう filed under こうそう (高 given an on-reading, so it cannot be linked
              to the adjective 高い/たかい), 轢こう under びこう, plus the カ変 forms of 来る.
  ja.keep   — pairs the heuristic demotes that must keep their rank: lexicalised forms (向こう),
              very common adverbs (良く), and rows whose only rival is junk (う炎, な面, 家用).

Run from the repo root:  python3 tools/demote_ja_conjugations.py [--report [--verbose]]
"""
import bisect
import os
import sys
from collections import defaultdict

SRC = 'app/src/main/assets/dictionaries/ja_readings.txt'
OUT = 'app/src/main/assets/dictionaries/ja.demoted'
MANUAL_DEMOTE = 'tools/demoted_manual/ja.demote'
MANUAL_KEEP = 'tools/demoted_manual/ja.keep'

# The penalty the converter applies. Mirrored here ONLY so --report can show what the ranking will
# look like; KanaKanjiConverter.INFLECTION_PENALTY is the authority.
REPORT_PENALTY = 2000

# Godan rows: terminal kana -> (未然a, 連用i, 仮定/命令e, 意志o, 音便t).
GODAN = {
    'う': ('わ', 'い', 'え', 'お', 'っ'),
    'く': ('か', 'き', 'け', 'こ', 'い'),
    'ぐ': ('が', 'ぎ', 'げ', 'ご', 'い'),
    'す': ('さ', 'し', 'せ', 'そ', 'し'),
    'つ': ('た', 'ち', 'て', 'と', 'っ'),
    'ぬ': ('な', 'に', 'ね', 'の', 'ん'),
    'ぶ': ('ば', 'び', 'べ', 'ぼ', 'ん'),
    'む': ('ま', 'み', 'め', 'も', 'ん'),
    'る': ('ら', 'り', 'れ', 'ろ', 'っ'),
}

# An ichidan tail set, reused for the ichidan AUXILIARIES (causative 〜せる, passive 〜れる), which
# conjugate in turn: 取らせ, 取らせる, 取らせよう, 取らせりゃ.
ICHIDAN_TAILS = ['る', 'れ', 'れば', 'りゃ', 'ろ', 'よ', 'よう', 'ます', 'ませ', 'ましょ',
                 'ましょう', 'ません', 'まし', 'ました', 'ない', 'なく', 'なかっ', 'なけれ',
                 'なかろ', 'ん', 'ず', 'た', 'て', 'たら', 'たり', 'ちゃ', 'ちゃう', 'ちゃっ',
                 'たい', 'たく', 'たかっ', 'られ', 'られる', 'させ', 'させる']
A_TAILS = ['', 'ない', 'なく', 'なかっ', 'なけれ', 'なかろ', 'ず', 'ずに', 'ん', 'ぬ', 'ね', 'な',
           'なけりゃ', 'なきゃ', 'なさ', 'んで', 'んと']
E_TAILS = ['ば', 'りゃ']
O_TAILS = ['', 'う']
T_TAILS = ['', 'て', 'た', 'たら', 'たり', 'ちゃ', 'ちゃう', 'ちゃっ', 'ちゃい', 'ちゃえ', 'ちゃお',
           'ちゃおう', 'とい', 'とく', 'とけ', 'とこ', 'どい', 'どく', 'どけ', 'どこ', 'ても',
           'たって', 'でも', 'だって', 'で', 'だ', 'だら', 'だり']
I_TAILS = ['ます', 'ませ', 'ましょ', 'ましょう', 'ません', 'まし', 'ました', 'たい', 'たく',
           'たかっ', 'たけれ', 'たかろ', 'ながら', 'やすい', 'やすく', 'にくい', 'にくく', 'つつ',
           'そう', 'すぎ', 'すぎる', 'なさい']
# 〜さ / 〜み / 〜げ are nominalisations (real nouns) and are deliberately absent. So is 〜そう:
# under its own reading (たかそう) the 〜そう form has no better rival, and the cases that DO need
# demoting (こうそう 高そう) are reading errors the structure cannot reach — they are curated.
ADJ_TAILS = ['く', 'かっ', 'けれ', 'かろ', 'う', 'くっ', 'きゃ', 'ければ', 'くて', 'くない',
             'くなく', 'くなかっ', 'かった', 'うっ']
SA_TAILS = ['し', 'しよ', 'しよう', 'しろ', 'しちゃ', 'す', 'すりゃ', 'すれ', 'すれば', 'せ',
            'せよ', 'せん', 'せず', 'せる', 'せれ', 'せろ', 'させ', 'させる', 'され', 'される',
            'しない', 'しなく', 'しなかっ', 'します', 'しませ', 'しました', 'した', 'して',
            'したら', 'しても', 'しつつ', 'しながら', 'したい']
AUX = ('せ', 'れ', 'させ', 'られ')
AUX_FLAT = ('さ', 'さない', 'す', 'せ', 'しゃ', 'そ', 'そう', 'され', 'される')

I_ROW = set('いきぎしじちぢにひびぴみりゐ')
E_ROW = set('えけげせぜてでねへべぺめれゑ')
KANA = set('ぁあぃいぅうぇえぉおかがきぎくぐけげこごさざしじすずせぜそぞただちぢっつづてでとど'
           'なにぬねのはばぱひびぴふぶぷへべぺほぼぽまみむめもゃやゅゆょよらりるれろゎわゐゑをん'
           'ーゝゞ')
MIN_SLOTS = 3


def read_pairs(path):
    """A hand-curated list: `reading<TAB>surface` per line, # comments and blanks ignored."""
    out = set()
    if not os.path.exists(path):
        return out
    with open(path, encoding='utf-8') as f:
        for n, line in enumerate(f, 1):
            line = line.split('#')[0].strip()
            if not line:
                continue
            parts = line.split('\t')
            if len(parts) != 2 or not parts[0] or not parts[1]:
                sys.exit(f"{path}:{n}: expected 'reading<TAB>surface', got {line!r}")
            out.add((parts[0], parts[1]))
    return out


def main():
    by_pair = set()
    by_reading = defaultdict(list)
    with open(SRC, encoding='utf-8') as f:
        for line in f:
            if line.startswith('#') or not line.strip():
                continue
            p = line.rstrip('\n').split('\t')
            if len(p) == 3:
                by_pair.add((p[0], p[1]))
                by_reading[p[0]].append((p[1], int(p[2])))

    # Candidate stems: every entry with one or two trailing kana removed (the okurigana is shared
    # by reading and surface, so both are cut together).
    stems = set()
    for r, lst in by_reading.items():
        for s, _ in lst:
            if len(s) > 1 and len(r) > 1 and s[-1] == r[-1]:
                stems.add((r[:-1], s[:-1]))
            if len(s) > 2 and len(r) > 2 and s[-2:] == r[-2:]:
                stems.add((r[:-2], s[:-2]))

    def has(rs, ss, tail):
        return (rs + tail, ss + tail) in by_pair

    core, protect = set(), set()

    def emit(rs, ss, tail, bucket):
        pair = (rs + tail, ss + tail)
        if pair in by_pair:
            bucket.add(pair)

    counts = defaultdict(int)
    for rs, ss in stems:
        if not ss:
            continue
        sahen = has(rs, ss, 'する')

        # ---- godan
        for term, (a, i, e, o, t_) in GODAN.items():
            # Anchor: the 未然形 or 意志形 — the slots a noun stem never has.
            if not (has(rs, ss, a) or has(rs, ss, o)):
                continue
            slots = sum(1 for k in (term, a, i, e, o, t_) if has(rs, ss, k))
            # 未然形 + dictionary form is already conclusive, however sparse the rest (轢か/轢く).
            if slots < MIN_SLOTS and not (has(rs, ss, a) and has(rs, ss, term)):
                continue
            counts['godan'] += 1
            emit(rs, ss, term, protect)
            if not (term == 'す' and sahen):
                emit(rs, ss, i, protect)       # 読み, 残し, 話し — productively nouns
            emit(rs, ss, e, protect)           # 晴れ, 届け — likewise
            for x in A_TAILS:
                emit(rs, ss, a + x, core)
            for x in O_TAILS:
                emit(rs, ss, o + x, core)
            for x in T_TAILS:
                emit(rs, ss, t_ + x, core)
            for x in I_TAILS:
                emit(rs, ss, i + x, core)
            for x in E_TAILS + ICHIDAN_TAILS:  # 〜えば, and the potential 〜える
                emit(rs, ss, e + x, core)
            for aux in AUX:
                emit(rs, ss, a + aux, core)
                for x in ICHIDAN_TAILS:
                    emit(rs, ss, a + aux + x, core)
            for x in AUX_FLAT:
                emit(rs, ss, a + x, core)

        # ---- ichidan (the stem's READING must end in an i/e-row kana)
        if rs and rs[-1] in (I_ROW | E_ROW) and has(rs, ss, 'る') and \
                any(has(rs, ss, k) for k in ('よう', 'ろ', 'ない', 'ます', 'れ')) and \
                sum(1 for k in ('', 'る', 'れ', 'ろ', 'よ', 'よう', 'た', 'て', 'ない', 'ます')
                    if has(rs, ss, k)) >= MIN_SLOTS:
            counts['ichidan'] += 1
            emit(rs, ss, '', protect)          # bare stem = 連用形
            emit(rs, ss, 'る', protect)
            for x in ICHIDAN_TAILS:
                emit(rs, ss, x, core)
            for aux in AUX:
                emit(rs, ss, aux, core)
                for x in ICHIDAN_TAILS:
                    emit(rs, ss, aux + x, core)
            for x in AUX_FLAT:
                emit(rs, ss, x, core)

        # ---- adjective
        if has(rs, ss, 'い') and has(rs, ss, 'く') and \
                sum(1 for k in ('い', 'く', 'かっ', 'けれ', 'かろ') if has(rs, ss, k)) >= MIN_SLOTS:
            counts['adj'] += 1
            emit(rs, ss, 'い', protect)
            for x in ('さ', 'み', 'げ'):
                emit(rs, ss, x, protect)
            for x in ADJ_TAILS:
                emit(rs, ss, x, core)

        # ---- サ変
        if sahen and sum(1 for k in ('し', 'す', 'する', 'すれ', 'せ')
                         if has(rs, ss, k)) >= MIN_SLOTS:
            counts['sahen'] += 1
            emit(rs, ss, 'する', protect)
            for x in SA_TAILS:
                emit(rs, ss, x, core)

    manual_demote = read_pairs(MANUAL_DEMOTE)
    manual_keep = read_pairs(MANUAL_KEEP)
    for pair in manual_demote:
        if pair not in by_pair:
            print(f"  ja.demote entry not in the dictionary, skipped: {pair[0]}\t{pair[1]}")
    core |= (manual_demote & by_pair)

    def shared_kana_tail(a, b):
        n = 0
        while n < len(a) and n < len(b) and a[-1 - n] == b[-1 - n] and a[-1 - n] in KANA:
            n += 1
        return n

    base = core - protect
    propagated = set()
    for r in {r for r, _ in base}:
        for s, _ in by_reading[r]:
            pair = (r, s)
            if pair in base or pair in protect:
                continue
            if all(ch in KANA for ch in s):    # the plain kana reading itself
                continue
            if shared_kana_tail(r, s) >= 1:
                propagated.add(pair)

    demote = ((core | propagated) - protect) - manual_keep
    for pair in manual_keep:
        if pair not in by_pair:
            print(f"  ja.keep entry not in the dictionary, skipped: {pair[0]}\t{pair[1]}")

    with open(OUT, 'w', encoding='utf-8') as o:
        for r, s in sorted(demote):
            o.write(f"{r}\t{s}\n")

    print(f"paradigms found: {dict(counts)}")
    print(f"{OUT}: {len(demote)} inflected pairs "
          f"({len(propagated)} orthographic variants, "
          f"+{len(manual_demote)} curated, -{len(manual_keep)} kept)")

    if '--report' in sys.argv:
        # Two figures, because getCandidates does a PREFIX lookup: the exact-reading one is what
        # changes once a whole reading is typed (すもう), the prefix one additionally counts the
        # mid-typing states, where a longer reading's completion is in the running too.
        flat = sorted((r, s, f) for r, lst in by_reading.items() for s, f in lst)
        keys = [x[0] for x in flat]

        def adjusted(row):
            return row[2] - (REPORT_PENALTY if (row[0], row[1]) in demote else 0)

        for label, exact in (("exact reading", True), ("prefix lookup", False)):
            rows = []
            for r in sorted(by_reading):
                if exact:
                    sub = [(r, s, f) for s, f in by_reading[r]]
                else:
                    lo = bisect.bisect_left(keys, r)
                    hi = bisect.bisect_left(keys, r + '￿')
                    sub = flat[lo:hi]
                if len(sub) < 2:
                    continue
                old = max(sub, key=lambda x: x[2])
                new = max(sub, key=adjusted)
                if old[1] != new[1]:
                    rows.append((r, old[1], old[2], new[1], new[2]))
            print(f"\nat penalty {REPORT_PENALTY}, {label}: "
                  f"{len(rows)} readings change their first candidate")
            if '--verbose' in sys.argv:
                for r, o_, of, n_, nf in rows:
                    print(f"  {r}\t{o_} {of}\t->\t{n_} {nf}")


main()
