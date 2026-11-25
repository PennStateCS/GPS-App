# Raw NMEA recording (Developer Tools)

A developer diagnostic that saves the NMEA sentences a **live** GNSS source delivers to the app, in order and as
received, to a file on the tablet. It exists to get real receiver output (e.g. an Emlid Reach RS4 Pro) into the
hands of developers and into test fixtures. It is **not** point capture: it never changes positions, fix
status, tilt status, capture, coordinates or AR, and it never opens its own receiver connection.

## Where

Settings → **Developer Tools** → card **GNSS Diagnostics** → **Raw NMEA Recording**
(`ui/settings/categories/RawNmeaRecordingSection.kt`, layout `content_settings_developer.xml`). Not on the Receiver
page and not in the capture UI.

Controls: duration **10 s / 30 s (default) / 60 s / Manual**, **Start** / **Stop**, status with elapsed time,
counters (sentences, bytes, GGA, RMC, GST, ETC, EBP, other, bad checksum, malformed, dropped), a red "data lost"
line when anything was lost, and the list of recordings on the device with **Share** and **Delete**.

Before the first recording a one-time notice explains that recordings contain precise coordinates and receiver
identifiers and stay on the device until shared. The acknowledgment is stored in the DataStore key
`dev_raw_nmea_privacy_ack` (boolean, absent = false).

## Availability

| Active source | Recordable | Tap |
|---|---|---|
| External receiver over TCP, link **CONNECTED** | yes | TcpNmeaSource `onLine` |
| Tablet GNSS (internal) | yes | InternalNmeaSource raw-line hook |
| External receiver selected but not connected | no — "the external receiver is not connected" | — |
| NMEA replay (`Provider.REPLAY`) | no — "NMEA replay is not a live source and is never recorded" | — |

## Tap point and what is preserved

Code: `gnss/diagnostics/raw/` (`RawNmeaRecorder`, `RawNmeaTaps`, `NmeaLineClassifier`, `RawNmeaMetadataJson`,
`RawNmeaFiles`), wired in `di/GnssModule.kt` and `di/RawNmeaModule.kt`.

- **External TCP:** GnssModule passes `RawNmeaTaps.externalOnLine(recorder, diagnostics::recordLine, tiltMonitor::onLine)`
  as TcpNmeaSource's existing `onLine` hook. The recorder tap runs first, then the unchanged diagnostics counter
  and RS4 tilt monitor, then (inside TcpNmeaSource, unchanged) `fuser.accept`. **No change to geokit-gnss
  `link` (TcpNmeaSource / ReconnectingNmeaAdapter) and no second TCP connection.**
- **Tablet GNSS:** InternalNmeaSource got an optional `onRawLine` constructor hook, called with Android's
  `OnNmeaMessageListener` message before `diagnostics.recordLine` and `fuser.accept`. Only its trailing CR/LF is
  removed when written.
- The tap is `RawNmeaRecorder.offer(origin, line)`: a volatile read and, while recording, one non-blocking
  `Channel.trySend` into a bounded queue (4096 lines). It never throws and never waits; all file I/O happens in a
  background writer coroutine on `Dispatchers.IO` with a 64 KB buffered stream. A line rejected by a later hook
  (TcpNmeaSource drops a sentence whose processing throws) is still recorded.
- Lines are never rebuilt from parsed objects. Sentence text, order, proprietary sentences (`$GNETC`, `$GNEBP`,
  `$P…`), unknown types and wrong checksums are kept exactly as delivered by the hook. Each line is written
  followed by `\r\n` (the NMEA terminator).

**Lines, not bytes.** The TCP hook receives what `BufferedReader.readLine()` + `String.trim()` produce, so a
recording is *line-exact*, not *byte-exact*. Not preserved:

1. the original line terminators (CRLF, LF or CR are all written as CRLF);
2. leading/trailing whitespace on a line (trimmed);
3. blank lines and lines not starting with `$` (dropped by TcpNmeaSource before the hook);
4. a partial last line: at a clean EOF `readLine()` returns it and it **is** recorded (with CRLF added — the
   checksum check then counts it as malformed); on a read error, dead-link timeout or `stop()` it is lost;
5. bytes that are not valid UTF-8 (decoded by `InputStreamReader(UTF_8)`, replaced by U+FFFD, written back as
   UTF-8 — counted as malformed). NMEA is ASCII, so for a normal stream the text is identical.

Framing (several sentences in one TCP read, one sentence split across reads) is done by `BufferedReader` before
the hook; `RawNmeaTapsTest` shows this over a real loopback socket.

### Proposed (not implemented) change for a byte-level tap

Needs explicit approval because it touches the off-limits receiver connection code. Minimal version in
`libs/geokit-gnss/.../link/TcpNmeaSource.kt`: add an optional constructor parameter
`onBytes: ((ByteArray, Int, Int) -> Unit)? = null` and, when it is set, wrap the socket input stream before the
reader is built:

