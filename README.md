# biodiv-externalOccurrences

Serves external occurrence data (currently GBIF, from a parquet file) for a geometry, so any portal can use it. Moved out of biodiv-cca.

Base path: `/externalOccurrences-api/api/v1/gbif`

Each endpoint is a `POST` whose body is any GeoJSON: a FeatureCollection, Feature, GeometryCollection or a single geometry (Multi* included). Coordinates are `[longitude, latitude]` in WGS84.

Every geometry part is grown by `bufferKm`, and the search area is the union of those zones:

| Part | Zone |
|---|---|
| Point | Circle of radius `bufferKm` |
| LineString | Corridor `bufferKm` either side |
| Polygon | The same shape grown outward by `bufferKm` (sharp corners); at `bufferKm=0` the exact polygon |

Buffers are true kilometres at any latitude (each part is buffered in a local azimuthal equidistant projection). At `bufferKm=0` points and lines contribute no area.

All endpoints take `bufferKm` (default `gbif_buffer_km`, 1 km; range 0 to `gbif_max_buffer_km`), which applies to every geometry.

| Path | Query params | Response |
|---|---|---|
| `/observations` | `offset` (0), `limit` (10), `speciesGroup`, `iucnCategory`, `bufferKm` | Species in the area with record counts, paginated |
| `/species-group-aggregation` | `bufferKm` | Records and species per species group, `Others` last |
| `/iucn-aggregation` | `bufferKm` | Records and species per IUCN category, including categories with zero |
| `/occurrence-locations` | `limit` (5000, max 10000), `speciesGroup`, `iucnCategory`, `bufferKm` | Locations in the search area's bounding box, flagged `insideGeometry` when inside the search area |

Invalid GeoJSON, coordinates out of range or a `bufferKm` out of range return `400`.

Example:

```
POST /externalOccurrences-api/api/v1/gbif/species-group-aggregation?bufferKm=5
Content-Type: application/json

{"type":"Point","coordinates":[94.9022,26.4633]}
```

## Configuration

| Key | |
|---|---|
| `gbif_parquet_path` | GBIF occurrence parquet file |
| `gbif_buffer_km` | Buffer used when `bufferKm` is not sent (default 1) |
| `gbif_max_buffer_km` | Largest `bufferKm` accepted (default 100) |
| `duckdb_database_path` | DuckDB file; holds the installed spatial extension |
| `duckdb_memory_limit` | Shared by all queries (default 50MB) |
| `duckdb_temp_directory` | Where DuckDB spills when over the memory limit |
