package com.strandls.externalOccurrences.service.impl;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.strandls.externalOccurrences.ExternalOccurrencesConfig;
import com.strandls.externalOccurrences.pojo.IUCNAggregation;
import com.strandls.externalOccurrences.pojo.OccurrenceLocation;
import com.strandls.externalOccurrences.pojo.SpeciesAggregation;
import com.strandls.externalOccurrences.pojo.SpeciesGroupAggregation;
import com.strandls.externalOccurrences.pojo.response.GBIFObservationResponse;
import com.strandls.externalOccurrences.pojo.response.IUCNAggregationResponse;
import com.strandls.externalOccurrences.pojo.response.OccurrenceLocationResponse;
import com.strandls.externalOccurrences.pojo.response.SpeciesGroupAggregationResponse;
import com.strandls.externalOccurrences.service.GBIFObservationService;
import com.strandls.externalOccurrences.util.DuckDBUtil;
import com.strandls.externalOccurrences.util.GeoJsonUtil;

public class GBIFObservationServiceImpl implements GBIFObservationService {

	private final Logger logger = LoggerFactory.getLogger(GBIFObservationServiceImpl.class);
	private static final double DEFAULT_BUFFER_KM;
	private static final double MAX_BUFFER_KM;
	private static final int DEFAULT_LOCATION_LIMIT = 5000;
	private static final int MAX_LOCATION_LIMIT = 10000;

	static {
		DEFAULT_BUFFER_KM = readDouble("gbif_buffer_km", 1);
		MAX_BUFFER_KM = readDouble("gbif_max_buffer_km", 100);
	}

	private static double readDouble(String key, double defaultValue) {
		String value = ExternalOccurrencesConfig.getProperty(key);
		return (value != null && !value.isEmpty()) ? Double.parseDouble(value) : defaultValue;
	}

	/**
	 * Builds the search area from parameter 1 (JSON array of geometries, see
	 * {@link GeoJsonUtil#toGeometryArray}) and parameter 2 (buffer in metres).
	 *
	 * Every geometry part is buffered on its own in a local azimuthal equidistant
	 * projection centred on it, so the buffer is true metres at any latitude:
	 * points become circles, lines become corridors, and polygons grow outward
	 * keeping their shape (mitre joins keep corners sharp). With a zero buffer a
	 * polygon is used as-is and points/lines drop out. The area is the union of
	 * all zones; x0/x1/y0/y1 is its bounding box, used to prune parquet row
	 * groups before the exact ST_Intersects test.
	 */
	private static final String AREA_CTE = "WITH geoms AS ("
		+ "    SELECT ST_GeomFromGeoJSON(g::VARCHAR) AS g FROM (SELECT unnest(from_json(?, '[\"JSON\"]')) AS g)"
		+ "), "
		+ "params AS (" + "    SELECT ?::DOUBLE AS buf" + "), "
		+ "parts AS (" + "    SELECT unnest(ST_Dump(ST_MakeValid(g))).geom AS p FROM geoms" + "), "
		+ "projected AS ("
		+ "    SELECT p, printf('+proj=aeqd +lat_0=%%f +lon_0=%%f +datum=WGS84 +units=m',"
		+ "        ST_Y(ST_Centroid(p)), ST_X(ST_Centroid(p))) AS proj"
		+ "    FROM parts" + "), "
		+ "zones AS ("
		+ "    SELECT CASE WHEN buf = 0 AND ST_GeometryType(p) = 'POLYGON' THEN p"
		+ "        ELSE ST_Transform("
		+ "            ST_Buffer(ST_Transform(p, 'EPSG:4326', proj, always_xy := true), buf, 16, 'CAP_ROUND', 'JOIN_MITRE', 5.0),"
		+ "            proj, 'EPSG:4326', always_xy := true)"
		+ "    END AS z"
		+ "    FROM projected, params" + "), "
		+ "area AS ("
		+ "    SELECT a, ST_XMin(a) AS x0, ST_XMax(a) AS x1, ST_YMin(a) AS y0, ST_YMax(a) AS y1"
		+ "    FROM (SELECT ST_Union_Agg(z) AS a FROM zones WHERE NOT ST_IsEmpty(z))" + ")";

