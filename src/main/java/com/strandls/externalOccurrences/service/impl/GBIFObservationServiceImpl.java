package com.strandls.externalOccurrences.service.impl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.strandls.externalOccurrences.ExternalOccurrencesConfig;
import com.strandls.externalOccurrences.pojo.IUCNAggregation;
import com.strandls.externalOccurrences.pojo.MonthYearCount;
import com.strandls.externalOccurrences.pojo.OccurrenceLocation;
import com.strandls.externalOccurrences.pojo.SpeciesAggregation;
import com.strandls.externalOccurrences.pojo.SpeciesGroupAggregation;
import com.strandls.externalOccurrences.pojo.response.GBIFObservationResponse;
import com.strandls.externalOccurrences.pojo.response.IUCNAggregationResponse;
import com.strandls.externalOccurrences.pojo.response.MonthYearResponse;
import com.strandls.externalOccurrences.pojo.response.OccurrenceLocationResponse;
import com.strandls.externalOccurrences.pojo.response.SpeciesGroupAggregationResponse;
import com.strandls.externalOccurrences.service.GBIFObservationService;
import com.strandls.externalOccurrences.util.DuckDBUtil;
import com.strandls.externalOccurrences.util.GeoJsonUtil;

public class GBIFObservationServiceImpl implements GBIFObservationService {

	private final Logger logger = LoggerFactory.getLogger(GBIFObservationServiceImpl.class);
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final double DEFAULT_POINT_BUFFER_KM;
	private static final double DEFAULT_BUFFER_KM;
	private static final double MAX_BUFFER_KM;
	private static final int DEFAULT_LOCATION_LIMIT = 5000;
	private static final int MAX_LOCATION_LIMIT = 10000;
	private static final int MIN_GRID_PRECISION = 1;
	private static final int MAX_GRID_PRECISION = 8;

	static {
		DEFAULT_POINT_BUFFER_KM = readDouble("gbif_point_buffer_km", 10);
		DEFAULT_BUFFER_KM = readDouble("gbif_buffer_km", 1);
		MAX_BUFFER_KM = readDouble("gbif_max_buffer_km", 100);
	}

	private static double readDouble(String key, double defaultValue) {
		String value = ExternalOccurrencesConfig.getProperty(key);
		return (value != null && !value.isEmpty()) ? Double.parseDouble(value) : defaultValue;
	}

	/**
	 * Every query reads the search area from the
	 * search_area(geojson, point_buffer_m, buffer_m) macro (see {@link DuckDBUtil}):
	 * parameter 1 is a GeometryCollection (see
	 * {@link GeoJsonUtil#toGeometryCollection}), parameter 2 the buffer around
	 * points in metres, parameter 3 the buffer around lines and polygons.
	 * An occurrence is in the area when it is inside the area's bounding box and
	 * ST_Intersects(area.a, point) is true. The bounding box test is cheap and
	 * keeps the exact test off most rows (about 4x faster than ST_Intersects alone).
	 */
	private static final String IN_EXTENT = " o.decimalLongitude BETWEEN area.x0 AND area.x1"
		+ " AND o.decimalLatitude BETWEEN area.y0 AND area.y1";
	private static final String IN_AREA = IN_EXTENT
		+ " AND ST_Intersects(area.a, ST_Point(o.decimalLongitude, o.decimalLatitude))";

	private static String buildCountQueryTemplate() {
		return "SELECT COUNT(DISTINCT o.scientificName) as totalSpecies, COUNT(*) as totalRecords"
			+ " FROM '%s' o, search_area(?, ?, ?) area"
			+ " WHERE" + IN_AREA
			+ "  AND o.scientificName IS NOT NULL";
	}

	private static String buildAggregationQueryTemplate() {
		return "SELECT o.scientificName, COUNT(*) as count, FIRST(o.iucnRedListCategory) as iucnRedListCategory, FIRST(o.species_group) as speciesGroup, FIRST(o.taxonKey) as taxonKey, FIRST(o.iucn_link) as iucnLink"
			+ ", ANY_VALUE(o.ibp_taxon_id) as taxonId, ANY_VALUE(o.ibp_species_id) as speciesId"
			+ " FROM '%s' o, search_area(?, ?, ?) area"
			+ " WHERE" + IN_AREA
			+ "  AND o.scientificName IS NOT NULL" + " %%FILTERS%%"
			+ " GROUP BY o.scientificName" + " ORDER BY count DESC"
			+ " LIMIT ? OFFSET ?";
	}

