# Settings architecture

How app settings are persisted, read, and defaulted. Read this before touching anything under
`data/settings/`, `settings/`, or the receiver/connection settings.

## Layers

```
DataStore (Preferences)                  raw key/value, never read directly by features
  └─ SettingsLocalDataSource             raw Flow<String?>/Flow<Int?> + raw setters, one per key
       └─ SettingsRepositoryImpl         maps raw → typed domain/model types, sanitizes, combines
            ├─ SettingsRepository         aggregate iface = union of all focused interfaces
            └─ focused settings ifaces    one settings area each (preferred for new consumers)
```

- `SettingsLocalDataSource` ([Preferences.kt](../app/src/main/java/com/example/surveyingapp/data/settings/datastore/Preferences.kt))
  owns a `DataStore<Preferences>`. Production uses the secondary `constructor(context)` →
  `context.appDataStore` (the `app_settings` file). Tests use the primary
  `constructor(dataStore)` to inject an isolated per-test store — see the DI seam note below.
- `SettingsRepositoryImpl` ([SettingsRepositoryImpl.kt](../app/src/main/java/com/example/surveyingapp/data/settings/repository/SettingsRepositoryImpl.kt))
  is where raw strings/ints become typed values, where invalid input is sanitized, and where
  related keys are `combine()`d into aggregate models.

## Focused vs. aggregate interfaces

The settings surface is split into small, role-segregated interfaces
([FocusedSettingsRepositories.kt](../app/src/main/java/com/example/surveyingapp/domain/repository/FocusedSettingsRepositories.kt)) —
one per settings area — so a consumer depends only on what it uses:

`LocationSourceSettingsRepository`, `ExternalReceiverSettingsRepository`,
`GnssCaptureSettingsRepository`, `ArDisplaySettingsRepository`,
`CoordinateDisplaySettingsRepository`, `MockLocationSettingsRepository`,
`GnssReceiverSettingsRepository`, `DeveloperSettingsRepository`, `AppearanceSettingsRepository`.

- The aggregate **`SettingsRepository` extends all of them** and adds no members of its own — it is
  exactly their union. It is kept for compatibility and for screens that genuinely touch several
  areas (e.g. `SettingsFragment`, `MainActivity`, `AddCoordinateDialogFragment`).
- **New ViewModels/consumers should inject the narrowest focused interface(s) they need**, not the
  aggregate. A consumer that needs two areas injects two focused interfaces.
- **All interfaces — aggregate and focused — resolve to the same `SettingsRepositoryImpl`
  singleton** over the one Preferences DataStore. Hilt binds each via `@Provides` returning
  `SurveyingApp.settingsRepo` in [SettingsModule.kt](../app/src/main/java/com/example/surveyingapp/di/SettingsModule.kt).
  `@Provides` (not `@Binds`) is used because the impl is constructed by `SurveyingApp.setupSettings()`
  to guarantee a single DataStore over the `app_settings` file; letting Hilt construct it would
  create a duplicate DataStore and crash. The Preferences DataStore remains the single source of truth.
- `ArchitectureGuardTest` carries a (non-enforced) guidance note steering new code to focused
  interfaces; it is intentionally not a hard rule while the aggregate still backs the large screens.

## Production ownership

**Today, `SurveyingApp` owns settings-repository construction — Hilt does not.**

- `SurveyingApp.setupSettings()` (in `Application.onCreate`) builds the one
  `SettingsLocalDataSource` (which opens the single `app_settings` Preferences DataStore) and the one
  `SettingsRepositoryImpl`, storing it in `SurveyingApp.settingsRepo`. It runs this early so the
  startup theme (`applyThemeFromSettings()`) and the mock-location publisher can read settings
  synchronously before the rest of the app comes up.
- Hilt **exposes that existing singleton** rather than constructing its own: every provider in
  `SettingsModule` (aggregate + focused interfaces) is a `@Provides` returning
  `SurveyingApp.settingsRepo`.
- This is what keeps the DataStore count at **exactly one**. A second `SettingsRepositoryImpl` (e.g.
  via `@Binds`, an `@Inject` constructor, or a stray `SettingsRepositoryImpl(...)` call) would open a
  second DataStore over the same file and crash at runtime (`IllegalStateException: There are
  multiple DataStores active for the same file`). Tests avoid this by constructing the impl over an
  **isolated temp DataStore** (`PreferenceDataStoreFactory.create`), never the production file.
- **Guardrails:** `ArchitectureGuardTest` fails the build if production code constructs
  `SettingsRepositoryImpl(...)` outside `SurveyingApp.kt`, or declares a second
  `preferencesDataStore(...)` settings delegate outside `Preferences.kt`. A round-trip test
  (`aggregate and focused settings interfaces are one and the same owner instance`) asserts the
  aggregate and focused interfaces are referentially the same object.
