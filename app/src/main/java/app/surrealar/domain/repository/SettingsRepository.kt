package app.surrealar.domain.repository

/**
 * Aggregate settings repository — the union of all focused settings interfaces (see
 * `FocusedSettingsRepositories.kt`). It is retained for compatibility and for screens that
 * genuinely touch many settings areas at once (e.g. SettingsFragment, MainActivity).
 *
 * **New code should prefer a focused interface** (e.g. [GnssCaptureSettingsRepository],
 * [ArDisplaySettingsRepository], [ExternalReceiverSettingsRepository]) so a consumer depends only on
 * the settings area it uses. All interfaces — aggregate and focused — resolve to the same singleton
 * [app.surrealar.data.settings.repository.SettingsRepositoryImpl] over the single
 * Preferences DataStore; see `di/SettingsModule.kt` and `docs/settings-architecture.md`.
 */
interface SettingsRepository :
    LocationSourceSettingsRepository,
    ExternalReceiverSettingsRepository,
    GnssCaptureSettingsRepository,
    ArDisplaySettingsRepository,
    CoordinateDisplaySettingsRepository,
    MockLocationSettingsRepository,
    ArVisibilitySettingsRepository,
    GnssReceiverSettingsRepository,
    DeveloperSettingsRepository,
    AppearanceSettingsRepository,
    StakeoutSettingsRepository,
    MapSettingsRepository {

    /**
     * Phase 5 receiver selection: saves a tested External TCP receiver in ONE DataStore transaction (all or nothing):
     * connection type TCP, [host]/[port], the display name, the profile with its [origin] (only when [profile] is not
     * null) and the selected [source]. A blank [name] keeps the stored name when host:port is unchanged and clears it
     * for another receiver (a non-blank one is always written).
     *
     * It spans two focused areas (location source + external receiver), so it lives on the aggregate. The default
     * (test fakes, older implementations) performs the separate writes in the coordinator's order and is NOT atomic;
     * [app.surrealar.data.settings.repository.SettingsRepositoryImpl] overrides it with a single edit.
     */
    suspend fun saveExternalTcpReceiver(
        host: String,
        port: Int,
        name: String,
        profile: app.surrealar.settings.model.ExternalReceiverProfile?,
        origin: app.surrealar.settings.model.ProfileOrigin,
        source: app.surrealar.domain.model.LocationSourceType,
    ) {
        setExternalConnType(app.surrealar.domain.model.ExternalConnectionType.TCP)
        setExternalTcp(host, port, name)
        if (profile != null) setExternalReceiverProfile(profile, origin)
        setLocationSource(source)
    }
}