	private static String buildSpeciesGroupAggregationQueryTemplate() {
		return "SELECT o.species_group, COUNT(*) as totalCount, COUNT(DISTINCT o.scientificName) as uniqueSpeciesCount"
			+ " FROM '%s' o, search_area(?, ?, ?) area"
			+ " WHERE" + IN_AREA
			+ "  AND o.species_group IS NOT NULL" + " GROUP BY o.species_group"
			+ " ORDER BY CASE WHEN o.species_group = 'Others' THEN 1 ELSE 0 END, totalCount DESC";
	}

	private static String buildIUCNAggregationQueryTemplate() {
		return "WITH all_categories AS ("
			+ "    SELECT unnest(['CR', 'EN', 'VU', 'NT', 'LC', 'DD', 'NE']) as category"
			+ "), "
			+ "observed_counts AS ("
			+ "    SELECT o.iucnRedListCategory, COUNT(*) as totalCount, COUNT(DISTINCT o.scientificName) as uniqueSpeciesCount"
			+ "    FROM '%s' o, search_area(?, ?, ?) area"
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
	 * Returns the occurrence locations inside the search area, grouped on
	 * coordinates rounded to 4 decimals (~11m). Only records inside the area are
	 * counted, so insideRecords matches the other endpoints, and polygonRecords
	 * counts the ones inside the input's own polygons. Locations inside the
	 * polygons are ordered first so they survive the LIMIT. The area is LEFT
	 * JOINed so it is returned even when there are no occurrences. %%FILTERS%%
	 * (%FILTERS% after String.format) is replaced with the optional species
	 * group / IUCN filters.
	 */
	private static String buildOccurrenceLocationQueryTemplate() {
		return "WITH area AS (" + "    SELECT * FROM search_area(?, ?, ?)" + "), "
			+ "grouped AS ("
			+ "    SELECT ROUND(o.decimalLatitude, 4) AS lat, ROUND(o.decimalLongitude, 4) AS lon,"
			+ "        COUNT(*) AS recordCount, COUNT(DISTINCT o.scientificName) AS speciesCount,"
			+ "        COUNT(*) FILTER (WHERE ST_Intersects(area.polygons, ST_Point(o.decimalLongitude, o.decimalLatitude))) AS polygonCount"
			+ "    FROM '%s' o, area"
			+ "    WHERE" + IN_AREA
			+ "      AND o.scientificName IS NOT NULL"
			+ "      %%FILTERS%%"
			+ "    GROUP BY 1, 2" + "), "
			+ "limited AS ("
			+ "    SELECT *, polygonCount > 0 AS insidePolygon,"
			+ "        COUNT(*) OVER () AS totalLocations,"
			+ "        SUM(recordCount) OVER ()::BIGINT AS insideRecords,"
			+ "        SUM(polygonCount) OVER ()::BIGINT AS polygonRecords"
			+ "    FROM grouped"
			+ "    ORDER BY insidePolygon DESC, recordCount DESC"
			+ "    LIMIT ?" + ") "
			+ "SELECT area.x0 AS min_lon, area.y0 AS min_lat, area.x1 AS max_lon, area.y1 AS max_lat,"
			+ "    ST_AsGeoJSON(area.a) AS search_area, limited.*"
			+ " FROM area LEFT JOIN limited ON true"
			+ " ORDER BY limited.insidePolygon DESC, limited.recordCount DESC";
	}

	/**
	 * Buffers the search area is built with: points (and multipoints) grow by
	 * pointMeters, lines and polygons by meters.
	 */
	private static final class SearchBuffers {
		private final double pointMeters;
		private final double meters;

		private SearchBuffers(double pointMeters, double meters) {
			this.pointMeters = pointMeters;
			this.meters = meters;
		}

		/**
		 * @return the buffers in metres; the configured defaults where a value is null
		 * @throws IllegalArgumentException if a buffer is outside 0..gbif_max_buffer_km
		 */
		private static SearchBuffers of(Double pointBufferKm, Double bufferKm) {
			return new SearchBuffers(toMeters("pointBufferKm", pointBufferKm, DEFAULT_POINT_BUFFER_KM),
					toMeters("bufferKm", bufferKm, DEFAULT_BUFFER_KM));
		}

		private static double toMeters(String name, Double valueKm, double defaultKm) {
			double km = valueKm == null ? defaultKm : valueKm;
			if (Double.isNaN(km) || km < 0 || km > MAX_BUFFER_KM) {
				throw new IllegalArgumentException(name + " must be between 0 and " + MAX_BUFFER_KM);
			}
			return km * 1000;
		}

		@Override
		public String toString() {
			return "points " + pointMeters + "m, other geometries " + meters + "m";
		}
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
	private static int bindAreaAndFilters(PreparedStatement stmt, String geometries, SearchBuffers buffers,
			String speciesGroup, String iucnCategory) throws SQLException {
		int paramIndex = 1;
		stmt.setString(paramIndex++, geometries);
		stmt.setDouble(paramIndex++, buffers.pointMeters);
		stmt.setDouble(paramIndex++, buffers.meters);
		if (speciesGroup != null && !speciesGroup.isEmpty()) {
			stmt.setString(paramIndex++, speciesGroup);
		}
		if (iucnCategory != null && !iucnCategory.isEmpty()) {
			stmt.setString(paramIndex++, iucnCategory);
		}
		return paramIndex;
	}

	/** @return the column as a Long, or null when it is SQL NULL (getLong would give 0) */
	private static Long getNullableLong(ResultSet rs, String column) throws SQLException {
		long value = rs.getLong(column);
		return rs.wasNull() ? null : value;
	}

	@Override
	public GBIFObservationResponse getObservations(String geoJson, Integer offset, Integer limit, String speciesGroup,
			String iucnCategory, Double pointBufferKm, Double bufferKm) {
		// Set default values
		if (offset == null || offset < 0) {
			offset = 0;
		}
		if (limit == null || limit <= 0) {
			limit = 10;
		}

		// Invalid input is thrown as IllegalArgumentException (400)
		String geometries = GeoJsonUtil.toGeometryCollection(geoJson);
		SearchBuffers buffers = SearchBuffers.of(pointBufferKm, bufferKm);

		try {
			logger.debug("Received GeoJSON: {}", geoJson);

			// Get parquet file path from configuration
			String parquetPath = ExternalOccurrencesConfig.getProperty("gbif_parquet_path");
			if (parquetPath == null || parquetPath.isEmpty()) {
				logger.error("GBIF parquet file path not configured");
				return new GBIFObservationResponse(0L, offset, limit, new ArrayList<>());
			}

			// Execute queries
			CountResult countResult = executeCountQuery(geometries, buffers, parquetPath, speciesGroup, iucnCategory);
			List<SpeciesAggregation> aggregations = executeAggregationQuery(geometries, buffers, parquetPath, limit,
					offset, speciesGroup, iucnCategory);

			logger.info("Found {} species aggregations (total species: {}, total occurrence records: {}) with speciesGroup filter: {}, iucnCategory filter: {}, buffers: {}",
					aggregations.size(), countResult.totalSpecies, countResult.totalRecords, speciesGroup, iucnCategory,
					buffers);

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

	private CountResult executeCountQuery(String geometries, SearchBuffers buffers, String parquetPath,
			String speciesGroup, String iucnCategory) {
		try {
			return DuckDBUtil.withConnection(conn -> {
				CountResult result = new CountResult(0L, 0L);

				// Build the count query with parquet path and optional filters
				String query = String.format(buildCountQueryTemplate(), parquetPath)
						+ filterClause(speciesGroup, iucnCategory);

				logger.debug("Executing DuckDB count query with speciesGroup: {}, iucnCategory: {}", speciesGroup, iucnCategory);

				try (PreparedStatement stmt = conn.prepareStatement(query)) {
					bindAreaAndFilters(stmt, geometries, buffers, speciesGroup, iucnCategory);

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

	private List<SpeciesAggregation> executeAggregationQuery(String geometries, SearchBuffers buffers,
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
					int paramIndex = bindAreaAndFilters(stmt, geometries, buffers, speciesGroup, iucnCategory);
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
							agg.setTaxonId(getNullableLong(rs, "taxonId"));
							agg.setSpeciesId(getNullableLong(rs, "speciesId"));
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
	public SpeciesGroupAggregationResponse getSpeciesGroupAggregation(String geoJson, Double pointBufferKm, Double bufferKm) {
		// Invalid input is thrown as IllegalArgumentException (400)
		String geometries = GeoJsonUtil.toGeometryCollection(geoJson);
		SearchBuffers buffers = SearchBuffers.of(pointBufferKm, bufferKm);

		try {
			logger.debug("Received GeoJSON for species group aggregation: {}", geoJson);

			// Get parquet file path from configuration
			String parquetPath = ExternalOccurrencesConfig.getProperty("gbif_parquet_path");
			if (parquetPath == null || parquetPath.isEmpty()) {
				logger.error("GBIF parquet file path not configured");
				return new SpeciesGroupAggregationResponse(new ArrayList<>());
			}

			// Execute species group aggregation query
			List<SpeciesGroupAggregation> aggregations = executeSpeciesGroupAggregationQuery(geometries, buffers,
					parquetPath);

			logger.info("Found {} species group aggregations", aggregations.size());

			return new SpeciesGroupAggregationResponse(aggregations);

		} catch (Exception e) {
			logger.error("Error querying species group aggregations", e);
			return new SpeciesGroupAggregationResponse(new ArrayList<>());
		}
	}

	private List<SpeciesGroupAggregation> executeSpeciesGroupAggregationQuery(String geometries, SearchBuffers buffers,
			String parquetPath) {
		try {
			return DuckDBUtil.withConnection(conn -> {
				List<SpeciesGroupAggregation> aggregations = new ArrayList<>();

				// Build the species group aggregation query with parquet path
				String query = String.format(buildSpeciesGroupAggregationQueryTemplate(), parquetPath);

				logger.debug("Executing DuckDB species group aggregation query with parquet path: {}", parquetPath);

				try (PreparedStatement stmt = conn.prepareStatement(query)) {
					bindAreaAndFilters(stmt, geometries, buffers, null, null);

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
	public IUCNAggregationResponse getIUCNAggregation(String geoJson, Double pointBufferKm, Double bufferKm) {
		// Invalid input is thrown as IllegalArgumentException (400)
		String geometries = GeoJsonUtil.toGeometryCollection(geoJson);
		SearchBuffers buffers = SearchBuffers.of(pointBufferKm, bufferKm);

		try {
			logger.debug("Received GeoJSON for IUCN aggregation: {}", geoJson);

			// Get parquet file path from configuration
			String parquetPath = ExternalOccurrencesConfig.getProperty("gbif_parquet_path");
			if (parquetPath == null || parquetPath.isEmpty()) {
				logger.error("GBIF parquet file path not configured");
				return new IUCNAggregationResponse(new ArrayList<>());
			}

			// Execute IUCN aggregation query
			List<IUCNAggregation> aggregations = executeIUCNAggregationQuery(geometries, buffers, parquetPath);

			logger.info("Found {} IUCN category aggregations", aggregations.size());

			return new IUCNAggregationResponse(aggregations);

		} catch (Exception e) {
			logger.error("Error querying IUCN aggregations", e);
			return new IUCNAggregationResponse(new ArrayList<>());
		}
	}

	private List<IUCNAggregation> executeIUCNAggregationQuery(String geometries, SearchBuffers buffers,
			String parquetPath) {
		try {
			return DuckDBUtil.withConnection(conn -> {
				List<IUCNAggregation> aggregations = new ArrayList<>();

				// Build the IUCN aggregation query with parquet path
				String query = String.format(buildIUCNAggregationQueryTemplate(), parquetPath);

				logger.debug("Executing DuckDB IUCN aggregation query with parquet path: {}", parquetPath);

				try (PreparedStatement stmt = conn.prepareStatement(query)) {
					bindAreaAndFilters(stmt, geometries, buffers, null, null);

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
			String iucnCategory, Double pointBufferKm, Double bufferKm) {
		if (limit == null || limit <= 0) {
			limit = DEFAULT_LOCATION_LIMIT;
		}
		limit = Math.min(limit, MAX_LOCATION_LIMIT);

		// Invalid input is thrown as IllegalArgumentException (400)
		String geometries = GeoJsonUtil.toGeometryCollection(geoJson);
		SearchBuffers buffers = SearchBuffers.of(pointBufferKm, bufferKm);

		OccurrenceLocationResponse empty = emptyOccurrenceLocationResponse(buffers);

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
					int paramIndex = bindAreaAndFilters(stmt, geometries, buffers, speciesGroup, iucnCategory);
					stmt.setInt(paramIndex++, locationLimit);

					OccurrenceLocationResponse response = emptyOccurrenceLocationResponse(buffers);

					try (ResultSet rs = stmt.executeQuery()) {
						while (rs.next()) {
							if (response.getBbox() == null && rs.getObject("min_lon") != null) {
								response.setBbox(Arrays.asList(rs.getDouble("min_lon"), rs.getDouble("min_lat"),
										rs.getDouble("max_lon"), rs.getDouble("max_lat")));
								response.setSearchArea(parseGeometry(rs.getString("search_area")));
							}
							// LEFT JOIN yields a single all-null row when there are no occurrences
							if (rs.getObject("lat") == null) {
								continue;
							}
							response.setTotalLocations(rs.getLong("totalLocations"));
							// Every returned location is inside the search area
							response.setTotalRecords(rs.getLong("insideRecords"));
							response.setInsideRecords(rs.getLong("insideRecords"));
							response.setPolygonRecords(rs.getLong("polygonRecords"));
							response.getLocations().add(new OccurrenceLocation(rs.getDouble("lat"), rs.getDouble("lon"),
									rs.getLong("recordCount"), rs.getLong("speciesCount"),
									true, rs.getBoolean("insidePolygon")));
						}
					}

					response.setTruncated(response.getTotalLocations() > response.getLocations().size());

					logger.info("Found {} occurrence locations (returned: {}, total records: {}, inside records: {}, polygon records: {})",
							response.getTotalLocations(), response.getLocations().size(), response.getTotalRecords(),
							response.getInsideRecords(), response.getPolygonRecords());

					return response;
				}
			});
		} catch (Exception e) {
			logger.error("Error executing DuckDB occurrence location query", e);
			return empty;
		}
	}

	/**
	 * Record count per geohash cell (pintail st_geohash) for one IBP taxon inside
	 * a bounding box. Reads only ibp_taxon_id, decimalLatitude and
	 * decimalLongitude: the gbif_taxon_grid_parquet_path copy holds just these, so
	 * a new column used here must be added to that file too.
	 */
	private static final String TAXON_GRID_QUERY_TEMPLATE = "SELECT st_geohash(o.decimalLatitude, o.decimalLongitude, ?) AS geohash,"
			+ " COUNT(*) AS count"
			+ " FROM '%s' o"
			+ " WHERE o.ibp_taxon_id = ?"
			+ " AND o.decimalLatitude BETWEEN ? AND ? AND o.decimalLongitude BETWEEN ? AND ?"
			+ " GROUP BY geohash";

	@Override
	public Map<String, Long> getTaxonGrid(Long taxonId, Double top, Double left, Double bottom, Double right,
			Integer precision) {
		if (taxonId == null) {
			throw new IllegalArgumentException("taxonId is required");
		}
		checkGridPrecision(precision);

		String parquetPath = getTaxonGridParquetPath();
		if (parquetPath == null) {
			logger.error("GBIF parquet file path not configured");
			return new LinkedHashMap<>();
		}
		return queryGrid(String.format(TAXON_GRID_QUERY_TEMPLATE, parquetPath), taxonId, precision, top, left, bottom,
				right);
	}

	/** Record count per geohash cell (pintail st_geohash) for all GBIF records inside a bounding box */
	private static final String GRID_QUERY_TEMPLATE = "SELECT st_geohash(o.decimalLatitude, o.decimalLongitude, ?) AS geohash,"
			+ " COUNT(*) AS count"
			+ " FROM '%s' o"
			+ " WHERE o.decimalLatitude BETWEEN ? AND ? AND o.decimalLongitude BETWEEN ? AND ?"
			+ " GROUP BY geohash";

	/**
	 * Up to this precision getGrid filters a grid of the whole file kept in memory
	 * instead of querying the file. Zoomed-out maps (naksha asks for precision 4
	 * below zoom 6) cover nearly every record, so each of those queries would
	 * aggregate the whole file (seconds); at precision 4 the whole file is about
	 * 6,500 cells. The grids are built on first use and kept until a restart.
	 */
	private static final int WHOLE_GRID_MAX_PRECISION = 4;
	private static final Map<Integer, Map<String, Long>> WHOLE_GRIDS = new ConcurrentHashMap<>();

	@Override
	public Map<String, Long> getGrid(Double top, Double left, Double bottom, Double right, Integer precision) {
		checkGridPrecision(precision);

		String parquetPath = ExternalOccurrencesConfig.getProperty("gbif_parquet_path");
		if (parquetPath == null || parquetPath.isEmpty()) {
			logger.error("GBIF parquet file path not configured");
			return new LinkedHashMap<>();
		}
		String query = String.format(GRID_QUERY_TEMPLATE, parquetPath);

		if (precision > WHOLE_GRID_MAX_PRECISION) {
			return queryGrid(query, null, precision, top, left, bottom, right);
		}

		// a failed query returns an empty grid, which is not kept, so the next request retries
		Map<String, Long> wholeGrid = WHOLE_GRIDS.computeIfAbsent(precision, p -> {
			Map<String, Long> grid = queryGrid(query, null, p, null, null, null, null);
			return grid.isEmpty() ? null : grid;
		});
		if (wholeGrid == null) {
			return new LinkedHashMap<>();
		}

		double north = clamp(top, -90, 90, 90);
		double south = clamp(bottom, -90, 90, -90);
		double east = clamp(right, -180, 180, 180);
		double west = clamp(left, -180, 180, -180);

		Map<String, Long> grid = new LinkedHashMap<>();
		wholeGrid.forEach((geohash, count) -> {
			double[] cell = geohashBounds(geohash);
			if (cell[0] <= north && cell[1] >= south && cell[2] <= east && cell[3] >= west) {
				grid.put(geohash, count);
			}
		});
		return grid;
	}

	private static void checkGridPrecision(Integer precision) {
		if (precision == null || precision < MIN_GRID_PRECISION || precision > MAX_GRID_PRECISION) {
			throw new IllegalArgumentException(
					"precision must be between " + MIN_GRID_PRECISION + " and " + MAX_GRID_PRECISION);
		}
	}

	/**
	 * Runs a grid query whose parameters are precision, then taxonId when not null,
	 * then south, north, west and east
	 *
	 * @return record count per geohash, empty when the query fails
	 */
	private Map<String, Long> queryGrid(String query, Long taxonId, int precision, Double top, Double left,
			Double bottom, Double right) {
		// Map bounds can go past the poles and wrap past the antimeridian
		double north = clamp(top, -90, 90, 90);
		double south = clamp(bottom, -90, 90, -90);
		double east = clamp(right, -180, 180, 180);
		double west = clamp(left, -180, 180, -180);

		try {
			return DuckDBUtil.withConnection(conn -> {
				try (PreparedStatement stmt = conn.prepareStatement(query)) {
					int i = 1;
					stmt.setInt(i++, precision);
					if (taxonId != null) {
						stmt.setLong(i++, taxonId);
					}
					stmt.setDouble(i++, south);
					stmt.setDouble(i++, north);
					stmt.setDouble(i++, west);
					stmt.setDouble(i, east);

					Map<String, Long> grid = new LinkedHashMap<>();
					try (ResultSet rs = stmt.executeQuery()) {
						while (rs.next()) {
							grid.put(rs.getString("geohash"), rs.getLong("count"));
						}
					}

					logger.debug("Found {} grid cells for {} at precision {}", grid.size(),
							taxonId != null ? "taxon " + taxonId : "all records", precision);
					return grid;
				}
			});
		} catch (Exception e) {
			logger.error("Error executing DuckDB grid query", e);
			return new LinkedHashMap<>();
		}
	}

	private static final String GEOHASH_BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";

	/** @return {south, north, west, east} of a geohash cell */
	private static double[] geohashBounds(String geohash) {
		double[] lat = { -90, 90 };
		double[] lon = { -180, 180 };
		boolean isLon = true;
		for (char c : geohash.toCharArray()) {
			int bits = GEOHASH_BASE32.indexOf(c);
			for (int mask = 16; mask > 0; mask >>= 1) {
				double[] range = isLon ? lon : lat;
				double middle = (range[0] + range[1]) / 2;
				if ((bits & mask) != 0) {
					range[0] = middle;
				} else {
					range[1] = middle;
				}
				isLon = !isLon;
			}
		}
		return new double[] { lat[0], lat[1], lon[0], lon[1] };
	}

	/** Earliest and latest year with a month for one IBP taxon */
	private static final String TAXON_YEAR_RANGE_QUERY_TEMPLATE = "SELECT MIN(o.year) AS minYear, MAX(o.year) AS maxYear"
			+ " FROM '%s' o"
			+ " WHERE o.ibp_taxon_id = ? AND o.month BETWEEN 1 AND 12 AND o.year IS NOT NULL";

	/** Record count per year and month for one IBP taxon inside a range of years */
	private static final String TAXON_MONTH_YEAR_QUERY_TEMPLATE = "SELECT o.year AS year, o.month AS month, COUNT(*) AS count"
			+ " FROM '%s' o"
			+ " WHERE o.ibp_taxon_id = ? AND o.month BETWEEN 1 AND 12 AND o.year BETWEEN ? AND ?"
			+ " GROUP BY o.year, o.month"
			+ " ORDER BY o.year, o.month";

	private static final String[] MONTH_YEAR_COLUMNS = { "ibp_taxon_id", "year", "month" };

	/** Whether a parquet file has the columns a query needs, per path, checked once */
	private static final Map<String, Boolean> PARQUET_HAS_COLUMNS = new ConcurrentHashMap<>();

	@Override
	public MonthYearResponse getTaxonMonthYear(Long taxonId, Integer minYear, Integer maxYear) {
		if (taxonId == null) {
			throw new IllegalArgumentException("taxonId is required");
		}
		if (minYear != null && maxYear != null && minYear > maxYear) {
			throw new IllegalArgumentException("minYear must not be after maxYear");
		}

		MonthYearResponse response = new MonthYearResponse(null, null, new ArrayList<>());

		try {
			return DuckDBUtil.withConnection(conn -> {
				String parquetPath = getTaxonMonthYearParquetPath(conn);
				if (parquetPath == null) {
					logger.error("GBIF parquet file path not configured");
					return response;
				}

				try (PreparedStatement stmt = conn
						.prepareStatement(String.format(TAXON_YEAR_RANGE_QUERY_TEMPLATE, parquetPath))) {
					stmt.setLong(1, taxonId);
					try (ResultSet rs = stmt.executeQuery()) {
						if (rs.next() && rs.getObject("minYear") != null) {
							response.setMinYear(rs.getInt("minYear"));
							response.setMaxYear(rs.getInt("maxYear"));
						}
					}
				}
				if (response.getMinYear() == null) {
					return response;
				}

				try (PreparedStatement stmt = conn
						.prepareStatement(String.format(TAXON_MONTH_YEAR_QUERY_TEMPLATE, parquetPath))) {
					stmt.setLong(1, taxonId);
					stmt.setInt(2, minYear != null ? minYear : response.getMinYear());
					stmt.setInt(3, maxYear != null ? maxYear : response.getMaxYear());
					try (ResultSet rs = stmt.executeQuery()) {
						while (rs.next()) {
							response.getCounts().add(
									new MonthYearCount(rs.getInt("year"), rs.getInt("month"), rs.getLong("count")));
						}
					}
				}

				logger.debug("Found {} year-month counts for taxon {} in {}-{}", response.getCounts().size(), taxonId,
						minYear, maxYear);
				return response;
			});
		} catch (Exception e) {
			logger.error("Error executing DuckDB taxon month-year query", e);
			return new MonthYearResponse(null, null, new ArrayList<>());
		}
	}

	/**
	 * The taxon-sorted copy (gbif_taxon_grid_parquet_path) when it also has year and
	 * month, else the full file. The sorted copy is much faster per taxon; until it
	 * is rebuilt with year and month, the full file keeps this endpoint working.
	 */
	private String getTaxonMonthYearParquetPath(Connection conn) {
		String gridPath = getTaxonGridParquetPath();
		String fullPath = ExternalOccurrencesConfig.getProperty("gbif_parquet_path");
		if (gridPath == null || gridPath.equals(fullPath)) {
			return gridPath;
		}
		boolean hasColumns = PARQUET_HAS_COLUMNS.computeIfAbsent(gridPath, path -> hasColumns(conn, path, MONTH_YEAR_COLUMNS));
		return hasColumns ? gridPath : fullPath;
	}

	/** @return whether the parquet file at path has all the columns */
	private boolean hasColumns(Connection conn, String path, String... columns) {
		try (PreparedStatement stmt = conn
				.prepareStatement(String.format("SELECT column_name FROM (DESCRIBE SELECT * FROM '%s')", path));
				ResultSet rs = stmt.executeQuery()) {
			List<String> present = new ArrayList<>();
			while (rs.next()) {
				present.add(rs.getString("column_name"));
			}
			boolean all = present.containsAll(Arrays.asList(columns));
			if (!all) {
				logger.info("{} has no year/month columns, the month-year counts read gbif_parquet_path", path);
			}
			return all;
		} catch (SQLException e) {
			logger.warn("Could not read the columns of {}", path, e);
			return false;
		}
	}

	/**
	 * The taxon-sorted copy (gbif_taxon_grid_parquet_path) when it is configured and
	 * readable, else the full file (gbif_parquet_path). In the full file every row
	 * group spans almost every ibp_taxon_id, so each grid query reads all of it; in
	 * the sorted copy DuckDB reads only the row groups holding that taxon.
	 *
	 * @return parquet path, or null when neither is configured
	 */
	private String getTaxonGridParquetPath() {
		String sortedPath = ExternalOccurrencesConfig.getProperty("gbif_taxon_grid_parquet_path");
		if (sortedPath != null && !sortedPath.isEmpty()) {
			if (Files.isReadable(Path.of(sortedPath))) {
				return sortedPath;
			}
			logger.warn("gbif_taxon_grid_parquet_path {} is not readable, using gbif_parquet_path", sortedPath);
		}
		String parquetPath = ExternalOccurrencesConfig.getProperty("gbif_parquet_path");
		return parquetPath == null || parquetPath.isEmpty() ? null : parquetPath;
	}

	/** @return value limited to min..max, or defaultValue when it is null or NaN */
	private static double clamp(Double value, double min, double max, double defaultValue) {
		if (value == null || value.isNaN()) {
			return defaultValue;
		}
		return Math.max(min, Math.min(max, value));
	}

	private static OccurrenceLocationResponse emptyOccurrenceLocationResponse(SearchBuffers buffers) {
		OccurrenceLocationResponse response = new OccurrenceLocationResponse(null, 0L, 0L, 0L, false,
				new ArrayList<>());
		response.setPolygonRecords(0L);
		response.setPointBufferKm(buffers.pointMeters / 1000);
		response.setBufferKm(buffers.meters / 1000);
		return response;
	}

	/** @return a GeoJSON geometry string as a map, so it is returned as JSON rather than a string */
	private Map<String, Object> parseGeometry(String geoJson) {
		if (geoJson == null) {
			return null;
		}
		try {
			return MAPPER.readValue(geoJson, new TypeReference<Map<String, Object>>() {
			});
		} catch (JsonProcessingException e) {
			logger.error("Could not parse search area GeoJSON", e);
			return null;
		}
	}
}
