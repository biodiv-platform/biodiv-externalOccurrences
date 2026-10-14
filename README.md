# biodiv-externalOccurrences

Serves external occurrence data (currently GBIF, from a parquet file) for a geometry, so any portal can use it. Moved out of biodiv-cca.

Base path: `/externalOccurrences-api/api/v1/gbif`

Each endpoint is a `POST` whose body is any GeoJSON: a FeatureCollection, Feature, GeometryCollection or a single geometry (Multi* included). Coordinates are `[longitude, latitude]` in WGS84.

Every geometry part is grown by `bufferKm`, and the search area is the union of those zones:

| Part | Zone |
|---|---|
| Point | Circle of radius `bufferKm` |
| LineString | Corridor `bufferKm` either side |
| Polygon | The same shape grown outward by `bufferKm` (rounded corners, so every edge is exactly `bufferKm` away); at `bufferKm=0` the exact polygon |

Buffers are true kilometres at any latitude. At `bufferKm=0` the geometry is used as-is: polygons match exactly, and a point or line matches only occurrences exactly on it.

All endpoints take `bufferKm` (default `gbif_buffer_km`, 1 km; range 0 to `gbif_max_buffer_km`), which applies to every geometry.

| Path | Query params | Response |
|---|---|---|
| `/observations` | `offset` (0), `limit` (10), `speciesGroup`, `iucnCategory`, `bufferKm` | Species in the area with record counts, paginated |
| `/species-group-aggregation` | `bufferKm` | Records and species per species group, `Others` last |
| `/iucn-aggregation` | `bufferKm` | Records and species per IUCN category, including categories with zero |
| `/occurrence-locations` | `limit` (5000, max 10000), `speciesGroup`, `iucnCategory`, `bufferKm` | Locations in the search area's bounding box, flagged `insideGeometry` (inside the search area) and `insidePolygon` (inside the input's own polygons). Also returns the exact `searchArea` as GeoJSON, the `bufferKm` used, and record totals `insideRecords` and `polygonRecords`, so a map can draw exactly what was counted |

Invalid GeoJSON, coordinates out of range or a `bufferKm` out of range return `400`.

Example:

```
POST /externalOccurrences-api/api/v1/gbif/species-group-aggregation?bufferKm=5
Content-Type: application/json

{"type":"Point","coordinates":[94.9022,26.4633]}
```

## How the search area is built

The service flattens the posted GeoJSON into one GeometryCollection (`GeoJsonUtil`), then every query reads the area from the `search_area(geojson, buffer_m)` macro, which `DuckDBUtil` creates at startup. `ST_Buffer` works in the geometry's own units, so the macro projects the geometry into metres (an azimuthal equidistant projection centred on it), buffers it, and projects it back to lon/lat. It returns the area `a` and its bounding box `x0, x1, y0, y1`.

To try a query in the DuckDB CLI:

```sql
LOAD spatial;

CREATE OR REPLACE MACRO search_area(geojson, buffer_m) AS TABLE
  WITH input AS (SELECT ST_MakeValid(ST_GeomFromGeoJSON(geojson)) AS g),
  local AS (
    SELECT g, printf('+proj=aeqd +lat_0=%f +lon_0=%f +datum=WGS84 +units=m',
                     ST_Y(ST_Centroid(g)), ST_X(ST_Centroid(g))) AS crs
    FROM input
  ),
  buffered AS (
    SELECT CASE WHEN buffer_m = 0 THEN g
      ELSE ST_Transform(
        ST_Buffer(ST_Transform(g, 'EPSG:4326', crs, always_xy := true), buffer_m, 16),
        crs, 'EPSG:4326', always_xy := true)
    END AS a
    FROM local
  )
  SELECT a, ST_XMin(a) AS x0, ST_XMax(a) AS x1, ST_YMin(a) AS y0, ST_YMax(a) AS y1 FROM buffered;

-- Species and records within 1 km of a point
SELECT COUNT(DISTINCT o.scientificName) AS totalSpecies, COUNT(*) AS totalRecords
FROM 'gbif-occurrence.parquet' o,
     search_area('{"type":"GeometryCollection","geometries":[{"type":"Point","coordinates":[94.9022,26.4633]}]}', 1000) area
WHERE o.decimalLongitude BETWEEN area.x0 AND area.x1
  AND o.decimalLatitude BETWEEN area.y0 AND area.y1
  AND ST_Intersects(area.a, ST_Point(o.decimalLongitude, o.decimalLatitude))
  AND o.scientificName IS NOT NULL;
```

The `BETWEEN` bounding-box test is cheap and keeps the exact `ST_Intersects` test off most rows; queries are about 4x slower without it. To see the area itself: `SELECT ST_AsGeoJSON(a) FROM search_area('<geojson>', 1000);`

## Configuration

| Key | |
|---|---|
| `gbif_parquet_path` | GBIF occurrence parquet file |
| `gbif_buffer_km` | Buffer used when `bufferKm` is not sent (default 1) |
| `gbif_max_buffer_km` | Largest `bufferKm` accepted (default 100) |
| `duckdb_database_path` | DuckDB file; holds the installed spatial extension |
| `duckdb_memory_limit` | Shared by all queries (default 50MB) |
| `duckdb_temp_directory` | Where DuckDB spills when over the memory limit |
