# biodiv-externalOccurrences

Serves external occurrence data (currently GBIF, from a parquet file) for a geometry, so any portal can use it. Moved out of biodiv-cca.

Base path: `/externalOccurrences-api/api/v1/gbif`

Each endpoint is a `POST` whose body is any GeoJSON: a FeatureCollection, Feature, GeometryCollection or a single geometry (Multi* included). Coordinates are `[longitude, latitude]` in WGS84.

Points are grown by `pointBufferKm`, lines and polygons by `bufferKm`, and the search area is the union of those zones:

| Part | Zone |
|---|---|
| Point | Circle of radius `pointBufferKm` |
| LineString | Corridor `bufferKm` either side |
| Polygon | The same shape grown outward by `bufferKm` (rounded corners, so every edge is exactly `bufferKm` away); at `bufferKm=0` the exact polygon |

Buffers are true kilometres at any latitude. A buffer of 0 uses those parts as-is: polygons match exactly, and a point or line matches only occurrences exactly on it.

All endpoints take both buffers, each from 0 to `gbif_max_buffer_km`:

| Param | Applies to | Default |
|---|---|---|
| `pointBufferKm` | Point, MultiPoint | `gbif_point_buffer_km`, 10 km |
| `bufferKm` | LineString, Polygon and their Multi* | `gbif_buffer_km`, 1 km |

| Path | Query params | Response |
|---|---|---|
| `/observations` | `offset` (0), `limit` (10), `speciesGroup`, `iucnCategory`, `pointBufferKm`, `bufferKm` | Species in the area with record counts, paginated |
| `/species-group-aggregation` | `pointBufferKm`, `bufferKm` | Records and species per species group, `Others` last |
| `/iucn-aggregation` | `pointBufferKm`, `bufferKm` | Records and species per IUCN category, including categories with zero |
| `/occurrence-locations` | `limit` (5000, max 10000), `speciesGroup`, `iucnCategory`, `pointBufferKm`, `bufferKm` | Locations in the search area's bounding box, flagged `insideGeometry` (inside the search area) and `insidePolygon` (inside the input's own polygons). Also returns the exact `searchArea` as GeoJSON, the `pointBufferKm` and `bufferKm` used, and record totals `insideRecords` and `polygonRecords`, so a map can draw exactly what was counted |

Invalid GeoJSON, coordinates out of range or a buffer out of range return `400`.

Example:

```
POST /externalOccurrences-api/api/v1/gbif/species-group-aggregation?pointBufferKm=5
Content-Type: application/json

{"type":"Point","coordinates":[94.9022,26.4633]}
```

## How the search area is built

The service flattens the posted GeoJSON into one GeometryCollection (`GeoJsonUtil`), then every query reads the area from the `search_area(geojson, point_buffer_m, buffer_m)` macro, which `DuckDBUtil` creates at startup. It splits the points from the lines and polygons so each gets its own buffer. `ST_Buffer` works in the geometry's own units, so the macro projects each part into metres (an azimuthal equidistant projection centred on the whole input), buffers it, projects it back to lon/lat, and unions the parts. It returns the area `a`, its bounding box `x0, x1, y0, y1`, and `polygons`, the input's own polygons without a buffer.

To try a query in the DuckDB CLI:

```sql
LOAD spatial;

CREATE OR REPLACE MACRO search_area(geojson, point_buffer_m, buffer_m) AS TABLE
  WITH input AS (SELECT ST_MakeValid(ST_GeomFromGeoJSON(geojson)) AS g),
  parts AS (
    SELECT g, ST_CollectionExtract(g, 1) AS points,
      ST_Collect([ST_CollectionExtract(g, 2), ST_CollectionExtract(g, 3)]) AS others,
      printf('+proj=aeqd +lat_0=%f +lon_0=%f +datum=WGS84 +units=m',
             ST_Y(ST_Centroid(g)), ST_X(ST_Centroid(g))) AS crs
    FROM input
  ),
  buffered AS (
    SELECT g,
      CASE WHEN ST_IsEmpty(points) OR point_buffer_m = 0 THEN points
        ELSE ST_Transform(ST_Buffer(ST_Transform(points, 'EPSG:4326', crs, always_xy := true), point_buffer_m, 16),
          crs, 'EPSG:4326', always_xy := true)
      END AS p,
      CASE WHEN ST_IsEmpty(others) OR buffer_m = 0 THEN others
        ELSE ST_Transform(ST_Buffer(ST_Transform(others, 'EPSG:4326', crs, always_xy := true), buffer_m, 16),
          crs, 'EPSG:4326', always_xy := true)
      END AS o
    FROM parts
  ),
  area AS (
    SELECT CASE WHEN ST_IsEmpty(p) THEN o WHEN ST_IsEmpty(o) THEN p ELSE ST_Union(p, o) END AS a,
      ST_CollectionExtract(g, 3) AS polygons
    FROM buffered
  )
  SELECT a, ST_XMin(a) AS x0, ST_XMax(a) AS x1, ST_YMin(a) AS y0, ST_YMax(a) AS y1, polygons FROM area;

-- Species and records within 10 km of a point (1 km around any lines or polygons)
SELECT COUNT(DISTINCT o.scientificName) AS totalSpecies, COUNT(*) AS totalRecords
FROM 'gbif-occurrence.parquet' o,
     search_area('{"type":"GeometryCollection","geometries":[{"type":"Point","coordinates":[94.9022,26.4633]}]}', 10000, 1000) area
WHERE o.decimalLongitude BETWEEN area.x0 AND area.x1
  AND o.decimalLatitude BETWEEN area.y0 AND area.y1
  AND ST_Intersects(area.a, ST_Point(o.decimalLongitude, o.decimalLatitude))
  AND o.scientificName IS NOT NULL;
```

The `BETWEEN` bounding-box test is cheap and keeps the exact `ST_Intersects` test off most rows; queries are about 4x slower without it. To see the area itself: `SELECT ST_AsGeoJSON(a) FROM search_area('<geojson>', 10000, 1000);`

## Configuration

| Key | |
|---|---|
| `gbif_parquet_path` | GBIF occurrence parquet file |
| `gbif_point_buffer_km` | Buffer around points when `pointBufferKm` is not sent (default 10) |
| `gbif_buffer_km` | Buffer around lines and polygons when `bufferKm` is not sent (default 1) |
| `gbif_max_buffer_km` | Largest `pointBufferKm` or `bufferKm` accepted (default 100) |
| `duckdb_database_path` | DuckDB file; holds the installed spatial extension |
| `duckdb_memory_limit` | Shared by all queries (default 50MB) |
| `duckdb_temp_directory` | Where DuckDB spills when over the memory limit |