```kotlin
val raw = socket.getInputStream()
val tapped = if (onBytes == null) raw else object : java.io.FilterInputStream(raw) {
    override fun read(b: ByteArray, off: Int, len: Int): Int =
        super.read(b, off, len).also { n -> if (n > 0) runCatching { onBytes.invoke(b, off, n) } }
    override fun read(): Int = super.read().also { c -> if (c >= 0) runCatching { onBytes.invoke(byteArrayOf(c.toByte()), 0, 1) } }
}
reader = BufferedReader(InputStreamReader(tapped, Charsets.UTF_8), 1024)
```

The callback would copy the chunk into the recorder's bounded queue (same non-blocking rule). Default `null`
keeps behaviour bit-for-bit; the line path, parsing and reconnect logic are untouched. The recorder would then
write chunks verbatim (no added CRLF) and count sentences on a copy. Internal GNSS has no byte stream (Android
delivers strings), so it would stay line-based.

## Files

App-private: `filesDir/nmea_recordings/` (not visible to other apps, excluded from Android cloud backup via
`backup_rules.xml` / `data_extraction_rules.xml`, not part of the coordinate backup). Shared only through the
Android share sheet (FileProvider path `nmea_recordings`); nothing is uploaded automatically.

- `GNSS_<yyyy-MM-dd>_<HHmmss>_<label>.nmea` — device local time; label = the receiver profile's short label with
  only letters and digits (`RS4Pro`, `RS2Plus`, `EXT` for Generic NMEA) or `Internal` for tablet GNSS; `_2`, `_3`…
  on a collision. Contains **only** the sentences, one per line, CRLF. No header or comments.
- `GNSS_<…>.json` — companion metadata, same base name.

### Companion JSON (schemaVersion 1)

Written when the recording starts (`complete: false`, `terminationReason: null`, `endedAtUtc: null`) and rewritten
when it ends. A recording interrupted by process death therefore keeps `complete: false` with no reason.

```json
{
  "schemaVersion": 1,
  "kind": "surrealar.raw_nmea_recording",
  "nmeaFile": "GNSS_2026-11-25_143012_RS4Pro.nmea",
  "appVersion": "1.0-dev",
  "startedAtUtc": "2026-11-25T19:30:12.345Z",
  "endedAtUtc": "2026-11-25T19:30:42.351Z",
  "startedAtLocal": "2026-11-25T14:30:12.345-05:00",
  "endedAtLocal": "2026-11-25T14:30:42.351-05:00",
  "deviceTimeZone": "America/New_York",
  "durationMs": 30006,
  "mode": "TIMED",
  "requestedDurationS": 30,
  "source": { "provider": "EXTERNAL_TCP", "connectionType": "TCP", "live": true, "tapPoint": "TcpNmeaSource.onLine (…)" },
  "receiver": { "profile": "reach_rs4_pro", "profileLabel": "Emlid Reach RS4 Pro",
                "reportedModel": null, "reportedFirmware": null, "reportedSerial": null },
  "compensatedOutput": { "setting": "UNKNOWN", "confirmed": false },
  "format": { "encoding": "…", "lineTerminator": "CRLF", "byteExact": false, "notPreserved": "…" },
  "counts": { "GGA": 150, "RMC": 30, "GST": 30, "ETC": 150, "EBP": 30, "OTHER": 420 },
  "totals": { "sentences": 810, "bytes": 52344 },
  "checksum": { "valid": 810, "invalid": 0 },
  "malformed": 0,
  "droppedLines": 0,
  "maxFileBytes": 10485760,
  "terminationReason": "DURATION",
  "writerError": null,
  "complete": true
}
```

(Example values are illustrative, not a real recording.)

- `receiver.reported*` come only from what the receiver reported through `ReceiverDeviceManager.deviceInfo`
  (Emlid HTTP device info); `null` otherwise — never inferred from the profile. A value that first arrives during
  the recording is filled in at the end (same profile only). Internal GNSS: all receiver fields `null`.
- `compensatedOutput`: the Settings → Receiver "Compensated NMEA output" value; `confirmed` is true only for
  `ENABLED` / `DISABLED` set by the user, false for `UNKNOWN`. `null` for internal GNSS.
- Never included: host / IP / port / receiver network name, NTRIP credentials, tokens, HTTP responses.
- Types are talker-agnostic (`$GNGGA`/`$GPGGA` → GGA). A proprietary `$P…` address counts as OTHER.
- `checksum.valid/invalid`: XOR between `$` and `*` compared with the two hex digits, on a copy of the line.
  `malformed`: not `$`-prefixed, non-printable / non-ASCII characters, or no `*hh` ending (not checksum-checked).
- `complete` is true only when `terminationReason` is `DURATION`, `SIZE_LIMIT` or `USER_STOP`, **and**
  `droppedLines` is 0, **and** there was no writer error.

## Limits and failure behaviour

