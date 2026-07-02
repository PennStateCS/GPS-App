package app.surrealar.gnss.mock

/**
 * Process-wide, in-memory snapshot of the most recent Android mock-location injection performed by
 * [AndroidMockLocationPublisher]. Lets other screens (notably the AR screen and the diagnostic
 * export) answer, without coupling to the publisher's lifecycle:
 *  - Is the app's GPS test provider currently active?
 *  - When did the app last inject a mock location, and how old is that now?
 *  - What source fix (provider / time) drove the last injection, and what accuracy did it carry?
 *
 * IMPORTANT: [lastInjectedHAccM]/[lastInjectedVAccM] hold the REAL source-fix accuracy — `null` when
 * the receiver/parser did not provide it. When accuracy is missing the publisher must inject a
 * non-zero placeholder (the Android test-provider API rejects 0), and [lastInjectedHAccWasFallback]
 * records that so a `null`/unknown accuracy is never mistaken for a genuine 0 m (perfect) fix.
 *
 * All fields are @Volatile and written from the publisher's collector coroutine; reads elsewhere are
 * plain volatile reads (a slightly stale snapshot is fine for diagnostics).
 */
object MockInjectionStatus {

    /** True while the app's GPS test provider is added + enabled (i.e. actively able to inject). */
    @Volatile var providerActive: Boolean = false

    /** Total successful injections since process start (monotonic; not reset per AR session). */
    @Volatile var injectionCount: Long = 0L

    /** Wall-clock time (epoch ms) of the last successful injection; 0 = never. */
    @Volatile var lastInjectionAtMs: Long = 0L

    /** Android provider name the mock was published under (GPS provider). */
    @Volatile var lastInjectedProvider: String? = null

    @Volatile var lastInjectedLat: Double? = null
    @Volatile var lastInjectedLon: Double? = null
    @Volatile var lastInjectedAltM: Double? = null

    /** REAL source horizontal accuracy (m); null = the source fix carried none. */
    @Volatile var lastInjectedHAccM: Double? = null
    /** REAL source vertical accuracy (m); null = the source fix carried none. */
    @Volatile var lastInjectedVAccM: Double? = null
    /** True when a placeholder accuracy was injected because the source fix had none. */
    @Volatile var lastInjectedHAccWasFallback: Boolean = false

    /** The source GNSS [app.surrealar.gnss.model.Provider] name that drove the last injection. */
    @Volatile var lastSourceFixProvider: String? = null
    /** Epoch ms of the source fix's own timestamp (fix.timeUtc); 0 = unknown. */
    @Volatile var lastSourceFixTimeMs: Long = 0L

    /** Age (ms) of the last injection relative to now; null = never injected. */
    fun lastInjectionAgeMs(): Long? =
        if (lastInjectionAtMs > 0L) System.currentTimeMillis() - lastInjectionAtMs else null

    /** Age (ms) of the source fix that drove the last injection; null = unknown. */
    fun lastSourceFixAgeMs(): Long? =
        if (lastSourceFixTimeMs > 0L) System.currentTimeMillis() - lastSourceFixTimeMs else null
}
