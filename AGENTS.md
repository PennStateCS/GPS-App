# AGENTS.md — SurveyingApp AI Coding Guide

## Project Overview
Android surveying application (Kotlin, API 24+, MVVM) that combines GPS/GNSS positioning, AR, OSM mapping, and 3D model viewing for field data collection. Package: `com.example.surveyingapp`.

## Architecture

### Layer Map
```
gnss/           ← GNSS domain: sources, NMEA parsing, fix fusion, satellite tracking
data/           ← Room DB, DataStore settings, repository implementations
domain/         ← Repository interfaces, domain models (Coordinate, Fix, LocationSettings)
di/             ← Hilt modules wiring everything together
ui/             ← MVVM fragments/ViewModels per screen (ViewBinding, no Compose)
service/        ← LocationService foreground service (keeps GPS alive in background)
util/           ← GeoProjection (WGS84→UTM), UtmConverter, PermissionsGuard
```

### GNSS Data Flow (critical to understand)
```
GnssSource (GnssSource.kt)          ← interface: emits raw NMEA line strings
    └─ InternalNmeaSource           ← Android internal GPS via NMEA listener
    └─ TcpNmeaSource                ← External RS2+ receiver over TCP (default host: 192.168.42.1)
       └─ GnssController            ← orchestrates: lines() → NmeaRegistry.parse() → FixAccumulator.accept()
          └─ NmeaRegistry           ← dispatches GGA/RMC/GSA/GSV/ZDA to typed parsers (checksum-verified)
          └─ FixAccumulator         ← fuses multi-sentence state into FixSnapshot (StateFlow)
FixSwitchboard (FixBus/SkyBus)      ← selects INTERNAL vs RS2_EXTERNAL adapter; single publisher at a time
    └─ InternalAdapter / ExternalAdapter
```

UI subscribes to `FixSwitchboard` (injected as `FixBus`/`SkyBus`), **never** to raw sources directly.  
Provider switching is driven by `SourceSettings.activeProvider: StateFlow<ProviderChoice>`.

### DI Structure (Hilt, `SingletonComponent`)
- **`AppModule`** — DB, DAOs, repositories, `FixSwitchboard`, `SourceSettings`, adapter wiring
- **`GnssModule`** — `FixAccumulator`, `NmeaRegistry` (GGA/RMC/GSA/GSV/ZDA parsers), `DiagnosticsService`
- **`SettingsModule`** — `SettingsRepository` (DataStore-backed)
- `SurveyingApp.settingsRepo` is a companion-object singleton bootstrapped in `Application.onCreate()` before Hilt; `AppModule` delegates to it via `provideSettingsRepository()`.

### Key Domain Types
| Type | File | Purpose |
|---|---|---|
| `Fix` | `gnss/model/Fix.kt` | Single normalized GNSS observation (lat/lon/alt/RTK/DOP/accuracy) |
| `FixSnapshot` | `gnss/accumulator/FixAccumulator.kt` | Fused multi-sentence state exposed as `StateFlow` |
| `NmeaSentence` | `gnss/nmea/sentence/NmeaSentence.kt` | Sealed interface; subtypes: GGA, RMC, GSA, GSV, ZDA |
| `Coordinate` | `domain/model/` | Persisted survey point (Room entity via mapper) |
| `SourceSettings` | `gnss/settings/SourceSettings.kt` | `INTERNAL` vs `RS2_EXTERNAL` provider choice + TCP profiles |

## Critical Conventions

### Adding a new NMEA sentence type
1. Create data class implementing `NmeaSentence` in `gnss/nmea/sentence/`
2. Create `SentenceParser<YourSentence>` in `gnss/nmea/parse/`
3. Register it in `GnssModule.provideNmeaRegistry()` map
4. Handle it in `FixAccumulator.accept()` switch

### ViewModel pattern
All ViewModels use `@HiltViewModel` + `@Inject constructor`. They consume `FixAccumulator.state` as a `StateFlow` converted with `stateIn(WhileSubscribed(5000))`. Example: `HomeViewModel`.

### Repository pattern
Domain interfaces live in `domain/repository/`; implementations in `data/repository/impl/`. The interface is the Hilt binding target.

### Room schema
- Schema exports to `app/schemas/` (KSP arg `room.schemaLocation`). Do **not** delete schema JSON files — they are used for migration testing.
- `fallbackToDestructiveMigration()` is set; add proper migrations before any production release.

### 3D Model Viewer
`ModelViewerActivity` uses **Filament** (Google's real-time renderer). Key points:
- `ModelViewer` must be created on the **main thread** (attaches to `SurfaceView`)
- File I/O and buffer loading run on `Dispatchers.IO` via `lifecycleScope.launch`
- Thumbnail capture uses `PixelCopy` with up to 20 retry attempts; falls back to `ThumbnailCaptureActivity`
- Thumbnails stored at `filesDir/thumbnails/<safeBase>_thumb.png`; DB `ModelEntity.thumbnailFilePath` is updated after capture
- `Choreographer` drives the render loop; always cancel `frameCallback` in `onPause`/`onDestroy`

### API Key pattern
New keys go in `local.properties` (gitignored), read in `app/build.gradle.kts` via `localProperties.getProperty(...)`, injected as `manifestPlaceholders` or `buildConfigField`. See `GOOGLE_MAPS_API_KEY` as the reference example.

## Build & Test Commands
```bash
# Debug build
./gradlew assembleDebug

# Unit tests (Robolectric)
./gradlew test

# Instrumented tests
./gradlew connectedAndroidTest

# SHA-1 fingerprint (for Maps API key restriction)
./gradlew signingReport
```

## Navigation
Single-activity app (`MainActivity`). All screens are Fragments registered in `app/src/main/res/navigation/mobile_navigation.xml`. `ModelViewerActivity` and `CoordinatesActivity` are standalone activities launched via explicit intents.

## Key Files to Read First
- `gnss/bus/FixSwitchboard.kt` — provider switching logic
- `gnss/accumulator/FixAccumulator.kt` — NMEA fusion state machine
- `di/AppModule.kt` + `di/GnssModule.kt` — full dependency graph
- `gnss/model/Fix.kt` — all GNSS fields and their semantics
- `app/build.gradle.kts` — dependency versions, KSP args, key injection