	/** Occurrence `o` is inside the search area. Bbox first so DuckDB can skip row groups. */
	private static final String IN_AREA = " o.decimalLongitude BETWEEN area.x0 AND area.x1"
		+ " AND o.decimalLatitude BETWEEN area.y0 AND area.y1"
		+ " AND ST_Intersects(area.a, ST_Point(o.decimalLongitude, o.decimalLatitude))";

	private static String buildCountQueryTemplate() {
		return AREA_CTE
			+ " SELECT COUNT(DISTINCT o.scientificName) as totalSpecies, COUNT(*) as totalRecords FROM '%s' o, area"
			+ " WHERE" + IN_AREA
			+ "  AND o.scientificName IS NOT NULL";
	}

	private static String buildAggregationQueryTemplate() {
		return AREA_CTE
			+ " SELECT o.scientificName, COUNT(*) as count, FIRST(o.iucnRedListCategory) as iucnRedListCategory, FIRST(o.species_group) as speciesGroup, FIRST(o.taxonKey) as taxonKey, FIRST(o.iucn_link) as iucnLink FROM '%s' o, area"
			+ " WHERE" + IN_AREA
			+ "  AND o.scientificName IS NOT NULL" + " %%FILTERS%%"
			+ " GROUP BY o.scientificName" + " ORDER BY count DESC"
			+ " LIMIT ? OFFSET ?";
	}

	private static String buildSpeciesGroupAggregationQueryTemplate() {
		return AREA_CTE
			+ " SELECT o.species_group, COUNT(*) as totalCount, COUNT(DISTINCT o.scientificName) as uniqueSpeciesCount FROM '%s' o, area"
			+ " WHERE" + IN_AREA
			+ "  AND o.species_group IS NOT NULL" + " GROUP BY o.species_group"
			+ " ORDER BY CASE WHEN o.species_group = 'Others' THEN 1 ELSE 0 END, totalCount DESC";
	}

	private static String buildIUCNAggregationQueryTemplate() {
		return AREA_CTE + ", "
			+ "all_categories AS ("
			+ "    SELECT unnest(['CR', 'EN', 'VU', 'NT', 'LC', 'DD', 'NE']) as category"
			+ "), "
			+ "observed_counts AS ("
			+ "    SELECT o.iucnRedListCategory, COUNT(*) as totalCount, COUNT(DISTINCT o.scientificName) as uniqueSpeciesCount"
			+ "    FROM '%s' o, area"
			+ "    WHERE" + IN_AREA
			+ "      AND o.iucnRedListCategory IS NOT NULL"
			+ "    GROUP BY o.iucnRedListCategory"
			+ ") "
			+ "SELECT ac.category as iucnRedListCategory, "
			+ "    COALESCE(oc.totalCount, 0) as totalCount, "
			+ "    COALESCE(oc.uniqueSpeciesCount, 0) as uniqueSpeciesCount "
			+ "FROM all_categories ac "
			+ "LEFT JOIN observed_counts oc ON ac.category = oc.iucnRedListCategory "
			+ "ORDER BY totalCount DESC";
	}

