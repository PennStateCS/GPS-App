package app.surrealar.gnss.bus

import app.surrealar.gnss.model.Fix
import app.surrealar.gnss.model.SkySnapshot
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Public GNSS streams used by the rest of the app.
 *
 * Sources and adapters publish fixes and sky state internally. View models,
 * repositories, and UI components should consume these buses instead of
 * depending on a specific GNSS source implementation.
 */
interface FixBus {
    val fixes: SharedFlow<Fix>
}

/**
 * Current satellite/sky state for skyplot and signal views.
 *
 * Unlike [FixBus], which streams discrete fixes, this exposes the latest [SkySnapshot] as state.
 * The value is replaced wholesale on each receiver update; on a source switch it must be reset so a
 * new source never shows the previous one's satellites.
 */
interface SkyBus {
    val sky: StateFlow<SkySnapshot>
}