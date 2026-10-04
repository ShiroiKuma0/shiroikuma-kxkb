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
