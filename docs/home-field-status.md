# Home Field Status

The Field Status card on Home is a short summary of the live position: a headline, any other warnings, a quality
line, a receiver line, and a few chips. The toolbar and Receiver settings show the details.

Code: `ui/home/HomeFieldStatusMapper.kt` (classification, pure), `ui/home/HomeFieldStatusInputs.kt` (gathers the
inputs from the live fix, the selection core and the tilt monitor), `ui/home/HomeLiveSource.kt` (which source produced
the live fix), `ui/home/RecentCountWindow.kt` (recent NMEA parse errors). Tests: `HomeFieldStatusMapperTest`,
`HomeFieldStatusInputsTest`, `HomeLiveSourceAndWindowTest`.

## Rules

- **Conservative.** RTK Fixed does not prove centimetre accuracy. "High accuracy" needs RTK Fixed **and** a
  horizontal accuracy reported by the receiver (GST or `$..ACCURACY`, 68 % radius) of at most 0.05 m **and** no
  warning. A missing or estimated accuracy never counts. Single, DGPS and unknown solutions never show as ready or
  green. "Ready for field work" is no longer used.
- **Warnings win.** Stale corrections, an unconfirmed RS4 position reference and an invalid receiver vertical
  accuracy all rank above any quality headline. When several apply, the first one is the headline and the others are
  listed on the line below it ("Also: …"), in text, so the meaning never depends on colour.
- **Selected is not live.** The selected source and the saved receiver are settings. The card attaches receiver
  identity (the name and model the receiver reported) and RTK metrics only to a live fix whose provider is an external
  receiver. A tablet GNSS fix while an external receiver is selected (startup, switching) is not shown as the
  receiver's position. Receiver-reported identity is used only when it was read from the saved host while the external
  adapter is active.
- **Concise identity.** The receiver line is the name plus the receiver-reported model. The IP address appears only
  when no name is known. Without a live fix it reads "Selected: <name>".
- **Recent errors.** The NMEA errors chip counts parse errors in the last 60 seconds ("Errors 3 in 1 min").
  `DiagnosticsService` is unchanged, so the Developer page keeps the cumulative totals.

## Precedence

The first matching row wins.

| # | Condition | Headline | Severity |
|---|-----------|----------|----------|
| 1 | Tablet GNSS selected, no live tablet fix | Waiting for position | Neutral |
| 2 | External selected, no live external fix, link connecting (first connect or test of the saved receiver) | Connecting to receiver | Neutral |
| 3 | External selected, no live external fix, link reconnecting | Reconnecting to receiver | Caution |
| 4 | External selected, no live external fix, link up | Waiting for position | Caution |
| 5 | External selected, no live external fix, link unavailable or failed | No receiver data | Warning |
| 6 | Live tablet GNSS fix | Using tablet GNSS | Neutral |
| 7 | Live NMEA replay fix | NMEA replay | Neutral |
| 8 | External fix, correction age > 15 s | Corrections stale | Warning |
| 9 | External fix, RS4 / RS4 Pro reference may be compensated (warning) or is unconfirmed (caution) | Position reference unconfirmed | Warning / Caution |
| 10 | External fix, receiver vertical accuracy is an invalid marker (e.g. 999 m) | Vertical accuracy invalid | Caution |
| 11 | RTK Fixed, reported hAcc ≤ 0.05 m | High accuracy | Good |
| 12 | RTK Fixed, hAcc missing or estimated | RTK Fixed — accuracy unverified | Caution |
| 13 | RTK Fixed, reported hAcc > 0.05 m | Accuracy below target | Caution |
| 14 | RTK Float | RTK Float | Caution |
| 15 | Other solution, reported hAcc > 1 m | Low accuracy | Warning |
| 16 | Other solution (Single, DGPS, dead reckoning, none) | Position available | Neutral |

Only row 11 is green. The header action opens Receiver settings for rows 2–5 and the full map otherwise.

## Layout

The headline, the warnings line, the quality line and the receiver line wrap to two lines, so the narrow tablet column
does not cut them off. The headline is a polite accessibility live region. The chip row is still rebuilt when its
content changes: the card only re-renders on a real change (`distinctUntilChanged`), the chips are not focusable
controls, and no benefit from reusing chip views was shown, so it was left as is.

## Limitations

- The live source is derived on Home from the fix provider, the external adapter's link state and the saved host. A
  switch between two external receivers cannot be told apart from the fix alone (both are `EXTERNAL_TCP`); the
  receiver-selection core is expected to expose the live positioning source, and `HomeLiveSource.resolve` is the one
  place to swap it in.
- The 0.05 m target is a display threshold, not a survey specification.
