package app.surrealar.gnss.source

import app.surrealar.domain.model.LocationSourceType
import app.surrealar.gnss.source.SourceSettings.ProviderChoice

/**
 * Pure, side-effect-free decisions for GNSS source routing.
 *
 * Extracted from [GnssSourceCoordinator] so the selected-source-vs-active-provider logic is unit
 * testable in isolation from DataStore, adapters, and Android. The coordinator uses these for
 * startup activation; the same [classifySelectedActiveMismatch] reasons describe the
 * `selected=EXTERNAL active=INTERNAL` state seen in diagnostics.
 *
 * No I/O, no clock, no logging.
 */
object SourceRoutingDecisions {

    /** External TCP config is usable only with a non-blank host AND a non-null port. */
    fun externalConfigUsable(host: String?, port: Int?): Boolean =
        !host.isNullOrBlank() && port != null

    /** Which provider to activate at startup, plus the diagnostic reason. */
    data class StartupResolution(val provider: ProviderChoice, val reason: String)

    /**
     * Pure decision behind `restoreSavedSourceOnStartup`: given the persisted selection and saved
     * external config, decide which provider to make active. External is only resolved when its
     * config is usable; otherwise the safe Internal default is kept (the app never pretends External
     * is active when it cannot be).
     */
    fun resolveStartupProvider(selected: LocationSourceType, host: String?, port: Int?): StartupResolution =
        when {
            selected != LocationSourceType.EXTERNAL ->
                StartupResolution(ProviderChoice.INTERNAL, "startup-restore-internal")
            !externalConfigUsable(host, port) ->
                StartupResolution(ProviderChoice.INTERNAL, "startup-restore-no-host")
            else ->
                StartupResolution(ProviderChoice.EXTERNAL_TCP, "startup-restore")
        }

    enum class MismatchKind { NONE, EXTERNAL_SELECTED_INTERNAL_ACTIVE, INTERNAL_SELECTED_EXTERNAL_ACTIVE }

    /** A selected-vs-active divergence with a short, greppable reason (reason is null when aligned). */
    data class Mismatch(val kind: MismatchKind, val reason: String?) {
        val isMismatch: Boolean get() = kind != MismatchKind.NONE
    }

    /**
     * Classifies whether the user's [selected] source and the live [active] provider diverge, and
     * why. [externalConfigured] distinguishes "External selected but never configured" from
     * "configured but not yet streaming / unavailable".
     */
    fun classifySelectedActiveMismatch(
        selected: LocationSourceType,
        active: ProviderChoice,
        externalConfigured: Boolean
    ): Mismatch = when {
        selected == LocationSourceType.EXTERNAL && active == ProviderChoice.INTERNAL ->
            Mismatch(
                MismatchKind.EXTERNAL_SELECTED_INTERNAL_ACTIVE,
                if (externalConfigured) "external_unavailable_or_connecting" else "external_not_configured"
            )
        selected != LocationSourceType.EXTERNAL && active == ProviderChoice.EXTERNAL_TCP ->
            Mismatch(
                MismatchKind.INTERNAL_SELECTED_EXTERNAL_ACTIVE,
                "active_external_while_${selected.name.lowercase()}_selected"
            )
        else -> Mismatch(MismatchKind.NONE, null)
    }
}
