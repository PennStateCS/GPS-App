package app.surrealar.ui.toolbar

/** Long-press explanations for the toolbar tokens. */
object ToolbarHelp {
    const val SOURCE = "Source: where the position comes from — the device's own GNSS or the external receiver."
    const val SOL = "Solution: the fix type (Fix / Float = RTK, DGPS, Single, No fix). With no position it says why: Connecting…, No data, No fix, Location off, or (lost Ns) with the last position greyed."
    const val ACC = "Horizontal accuracy: a 68% radius. \"~\" means estimated from DOP because the receiver didn't report it."
    const val AGE = "Correction age: seconds since the last RTK/DGNSS correction. RTK quality drops as it grows."
    const val SATS = "Satellites: used in the solution / visible."
    const val ALT = "Height in the reference shown in the label (NAVD88, ellipsoid or MSL)."
    const val BATTERY = "Receiver battery. Tap for receiver details."
}