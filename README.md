# biodiv-externalOccurrences

Serves external occurrence data (currently GBIF, from a parquet file) for a geometry, so any portal can use it. Moved out of biodiv-cca; the queries are unchanged from biodiv-cca's `GBIFObservationServiceImpl`.

Base path: `/externalOccurrences-api/api/v1/gbif`

Each endpoint is a `POST` whose body is a GeoJSON FeatureCollection. The first feature's geometry is used: a point gets a box of ± `gbif_point_padding` degrees around it, and any other geometry uses its bounding box.

| Path | Query params | Response |
|---|---|---|
| `/observations` | `offset` (0), `limit` (10), `speciesGroup`, `iucnCategory` | Species in the area with record counts, paginated |
| `/species-group-aggregation` | | Records and species per species group, `Others` last |
| `/iucn-aggregation` | | Records and species per IUCN category, including categories with zero |

Example:

```
POST /externalOccurrences-api/api/v1/gbif/species-group-aggregation
Content-Type: application/json

{"type":"FeatureCollection","features":[{"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[94.9022,26.4633]}}]}
```

## Configuration

| Key | |
|---|---|
| `gbif_parquet_path` | GBIF occurrence parquet file |
| `gbif_point_padding` | Degrees added around a point geometry (default 0.1, about 11 km) |
| `duckdb_database_path` | DuckDB file; holds the installed spatial extension |
| `duckdb_memory_limit` | Shared by all queries (default 50MB) |
| `duckdb_temp_directory` | Where DuckDB spills when over the memory limit |
