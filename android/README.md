# Weniger Lärm — citizen noise measurement (Android; v0.5.1 published, v0.6.0 in preparation)

Weniger Lärm («Less Noise»; until v0.5.1 «Stadtlärm») turns an Android phone into a noise logger.
It measures A-weighted sound levels the way a sound level meter does (IEC 61672-1 A-weighting,
Fast time weighting), detects individual noise events (a motorbike, a shouting group, a tram), tags
each with its likely source using an on-device sound classifier, and summarises every
**measurement** — one run from «Start» to «Stopp», at any time of day, possibly over several days
(since v0.6.0; before, the app summarised nights 22:00–06:00).

**Privacy by construction:** no audio is ever stored or sent. The app comes in
[two editions](#editions): the **«Offline-Version»** (the default download) has no internet and no location
permission at all; the **«Online-Version»** uses the internet only for the opt-in upload
[«Messwerte teilen»](#messwerte-teilen-opt-in-upload) and for «Laut! Melden» reports (off until
switched on, one host, one network class, no third-party SDK), and the location only for one fix at
the start of a shared measurement and one per report. See [PRIVACY.md](../PRIVACY.md). (A separate,
unpublished diagnostics app, «Weniger Lärm Labor», can record audio; the public app contains none of
its code. See [Labor build](#labor-build).)

The UI is German (Swiss spelling; launcher name also in French «Moins de bruit», Italian «Meno
rumore» and English «Less Noise»); the code and docs are English. License: [Apache-2.0](../LICENSE).

This is the `android/` part of the [Weniger Lärm repository](../README.md); paths and commands
below are relative to this directory.

---

## What it measures

| Quantity | Definition in this app |
|---|---|
| LAF | A-weighted level with Fast time weighting (exponential, τ = 125 ms), sampled every 125 ms |
| LAeq,1s / LAeq,1min | Energy-equivalent A-weighted level over 1 s / 1 min (from the un-time-weighted signal) |
| LAFmax / LAFmin | Max/min of LAF in the period (max from the continuous Fast signal, min from the 125 ms samples) |
| L1, L10, L50, L90 | Level exceeded 1/10/50/90 % of the time, from the 480 LAF samples of the minute (so L10 > L90) |
| Local floor | L90 of the LAF samples over the trailing 30 s (setting 10–60 s), recomputed every 125 ms; the reference for events (since v0.4.0) |
| Hum («Grundrauschen») | The steady level under the bursts: per minute the stored L90; for a measurement or chart window the median of the valid minutes' L90 |
| Event (burst) | LAF ≥ local floor + excess (setting, default 6.5 dB, 3–20) for ≥ 0.5 s; ends below floor + excess − 3 dB; the floor is frozen at the start; at most 300 s. Kept only if its LAFmax also reaches the absolute **event floor** (setting «Mindestpegel», default 30 dB(A), 20–70). Events flagged as **wind** are stored but not counted. See [Event detection](#event-detection-detector-v2) |
| Background (5 min) | L90 of LAF over the trailing 5 min. Before v0.4.0 the event reference; now only stored with each event (`background_db`) and shown live, for continuity |
| SEL (LAE) | Sound exposure level of the event, re 1 s: `10·log10(Σ 10^(Leq_tick/10)·0.125 s)` |
| Valid time / coverage | Seconds of a minute with a real microphone signal. Time while Android silences the mic (phone call, voice assistant — the app then receives zeros) or while the input is digital silence, plus 0.5 s of filter recovery, is excluded from every level, percentile, event and the background |
| Measurement LAeq («Mittel») | Energy average over the valid time of all minutes of the measurement (from «Start» to «Stopp»); minutes with < 50 % coverage are left out |

Level = `10·log10(mean square) + calibration offset`. Without a calibration the app uses the
Android CDD sensitivity guideline (90 dB SPL at 1 kHz → RMS 2500/32768, i.e. −22.35 dBFS),
which gives an offset of **112.35 dB**. All data measured this way is flagged
`calibrated = false`.

**Event floor.** Relative to a very quiet background (20 dB at night indoors) even keystrokes are
10 dB louder, so events must also reach an absolute LAFmax. The default of 30 dB(A) filters noise
at the phone itself (typing, breathing) but keeps quiet pass-bys: on a real windowsill night,
distant highway passes peaked at a median of 37 dB(A), about 13 dB above a 20 dB background. A candidate that never reaches the
floor is discarded and not counted in the minute's `event_count`. The floor in force is stored
with every event (`min_level_db`). Events recorded before v0.3.0 have none; the measurement list and the
chart apply the *current* floor to all stored events, so they always agree. With an uncalibrated
phone the floor is only approximate, like every level. When a calibration is saved, the floor
moves with the offset (see "Calibration" below).

Every minute record stores: start (ISO-8601 with zone offset), duration, LAeq, LAFmax,
LAFmin, L1, L10, L50, L90, event count, dominant source category, time share per category,
calibration id and offset, audio source and the calibrated flag. Every event stores start,
duration, LAFmax, SEL, background level, threshold, dominant category and score, and the top-3
raw AudioSet labels with scores, and the event floor in force. Since v0.3.2 both also keep the
original levels when they are re-evaluated with a later calibration (see "Re-evaluating old
measurements").

Since v0.4.0 every event also stores its detector-v2 features (local floor, excess, rise and decay
time, jaggedness, mid-band rise, LF share and flutter, the wind flag and the shape), and every minute
the median local floor and the number of wind events (`wind_event_count`).

**What `event_count` means.** A minute's `event_count` is the number of **bursts** — events without
the wind flag — that *started* in that minute and passed the floor in force *when it was measured*
(before v0.4.0 there was no wind flag, so it counted all events). Wind events of the minute are in
`wind_event_count`. Whether an event is wind is known only when it is complete (up to 5 s after its
end), so a minute is stored only once all events that started in it are complete. Neither count is
ever recomputed: neither when the floor setting changes nor when the minute is re-evaluated with a
new calibration. The measurement list and the chart do not use them; they count the stored events with
the *current* floor at read time, so a raised floor shows fewer events there while `event_count` in
the CSV stays as recorded.

## Event detection (detector v2)

**Hum vs bursts.** At a window near a highway the night has two parts: a constant hum (the distant
highway itself; it drops overnight and changes with the wind) and bursts above it (a car or a
motorbike passing nearby, voices). The hum *is* the L90; the app reports it as its own quantity,
«Hintergrund (L90)», and counts and characterises only the bursts above it, as «Ereignisse».

**Local floor and trigger.** Until v0.3 an event started 10 dB above the L90 of the last 5 minutes.
Measured on one night with 528 recorded event clips (uncalibrated), that 5-minute L90 sat a median
3.1 dB *below* the level right around an event, because the hum moves within minutes; a third of the
events barely exceeded the local hum. Since v0.4.0 the reference is the **local floor**, the L90 of
LAF over the last 30 s (setting «Lokaler Hintergrund: Fenster», 10–60 s), updated every 125 ms. An
event starts when LAF ≥ floor + **excess** (setting «Ereignis-Schwelle über lokalem Hintergrund»,
default 6.5 dB — vehicle passes in that night were ≥ 6.5 dB above it, median 9.1, p10 6.9), ends when
LAF falls below floor + excess − 3 dB, needs ≥ 0.5 s and the absolute event floor, and is closed after
300 s. The floor is frozen at the event's start (the 30 s window keeps filling meanwhile). Detection
starts once 5 s of history exist. The excess in force is stored as `threshold_db`. Settings of v0.3
are migrated once: a threshold *t* over the 5-min background becomes an excess of max(3, *t* − 3.5) dB
(10 → 6.5, 7 → 3.5); if it had been changed, the app says so once.

**Features.** When an event is complete the app computes, from the 125 ms ticks (all O(1) per
sample, no FFT, no audio kept; code: `dsp/.../EventFeatures.kt`):

| Feature (CSV column) | Definition |
|---|---|
| `local_floor_db` | the local floor at the start (dB(A)) |
| `excess_db` | LAFmax − local floor (dB) |
| `rise_s` | rise time from 10 % to 90 % of the excess on the 125 ms LAF curve (relative to the curve's own peak; crossings interpolated; the 10 % point is searched up to 10 s before the start); empty if not found |
| `decay_s` | from the first fall below 90 % after the peak to the first time at or below 10 %. For this the app follows the level up to 5 s past the event's end (the "tail": until 10 % is reached, the next event starts, the audio becomes invalid or the measurement stops); empty if 10 % was not reached in time. The event is reported after the tail |
| `jaggedness` | RMS of the second difference of the 125 ms LAF curve during the event, divided by the excess (a smooth hump ≈ 0.01–0.1) |
| `mid_band_rise_db` | A-weighted level in 250 Hz–4.5 kHz (2nd-order Butterworth high-pass and low-pass) over the loudest 1 s of the event minus the same band over the 5 s before its start |
| `lf_share` | unweighted energy 20–200 Hz / energy 20 Hz–8 kHz over the event (20 Hz high-pass, then a 200 Hz resp. 8 kHz low-pass, 2nd-order Butterworth) |
| `lf_flutter_db` | RMS of the detrended 20–200 Hz level: the un-time-weighted 125 ms level of that band minus its centred running median over ±8 ticks (≈ 2.1 s; the window shrinks symmetrically towards the event's edges). The median follows slow trends and single steps (any on/offset), so what remains is the fast back-and-forth (≈ 0.5 Hz up to the 4 Hz the tick rate allows) of wind turbulence |
| `wind` | `lf_share ≥ 0.93` **or** `lf_flutter_db ≥ 4.5` (thresholds under Einstellungen → Experten; fitted on a recorded night, see below) |
| `shape` | `long` (≥ 30 s), else `impulse` (rise < 0.35 s and ≤ 2 s), else `jagged` (jaggedness ≥ 0.3), else `hump`. Informational only, not a filter; thresholds provisional |

**Wind on the microphone.** In the recorded night 14 % of the events were wind: almost all their
unweighted energy below 200 Hz, or a strongly fluttering low-frequency level, sub-second thumps or
multi-peak gusts, in episodes. A high-pass does not remove it. Wind events are stored (category
«Wind», id `wind`, not part of the classifier mapping; the classifier's labels are kept) and exported
with `wind = true`, but are left out of every count: events/h, the measurement's events, the categories,
the loudest event, `event_count`. The chart draws them as small hollow grey dots («Wind
(ausgeschlossen)»; can be hidden under Experten).

**Validation on a recorded night** (Labor build, 7./8.10.2026, one phone, uncalibrated; audio not in
the repository). The whole continuous recording (8.7 h) was replayed through the engine
(`./gradlew :dsp:replayNight`, see Status) and its events matched to the 528 Labor clips, which
carry weak labels from an offline analysis. With the defaults (excess 6.5 dB, floor 30 dB(A)) the
detector keeps 64 % of the labelled vehicle passes and drops 84 % of the "background" events
(nothing but a weak swell); with the event floor at 20 dB(A) — this phone's quiet night sat at
≈ 15–25 dB(A) uncalibrated — 80 % and 72 %. Excess 5 dB kept almost everything including the
background, 8 dB lost half of the passes; 6.5 dB was the best compromise of the three. The wind
thresholds were fitted on the same night: `lf_share ≥ 0.93 or lf_flutter_db ≥ 4.5` agrees with the
wind labels on 97.4 % of the 340 matched events (5 false, 4 missed); the first guess 0.95 / 3.8 dB
gave 96.2 % (5 false, 8 missed). `lf_share` matches the offline analysis closely (r = 0.95); this
app's flutter reads ≈ 0.6× the offline 0.5–5 Hz band-pass value (r = 0.66), so thresholds of the
two are not interchangeable. One night, one window, one phone: treat all of it as provisional.

## Measurements and chart

**What a measurement is (since v0.6.0).** Every «Messung starten» stores a row in the Room table
`measurements` (start, stop, and in the Karte editions the shifted point if the measurement is
shared, see «Location per measurement»);
«Stopp» sets its end. Minutes and events have no measurement id: a record belongs to the
measurement that started last before it (5 s tolerance, `MeasurementSplitter.ownerIndex` in
`dsp/.../Measurements.kt`). If Android killed the service before «Stopp», the row stays open and the
measurement ends with its last stored minute. Data from before v0.6.0 was split into measurements
by the database migration 5 → 6: a gap of more than 10 minutes between two minutes starts a new one.
No time of day is special.

The **Messungen** tab lists all measurements, newest first: start and end («Sa 10.10. 22:14 – So
11.10. 06:30», or «läuft seit …»), **Dauer**, **Mittel** (LAeq), **Grundrauschen** (the hum, median
of the valid minutes' L90) and **Lärmspitzen pro Stunde** (bursts per hour of valid measurement,
without wind), and «geteilt» for a shared measurement. Above the list is the chart of the selected
measurement (tap a card to select it; the newest one is shown first), drawn by the app (Compose
`Canvas`, no chart library):

- **Views:** **Messung** — the measurement's own time span, start to stop (a running one up to
  now); **Tagesverlauf** — all days of the measurement laid over one 0–24 h axis, one point per hour
  of the day (energy mean of all valid minutes that started in that hour on any day; the band is
  the median of their L90 and L10; an hour needs 10 valid minutes), events at their clock time.
  ‹ › or a sideways swipe on the chart (≥ 15 % of its width, or a fling) move to the older/newer
  measurement.
- **Marks:** a band from L90 to L10 (the background), the LAeq line per minute, and every event
  as a dot at (start, LAFmax); wind events as small hollow grey dots below the others (not in the
  hourly views; setting «Wind-Ereignisse in der Grafik zeigen»). One category is highlighted in orange and drawn on top (default
  Töff & Poser; chosen with the scrolling row of chips under the chart and remembered; the
  selected chip carries the same orange dot), all others as smaller grey dots. The y axis runs from the lowest L90 − 5 dB to the loudest shown event or
  LAeq + 5 dB (rounded to 5 dB, at least 30 dB).
- **Gaps:** the line and band are never drawn across a missing minute or one with < 50 % valid
  audio; spans of 10 min or more get a light «keine Messung» area. Time after now is not a gap.
- **Hourly values:** measurements longer than 36 h are drawn with hourly values (energy mean over
  the valid minutes, weighted by their valid seconds; L10 = maximum and L90 = minimum of the minute
  values; an hour needs 30 valid minutes), and so is the Tagesverlauf. There only events of the
  highlighted category ≥ max(floor, 60 dB(A)) are drawn, at most the loudest 300; the event count
  in the summary still includes all events.
- **Touch:** a tap shows a crosshair and a tooltip for the nearest minute (hour), or for an
  event dot within 16 dp (with weekday and date if the measurement spans more than one calendar
  date); while the tooltip is open, dragging sideways moves the crosshair.
  Tapping the tooltip (or outside the plot) closes it.
- **Summary:** three tiles — **«Grundrauschen (L90)»**, the hum, **«Lärmspitzen»**, bursts per hour
  of valid measurement without wind (below it «n Wind ausgeschlossen»), and **«Lautestes»**, the
  loudest burst — and a line with the LAeq of the measurement, the number of events (and of the
  highlighted category), the measured share of the time, the **dynamics** (median over the valid
  minutes of L10 − L90: a few dB for steady traffic, more for a quiet street with bursts) and any
  interruptions. The summary is the same in both views.

The chart code is in `chart/`: `Windows.kt` (axis arithmetic of the two views), `ChartModel.kt` (data → model:
series, runs, gaps, y range, events, summary), `ChartGeometry.kt` (pixels, hit testing),
`NoiseChart.kt` and `HistorySection.kt` (Compose). It is tested on the JVM, and rendered without a
device with [Paparazzi](https://github.com/cashapp/paparazzi):

```sh
STADTLAERM_SCREENSHOT_DIR=/tmp/shots ./gradlew :chart:testDebugUnitTest --rerun
# optional: also render real data from the app's CSV export (minuten.csv, ereignisse.csv)
STADTLAERM_REAL_DATA=/path/to/export ./gradlew :chart:testDebugUnitTest --rerun
```

The renders use synthetic data (`chart/src/test/.../SyntheticData.kt`); real exports are only
read from the directory given in `STADTLAERM_REAL_DATA` and never belong in the repository.

## Source categories

The on-device classifier is [YAMNet](https://www.kaggle.com/models/google/yamnet) (521 AudioSet
classes, MediaPipe float32 build, Apache-2.0), run on the latest 0.975 s every second. Its
scores are grouped into project categories by `app/src/main/assets/categories.json`. Category
score = max of its members. The dominant category is (a) the category of the **top-1 label** if
that label is mapped and scores ≥ `top1_threshold` (0.10), else (b) the highest category score if
≥ `category_threshold` (0.20), else (c) "unclassified". Rule (a) exists because quiet, distant
sources score low even when the best label is clearly right (a highway pass-by: «Vehicle» at
0.1–0.3). Ties are broken by list order, so **Töff & Poser beats Strassenverkehr**. Stored events
keep the category they were given when measured:

| id | UI name | AudioSet classes |
|---|---|---|
| `loud_vehicle` | Töff & Poser | Motorcycle; Accelerating, revving, vroom; Race car, auto racing; Engine starting |
| `road_traffic` | Strassenverkehr | Vehicle; Car; Car passing by; Motor vehicle (road); Traffic noise, roadway noise; Truck; Bus; Engine; Idling; Heavy/Medium/Light engine; Tire squeal; Skidding; Vehicle horn, car horn, honking; Air brake |
| `rail_tram` | Tram & Bahn | Rail transport; Train; Railroad car, train wagon; Train wheels squealing; Subway, metro, underground; Train horn; Train whistle |
| `aircraft` | Flugzeug | Aircraft; Aircraft engine; Jet engine; Fixed-wing aircraft, airplane; Propeller, airscrew; Helicopter |
| `construction` | Baustelle | Jackhammer; Drill; Power tool; Sawing; Hammer; Sanding; Chainsaw; Filing (rasp) |
| `voices` | Stimmen | Speech; Conversation; Shout; Yell; Children shouting; Children playing; Crowd; Chatter; Laughter; Hubbub, speech noise, speech babble; Cheering |
| `music` | Musik | Music; Pop/Rock/Hip hop/Electronic/Electronic dance/Dance/House music; Techno; Drum and bass; Disco; Reggae; Singing |
| `unclassified` | Sonstiges / unklassifiziert | everything else (birds, wind, silence, …) |
| `wind` | Wind | not a classifier category: the detector's wind flag (since v0.4.0, see [Event detection](#event-detection-detector-v2)) |

A unit test fails the build if any name in the JSON does not exist exactly in the shipped label
file (`yamnet_labels.txt`, extracted from the model's own metadata).

The classifier sits behind the `SoundClassifier` interface (`dsp/.../classify/SoundClassifier.kt`),
so a better model can be swapped in without touching the measurement code.

## Editions

| | «Offline-Version» | «Online-Version» | «Weniger Lärm Labor» | «Weniger Lärm Labor · Karte» (internal) |
|---|---|---|---|---|
| Gradle flavour | `offline` (default variant) | `public` | `labor` | `laborKarte` |
| `BuildConfig.EDITION` | `"offline"` | `"karte"` | `"labor"` | `"labor-karte"` |
| App id (since 0.6.0; before `ch.stadtlaerm.*`) | `ch.lessnoise.app` | `ch.lessnoise.app` | `ch.lessnoise.labor` | `ch.lessnoise.labor` |
| Version name | `0.6.0` | `0.6.0` | `0.6.0-labor` | `0.6.0-labor-karte` |
| `INTERNET`, `ACCESS_NETWORK_STATE` | no | yes, for «Messwerte teilen» and «Laut! Melden» only | no | yes, the same |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | no (removed) | yes, for the fix at «Start» of a shared measurement and «Laut! Melden» only | no (removed) | yes, the same |
| `ACCESS_BACKGROUND_LOCATION` | no | no | no | no (removed in `src/main`) |
| `BuildConfig.UPLOAD_AVAILABLE` | false | true | false | true |
| Sources | `src/main` + `src/public/kotlin` + `src/offline/AndroidManifest.xml` | `src/main` + `src/public` | `src/main` + `src/labor` | `src/main` + `src/labor/kotlin` + `src/labor/res` + `src/laborKarte/AndroidManifest.xml` |
| Published as | `docs/download/stadtlaerm.apk` on wenigerlaerm.ch | `docs/download/stadtlaerm-karte.apk` on wenigerlaerm.ch | never | never (not on the website, in a release or on `test-builds`) |

The two published editions share the app id and the release key, so installing one over the other
switches the edition and keeps all measurements. (0.6.0 changed the app id from `ch.stadtlaerm.app`
to `ch.lessnoise.app`, so it installs next to a 0.5.x install, not over it, and starts with empty
storage; the signing key is unchanged.) **Einstellungen → App-Version** shows which edition is
installed and how to switch; «Nach Update suchen» sends the edition to the update page
(`&e=offline` / `&e=karte`; the Labor builds send no `e`). When the offline edition starts on a
phone where the Karte edition had «Messwerte teilen» on, it switches sharing off, drops reports not
yet sent and cancels the upload jobs (device id and token are kept, so the Karte edition can still
delete the server data). The upload classes are compiled into the offline edition too (shared
`src/main`), but every entry point returns on `UPLOAD_AVAILABLE = false` and the manifest has no
`INTERNET`.

## Install (sideload)

The signed release APKs are on the website under fixed names:
`wenigerlaerm.ch/download/stadtlaerm.apk` («Offline-Version») and
`wenigerlaerm.ch/download/stadtlaerm-karte.apk` («Online-Version»);
[wenigerlaerm.ch](https://wenigerlaerm.ch) links both and shows the current version and each file's
SHA-256.
A German step-by-step guide is on the website.

> **Upgrading from an earlier test build (v0.1.x)?** Those were debug builds signed with a
> different key, and Android refuses to update an app across signing keys. Export your data
> (Daten → CSV exportieren), **uninstall the old Stadtlärm**, then install the current version.
> From v0.2.0 to v0.5.1, updates install over the previous version and keep the data. v0.6.0 has a
> new app id (`ch.lessnoise.app`): it installs as a separate app «Weniger Lärm» next to «Stadtlärm»
> 0.5.x, starting with no data; uninstall «Stadtlärm» once you no longer need its data.

1. Download the APK on the phone. If the browser's download dialog refuses to open it, open it
   from the **Files** app (Dateien → Downloads).
2. Android asks to allow installing unknown apps from that source (Files, Chrome, …): allow it.
3. If Play Protect warns about an unknown developer: *More details → Install anyway*.
4. Or with a computer: `adb install -r stadtlaerm.apk` (or `stadtlaerm-karte.apk`).
5. Start Weniger Lärm and allow **microphone** and **notifications** when asked.

To check the download: `sha256sum stadtlaerm.apk` (or `stadtlaerm-karte.apk`) must match the value
on the website, and `apksigner verify --print-certs <file>` must show the signer
`CN=Christian Frank, O=Stadtlaerm, L=Zuerich, C=CH` with certificate SHA-256
`a3d5d10e69b383846ead4d1aac9ea5df9bc32845237506dcd233f097743142be`.

Requirements: Android 8.0 (API 26) or newer, ARM phone (arm64-v8a or armeabi-v7a). Not yet
validated on many devices — see "Status" below.

## Updates without internet access

The app never asks a server for updates itself (up to v0.4.0, and in the offline edition, it has no
internet permission at all; in the Karte edition the internet is used only for «Messwerte teilen»
and «Laut! Melden», never for update checks).
Instead:

- **Einstellungen → App-Version** shows «Offline-Version 0.6.0 (Build vom 12.10.2026)» (the edition's
  name; «Online-Version …» in the edition with the map; the launcher label of both is «Weniger Lärm») and a button **Nach Update suchen**. It opens
  `https://wenigerlaerm.ch/update.html#v=<versionName>&c=<versionCode>&e=<edition>` in the browser
  (`e` since v0.5.0, `BuildConfig.EDITION` of the two published editions; the Labor builds send none). The page
  ([docs/update.html](../docs/update.html)) carries the published version as
  `data-version`/`data-code` and a small inline script compares the versionCode from the URL
  fragment with it (the version strings if `c` is missing). It offers the download of the same
  edition (`e` = `offline` or `karte`; anything else, or no `e` as from apps up to 0.4.0, gets the
  offline edition) and a link to the other edition. The part after `#` is never sent to
  the server, so the comparison happens only in the browser. Without a browser the app shows
  «Kein Browser gefunden».
- **Age reminder, fully offline:** `BuildConfig.BUILD_DATE` is the build date (ISO, Zürich
  calendar day of the Gradle build; `SOURCE_DATE_EPOCH` overrides it). From 30 days after it,
  the top of Einstellungen and a line under «Messung starten» say «Diese App-Version ist n Tage
  alt. Nach Update suchen?» (tap = same as the button). «Später» hides it for 14 days. If the
  phone's clock went backwards since «Später», the snooze is ignored. The logic is pure Kotlin
  (`app/.../update/UpdateCheck.kt`) with JVM tests.

## Measuring

1. Put the phone where you want to measure — ideally at an open window or on the balcony,
   microphone facing the street, out of wind and rain. Behind closed glass you measure the
   indoor level (typically 25–35 dB lower).
2. Optionally connect the charger. Measurement does not depend on charging; the charger only
   saves battery (see below).
3. **Messen → Messung starten.** In the «Online-Version» with «Messwerte teilen» on, the phone
   first takes one location fix for the measurement's place, moved at random on the phone (if that fails: «Nochmals»,
   «Koordinaten einfügen» or «Ohne Teilen starten»). While measuring, a persistent notification
   «Mikrofon aktiv – Messung läuft» with the current LAeq and a «Stopp» button is shown (its icon
   stays in the status bar); you can turn the screen off.
4. When you are done — after an hour, a night or several days: **Messung stoppen** (or «Stopp» in
   the notification), then look at **Messungen**.

**Closing the app.** By default the measurement keeps running as a background service when the
app is swiped away from the recent apps; the notification shows it and has the «Stopp» button.
With **Einstellungen → Messung → «Messung beenden, wenn die App geschlossen wird»** (off by
default) swiping the app away stops the measurement exactly like «Stopp»: the current, partial
minute is still saved.

### Battery

Measurement runs in a foreground service of type *microphone* so Android keeps it alive with the
screen off. By default the app additionally holds a partial wake lock while measuring (setting
"CPU wach halten"). The audio system already keeps the CPU partly awake while recording, so the
extra cost should be small; not yet measured on a device — my estimate is a few % of battery
per hour, plus one classifier inference per second (expected tens of milliseconds on a mid-range
phone). The app keeps measuring on battery; nothing in it depends on the charger. Over many
hours that estimate adds up to a noticeable share of the battery, so the charger is convenient,
not required («Messung & Akku» in Einstellungen). For measuring on battery, exempt Weniger Lärm from
battery optimisation (Einstellungen → Akku-Optimierung öffnen; on a Pixel: Settings → Apps → Weniger Lärm → App battery
usage → Unrestricted). Phones with aggressive power management (Xiaomi, Huawei, Samsung "deep
sleep") may need an extra exemption in the maker's own battery settings.

## Calibration (step by step)

Phones differ by several dB, so for data you want to show to anyone, calibrate. Calibrations are
stored per **device model and audio source** with a full history; every minute record carries the
id of the calibration that was active.

### Method 1 — compare with a sound level meter (recommended)

You need a Class 1 or Class 2 sound level meter that can show LAeq (borrow one: many
environmental offices and universities lend them).

1. Stop any running measurement. Open **Kalibrieren**.
2. Put the phone's microphone (usually at the bottom edge) right next to the meter's microphone,
   a few centimetres apart, both pointing at the source.
3. Create a **steady** noise of at least 60 dB(A): pink noise from a loudspeaker 1–2 m away is
   ideal; steady, dense traffic works too. Avoid speech or music.
4. Choose 30 s or 60 s, tap **Messung starten**, and at the same moment start an LAeq measurement
   of the same length on the meter.
5. When the countdown ends, type the meter's LAeq into the field. The app shows the new offset
   (`reference − measured`).
6. Check the warnings: standard deviation of the 1 s levels > 2 dB means the noise was not steady
   enough; a reference below 50 dB(A) is too close to the phone's own noise floor. Repeat if
   warned.
7. Tap **Kalibrierung übernehmen**. Repeat once or twice; the results should agree within ~0.5 dB.

### Method 2 — acoustic calibrator

1. Select 94 dB or 114 dB to match your calibrator (1 kHz).
2. Couple the calibrator tightly to the phone's microphone port. Phone microphones need an
   adapter (a rubber coupler or a 3D-printed sleeve); without an airtight seal the result is wrong.
3. Switch the calibrator on and tap **Kalibrator messen (10 s)**.
4. The app checks that the dominant frequency is 1 kHz ± 5 % and that the signal is a clean tone;
   otherwise saving is blocked. Offset = nominal − measured (A-weighting is 0 dB at 1 kHz).

### Method 3 — manual offset

Enter an offset determined elsewhere (e.g. another phone of the same model calibrated with
method 1). Use **Auf Standard** to return to the uncalibrated CDD default.

### Noise floor check

**Eigenrauschen messen** records 20 s in the quietest place you have and shows the resulting
LAeq. Levels within ~5 dB of this value are dominated by the phone's own noise and are not
meaningful. Many phones bottom out somewhere around 30–40 dB(A) (to be confirmed per model).

The calibration history can be exported as JSON (Kalibrieren → Als JSON exportieren).

The active calibration is always the most recent one for this device and audio source; there is no
way to pick an older one from the history (save it again as a manual offset instead).

### The event floor follows the calibration

The event floor («Mindestpegel», an absolute LAFmax) is chosen on the levels you see. Levels are
`raw + offset`, so a new offset shifts every level, and a floor tuned on uncalibrated data (default
offset 112.35 dB) would filter a different set of sounds after calibrating (a real calibration
typically moves the offset by +5 to +15 dB). Therefore, whenever a calibration is saved and becomes
active for the current device and audio source (all three methods, and **Auf Standard**), the app
sets

```
floor_new = round_0.5(floor_old + (offset_new − offset_previous))     clamped to 20–70 dB(A)
```

where `offset_previous` is the offset the measurement would have used until then (the default if no
calibration was active). The calibration screen confirms it, e.g. «Kalibrierung gespeichert. Offset
+9.6 dB gegenüber vorher; der Mindestpegel für Ereignisse wurde von 30.0 auf 39.5 dB(A)
angepasst.»; if the range limit bites, the message says so and gives the unclamped value. The
setting moves in 0.5 dB steps. The arithmetic is pure Kotlin (`EventFloor` in
`dsp/.../calibration/Recalibration.kt`) with unit tests.

### Re-evaluating old measurements («nachträglich kalibriert»)

For the same phone and the same audio source, a level stored with offset O1 corresponds under a
new offset O2 to `level − O1 + O2`. So measurements made before a calibration (or with an older
one) can be corrected afterwards:

- After saving a calibration the app asks «Frühere Messungen mit dieser Kalibrierung neu bewerten?
  Betrifft n Minuten und m Ereignisse …» (**Neu bewerten** / **Nicht jetzt**). The same action is in
  **Daten → Alte Messungen neu bewerten** (disabled, with the reason, when no calibration is active,
  nothing qualifies, or a measurement is running). Nothing happens automatically.
- **Scope:** minutes and events with the same audio source (`UNPROCESSED` and `VOICE_RECOGNITION`
  are calibrated separately) whose `calibration_id` differs from the new calibration's (including
  data measured without a calibration). Measurements are only ever made on this phone, so the device
  model matches.
- **Update:** every level column (minutes: LAeq, LAFmax, LAFmin, L1, L10, L50, L90; events:
  LAFmax, SEL, background) is shifted by `new offset − offset of the original measurement`;
  `calibrated` becomes true, `calibration_id`/`calibration_offset_db` are those of the new
  calibration. Event threshold (relative to the background or the local floor), the stored floor
  `min_level_db` (the floor in force at the time, on the original scale), `event_count`,
  `wind_event_count` and the event features that are differences (excess, rise, decay, …) stay as
  recorded. The local floor (v0.4.0) is a level and moves too, without an `orig_` column: for
  events it is `LAFmax − excess`, for minutes it is shifted by the difference of the offsets stored
  with the minute, so it does not compound either.
- **Originals are kept and corrections never compound:** the first re-evaluation copies the stored
  levels into `orig_*` and records the calibration they were measured with in
  `recalibrated_from_id` (its id, or `default`) and `recalibration_offset_db`. Every later
  re-evaluation computes from `orig_*` and leaves these three as they are, so A→B→C gives exactly
  the same values as A→C. Events store no offset of their own; theirs is the offset of their
  calibration (or the default).
- It runs in the background with a progress bar, in one database transaction (all or nothing,
  in batches of 500 rows).
- **Marking:** re-evaluated data counts as calibrated, so the «unkalibriert» badge disappears. The
  measurement list and the chart summary show «nachträglich kalibriert» when any minute of the
  measurement was re-evaluated, and the chart tooltip says «nachträglich kalibriert» instead of
  «unkalibriert».

## Data export

**Daten → CSV exportieren & teilen** creates two UTF-8 CSV files (minutes, events) with a
`.` decimal separator and ISO-8601 timestamps with zone offset, and hands them to the Android
share sheet. Column names are in the first row (`laeq_db`, `lafmax_db`, `share_loud_vehicle`, …).
Since v0.3.2 both files end with the re-evaluation columns (appended, the earlier columns keep
their order): minutes `orig_laeq_db`, `orig_lafmax_db`, `orig_lafmin_db`, `orig_l1_db`,
`orig_l10_db`, `orig_l50_db`, `orig_l90_db`, events `orig_lafmax_db`, `orig_sel_db`,
`orig_background_db`, and in both `recalibrated_from_id`, `recalibration_offset_db`. They are empty
for data that was never re-evaluated. Since v0.4.0 (appended after those): minutes `local_floor_db`,
`wind_event_count`; events `local_floor_db`, `excess_db`, `rise_s`, `decay_s`, `jaggedness`,
`mid_band_rise_db`, `lf_share`, `lf_flutter_db`, `wind`, `shape` (empty, `wind = false`, for events
recorded before). The export is per minute and per event; it has no night or measurement
aggregation (v0.6.0 changed no CSV column). The files are named `wenigerlaerm-minuten-<stamp>.csv`
and `wenigerlaerm-ereignisse-<stamp>.csv` (before v0.6.0 `stadtlaerm-…`).

## «Messwerte teilen» (opt-in upload)

Phones whose owners opt in send the minute and event records of their **shared measurements** to
the project's server for the shared noise map ([../server/DESIGN.md](../server/DESIGN.md): §2
privacy rules, §4 the exact request bodies, §9 the app changes), and «Laut! Melden» reports. Only in
the «Online-Version» (and the internal Labor build with the map). **Einstellungen → Messwerte
teilen**:

1. **Explanation** (shown until confirmed once): what is sent (the minute values and bursts of
   shared measurements, each with the place where the measurement was started, moved at random
   on the phone by the location noise — so loud moments of a shared measurement appear on the map; reports with time, exact place and category;
   phone model, audio source, calibration and the placement answers; a random id), what is never
   sent (audio, the exact location except with a report, name or account), where it goes
   (`api.lessnoise.ch`, Switzerland; only values per 100 m field are public), how to delete.
   Below the «Standort» card: **«Standort verschieben»** — σ of the location noise, 25 / 50
   (default) / 100 / 200 m, with the line «Der gesendete Punkt wird zufällig um etwa diese Strecke
   verschoben, …»; it applies from the next «Start». There is no «exact» choice.
2. **Placement** («Aufstellung (freiwillig)»): placement (offenes Fenster / Balkon / hinter Glas /
   anderes), floor, street or courtyard side, an optional note (≤ 200 characters, never
   published); all optional, remembered for the next measurements. The fixed hectare of v0.5.x is
   gone: the place is now chosen per measurement (below).
3. **The switch** «Messwerte teilen» (off by default; needs 1), «Nur über WLAN» (default on: only
   unmetered networks), «Zuletzt gesendet: …», «Noch zu senden: n Minuten» (of shared measurements),
   the last error and the count of records the server refused for good; a «Meldungen» card with the
   reports waiting and the last sent ones (category, time, cell).
4. **«Daten auf dem Server löschen»** (confirms, waits for the server's answer, then switches
   sharing off and drops unsent reports) and **«Neue Kennung»** (deletes the server data, then sends
   under a fresh id).

**Location per measurement (v0.6.0).** With sharing on, **Messung starten** first takes **one**
location fix (`ui/LocationFlows.kt` → `location/LocationFetcher.locate`): it requests
`ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION` if needed (Activity Result API), then asks the
platform `LocationManager` (no Google Play Services): GPS and network provider, whichever are
enabled, at the same time — `getCurrentLocation` on API 30+, `requestLocationUpdates` removed after
the first update on API 26–29; the first fix ≤ 25 m ends the wait, otherwise the best one after
both answered or after 30 s («Standort wird bestimmt …», «Abbrechen»). A last-known location is
used only if younger than 2 min and accurate to ≤ 50 m. The fix goes straight through
`CellInput.fromLatLon` (WGS84 → LV95, the same formulas as the map's `docs/map/lv95.js`) and
`LocationNoise.shift` (`dsp/geo/LocationNoise.kt`): the true LV95 point plus a Gaussian offset,
independent N(0, σ²) east and north, σ = «Standort verschieben», rounded to whole metres and kept
inside the server's box. The offset is fixed per place: its two normal draws come (Box–Muller)
from HMAC-SHA256(device noise secret, "<true hectare>|<σ>"), so every «Start» in the same hectare
gives the same offset and averaging many measurements cannot remove it (server/DESIGN.md §2
rule 2). The secret is 32 random bytes made at the first shared «Start» (`upload/NoiseSecret.kt`,
stored encrypted next to the upload token, never sent) and dropped by «Neue Kennung» and «Daten
auf dem Server löschen». The point is accepted only with «Genauer Standort» and accuracy ≤ 75 m
(`location/LocationPolicy.kt`, pure Kotlin). If it fails, one dialog says why in one sentence and offers «Nochmals», the app or
location settings where they help, «Koordinaten einfügen» (WGS84 decimal degrees `47.3769, 8.5417`,
LV95 `2'683'304, 1'247'925`, LV03 or a cell id — its centre —, `upload/CellInput.kt`; moved the same
way), «Ohne Teilen starten» and «Abbrechen». The shifted point, its σ and its display cell are
stored with the measurement (`MeasurementEntity.pointE/pointN/sigmaM/cell`, Room v7) and passed to
the service; the coordinates and the offset live only in local variables. With sharing off no
location is read and the measurement has no point; a measurement without a point is never sent
(also not a row of a 0.6.0 test build that still has only a cell).

**«Laut! Melden» (v0.6.0).** On **Messen**, while no measurement runs, a secondary button «Laut!
Melden»: «Was war laut?» (Töff, Auto, Lastwagen, Poser, anderes → ids `motorrad`, `auto`,
`lastwagen`, `poser`, `andere`) → one precise fix (`LocationFetcher.locateForReport`, accuracy
≤ 50 m, else the reason and «Nochmals») → «Meldung: Töff, 16:05, Standort ± 12 m – senden?»
(«Senden» / «Abbrechen»). «Senden» queues the report (`upload/Reports.kt`: time of the tap to the
second with the phone's offset, latitude/longitude, accuracy, category, and its cell) and schedules
an upload (same constraints as the other uploads; registers the device first if needed). If
sharing is off, the first «Melden» shows a short consent first and switches sharing on only with
«Ja, einschalten». The queue is a private SharedPreferences file (`reports`, written with
`commit()`); once the server has answered for a report its coordinates are deleted and only
category, time and cell stay in the list of sent reports. Switching sharing off, deleting the
server data, or the offline edition taking over also drops unsent reports. While a measurement
runs there is no «Melden» button: its loud events are uploaded with the measurement's cell anyway.

**What happens in the background.** At the first opt-in the shared measurements of the last 7 days
are included; afterwards nothing measured while sharing is off is ever sent. A WorkManager job runs
5 minutes after every full hour, and one-shot jobs 15 s after a measurement stops and 5 s after a
report; each first sends waiting reports (`POST /v1/devices/{id}/reports`, ≤ 50 per request), then
every minute and event newer than the last start the server acknowledged, in batches of ≤ 1440
minutes / ≤ 2000 events (gzip JSON, `Authorization: Bearer <token>`), each with `"cell":
"h<E100>_<N100>"` of its measurement; records of measurements without a cell are skipped (the
markers move past them, so they are never sent later). Markers move only after a batch was
acknowledged, so an interrupted upload resumes where it stopped; the server is idempotent on
`(device, start)`. Records the server lists in `rejected` are remembered and never sent again. A
server without the reports call (404/405) keeps the reports for a later run; any other refusal of a
report batch drops it. The site call (`PUT …/site`) sends the placement details with the cell of
the newest shared measurement. After «Alte Messungen neu bewerten» the last 7 days are sent again
with the new levels. At most 24 requests per run (the server allows 60 per device and hour); a
longer backlog continues 30 min later. Errors back off exponentially from 10 minutes; a refused
token (401) stops uploads until «Neue Kennung». The local database stays the source of truth:
nothing is deleted on the phone except the coordinates of sent reports.

**Code.** Everything network is in `app/src/main/kotlin/ch/lessnoise/app/upload/`:
`UploadClient.kt` (the only class that opens connections: `HttpURLConnection`, one host from
`BuildConfig.API_BASE`, HTTPS only, no redirects), `Payloads.kt` (the request bodies),
`Uploader.kt` (batching, markers, cells per record, reports, `rejected`, delete; pure JVM),
`Reports.kt` (the report queue), `UploadState.kt` (settings, id, markers; the token encrypted with
an Android Keystore key), `UploadWorker.kt` (WorkManager scheduling). The UI is `ui/ShareScreen.kt`
and `ui/LocationFlows.kt`; the fixes are `location/` (the upload package never calls it). For a
local test server build with `-Pstadtlaerm.api=https://…`. The Labor build has none of it enabled
and no network permission (`BuildConfig.UPLOAD_AVAILABLE = false`, `src/labor/AndroidManifest.xml`).

**Tests.** `app/src/test/.../upload/UploadTest.kt` runs the uploader against a small HTTP server
on 127.0.0.1 (`FakeServer.kt`, the server's semantics): register, site, exact bodies (no labels, no
originals, no coordinates, the measurement's `cell`), nothing from a measurement without a cell, batch limits, duplicates, resume after a failed batch, `rejected`,
refused token and «Neue Kennung», synchronous delete, failed delete, off by default, the 7-day
window, rate limit / 4xx / no network, coordinate entry. `ReportsTest.kt` checks the report
categories and body, ≤ 50 per request, that coordinates are gone after sending (and after switching
off or deleting), and the 404/4xx handling. `app/src/test/.../data/MeasurementsDbTest.kt` checks the
migration 5 → 6 on SQLite and how rows become measurements. `app/src/test/.../location/LocationPolicyTest.kt`
checks lat/lon → cell (Zürich points from `lv95.js`), the accuracy/«Ungefähr»/last-known rules, the
≤ 50 m rule of reports and the texts; `LocationIsolationTest.kt` checks the manifests (location only in `public`/`laborKarte`,
removed in `offline`/`labor`, no background location) and that the location code logs, stores and
sends nothing. `dsp/src/test/.../geo/Lv95Test.kt` checks
the conversion against the five reference points of DESIGN.md §3 and against values computed with
the website's `lv95.js`.

## Known limitations

- **Phone microphones are not measurement microphones.** Frequency response, self-noise and
  directivity vary by model. Even calibrated, expect a few dB of uncertainty, more at low and
  high frequencies. Uncalibrated data is a rough indication only (±5 dB or worse).
- **Audio source.** The app uses Android's `UNPROCESSED` source when the phone declares support
  for it, otherwise `VOICE_RECOGNITION`, and switches off AGC/noise suppression/echo
  cancellation where Android allows it. Some phones still apply processing; calibrate per source.
- **Wind** causes large false low-frequency levels. Shield the phone; don't measure in strong
  wind. Since v0.4.0 events that look like wind are flagged and not counted, but the levels (LAeq,
  L90 …) still contain the wind. The wind rule was derived from one night at one window and is
  not validated elsewhere; a sharp sub-second broadband impulse (a door slam) can also have a
  high LF flutter and be flagged as wind.
- **Behind glass vs open window**: these are different measurements; note which one you made.
  Reflections from the façade raise levels right at a wall by up to ~3 dB.
- **No tram class.** AudioSet has no "tram" class. Zürich's trams (a major noise source,
  including wheel squeal in curves) are classified as "Rail transport", "Train", "Train wheels
  squealing" or sometimes as road traffic. Treat the Tram & Bahn share as indicative.
- **YAMNet was trained on YouTube audio**, which is louder and closer-miked than a street at
  night. Measured with the real model, speech at −60 dBFS is already partly reported as
  "Silence". The app therefore amplifies quiet windows for classification only (up to +40 dB,
  setting "Pegelanpassung"); levels are never affected. Classifications of distant sources stay
  uncertain; motorcycles and cars are often confused, and the parent class "Vehicle" tends to
  dominate.
- **Duration of events** is measured on the Fast-weighted level, so it includes the decay tail
  (≈ 35 dB/s); a 2 s pass-by 25 dB above background is reported as ≈ 2.7 s.
- **A lasting level step** (the hum rises by more than the excess and stays) is one event of up to
  300 s, because the floor is frozen during an event; afterwards the floor has caught up.
- **Timing**: sample time is anchored to the wall clock when the first audio block arrives and
  re-anchored once per minute if the audio clock has drifted by more than 0.5 s (the number of
  corrections is stored per minute as `clock_corrections`). Timestamps are therefore accurate to
  roughly the audio input latency (tens of ms) plus up to 0.5 s of drift. Minutes are aligned to
  wall-clock minutes and close on the first 125 ms tick after the boundary.
- **Silenced microphone**: on Android 10+ a phone call or voice assistant silences the app's
  microphone. The app detects this (system callback plus a digital-silence check) and does not
  evaluate that time; the Measure screen shows a notice, minutes store `valid_s`/`coverage`, and
  calibrations in progress are aborted. Data recorded with v0.1 has no such check; on upgrade,
  v0.1 minutes with an LAeq below 0 dB(A) are marked as invalid.
- High frequencies: the digital A-filter matches IEC 61672-1 within ±0.23 dB from 20 Hz to
  10 kHz, but rolls off faster above (−1.3 dB at 12.5 kHz, −4.6 dB at 16 kHz vs nominal; still
  inside Class 1 tolerances).
- Settings changes (including the classifier level adjustment) apply from the next start of a measurement.
- Calibration must be done with the app in the foreground; leaving the app aborts the measurement.

## Status of v0.6.0, v0.5.x and v0.4.0

v0.6.0 (versionCode 13) is **in preparation, not published**: the app is renamed «Weniger Lärm»
with new app ids (`ch.lessnoise.app`, `ch.lessnoise.labor`) and Kotlin packages (`ch.lessnoise.*`),
the server host is `api.lessnoise.ch`, nights are gone in favour of measurements (database v5 → v6,
«Messungen» tab with the «Messung»/«Tagesverlauf» chart), and the Karte editions get a location fix
per shared measurement and «Laut! Melden» (see [«Messwerte teilen»](#messwerte-teilen-opt-in-upload)).
Verified on the JVM only (unit tests, the migration against SQLite, the upload and reports against
the fake server) and with `aapt2` on the four debug APKs; not run on a device, and the new reports
call needs the server side of 0.6.0.


v0.5.0 (versionCode 11, published 2026-10-11; v0.5.1 added «Standort bestimmen»): v0.4.0 plus the opt-in
[«Messwerte teilen»](#messwerte-teilen-opt-in-upload), off by default, developed on the
`map-design` branch (where it was numbered "0.4.0", code 10, and never published). It is built in
[two published editions](#editions): the Offline-Version (no internet permission, the
default download and the normal update for 0.4.0 users) and the Karten-Version (with «Messwerte
teilen»). Wind-flagged
events are not uploaded, and the minutes' `event_count` sent is the count without wind. The upload
client is unit-tested on the JVM, including a round trip against a local copy of the server
(`RealServerTest`, skipped unless `STADTLAERM_TEST_SERVER` is set); the server ran at
`api.stadtlaerm.ch`. Signed 0.5.0 APKs of the two editions are built at release time (see the
release checklist); 0.5.0 has not been run on a device.

v0.4.0 (engine «detector v2», both flavours, versionCode 9) is the public **test version**
published on stadtlaerm.ch (since 2026-10-09). It changes the event detection (local floor,
excess, features, wind flag; see [Event detection](#event-detection-detector-v2)), the database
(v4 → v5), the CSV columns and the summaries (hum vs bursts). Verified on the JVM only: unit tests,
the migration against SQLite, an offline replay of three synthetic WAV fixtures through the engine
(`dsp/src/test/.../ReplayTest.kt`; `./gradlew :dsp:replay --args="clip.wav"` replays any 16 or
48 kHz mono WAV, e.g. Labor clips, and prints events and minutes as CSV — note that the local floor
needs 5 s of history, so for the Labor clips' 5 s pre-roll use `--min-history 3`) and Paparazzi
renders, and the replay of one real night (see "Validation on a recorded night"). For such data:
`./gradlew :dsp:replayBatch --args="--manifest manifest.jsonl --clips clips/ --out v2.csv [--excess 6.5,5,8]
[--floor 30,20] [--min-history 3]"` replays every Labor clip and writes, per clip, the v2 event that
overlaps the manifest's event; `./gradlew :dsp:replayNight --args="--manifest manifest.jsonl --wavs dir
--session <id> --out prefix"` replays a session's continuous hour files (decoded to 16 kHz WAVs with
ffmpeg first) with the real 30 s history — clips alone have only 5 s of pre-roll, during which a
slow pass is often already rising, so the clip replay underestimates detection. Not yet checked:
the thresholds on real nights other than the one they were derived from,
the CPU cost of the extra filters on a phone, the settings screen and the one-time migration
notice on a device.

v0.3.4: no functional change from v0.3.3 in the public app. v0.3.3 was a public **test version**: v0.3.2 plus a clearer measurement notification («Mikrofon
aktiv – Messung läuft», visible in the status bar) and the setting «Messung beenden, wenn die App
geschlossen wird»; the code was split into two product flavours (`public` and the unpublished
[Labor build](#labor-build)) without any change to the measurement. v0.3.2: v0.3.1 (chart, event
floor, update check without internet permission) plus an event floor that follows the calibration and the re-evaluation of old
measurements with a new calibration (see [CHANGELOG](CHANGELOG.md)). The arithmetic, the database
migration (v3 → v4) and the CSV columns are unit-tested on the JVM; the dialogs, the progress bar
and the update check have not been tried on a phone yet.

- The chart is verified with JVM unit tests and JVM renders (Paparazzi) in light and dark mode,
  with synthetic data and with a real night; touch gestures and performance have not yet been
  tried on a phone.

- Built and unit-tested on the JVM: all DSP (A-weighting, Fast weighting, levels, percentiles,
  events, resampler, category mapping, calibration math, night summaries, CSV).
- Model input pipeline verified against the real `yamnet.tflite` with Python/LiteRT
  (`tools/verify_yamnet.py`).
- **Not yet validated on many devices.** Audio capture, the foreground service, the LiteRT
  runtime on Android, battery use and the UI need testing on a range of real phones. Reports
  are welcome as GitHub issues (please include the phone model and Android version).

## Labor build

«Weniger Lärm Labor» (`ch.lessnoise.labor`, version name `0.6.0-labor`) is a separate app built
from the same code that can **record audio**, to debug the event detector and the sound-source
classifier with real sound — e.g. why highway passes of cars and motorbikes (3–10 s, ≈ 13 dB above
a quiet background) are not recognised: each clip comes with the classifier's per-second results
(top-5 labels and scores, category decision, the input gain applied to the model, LAF), so you can
listen to what the model heard and see what it scored. It is installed next to the public app (own app id, own data, red
icon, a red banner «WENIGER LÄRM LABOR – kann Audio aufzeichnen» on every screen) and is signed with
the release key so that it can be updated. It is **never published**: not in `docs/`, not linked
from the website. The public app contains none of its code (how to check: PRIVACY.md →
«Labor-Build»).

```sh
./gradlew testLaborDebugUnitTest assembleLaborDebug
./gradlew assembleLaborRelease -Pstadtlaerm.keystoreProperties=…   # signed
# APK: app/build/outputs/apk/labor/release/app-labor-release.apk (keep it in android/dist/, git-ignored)
```

**«Weniger Lärm Labor · Karte»** (flavour `laborKarte`, internal, for the maintainer's own test phone,
never published): the Labor build plus the opt-in «Messwerte teilen» and «Laut! Melden» (`INTERNET`,
`ACCESS_NETWORK_STATE`, `ACCESS_FINE_LOCATION`/`ACCESS_COARSE_LOCATION` for the fix at «Start» and
for reports, the Karte edition's upload code; audio is never sent, checked by
`UploadSourceIsolationTest`, see PRIVACY.md → «Labor-Build»). It compiles `src/labor/kotlin` and
`src/labor/res` but not `src/labor/AndroidManifest.xml`; its own manifest is
`src/laborKarte/AndroidManifest.xml`. Same app id as Labor, so it installs over a Labor install and
keeps measurements and recordings; installing the plain Labor build over it switches sharing off.
Banner «WENIGER LÄRM LABOR · KARTE – kann Audio aufzeichnen». Use the plain Labor build for any phone
that measures in someone else's home.

```sh
./gradlew testLaborKarteDebugUnitTest assembleLaborKarteDebug
./gradlew assembleLaborKarteRelease -Pstadtlaerm.keystoreProperties=…   # signed
# APK: app/build/outputs/apk/laborKarte/release/app-laborKarte-release.apk (android/dist/, git-ignored)
```

**Settings → Labor.** «Audio während der Messung aufzeichnen» (off by default; switching it on
asks once), and below it:

- «Ereignis-Clips (±5 s, WAV 16 kHz)» (on): for every event, 5 s before its start to 5 s after its
  end, at most 60 s (longer events: the first 60 s, flagged `truncated`). A 6 s ring buffer of the
  48 kHz input provides the pre-roll; clips are resampled to 16 kHz with the app's anti-aliasing
  filter (the classifier's, group delay removed) and written as 16-bit PCM.
- «Durchgehend (AAC 64 kbit/s, Stundendateien)» (on): AAC-LC, mono, 16 kHz, one M4A per wall-clock
  hour and at every start of a measurement. A file is finalised when the hour ends or the
  measurement stops; if the app is killed, at most the current hour's file is lost.
- «Clip-Rate»: a clip for every event, every 2nd or every 5th (the 1st is always kept).
- «Maximaler Speicher» (default 2 GB): when the audio folder would exceed it, recording stops
  and the banner says so; **measuring continues**.

In addition, while recording is on, every classifier run (once per second) is logged to
`classifier/<yyyyMMdd_HH>.jsonl`, also where no clip exists, so missed events stay visible.

Settings apply from the next start of a measurement; switching recording off stops it at once.
The section also shows the space used and the number of clips and hour files, and has
«Ordner anzeigen», «Als ZIP teilen» and «Alle Aufnahmen löschen».

**Where the files are:** `Android/data/ch.lessnoise.labor/files/audio/` on the phone's shared
storage (`getExternalFilesDir(null)/audio`):

```
audio/manifest.jsonl                       one JSON object per line, appended
audio/clips/ev_<eventId>_<yyyyMMdd_HHmmss>.wav
audio/classifier/<yyyyMMdd_HH>.jsonl       one line per classifier run (≈ 1/s), appended
audio/continuous/<yyyyMMdd_HH>.m4a         (_2, _3 … if the hour already has a file)
```

The folder is visible over USB (file transfer). Many file managers on the phone may no longer
open `Android/data` since Android 11; then use «Als ZIP teilen» or USB.

**Storage per 8 h of measurement:** continuous ≈ 29 MB/h (64 kbit/s) ≈ 230 MB; clips ≈ 32 kB per second
of clip, typically 0.3–0.5 MB each (at most 1.9 MB), e.g. 100 events ≈ 40 MB; classifier log
≈ 0.35 kB per run ≈ 1.2 MB/h ≈ 10 MB; manifest < 1 MB (each clip line carries its trace, ≈ 0.35 kB
per second of clip). Roughly 280 MB per 8 h with everything on.

**Classifier results** (`classifier/*.jsonl` lines and the `classifierTrace` array of a `clip`
line): `time` = end of the 0.975 s model window (ISO-8601 with zone; the result describes the audio
before it), `atMsInClip` (trace only: position of the window end in the clip), `top5` (AudioSet
labels with scores), `category` / `categoryScore` (the category decision for this window; `null`
if the window was ignored because it touched silenced/invalid audio, then `ignored: true`),
`gainDb` (the level adjustment applied to the model input, 0–40 dB; 0 if switched off), `lafDb`
(LAF at the window end) and `lafMaxDb` (highest LAF in the window), `endSample` (input sample at
48 kHz, as in the `clock` lines); log lines also carry `session`. The event's own category is the
decision on the *average* of the windows overlapping it (as in the public app).

**Manifest lines** (`type`): `session_start` (device model, Android version, app version, audio
source, encoding, effects, calibration id/offset, the detector settings — since 0.4.0 `detector:
"v2"`, `eventExcessDb`, `localFloorWindowS`, `eventFloorDb`, `windLfShareMin`, `windFlutterMinDb`
(before: `eventThresholdDb`) —, classifier settings, recording settings), `clock` (the engine's sample clock:
input sample *s* at 48 kHz, counted from the start of the measurement, was recorded at
`anchorEpochMs + (s − anchorSample)·1000/48000`; a new line after every clock correction, with
`correctionMs`), `clip` (event id, event start/end ISO-8601 with zone, LAFmax, SEL, background,
category, top-3 labels, file, `offsetOfEventStartInClipMs`, `truncated`, sample indices,
`classifierTrace`: the classifier results whose window ends inside the clip; since 0.4.0 also
`features`, see below), `event` (since 0.4.0, every event — also without a clip — when it is
complete: start/end time and input samples and `features` = `localFloorDb`, `excessDb`, `riseS`,
`decayS`, `jaggedness`, `midBandRiseDb`, `lfShare`, `lfFlutterDb`, `wind`, `shape`, as defined in
[Event detection](#event-detection-detector-v2); match to `clip` lines by `eventStartSample`),
`continuous_open` / `continuous_close` (file, start time, `startInputSample`, codec, sample rate;
16 kHz sample *j* of the file = input sample `startInputSample + 3·j + 2`), `gap` (blocks the
encoder could not keep up with, filled with silence so the mapping holds), `storage_full`, and
`session_stop` with the drop counts (clips skipped by the clip rate, dropped because the writer
was busy or the storage full).

**Listening to clips** (since 0.3.4). Three ways in, all opening the same «Clip» player:

- **Messungen:** events with a clip have a thin ring around their dot. Tap the dot, then «▶ Abspielen»
  in the tooltip.
- **Einstellungen → Labor → «Clips anhören»:** all clips newest first, or «Letzte 24 h» (events of
  the last 24 hours; until v0.5.1 «Diese Nacht»); filter chips by category; count and total size on
  top. Tap a row to play it.
- **Messen → «Letzte Ereignisse»:** tap the red dot after an event.

The player plays the WAV through the media volume (use the volume keys; a hint appears if the
media volume is 0 or the phone is on silent). The bar shows the whole clip: the 5 s pre-roll and
the post-roll shaded, the event highlighted, small ticks where the classifier's 1 s windows end;
tap or drag the bar (or the slider) to seek. Below it: the classifier result for the second being
heard — top-3 AudioSet labels with scores, the category decision («ignoriert» for silenced/invalid
audio), the input gain and LAF — taken from the clip's `classifierTrace` in the manifest. «‹
Vorheriger» / «Nächster ›» step to the neighbouring clip by event time (from the chart: the clips of
the chart window; from the list: the filtered list; from «Letzte Ereignisse»: all clips).
«Teilen» sends the single WAV. The player closes when a measurement starts or stops; playing
while measuring works, but the speaker is then measured too (use headphones). A missing or broken
file shows «Clip nicht gefunden».

**Sending data for analysis:** «Als ZIP teilen» zips the clips, the manifest and the classifier
logs (and the hour files only if they total < 500 MB) and opens the share menu; it shows the size
first. Hour files are best copied over USB (`Android/data/ch.lessnoise.labor/files/audio/continuous/`).

**Performance.** The capture thread only copies (ring buffer, one block copy into a bounded
queue) and never waits; resampling, WAV writing, logging and AAC encoding run on two
background-priority threads. A finished clip waits up to 2.5 s for the classifier result that
covers its last second, so its trace is complete. If they fall behind, data is dropped and counted in the manifest, never the measurement.

## Build

```sh
export ANDROID_HOME=/path/to/android-sdk   # platform 35, build-tools 35.0.0
./gradlew test assembleDebug
# APKs: app/build/outputs/apk/offline/debug/app-offline-debug.apk  («Offline-Version»)
#       app/build/outputs/apk/public/debug/app-public-debug.apk    («Online-Version»)
#       app/build/outputs/apk/labor/debug/app-labor-debug.apk      (Labor build, see below)
#       app/build/outputs/apk/laborKarte/debug/app-laborKarte-debug.apk («Weniger Lärm Labor · Karte», internal)
```

The app module has four product flavours (dimension `edition`, see [Editions](#editions)):
**`offline`** and **`public`**, the two published editions («Offline-Version» and «Online-Version»),
**`labor`**, the unpublished diagnostics build, and **`laborKarte`**, the internal Labor build with
«Messwerte teilen». Tasks are named per variant, e.g. `assembleOfflineDebug`, `assemblePublicDebug`,
`assembleLaborKarteDebug`, `testOfflineDebugUnitTest`, `testLaborDebugUnitTest`,
`testLaborKarteDebugUnitTest`; `assembleDebug` / `test` run all four.

`./gradlew assembleOfflineRelease assemblePublicRelease` without a key produces **unsigned** release
APKs (`app-offline-release-unsigned.apk`, `app-public-release-unsigned.apk`), which Android will not
install until they are signed.

### Signed release build

Release signing is optional and configured from a properties file that lives **outside** the
repository (never commit it or the keystore; `.gitignore` excludes `keystore.properties`,
`*.jks` and `*.keystore` as a safety net):

```properties
# e.g. ~/stadtlaerm-keys/keystore.properties  (chmod 600)
storeFile=/home/you/stadtlaerm-keys/stadtlaerm-release.jks
storePassword=…
keyAlias=stadtlaerm
keyPassword=…
```

Pass its path as a Gradle property or an environment variable:

```sh
./gradlew test assembleOfflineRelease assemblePublicRelease -Pstadtlaerm.keystoreProperties=$HOME/stadtlaerm-keys/keystore.properties
# or
STADTLAERM_KEYSTORE_PROPERTIES=$HOME/stadtlaerm-keys/keystore.properties ./gradlew assembleOfflineRelease assemblePublicRelease
# APKs: app/build/outputs/apk/offline/release/app-offline-release.apk  → stadtlaerm.apk
#       app/build/outputs/apk/public/release/app-public-release.apk    → stadtlaerm-karte.apk
```

Check the result (build-tools 35), for **each** of the two APKs:

```sh
for A in app/build/outputs/apk/offline/release/app-offline-release.apk \
         app/build/outputs/apk/public/release/app-public-release.apk; do
  apksigner verify --verbose --print-certs $A   # v2 + v3, signer DN
  aapt2 dump badging $A | head -1               # package ch.lessnoise.app, versionCode, versionName
  aapt2 dump permissions $A                     # offline: no INTERNET / ACCESS_NETWORK_STATE / location; public: all four
  apkanalyzer dex packages $A | grep -c ch.lessnoise.app.labor   # 0: no Labor code (PRIVACY.md)
  sha256sum $A; stat -c %s $A
done
```

To publish, follow the release checklist below. The release key cannot be replaced without
forcing every user to uninstall and reinstall, so keep the keystore and its password backed up.

### Release checklist

Two published APKs per version, both in `../docs/download/`. Never a Labor APK.

1. Bump `versionName` and `versionCode` in `app/build.gradle.kts` (shared by both published
   editions); add a `CHANGELOG.md` entry.
2. Build both signed:
   `./gradlew test assembleOfflineRelease assemblePublicRelease -Pstadtlaerm.keystoreProperties=…`.
   Check for each APK (commands above): the signer certificate SHA-256; `aapt2 dump badging` →
   `ch.lessnoise.app` and the new version; `aapt2 dump permissions` = exactly the list in
   PRIVACY.md → «How to verify» (offline **without** `INTERNET`, `ACCESS_NETWORK_STATE`,
   `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION`, public with all four; neither with
   `ACCESS_BACKGROUND_LOCATION`); the dex contains no Labor classes (PRIVACY.md → «Labor-Build», on both APKs).
3. Name the files: `app-offline-release.apk` → **`stadtlaerm.apk`** («Offline-Version»),
   `app-public-release.apk` → **`stadtlaerm-karte.apk`** («Online-Version»). Note size (bytes) and
   SHA-256 of each.
4. Copy them to `../docs/download/stadtlaerm.apk` and `../docs/download/stadtlaerm-karte.apk`
   (GitHub Pages serves them; downloads are not counted). **Never** copy a Labor APK anywhere into
   `../docs/`. Optionally also attach both to a GitHub Release `vX.Y.Z` (a Claude session cannot
   create releases); `../scripts/download_stats.py` reads that release's download counts.
5. Optionally tag the commit `vX.Y.Z`.
6. Update `../docs/index.html`: version (button note, facts, «Stand des Projekts»), and for **each** APK the
   size in MB with a German decimal comma (decimal MB, e.g. «18,7 MB») and the SHA-256 (in 0.5.0
   0.5.0 the placeholders were `SIZE_OFFLINE`, `SHA256_OFFLINE`, `SIZE_KARTE`, `SHA256_KARTE`).
7. Update `../docs/update.html`: `data-version` and `data-code` on `<main>`, and the static «Aktuelle Version: …»
   heading (shown before the script runs).
8. If the inline script of `update.html` changed, recompute its CSP hash:
   `python3 tools/csp_hash.py --write ../docs/update.html` (without `--write` it only checks).
   If `../docs/style.css` or the map's assets changed: `python3 ../scripts/stamp_assets.py`.
9. If the server changed, deploy it before the website (server/README.md → «Updates»).

Release builds contain only `arm64-v8a` and `armeabi-v7a` native code; debug builds also
include `x86_64` for the emulator. Minification is off (LiteRT has not been tested with R8).

Python cross-check of the classifier pipeline (needs `pip install ai-edge-litert scipy numpy`):

```sh
python3 tools/verify_yamnet.py
```

### Project layout

```
dsp/   pure Kotlin/JVM, no Android dependencies — shared with the planned ESP32 sensor (../firmware/)
  FrequencyWeighting.kt   A- and Z-weighting (bilinear biquads, 0 dB at 1 kHz)
  TimeWeighting.kt        Fast/Slow exponential averaging
  MeasurementEngine.kt    the pipeline: ticks, seconds, minutes, events, classifier hook
  EventDetector.kt        local-floor + excess hysteresis event detector, SEL; 5-min background (L90)
  EventFeatures.kt        local floor (L90/30 s), band splits, event features, wind rule, shape
  Resampler.kt            48 → 16 kHz anti-aliasing FIR (241 taps, ≥ 70 dB) + ring buffer
  classify/               SoundClassifier interface, category mapping, input normalisation
  calibration/            calibration measurement, tone check (FFT), offset math
  Measurements.kt, Csv.kt measurement summaries and splitting, CSV export
  src/test/.../Replay.kt  offline replay of WAV files through the engine (fixtures: src/test/resources/replay/)
chart/ Android library: the history chart (drawing model, Compose Canvas) and the app theme;
       JVM tests and Paparazzi renders
dsp/geo/Lv95.kt          WGS84 ↔ LV95 and 100 m cell ids (same formulas as the map's docs/map/lv95.js)
dsp/geo/LocationNoise.kt the location noise of shared measurements (deterministic Gaussian offset per place)
app/   Android: AudioRecord capture, foreground service, LiteRT YAMNet, Room, Compose UI
  src/main/     shared code (AudioTap.kt: the no-op hook the Labor recorder plugs into;
                upload/: the opt-in «Messwerte teilen» and the «Laut! Melden» queue, the app's only network code;
                location/: the one-fix location code; data/: Room incl. the measurements table)
  src/public/   the published editions: kotlin/ (AudioTapProvider → NoAudioTap, no extra UI), compiled
                into both `offline` and `public`; AndroidManifest.xml (INTERNET) for `public` only
  src/offline/  AndroidManifest.xml only: removes INTERNET, ACCESS_NETWORK_STATE and location («Offline-Version»)
  src/labor/    Labor edition only: audio recorder, WAV/AAC writers, manifest, clip player, Labor UI, red icon
                (kotlin/ and res/ also compiled into the internal `laborKarte`, its manifest not)
  src/laborKarte/ AndroidManifest.xml only: INTERNET + ACCESS_NETWORK_STATE + location for «Messwerte teilen»
  src/testLabor/ JVM tests of the Labor recorder and clip-player parts (run for `labor` and `laborKarte`)
tools/verify_yamnet.py    model I/O + Kotlin-vs-Python preprocessing check
tools/csp_hash.py         CSP script hashes for the website's inline script (docs/update.html)
```

## Roadmap

- **Better classifier:** swap YAMNet for an EfficientAT MobileNet (higher AudioSet mAP, similar
  cost), then fine-tune a small head on labelled Zürich recordings (tram, tram squeal, motorbike,
  pass-by vs idling) collected *with explicit consent* by volunteers.
- **ESP32 sensor:** a fixed outdoor sensor (ESP32-S3 + MEMS microphone) running the same DSP
  (the `dsp` module is written to port 1:1 to C/C++ or Kotlin/Native) and the same calibration
  procedure, so phone and sensor data are comparable. See [firmware/](../firmware/README.md).
- Opt-in data sharing for a noise map: since v0.5.0 («Messwerte teilen» in the Karte edition, see
  above; server and map in [../server/](../server/DESIGN.md)); since v0.6.0 with a place per
  measurement and «Laut! Melden».
- Slow time weighting, Lnight/Lden reporting per ISO 1996; zoom and export of the chart.

## Third-party components

- YAMNet model (`app/src/main/assets/yamnet.tflite`, MediaPipe float32 build) and its AudioSet
  label list — Google, Apache-2.0.
- LiteRT (TensorFlow Lite runtime), AndroidX (incl. WorkManager), Jetpack Compose, Room, kotlinx — Apache-2.0.
