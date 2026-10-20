# Contract — walk capture: 自由作業盤 ⇄ 白い熊 kxkb ⇄ 言語島

Agreed 2026-10-04 between the 自由作業盤 chat and the kxkb chat, approved by 白い熊.

Sentences for 言語島 are spoken outside with the screen off, reviewed at home in kxkb (with Whisper's
corrections learned exactly as in dictation), and only then handed to 言語島 for island assignment and
translation.

```
物理鍵 (vol-down)                     kxkb                                 言語島
  triple: capture mode on/off          voice_capture_offer  ◀── clips ──  (自由作業盤)
  single: sentence start / save        review page: decode, correct, ✓
  → WAV per sentence (自由作業盤)       outbox ── gengoshima_intake ──▶  未分類 inbox → islands
```

Both directions are `ContentResolver.call()` on the receiver's existing automation door. Both doors
identify the caller by exact package, uid and pinned signing certificate (`AutomationCallers`, the same
file in both repos); a token is honoured only when the receiver's `automation_require_token` is on.
Every answer is a `Bundle` whose `result` is `OK…` or `ERROR:…`, returned, never thrown.

**Why `call()` and not broadcasts:** EMUI severs ordered-broadcast results and binders in broadcast
extras. `call()` is synchronous, instantiates the provider even when the app is not running, and its
returned Bundle is the receipt.

## 1. Clips: 自由作業盤 → kxkb — `voice_capture_offer`

Authority `shiroikuma.kxkb.automation`, method `voice_capture_offer`, `arg` unused.

Extras:

| key | type | meaning |
| --- | --- | --- |
| `items` | String (JSON array) | one object per clip, ≤ 10 per call |
| `fd_0` … `fd_9` | ParcelFileDescriptor | read-only descriptor of clip *i*, named by the item's `fd` |
| `token` | String, optional | only if kxkb requires one |

Item object:

```json
{"uuid":"…","fd":"fd_0","capturedAt":1791100000000,"durationMs":4210,
 "language":"en","sampleRate":16000,"channels":1,"bitsPerSample":16,
 "byteLength":134764,"sha256":"…"}
```

- **Format:** canonical 44-byte RIFF/`fmt `/`data` WAV, PCM16 little-endian, mono, 16 000 Hz —
  byte-for-byte the format of kxkb's corpus clips (`VoiceCorpusRepository.writeClip`), so an accepted
  clip moves into the corpus without re-encoding. `byteLength` is the whole file including the header.
- **Raw signal:** recorded with `AudioSource.UNPROCESSED` where supported, else `VOICE_RECOGNITION`; no
  AGC, no noise suppression, no normalising. kxkb peak-normalises at decode time and its confidence
  threshold is calibrated on that.