	/**
	 * Returns every occurrence location in the search area's bounding box,
	 * grouped on coordinates rounded to 4 decimals (~11m). A location is inside
	 * when any of its records falls inside the search area, and insideRecords
	 * counts exactly the records the other endpoints count. Inside locations are
	 * ordered first so they survive the LIMIT. The area is LEFT JOINed so its
	 * bbox is returned even when there are no occurrences. %%FILTERS%% (%FILTERS%
	 * after String.format) is replaced with the optional species group / IUCN
	 * filters.
	 */
	private static String buildOccurrenceLocationQueryTemplate() {
		return AREA_CTE + ", "
			+ "grouped AS ("
			+ "    SELECT ROUND(o.decimalLatitude, 4) AS lat, ROUND(o.decimalLongitude, 4) AS lon,"
			+ "        COUNT(*) AS recordCount, COUNT(DISTINCT o.scientificName) AS speciesCount,"
			+ "        COUNT(*) FILTER (WHERE ST_Intersects(area.a, ST_Point(o.decimalLongitude, o.decimalLatitude))) AS insideCount"
			+ "    FROM '%s' o, area"
			+ "    WHERE o.decimalLongitude BETWEEN area.x0 AND area.x1"
			+ "      AND o.decimalLatitude  BETWEEN area.y0 AND area.y1"
			+ "      AND o.scientificName IS NOT NULL"
			+ "      %%FILTERS%%"
			+ "    GROUP BY 1, 2" + "), "
			+ "limited AS ("
			+ "    SELECT *, insideCount > 0 AS insideGeometry,"
			+ "        COUNT(*) OVER () AS totalLocations,"
			+ "        SUM(recordCount) OVER ()::BIGINT AS totalRecords,"
			+ "        SUM(insideCount) OVER ()::BIGINT AS insideRecords"
			+ "    FROM grouped"
			+ "    ORDER BY insideGeometry DESC, recordCount DESC"
			+ "    LIMIT ?" + ") "
			+ "SELECT area.x0 AS min_lon, area.y0 AS min_lat, area.x1 AS max_lon, area.y1 AS max_lat, limited.*"
			+ " FROM area LEFT JOIN limited ON true"
			+ " ORDER BY limited.insideGeometry DESC, limited.recordCount DESC";
	}

	/**
	 * @return the buffer in metres; the configured default when bufferKm is null
	 * @throws IllegalArgumentException if bufferKm is outside 0..gbif_max_buffer_km
	 */
	private static double toBufferMeters(Double bufferKm) {
		double km = bufferKm == null ? DEFAULT_BUFFER_KM : bufferKm;
		if (Double.isNaN(km) || km < 0 || km > MAX_BUFFER_KM) {
			throw new IllegalArgumentException("bufferKm must be between 0 and " + MAX_BUFFER_KM);
		}
		return km * 1000;
	}

	private static String filterClause(String speciesGroup, String iucnCategory) {
		String filterClause = "";
		if (speciesGroup != null && !speciesGroup.isEmpty()) {
			filterClause += " AND o.species_group = ?";
		}
		if (iucnCategory != null && !iucnCategory.isEmpty()) {
			filterClause += " AND o.iucnRedListCategory = ?";
		}
		return filterClause;
	}

	/** Binds the area parameters and optional filters; returns the next parameter index. */
	private static int bindAreaAndFilters(PreparedStatement stmt, String geometries, double bufferMeters,
			String speciesGroup, String iucnCategory) throws SQLException {
		int paramIndex = 1;
		stmt.setString(paramIndex++, geometries);
		stmt.setDouble(paramIndex++, bufferMeters);
		if (speciesGroup != null && !speciesGroup.isEmpty()) {
			stmt.setString(paramIndex++, speciesGroup);
		}
		if (iucnCategory != null && !iucnCategory.isEmpty()) {
			stmt.setString(paramIndex++, iucnCategory);
		}
		return paramIndex;
	}

	@Override
	public GBIFObservationResponse getObservations(String geoJson, Integer offset, Integer limit, String speciesGroup,
			String iucnCategory, Double bufferKm) {
		// Set default values
		if (offset == null || offset < 0) {
			offset = 0;
		}
		if (limit == null || limit <= 0) {
			limit = 10;
		}

		// Invalid input is thrown as IllegalArgumentException (400)
		String geometries = GeoJsonUtil.toGeometryArray(geoJson);
		double bufferMeters = toBufferMeters(bufferKm);

		try {
			logger.debug("Received GeoJSON: {}", geoJson);

			// Get parquet file path from configuration
			String parquetPath = ExternalOccurrencesConfig.getProperty("gbif_parquet_path");
			if (parquetPath == null || parquetPath.isEmpty()) {
				logger.error("GBIF parquet file path not configured");
				return new GBIFObservationResponse(0L, offset, limit, new ArrayList<>());
			}

			// Execute queries
			CountResult countResult = executeCountQuery(geometries, bufferMeters, parquetPath, speciesGroup, iucnCategory);
			List<SpeciesAggregation> aggregations = executeAggregationQuery(geometries, bufferMeters, parquetPath, limit,
					offset, speciesGroup, iucnCategory);

			logger.info("Found {} species aggregations (total species: {}, total occurrence records: {}) with speciesGroup filter: {}, iucnCategory filter: {}, buffer: {}m",
					aggregations.size(), countResult.totalSpecies, countResult.totalRecords, speciesGroup, iucnCategory,
					bufferMeters);

			return new GBIFObservationResponse(countResult.totalSpecies, countResult.totalRecords, offset, limit, aggregations);

		} catch (Exception e) {
			logger.error("Error querying GBIF observations", e);
			return new GBIFObservationResponse(0L, offset, limit, new ArrayList<>());
		}
	}

