package app.surrealar.ui.toolbar

/** Long-press explanations for the toolbar tokens. */
object ToolbarHelp {
    const val SOURCE = "Source: where the position comes from — the device's own GNSS or the external receiver. Tap for the GNSS status report."
    const val SOL = "Solution: the fix type (Fix / Float = RTK, DGPS, Single, No fix). Green only when the receiver reports a high accuracy and nothing needs attention. With no position it says why: Connecting…, Reconnecting…, Switching…, No data, No fix, Location off, or (lost Ns) with the last position greyed (not for capture)."
    const val ACC = "H: horizontal accuracy, a 68% radius. \"~\" means estimated from DOP because the receiver didn't report it. Vertical accuracy, satellites and correction age are in the GNSS status report (tap)."
    const val WARNING = "Shown only when something needs attention: corrections aging / stale / unavailable, position reference unconfirmed, vertical accuracy invalid. Tap for details."
    const val ALT = "Live height at the antenna phase centre (no pole height removed), in the reference shown in the label: NAVD88 = orthometric height H from GEOID18, h = ellipsoidal height, H rcvr = orthometric height from the receiver's own geoid (model unknown), EGM2008 / EGM96 = H from that global model, NAPGD2022β = H from NGS GEOID2022 (beta, preliminary). \"loading\" = the height model's data is still loading. Model, version and epoch: point Details and Settings → Coordinates."
    const val BATTERY = "Receiver battery is low. Tap for the GNSS status report."
    const val REPORT = "GNSS status report: receiver, solution, corrections, position, diagnostics."
}