| Event | terminationReason | complete |
|---|---|---|
| Chosen duration reached | `DURATION` | true |
| File would exceed 10 MB (also in Manual mode; the line that would cross the limit is not written) | `SIZE_LIMIT` | true |
| Stop pressed | `USER_STOP` | true |
| Active provider changes, or the external receiver profile changes | `SOURCE_CHANGED` | false |
| External link leaves CONNECTED (disconnect, reconnect, host change) | `DISCONNECTED` | false |
| Leaving the Developer category, leaving Settings, app in background, screen off; app scope cancelled | `LIFECYCLE` | false |
| File cannot be opened / written / flushed (overrides any earlier reason) | `WRITER_ERROR` | false |
| Queue (4096 lines) full — writer fell behind | `BUFFER_OVERFLOW` | false |

Lines that could not be written (queue overflow, or still queued at a writer failure) are counted in
`droppedLines`; the UI then shows "Data lost: … This recording is incomplete." An incomplete recording is never
labelled complete in the UI, the list or the JSON. Rotation does **not** stop a recording (the page is rebuilt).
One recording at a time; the recorder is an app-scoped `@Singleton`. Status lines (no sentence text, no
coordinates) go to the diagnostic log under tag `GNSS`.

## Recording an RS4 Pro over TCP

1. Emlid Flow → the receiver → **Position streaming 1**: TCP server, NMEA, port 9001 (the app's RS4 Pro default).
   Enable the messages you want (GGA, RMC, GST, ZDA, GSA/GSV; tilt adds ETC; base position EBP).
2. If tilt is used: set Settings → Receiver → **Compensated NMEA output** to match Emlid Flow so the metadata says
   `confirmed: true`.
3. In the app: Settings → Receiver → connect the RS4 Pro (profile "Emlid Reach RS4 Pro"); wait for **Connected**.
   Open Receiver details once so the model/firmware/serial are reported (optional).
4. Settings → Developer Tools → Raw NMEA Recording → choose 60 s or Manual → **Start**. Stay on the page
   (leaving it stops the recording); keep the screen on.
5. Do what needs to be recorded (e.g. tilt the pole, lose/regain fix). **Stop** (or let it time out).
6. Check: status "Finished — …", Dropped 0, Bad checksum as expected. **Share** → both files.

## Replay and test fixtures

The REPLAY provider path (`ReplayAdapter` + an `NmeaLineSource`) can technically play a recording: each line is
a `$` sentence, CRLF is stripped by `readLine()`, and `ReplayAdapter` registers the same Emlid parsers as the live
registry and stamps fixes `Provider.REPLAY` (never captured). But the app has **no UI or code path that selects
REPLAY or loads a source into `ReplayAdapter`** today (`AssetNmeaReplaySource` is not used anywhere, and REPLAY
activation would have to go through `GnssSourceCoordinator`). Adding file selection, timing and "not live"
labelling would touch source selection, so it was not implemented here.

Use exported recordings as **test fixtures** instead:

1. Copy the `.nmea` into `libs/geokit-gnss/src/test/resources/nmea/` (e.g. `reach_rs4pro_<what>_<date>.txt` —
   the existing fixtures use `.txt`) and keep the `.json` next to it, or add a short provenance note: receiver,
   firmware, date, Emlid Flow settings, what happened during the recording, and that it is a real capture.
   Check for locations that must not be published (precise coordinates of a private property): the repo is
   going public.
2. The app adds that folder as a test-resource dir, so app tests read it with
   `javaClass.getResourceAsStream("/nmea/<file>")` (see `AndroidGnssLoggerTest`, `RawNmeaTapsTest`); library tests
   read it the same way (`NmeaFixtureGoldenTest`, emlid `EmlidNmeaGoldenTest`).
3. Feed lines to `NmeaFuser(...).accept(line)` (as TcpNmeaSource does), or through `ReplayAdapter.load(...)` with a
   small `NmeaLineSource` over the file. For a new golden, follow the existing golden tests and regenerate with
   `GEOKIT_WRITE_GOLDEN=1` only for an intended change.

## Tests

`app/src/test/.../gnss/diagnostics/raw/` (`RawNmeaRecorderTest`, `RawNmeaTapsTest`, `NmeaLineClassifierTest`),
`ui/settings/categories/RawNmeaUiTextTest`, `DeveloperSettingsControllerTest`. Synthetic sentences are labelled
SYNTHETIC; the only real receiver data is the existing Emlid fixture `reach_nmea_withimu.txt`. None of it is
RS4 Pro output.

## Hardware-only checks

Tablet acceptance checklist section 14 ([geodesy/tablet-acceptance-checklist.md](geodesy/tablet-acceptance-checklist.md)):
the real RS4 Pro stream and counters, no effect on live positions/tilt status, the share sheet, stop on
leave / background / screen off, continue on rotation, disconnect and source-change endings, and the 10 MB limit at
a high output rate.