	private static final class CountResult {
		private final Long totalSpecies;
		private final Long totalRecords;

		private CountResult(Long totalSpecies, Long totalRecords) {
			this.totalSpecies = totalSpecies;
			this.totalRecords = totalRecords;
		}
	}

	private CountResult executeCountQuery(String geometries, double bufferMeters, String parquetPath,
			String speciesGroup, String iucnCategory) {
		try {
			return DuckDBUtil.withConnection(conn -> {
				CountResult result = new CountResult(0L, 0L);

				// Build the count query with parquet path and optional filters
				String query = String.format(buildCountQueryTemplate(), parquetPath)
						+ filterClause(speciesGroup, iucnCategory);

				logger.debug("Executing DuckDB count query with speciesGroup: {}, iucnCategory: {}", speciesGroup, iucnCategory);

				try (PreparedStatement stmt = conn.prepareStatement(query)) {
					bindAreaAndFilters(stmt, geometries, bufferMeters, speciesGroup, iucnCategory);

					// Execute query
					try (ResultSet rs = stmt.executeQuery()) {
						if (rs.next()) {
							result = new CountResult(rs.getLong("totalSpecies"), rs.getLong("totalRecords"));
						}
					}
				}

				return result;
			});
		} catch (Exception e) {
			logger.error("Error executing DuckDB count query", e);
			return new CountResult(0L, 0L);
		}
	}

	private List<SpeciesAggregation> executeAggregationQuery(String geometries, double bufferMeters,
			String parquetPath, Integer limit, Integer offset, String speciesGroup, String iucnCategory) {
		try {
			return DuckDBUtil.withConnection(conn -> {
				List<SpeciesAggregation> aggregations = new ArrayList<>();

				// Build the aggregation query with parquet path and optional filters
				String query = String.format(buildAggregationQueryTemplate(), parquetPath).replace("%FILTERS%",
						filterClause(speciesGroup, iucnCategory));

				logger.debug("Executing DuckDB aggregation query with parquet path: {}, limit: {}, offset: {}, speciesGroup: {}, iucnCategory: {}",
						parquetPath, limit, offset, speciesGroup, iucnCategory);

				try (PreparedStatement stmt = conn.prepareStatement(query)) {
					// Set parameters
					int paramIndex = bindAreaAndFilters(stmt, geometries, bufferMeters, speciesGroup, iucnCategory);
					stmt.setInt(paramIndex++, limit);
					stmt.setInt(paramIndex++, offset);

					// Execute query
					try (ResultSet rs = stmt.executeQuery()) {
						while (rs.next()) {
							SpeciesAggregation agg = new SpeciesAggregation();
							agg.setScientificName(rs.getString("scientificName"));
							agg.setCount(rs.getLong("count"));
							agg.setIucnRedListCategory(rs.getString("iucnRedListCategory"));
							agg.setSpeciesGroup(rs.getString("speciesGroup"));
							agg.setTaxonKey(rs.getString("taxonKey"));
							agg.setIucnLink(rs.getString("iucnLink"));
							aggregations.add(agg);
						}
					}
				}

				return aggregations;
			});
		} catch (Exception e) {
			logger.error("Error executing DuckDB aggregation query", e);
			return new ArrayList<>();
		}
	}