- **Length:** a sentence is ≤ 28 s (Whisper's window is 30 s; the recorder auto-splits at 28 s) and
  ≥ 0.2 s (shorter is a stray press and is never offered).
- **Language:** `en` is a default, not a verdict; kxkb may re-decode in another language.

Reply `result`:

| result | meaning — and what 自由作業盤 does |
| --- | --- |
| `OK:<uuid>,<uuid>,…` | exactly these clips are copied into kxkb's `filesDir` **and fsynced** — delete them |
| `ERROR:locked` | kxkb's credential-protected storage is not available (before first unlock) — keep all, re-offer later |
| `ERROR:budget` | the capture inbox would exceed its size cap — keep all, re-offer later |
| `ERROR:items` | `items` could not be parsed — keep all, log |
| `ERROR:format:<uuid>` | single-item call whose clip failed validation — same as that uuid in `rejected` |
| other `ERROR:…` | caller refused, door off, … — keep all, show verbatim |

Alongside `OK:…` the reply MAY carry a second extra, **`rejected`** = `<uuid>:<reason>,<uuid>:<reason>`,
reasons from the closed set `format`, `sha256`, `length`, `nofd`, `duplicate`. A partial failure is the
common case with ten clips per call, and this keeps the nine acknowledgements when one clip fails.

- a uuid in `OK` → delivered, delete it;
- a uuid in `rejected` → **do not retry automatically**: 自由作業盤 moves the clip to
  `gengoshima_capture/rejected/` and logs the reason (kept, never deleted silently);
- a uuid in neither → re-offer later.

`ERROR:locked`, `ERROR:budget` and caller refusals are whole-call conditions. kxkb's `describe` also
reports `capture_budget_bytes` / `capture_used_bytes` (optional for 自由作業盤 to read).

- `OK` never means "accepted for processing" — the copy happens synchronously inside `call()`.
- kxkb de-duplicates by `uuid` (a persisted seen-set), so a re-offer after a crash on either side is
  harmless. kxkb recomputes `durationMs` from the byte count; the field is a cross-check.
- **Ownership:** the clip belongs to 自由作業盤 until its uuid comes back in `OK`, to kxkb afterwards. A
  sentence dropped at review deletes its clip. The audio exists in exactly one place at every instant.
- **When 自由作業盤 offers:** when capture mode ends, and again on every engine start while clips remain.

## 2. Sentences: kxkb → 言語島 — `gengoshima_intake`

Authority `shiroikuma.jiyusagyoban.automation`, method `gengoshima_intake`, `arg` unused.

Extras:

| key | type | meaning |
| --- | --- | --- |
| `items` | String (JSON array) | ≤ 50 reviewed sentences per call |
| `token` | String, optional | only if 自由作業盤 requires one |

Item object:

```json
{"uuid":"…","text":"I walked to the river this morning.","recognized":"I walk to the river this morning",
 "language":"en","capturedAt":1791100000000}
```

- `uuid` is the capture uuid (the same one offered in §1). `text` is the final, reviewed sentence;
  `recognized` is Whisper's original output (kept for reference, may equal `text`).
- No audio travels in this direction — the clip stays in kxkb's voice corpus.

Reply `result`:

| result | meaning — and what kxkb does |
| --- | --- |
| `OK:<uuid>,<uuid>,…` | these uuids are durably in 言語島's inbox (newly stored **or already there**) — mark handed over |
| `ERROR:items` | the payload could not be parsed — keep all, log |
| other `ERROR:…` | caller refused, door off, … — keep all, retry later |

- 自由作業盤 de-duplicates by `uuid` (primary key of `gengoshima_inbox`), so kxkb's outbox may retry
  freely. A uuid already assigned to an island and translated is still answered as `OK`.
- **When kxkb sends:** a 「言語島へ送る」 pill on its review page, and automatically whenever the outbox is
  non-empty and a call succeeds.
- **Fallback:** kxkb also exposes `gengoshima_pull` / `gengoshima_ack` on its own door for 自由作業盤 to
  pull, for the day a push is refused. Not used in normal operation.

## 3. What 言語島 does with the inbox

Sentences land in `gengoshima_inbox` (Room v35), not in an island. The 未分類 page proposes an island for
each (one Claude call per batch: an existing island, or a new one with an English topic and register),
lets 白い熊 change one by tapping its island chip or several at once by multi-select, and inserts them
into their islands as `new` on 「すべて確定」. The normal 訳して音声を作る run translates and voices them.

## 4. Capture on the phone (自由作業盤, for reference)

- 物理鍵 vol-down **triple** toggles capture mode (it no longer reads the time).
- In capture mode a **single** vol-down press starts a sentence and the next saves it; the grabber
  consumes these presses so the volume does not change, screen on or off.
- Vibrations: start = one 200 ms buzz · saved = two short · capture mode ended = three short · error
  (mic lost, nothing saved) = one long 800 ms.
- Ending capture mode while a sentence records saves it first.

## 5. Amendment — a partial answer to §1 (kxkb side, implemented; awaiting 自由作業盤's copy)

§1's reply grammar cannot express a PARTIAL failure, and with ten clips per call that is the common case,
not the exotic one: nine good clips and one with a bad checksum could only be answered `OK:<nine>` — leaving
the tenth merely absent, hence re-offered for ever — or `ERROR:format:<uuid>`, which throws away the
acknowledgement of nine clips that are already fsynced on our side.

So the answer may carry a **second extra** beside `result`:

| key | type | meaning |
| --- | --- | --- |
| `rejected` | String, optional | `<uuid>:<reason>,<uuid>:<reason>` — these clips failed and should NOT be retried automatically |

`reason` is a closed set: `format` (not the agreed WAV, or a declared field we did not agree on), `sha256`,
`length` (declared bytes, or a duration outside 0.2 s–30 s), `nofd` (the named descriptor was not in the
extras), `io`. A uuid in neither list is simply "re-offer later", exactly as §1 says. The whole-call errors
(`ERROR:locked`, `ERROR:budget`, a caller refusal) are unchanged — they really are whole-call conditions.

A caller that ignores `rejected` still behaves correctly: the bad uuid is absent from `OK:`, so it is
re-offered, and is refused again the same way — idempotent, just wasteful.

**`duplicate` is deliberately NOT a rejection reason.** A uuid we already hold (or held once and have since
reviewed) is answered inside `OK:`, because the right thing for 自由作業盤 to do with it is exactly what `OK`
means: delete its copy. A "duplicate" refusal would make it offer that clip for the rest of the walk file's
life.

Also implemented on this side, and needing nothing from the other: `ERROR:items` for an unparseable `items`
in §1 (§2 already had it), and two fields in `describe` — `capture_budget_bytes` and `capture_used_bytes` —
so the remaining budget can be read instead of discovered as an `ERROR:budget` after a walk.

## 6. What the kxkb side holds (for reference)

- `automation/AutomationProvider.kt` — the `voice_capture_offer` method; `automation/VoiceCaptureOffer.kt`
  is the rule set without the binder (so it is testable), and the provider does the caller check, turns each
  `fd_<n>` into a stream and closes the descriptors on the way out. The copy is synchronous: `OK` means
  written and fsynced.
- `data/VoiceCaptureStore.kt` — `filesDir/voice_capture/`: `index.json`, one `<uuid>.wav` per clip, and
  `seen.json`, the uuids ever taken in, so a re-offer is answered rather than imported twice.
- `service/voice/VoiceInputController.kt` — `beginBatch` / `transcribeSamples` / `endBatch`: decoding audio
  we already hold, reusing the dictation engine, its vocabulary bias and its 60 s unload (held off for the
  run), and mutually exclusive with the microphone.
- `service/voice/VoiceJudging.kt` — the marking rules, shared with the dictation review so the two cannot
  drift.
- `settings/voice/VoiceCaptureFragment.kt` — the review page: transcribe, play, tap a word to correct it
  (◂/▸ merge a run, ↺ restores what was heard), Keep → the corpus, Delete → gone.
- Accepting a sentence calls `VoiceCorpusRepository.recordAcceptance` with `keepAll` on and the clip MOVES
  into `filesDir/voice_corpus/` (`VoiceSessionLedger.Entry.clipPath`), so the recording is never re-encoded.

- `data/VoiceHandoffOutbox.kt` — `filesDir/voice_handoff/`: `outbox.json` (sentences waiting for 言語島)
  and `sent.json` (uuid + time of everything handed over, which is also what stops a sentence being filed
  twice). A kept sentence queues here; it leaves only when 自由作業盤 names it.
- `automation/GengoshimaHandoff.kt` — the §2 push (`gengoshima_intake`, batches of 50, only the uuids named
  in `OK:` leave the queue) and the pull fallback on our own door. A door that is not installed, answers
  nothing, or refuses all come to the same safe thing: the sentences stay queued.
- The review page queues on **Keep** and pushes at once; a 「言語島へ送る」 pill pushes on demand, and the page
  header shows how many are waiting and how many have gone.

### 6a. The pull fallback's shape (kxkb's door — normal operation does not use it)

`gengoshima_pull` — optional `limit` extra (default and maximum 50). Reply: `result = "OK:<n>"` and an
`items` extra holding the same §2 array the push would have sent, oldest first. **Reading changes
nothing** — a pull on its own hands nothing over.

`gengoshima_ack` — `items` is a JSON array of uuid STRINGS (not objects). Reply: `result = "OK:<uuid>,…"`
naming the uuids that were actually waiting and have now left the queue; a uuid we never queued is
IGNORED rather than remembered as sent, because remembering it would silently refuse a sentence later kept
under that uuid. An unparseable payload is `ERROR:items`. Saying the same ack twice is harmless.

**Still open:** a token. Both sides default `automation_require_token` to false, so neither asks for one;
if 自由作業盤 ever turns it on, the push has nowhere to read a token from and would need one — say so first.

## 7. Amendment — the sentence's language is decided in the review (both sides, 2026-10-11)

§1 says `en` is "a default, not a verdict; kxkb may re-decode in another language", and §2's item object has
carried a `language` field from the start. Until now it was always `en`, because nothing could change it.
kxkb's Walk capture page now can: every captured sentence carries a language pill (English ⇄ čeština) and
the page has a "mark all" for a whole walk, and the language a sentence is set to is the language its clip
is decoded in — the Whisper decode, the word marks, the Czech clause commas, and what the voice corpus
learns from the sentence.

So §2's `language`:

- is present on every item and holds exactly `en` or `cs`;
- is **authoritative** — 白い熊's verdict in the review, and the language the audio was really decoded in,
  not the recorder's guess;
- is normalised before it can be sent: an unknown or blank code on an offered clip becomes `en`, which the
  review can then change. 言語島 never sees a third value.

For 言語島 that makes the inbox a mixed-language queue: the row's language must be stored and shown, the
island proposal must be told which language the sentence is in, a `cs` row translates cs→ja, and its
source-side voice is a Czech voice.

§1 is unchanged. A 自由作業盤 that wants to mark a whole walk at record time may send `"cs"` in the offer —
kxkb honours it as the sentence's starting language — but nothing requires it, and the review has the last
word either way. §2 still carries no audio: the clip belongs to kxkb's voice corpus once its uuid came back
in `OK:`, and a 言語島 that needs the recording itself would need a new section here, agreed first.

Switching a sentence's language DISCARDS its decode: a transcription belongs to the language it was made
in, and a Czech sentence read as English is not a sentence to correct but one to decode again. The page
asks before dropping one. Corrections already saved stay — they went into the voice corpus the moment they
were saved — and the recording is untouched, which is all a re-decode needs.

## 8. Amendment — the way back from the review (kxkb side, 2026-10-18)

白い熊 opens the review from the 言語島 board's 「録音確認」 tile, and expects Back to show the board
again. It did not, and no flag on either side could make it: **the board finishes itself the moment a
tile hands its task over** (`GengoshimaBoardActivity`, "two stacked windows is one too many" — right for
a tile that opens a window of 自由作業盤's own). By the time the review appears there is no board
anywhere — not behind us, not in another task, not in recents. An activity that no longer exists cannot
be revealed; it has to be **opened again**, and it is `exported="false"`, so kxkb cannot open it.

So the caller names a task to run on the way out, as a **third extra on the §1-style launch intent**
that opens the review:

| key | type | meaning |
| --- | --- | --- |
| `return_task` | String, optional | the name of a 自由作業盤 task, run when the review is left by Back, the toolbar arrow or Close |

kxkb runs it through 自由作業盤's **own exported** run-a-task trampoline —
`com.opentasker.widget.TaskRunActivity`, extras `com.opentasker.widget.TASK_NAME` and
`…SOURCE` (sent as `白い熊 kxkb`, so the run log says where it came from) — with the component pinned to
`shiroikuma.jiyusagyoban`, never an implicit intent. **This grants nothing:** that trampoline is exported
for launcher shortcuts, so every app on the phone may already run any task by name. The door still reads
nothing else, still opens exactly one page, and still hands over no data.

Nothing passed → the review closes exactly as it did before: no guessing at a screen to show instead.
Nothing passed is also what the space-slide menu's own entry to the page means.

**No code is needed in 自由作業盤.** The 「言語島 録音確認」 task's send-intent action already carries six
string extras: `extra1_key = return_task`, `extra1_value = <the name of the task whose action is
「言語島の盤」 / `gengoshima.board`>`. A task name rather than a component because the board is not
exported, and a name rather than an id because a name survives a re-import — 自由作業盤's own reason, in
`TaskRunActivity`.

Rejected on the way here, for the record: bringing 自由作業盤's launcher task forward (it shows
`MainActivity`, a different task from the board's `.gengoshimaboard` one — 白い熊 landed on the
Variables page); a `PendingIntent` from the caller (a send-intent action cannot build one); and keeping
the board alive behind the review (it is 自由作業盤's call, and the stack between two apps' tasks is not
a thing either side can rely on).

### 8a. The board's door (自由作業盤 side, 2026-10-18)

So that the return needs no configuration at all, 自由作業盤 also has a **door to the board**, the mirror of
kxkb's `WalkCaptureEntryActivity`:

| | |
| --- | --- |
| action | `shiroikuma.jiyusagyoban.action.OPEN_GENGOSHIMA_BOARD`, category `DEFAULT` |
| component | `com.opentasker.ui.gengoshima.GengoshimaBoardDoorActivity` in `shiroikuma.jiyusagyoban` |
| manifest | `exported="true"`, `Theme.NoDisplay`, `noHistory`, `excludeFromRecents`, `taskAffinity=""` |
| reads | **nothing** — its whole body is `GengoshimaBoardActivity.open(this)` then `finish()` |

A trampoline rather than an exported board or alias: a door that takes no extras can open exactly one
screen, so exporting it cannot be talked into opening another part of the app. No data crosses it in
either direction.

kxkb fires it, package-pinned and resolve-checked, as its **default** return whenever the review was
opened through its door; `return_task` still wins when a caller names one; and with the door missing
(an older 自由作業盤) the review falls back to a plain close. So the two halves land in either order.

## 9. Amendment — long recordings (自由作業盤 side 2026-10-19; kxkb side by hand-off)

白い熊 wants the walk capture's press to double as a plain audio recorder — "record this, keep it for
later", not transcribed — with no second key. So the recorder no longer cuts at 28 s: one press-to-press
is **one clip, however long** (up to 4 h, or until the phone's free storage falls under 200 MB, where the
clip is saved and the mode stays armed). Whisper still hears only 30 s, so what a long clip IS for is
decided in kxkb's review, not by the recorder.

Changes to §1, everything else unchanged:

- **Length:** `data` ≥ 0.2 s, no upper bound but the recorder's 4 h. A clip over **28 s is a long
  recording**: the item carries `"kind":"long"` (absent on a sentence), and it must never be decoded as
  one piece.
- **Batching:** a long recording is offered **alone** in its call (one descriptor), never with sentences.
- **An older kxkb** answers it `rejected` `length` (its pre-§9 size check, before anything is copied).
  自由作業盤 then **keeps** it pending — it is not a bad clip — and re-offers it on the next mode end or
  engine start. So the halves land in either order.
- **Salvage:** the recorder now rewrites the header every 5 s, and a recording cut off by a kill is
  saved on the next start as what was on disk. Its item carries `"salvaged":true`; it is otherwise
  ordinary.

kxkb's side (in its review):

- Store a long recording, show it as one row (duration, time, play / seek), and never auto-decode it.
- **Save as OGG** — Opus to a directory 白い熊 picks once on the review page, named by its `capturedAt`
  (`yyyy-MM-dd_HH-mm-ss.ogg`), never written over. Offered on **every** row that still has its recording,
  not only a long one — what makes audio worth keeping is what was said into it.
- **Split into sentences** — cut at the pauses (the VAD kxkb already carries), each piece ≤ 28 s, a hard
  cut only where 28 s holds no pause. Each piece is an ordinary sentence with a **new uuid**, `parent` =
  the long clip's uuid and `capturedAt` = parent + its offset, so §2 to 言語島 is unchanged.
- Both may be done; the long WAV goes when the last of them is done, or on **Drop** (confirmed).
- The capture budget (512 MB, about 4.6 h of audio) is kxkb's to raise; `ERROR:budget` keeps working
  as before (自由作業盤 keeps the clip and re-offers later).
