# Coordinate data architecture

How saved-coordinate data flows, and **which layer owns which responsibility**. The goal is a thin
repository: data access only. Validation, statistics, export, and capture-construction live in
focused, mostly-pure classes.

## Layers & responsibilities

| Layer | Type | Responsibility |
|-------|------|----------------|
| **DAO** | `data/local/dao/CoordinateDao` | Room SQL: queries, inserts, updates, deletes. The place to add new filtered/spatial queries. |
| **Entity** | `data/local/entity/CoordinateEntity` | Room table row. Schema — do not change without a migration. |
| **Mappers** | `data/repository/mapper` (`toDomain`/`toEntity`) | Entity ⇄ domain `Coordinate` conversion. |
| **Repository** | `domain/repository/CoordinateRepository` + `data/repository/impl/CoordinateRepositoryImpl` | **Core CRUD + reads only** (observe streams, insert/update/delete, get-by-id, list, count). Nothing else. |
| **Domain model** | `domain/model/Coordinate` | Pure coordinate data object. |
| **Capture construction** | `domain/model/CoordinateFactory` | Builds a `Coordinate` from a captured fix / capture result. |

## Where non-CRUD logic lives (do NOT add these to the repository)

| Concern | Home | Notes |
|--------|------|-------|
| Validation & duplicate detection | `domain/coordinates/CoordinateValidator` | Pure Kotlin. `validate`, `coordinatesWithIssues`, `findDuplicates`, `distanceMeters`. Unit-tested. |
| Statistics (counts, accuracy, bounding box) | `domain/coordinates/CoordinateStatsCalculator` | Pure Kotlin over a `List<Coordinate>`. Unit-tested. Owns `CoordinateStats`, `AccuracyStats`, `BoundingBox`. |
| CSV / GeoJSON export | `data/export` (`CsvExporter`, `GeoJsonExporter`) | The real export path (used by `ViewCoordinatesFragment`). Operates on captured data and writes a `File`. |

For statistics/validation, fetch the list once via `repository.getAllCoordinatesList()` and pass it to
the calculator/validator — keep the computation out of the repository.

## What the repository deliberately no longer contains

The repository previously carried a large amount of unused or duplicated surface that was removed in
the data-layer cleanup. **Do not re-add it**:

- Spatial/filter queries done by in-memory filtering (`getCoordinatesInBounds`, `getCoordinatesNear`,
  `getCoordinatesByProject`, `getCoordinatesByProvider`, `getCoordinatesByRtkStatus`,
  `getCoordinatesWithMinAccuracy`, `searchCoordinatesByName`, `getCoordinatesByDateRange`) — if a real
  need arises, back it with a `CoordinateDao` query instead.
- Statistics methods (`getCoordinateStats`, `getAccuracyStatistics`, `getProviderStatistics`,
  `getRtkStatusStatistics`) → use `CoordinateStatsCalculator`.
- Validation methods (`validateCoordinate`, `findDuplicateCoordinates`, `getCoordinatesWithIssues`) →
  use `CoordinateValidator`.
- Batch ops (`updateMultiple`, `deleteMultiple`, `deleteByProvider`, `deleteByDateRange`) and
  `pruneOlderThan` — were unused.
- In-repository export (`exportToFormat` + private CSV/KML/GPX/GeoJSON builders) — this was a dead
  parallel implementation; the real exporters live in `data/export`.

Removed dead support types: `ExportFormat`, `CoordinateQuality`, `SortOrder`, `CoordinateQuery`,
`SurveyProject` (referenced nowhere). `CoordinateStats`/`AccuracyStats`/`BoundingBox`/`ValidationResult`
moved into the `domain/coordinates` helper files alongside the logic that produces them.

## Current repository surface (the whole contract)

```
val allCoordinates: LiveData<List<Coordinate>>
val allCoordinatesFlow: Flow<List<Coordinate>>
val coordinateCountFlow: Flow<Int>
suspend fun insert(coordinate)
suspend fun insertAll(coordinates)
suspend fun update(coordinate)
suspend fun deleteById(id)
suspend fun deleteAll()
suspend fun getAllCoordinatesList(): List<Coordinate>
suspend fun getById(id): Coordinate?
suspend fun count(): Int
```