	@Override
	public SpeciesGroupAggregationResponse getSpeciesGroupAggregation(String geoJson, Double bufferKm) {
		// Invalid input is thrown as IllegalArgumentException (400)
		String geometries = GeoJsonUtil.toGeometryArray(geoJson);
		double bufferMeters = toBufferMeters(bufferKm);

		try {
			logger.debug("Received GeoJSON for species group aggregation: {}", geoJson);

			// Get parquet file path from configuration
			String parquetPath = ExternalOccurrencesConfig.getProperty("gbif_parquet_path");
			if (parquetPath == null || parquetPath.isEmpty()) {
				logger.error("GBIF parquet file path not configured");
				return new SpeciesGroupAggregationResponse(new ArrayList<>());
			}

			// Execute species group aggregation query
			List<SpeciesGroupAggregation> aggregations = executeSpeciesGroupAggregationQuery(geometries, bufferMeters,
					parquetPath);

			logger.info("Found {} species group aggregations", aggregations.size());

			return new SpeciesGroupAggregationResponse(aggregations);

		} catch (Exception e) {
			logger.error("Error querying species group aggregations", e);
			return new SpeciesGroupAggregationResponse(new ArrayList<>());
		}
	}

	private List<SpeciesGroupAggregation> executeSpeciesGroupAggregationQuery(String geometries, double bufferMeters,
			String parquetPath) {
		try {
			return DuckDBUtil.withConnection(conn -> {
				List<SpeciesGroupAggregation> aggregations = new ArrayList<>();

				// Build the species group aggregation query with parquet path
				String query = String.format(buildSpeciesGroupAggregationQueryTemplate(), parquetPath);

				logger.debug("Executing DuckDB species group aggregation query with parquet path: {}", parquetPath);

				try (PreparedStatement stmt = conn.prepareStatement(query)) {
					bindAreaAndFilters(stmt, geometries, bufferMeters, null, null);

					// Execute query
					try (ResultSet rs = stmt.executeQuery()) {
						while (rs.next()) {
							SpeciesGroupAggregation agg = new SpeciesGroupAggregation();
							agg.setSpeciesGroup(rs.getString("species_group"));
							agg.setTotalCount(rs.getLong("totalCount"));
							agg.setUniqueSpeciesCount(rs.getLong("uniqueSpeciesCount"));
							aggregations.add(agg);
						}
					}
				}

				return aggregations;
			});
		} catch (Exception e) {
			logger.error("Error executing DuckDB species group aggregation query", e);
			return new ArrayList<>();
		}
	}

	@Override
	public IUCNAggregationResponse getIUCNAggregation(String geoJson, Double bufferKm) {
		// Invalid input is thrown as IllegalArgumentException (400)
		String geometries = GeoJsonUtil.toGeometryArray(geoJson);
		double bufferMeters = toBufferMeters(bufferKm);

		try {
			logger.debug("Received GeoJSON for IUCN aggregation: {}", geoJson);

			// Get parquet file path from configuration
			String parquetPath = ExternalOccurrencesConfig.getProperty("gbif_parquet_path");
			if (parquetPath == null || parquetPath.isEmpty()) {
				logger.error("GBIF parquet file path not configured");
				return new IUCNAggregationResponse(new ArrayList<>());
			}

			// Execute IUCN aggregation query
			List<IUCNAggregation> aggregations = executeIUCNAggregationQuery(geometries, bufferMeters, parquetPath);

			logger.info("Found {} IUCN category aggregations", aggregations.size());

			return new IUCNAggregationResponse(aggregations);

		} catch (Exception e) {
			logger.error("Error querying IUCN aggregations", e);
			return new IUCNAggregationResponse(new ArrayList<>());
		}
	}