- **Future refactor (deliberate, not incidental):** moving ownership to Hilt (so it constructs
  `SettingsRepositoryImpl` via `@Binds`/`@Inject`) is possible but requires a startup-order migration
  — the theme/mock-location startup paths must obtain the repository from the Hilt graph instead of a
  static field, and `setupSettings()` must stop constructing it. Until that is done intentionally, the
  providers above must keep returning the `SurveyingApp`-owned singleton.

## Persistence contract

**DataStore key names and stored token strings are a compatibility contract.** Existing installs
already hold these values; changing a key name or a token spelling silently drops a user's setting.

- **Enum persistence uses stable tokens, not `enum.name`.** Each persisted enum exposes
  `prefKey` (a fixed lowercase token, e.g. `reach_rs4`) and `fromPrefKey(value)`. `fromPrefKey`
  accepts BOTH the current token AND the legacy uppercase `name` so older stored values keep
  resolving, and falls back to the type's `DEFAULT` for null/unknown. Persist with `enum.prefKey`,
  read with `fromPrefKey`. Writing `enum.name` is flagged by `ArchitectureGuardTest`.
  Applies to: `LocationSourceType`, `ExternalConnectionType`, `AppThemeMode`, `RtkStatus`,
  `ExternalReceiverProfile`.

## Defaults

`SettingsDefaults` ([SettingsDefaults.kt](../app/src/main/java/com/example/surveyingapp/settings/SettingsDefaults.kt))
is the single source of truth for default values. Define a default once there (as a typed
instance or constant) and reference it from the repository — do not hardcode defaults at read
sites. `sanitizeTcpPort(port)` clamps to a valid `1..65535` port, returning `externalTcpPort`
(9000) for null/invalid input.

## Schema version + migration

`SettingsDefaults.CURRENT_SETTINGS_SCHEMA_VERSION` is the on-disk settings schema version, stored
under its own key. `SettingsMigrationRunner` is idempotent: it reads the stored version, runs any
needed steps, then writes the current version. It takes injected read/write lambdas so it can be
unit-tested against either a fake or a real DataStore. Bump the version and add a step when a
stored representation changes incompatibly.

## External receiver settings

The receiver's profile, connection type, TCP host, TCP port, and display name are stored as
**separate Preferences keys** (unchanged, for back-compat). For typed access, prefer the aggregate
[`ExternalReceiverSettings`](../app/src/main/java/com/example/surveyingapp/settings/model/ExternalReceiverSettings.kt),
exposed as `SettingsRepository.externalReceiverSettings: Flow<…>` (a `combine()` over the raw keys
that sanitizes the port and normalizes blank host/name to `""`) with
`setExternalReceiverSettings(…)`. The individual flows/setters
(`externalReceiverProfile`, `externalConnType`, `externalTcpHost/Port/Name`) are retained for
existing call sites; new code should reach for the aggregate.

### Port defaults on profile change

When the user picks a receiver profile, the port is chosen by
`ExternalReceiverProfile.portForProfileChange(currentPort, newProfile)`, which is **conservative**:
it applies `newProfile.defaultPort` only when the current port is missing/invalid or still sits at
*some* profile's default (i.e. untouched); a user-entered custom port is preserved across profile
changes. Profile default ports: Generic NMEA TCP = 9000; RS2+ / RS4 / RS4 Pro = 9001.

### Connection type — TCP is the only implemented transport

`ExternalConnectionType` defines `BT`, `TCP`, `USB`, `RADIO`, `WIFI`, but **only `TCP` has a
working connection path** (`TcpNmeaSource`, wired in `GnssModule`). Every Reach profile
(RS2+/RS4/RS4 Pro) connects over TCP NMEA host/port. The other modes are **reserved for future
support** — no source adapter exists for them and the Settings UI never offers them as selectable
active options (there is no connection-type picker; the app sets `TCP` when the user connects).
The `BLUETOOTH_CONNECT` permission is used only to read bonded device names for diagnostic reports,
not to open a Bluetooth data connection.

Because of this, `ExternalConnectionType.DEFAULT` is **`TCP`**: a fresh install with no saved value
defaults to TCP, and unknown/garbage stored values fall back to TCP. **The default applies only
when there is no saved value** — a legacy saved `bt` (or uppercase `BT`) still parses to `BT`, and
an existing `tcp` still parses to `TCP`. The non-TCP enum constants are intentionally retained so
old saved values keep parsing and future migrations have stable tokens to target; do not delete
them. Diagnostics still display whatever connection type is stored.

## Testing the real stack — the DataStore DI seam

`SettingsRepositoryRoundTripTest` exercises the real `SettingsRepositoryImpl` over a real
DataStore. Each test builds an **isolated** store with
`PreferenceDataStoreFactory.create(scope) { File(tempDir, …) }` and injects it via
`SettingsLocalDataSource(dataStore)`, tearing down the scope and temp dir afterward. This gives
true per-test isolation (no shared file, no clear-between-tests) and avoids a Windows-specific
flake where DataStore's atomic `.tmp → .preferences_pb` rename throws `AccessDeniedException` when
the target file is still open. Do not revert these tests to a shared/process DataStore.