	private List<IUCNAggregation> executeIUCNAggregationQuery(String geometries, double bufferMeters,
			String parquetPath) {
		try {
			return DuckDBUtil.withConnection(conn -> {
				List<IUCNAggregation> aggregations = new ArrayList<>();

				// Build the IUCN aggregation query with parquet path
				String query = String.format(buildIUCNAggregationQueryTemplate(), parquetPath);

				logger.debug("Executing DuckDB IUCN aggregation query with parquet path: {}", parquetPath);

				try (PreparedStatement stmt = conn.prepareStatement(query)) {
					bindAreaAndFilters(stmt, geometries, bufferMeters, null, null);

					// Execute query
					try (ResultSet rs = stmt.executeQuery()) {
						while (rs.next()) {
							IUCNAggregation agg = new IUCNAggregation();
							agg.setIucnRedListCategory(rs.getString("iucnRedListCategory"));
							agg.setTotalCount(rs.getLong("totalCount"));
							agg.setUniqueSpeciesCount(rs.getLong("uniqueSpeciesCount"));
							aggregations.add(agg);
						}
					}
				}

				return aggregations;
			});
		} catch (Exception e) {
			logger.error("Error executing DuckDB IUCN aggregation query", e);
			return new ArrayList<>();
		}
	}

	@Override
	public OccurrenceLocationResponse getOccurrenceLocations(String geoJson, Integer limit, String speciesGroup,
			String iucnCategory, Double bufferKm) {
		if (limit == null || limit <= 0) {
			limit = DEFAULT_LOCATION_LIMIT;
		}
		limit = Math.min(limit, MAX_LOCATION_LIMIT);

		// Invalid input is thrown as IllegalArgumentException (400)
		String geometries = GeoJsonUtil.toGeometryArray(geoJson);
		double bufferMeters = toBufferMeters(bufferKm);

		OccurrenceLocationResponse empty = new OccurrenceLocationResponse(null, 0L, 0L, 0L, false, new ArrayList<>());

		String parquetPath = ExternalOccurrencesConfig.getProperty("gbif_parquet_path");
		if (parquetPath == null || parquetPath.isEmpty()) {
			logger.error("GBIF parquet file path not configured");
			return empty;
		}

		final int locationLimit = limit;
		try {
			return DuckDBUtil.withConnection(conn -> {
				String query = String.format(buildOccurrenceLocationQueryTemplate(), parquetPath)
						.replace("%FILTERS%", filterClause(speciesGroup, iucnCategory));

				logger.debug("Executing DuckDB occurrence location query with limit: {}, speciesGroup: {}, iucnCategory: {}",
						locationLimit, speciesGroup, iucnCategory);

				try (PreparedStatement stmt = conn.prepareStatement(query)) {
					int paramIndex = bindAreaAndFilters(stmt, geometries, bufferMeters, speciesGroup, iucnCategory);
					stmt.setInt(paramIndex++, locationLimit);

					OccurrenceLocationResponse response = new OccurrenceLocationResponse(null, 0L, 0L, 0L, false,
							new ArrayList<>());

					try (ResultSet rs = stmt.executeQuery()) {
						while (rs.next()) {
							// The area is empty (null bbox) when only points/lines are given with a zero buffer
							if (response.getBbox() == null && rs.getObject("min_lon") != null) {
								response.setBbox(Arrays.asList(rs.getDouble("min_lon"), rs.getDouble("min_lat"),
										rs.getDouble("max_lon"), rs.getDouble("max_lat")));
							}
							// LEFT JOIN yields a single all-null row when there are no occurrences
							if (rs.getObject("lat") == null) {
								continue;
							}
							response.setTotalLocations(rs.getLong("totalLocations"));
							response.setTotalRecords(rs.getLong("totalRecords"));
							response.setInsideRecords(rs.getLong("insideRecords"));
							response.getLocations().add(new OccurrenceLocation(rs.getDouble("lat"), rs.getDouble("lon"),
									rs.getLong("recordCount"), rs.getLong("speciesCount"),
									rs.getBoolean("insideGeometry")));
						}
					}

					response.setTruncated(response.getTotalLocations() > response.getLocations().size());

					logger.info("Found {} occurrence locations (returned: {}, total records: {}, inside records: {})",
							response.getTotalLocations(), response.getLocations().size(), response.getTotalRecords(),
							response.getInsideRecords());

					return response;
				}
			});
		} catch (Exception e) {
			logger.error("Error executing DuckDB occurrence location query", e);
			return empty;
		}
	}
}
