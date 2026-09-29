package com.strandls.externalOccurrences.service.impl;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
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

public class GBIFObservationServiceImpl implements GBIFObservationService {

	private final Logger logger = LoggerFactory.getLogger(GBIFObservationServiceImpl.class);
	private static final double GBIF_POINT_PADDING;
	private static final int DEFAULT_LOCATION_LIMIT = 5000;
	private static final int MAX_LOCATION_LIMIT = 10000;

	static {
		// Load padding value from config
		String paddingStr = ExternalOccurrencesConfig.getProperty("gbif_point_padding");
		GBIF_POINT_PADDING = (paddingStr != null && !paddingStr.isEmpty()) ? Double.parseDouble(paddingStr) : 0.1;
	}

	private static String buildCountQueryTemplate(double padding) {
		return "WITH input AS (" + "    SELECT ? AS geojson" + "), "
			+ "geom AS (" + "    SELECT" + "        ST_GeomFromGeoJSON("
			+ "            json_extract(geojson, '$.features[0].geometry')::VARCHAR" + "        ) AS shape,"
			+ "        json_extract(geojson, '$.features[0].geometry.type')::VARCHAR AS geom_type"
			+ "    FROM input" + "), " + "bbox AS (" + "    SELECT"
			+ "        CASE WHEN geom_type = '\"Point\"'"
			+ "            THEN ST_YMin(shape) - " + padding + "            ELSE ST_YMin(shape)"
			+ "        END AS min_lat," + "        CASE WHEN geom_type = '\"Point\"'"
			+ "            THEN ST_YMax(shape) + " + padding + "            ELSE ST_YMax(shape)"
			+ "        END AS max_lat," + "        CASE WHEN geom_type = '\"Point\"'"
			+ "            THEN ST_XMin(shape) - " + padding + "            ELSE ST_XMin(shape)"
			+ "        END AS min_lon," + "        CASE WHEN geom_type = '\"Point\"'"
			+ "            THEN ST_XMax(shape) + " + padding + "            ELSE ST_XMax(shape)" + "        END AS max_lon"
			+ "    FROM geom" + ") "
			+ "SELECT COUNT(DISTINCT o.scientificName) as totalSpecies, COUNT(*) as totalRecords FROM '%s' o, bbox"
			+ " WHERE o.decimalLatitude  BETWEEN bbox.min_lat AND bbox.max_lat"
			+ "  AND o.decimalLongitude BETWEEN bbox.min_lon AND bbox.max_lon"
			+ "  AND o.decimalLatitude  IS NOT NULL" + "  AND o.decimalLongitude IS NOT NULL"
			+ "  AND o.scientificName IS NOT NULL";
	}

	private static String buildAggregationQueryTemplate(double padding) {
		return "WITH input AS (" + "    SELECT ? AS geojson"
			+ "), " + "geom AS (" + "    SELECT" + "        ST_GeomFromGeoJSON("
			+ "            json_extract(geojson, '$.features[0].geometry')::VARCHAR" + "        ) AS shape,"
			+ "        json_extract(geojson, '$.features[0].geometry.type')::VARCHAR AS geom_type"
			+ "    FROM input" + "), " + "bbox AS (" + "    SELECT"
			+ "        CASE WHEN geom_type = '\"Point\"'"
			+ "            THEN ST_YMin(shape) - " + padding + "            ELSE ST_YMin(shape)"
			+ "        END AS min_lat," + "        CASE WHEN geom_type = '\"Point\"'"
			+ "            THEN ST_YMax(shape) + " + padding + "            ELSE ST_YMax(shape)"
			+ "        END AS max_lat," + "        CASE WHEN geom_type = '\"Point\"'"
			+ "            THEN ST_XMin(shape) - " + padding + "            ELSE ST_XMin(shape)"
			+ "        END AS min_lon," + "        CASE WHEN geom_type = '\"Point\"'"
			+ "            THEN ST_XMax(shape) + " + padding + "            ELSE ST_XMax(shape)" + "        END AS max_lon"
			+ "    FROM geom" + ") "
			+ "SELECT o.scientificName, COUNT(*) as count, FIRST(o.iucnRedListCategory) as iucnRedListCategory, FIRST(o.species_group) as speciesGroup, FIRST(o.taxonKey) as taxonKey, FIRST(o.iucn_link) as iucnLink FROM '%s' o, bbox"
			+ " WHERE o.decimalLatitude  BETWEEN bbox.min_lat AND bbox.max_lat"
			+ "  AND o.decimalLongitude BETWEEN bbox.min_lon AND bbox.max_lon"
			+ "  AND o.decimalLatitude  IS NOT NULL" + "  AND o.decimalLongitude IS NOT NULL"
			+ "  AND o.scientificName IS NOT NULL" + " GROUP BY o.scientificName" + " ORDER BY count DESC"
			+ " LIMIT ? OFFSET ?";
	}

	private static String buildSpeciesGroupAggregationQueryTemplate(double padding) {
		return "WITH input AS ("
			+ "    SELECT ? AS geojson" + "), " + "geom AS (" + "    SELECT" + "        ST_GeomFromGeoJSON("
			+ "            json_extract(geojson, '$.features[0].geometry')::VARCHAR" + "        ) AS shape,"
			+ "        json_extract(geojson, '$.features[0].geometry.type')::VARCHAR AS geom_type"
			+ "    FROM input" + "), " + "bbox AS (" + "    SELECT"
			+ "        CASE WHEN geom_type = '\"Point\"'"
			+ "            THEN ST_YMin(shape) - " + padding + "            ELSE ST_YMin(shape)"
			+ "        END AS min_lat," + "        CASE WHEN geom_type = '\"Point\"'"
			+ "            THEN ST_YMax(shape) + " + padding + "            ELSE ST_YMax(shape)"
			+ "        END AS max_lat," + "        CASE WHEN geom_type = '\"Point\"'"
			+ "            THEN ST_XMin(shape) - " + padding + "            ELSE ST_XMin(shape)"
			+ "        END AS min_lon," + "        CASE WHEN geom_type = '\"Point\"'"
			+ "            THEN ST_XMax(shape) + " + padding + "            ELSE ST_XMax(shape)" + "        END AS max_lon"
			+ "    FROM geom" + ") "
			+ "SELECT o.species_group, COUNT(*) as totalCount, COUNT(DISTINCT o.scientificName) as uniqueSpeciesCount FROM '%s' o, bbox"
			+ " WHERE o.decimalLatitude  BETWEEN bbox.min_lat AND bbox.max_lat"
			+ "  AND o.decimalLongitude BETWEEN bbox.min_lon AND bbox.max_lon"
			+ "  AND o.decimalLatitude  IS NOT NULL" + "  AND o.decimalLongitude IS NOT NULL"
			+ "  AND o.species_group IS NOT NULL" + " GROUP BY o.species_group"
			+ " ORDER BY CASE WHEN o.species_group = 'Others' THEN 1 ELSE 0 END, totalCount DESC";
	}

	private static String buildIUCNAggregationQueryTemplate(double padding) {
		return "WITH input AS ("
			+ "    SELECT ? AS geojson" + "), " + "geom AS (" + "    SELECT" + "        ST_GeomFromGeoJSON("
			+ "            json_extract(geojson, '$.features[0].geometry')::VARCHAR" + "        ) AS shape,"
			+ "        json_extract(geojson, '$.features[0].geometry.type')::VARCHAR AS geom_type"
			+ "    FROM input" + "), " + "bbox AS (" + "    SELECT"
			+ "        CASE WHEN geom_type = '\"Point\"'"
			+ "            THEN ST_YMin(shape) - " + padding + "            ELSE ST_YMin(shape)"
			+ "        END AS min_lat," + "        CASE WHEN geom_type = '\"Point\"'"
			+ "            THEN ST_YMax(shape) + " + padding + "            ELSE ST_YMax(shape)"
			+ "        END AS max_lat," + "        CASE WHEN geom_type = '\"Point\"'"
			+ "            THEN ST_XMin(shape) - " + padding + "            ELSE ST_XMin(shape)"
			+ "        END AS min_lon," + "        CASE WHEN geom_type = '\"Point\"'"
			+ "            THEN ST_XMax(shape) + " + padding + "            ELSE ST_XMax(shape)" + "        END AS max_lon"
			+ "    FROM geom" + "), "
			+ "all_categories AS ("
			+ "    SELECT unnest(['CR', 'EN', 'VU', 'NT', 'LC', 'DD', 'NE']) as category"
			+ "), "
			+ "observed_counts AS ("
			+ "    SELECT o.iucnRedListCategory, COUNT(*) as totalCount, COUNT(DISTINCT o.scientificName) as uniqueSpeciesCount"
			+ "    FROM '%s' o, bbox"
			+ "    WHERE o.decimalLatitude BETWEEN bbox.min_lat AND bbox.max_lat"
			+ "      AND o.decimalLongitude BETWEEN bbox.min_lon AND bbox.max_lon"
			+ "      AND o.decimalLatitude IS NOT NULL"
			+ "      AND o.decimalLongitude IS NOT NULL"
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
	 * Occurrences are grouped on coordinates rounded to 4 decimals (~11m); the
	 * inside test runs on the rounded point. Locations inside the geometry are
	 * ordered first so they survive the LIMIT. The bbox is LEFT JOINed so it is
	 * returned even when there are no occurrences. %%FILTERS%% (%FILTERS% after
	 * String.format) is replaced with
	 * the optional species group / IUCN filters.
	 */
	private static String buildOccurrenceLocationQueryTemplate(double padding) {
		return "WITH input AS (" + "    SELECT ? AS geojson" + "), "
			+ "geom AS (" + "    SELECT" + "        ST_GeomFromGeoJSON("
			+ "            json_extract(geojson, '$.features[0].geometry')::VARCHAR" + "        ) AS shape,"
			+ "        json_extract(geojson, '$.features[0].geometry.type')::VARCHAR AS geom_type"
			+ "    FROM input" + "), " + "bbox AS (" + "    SELECT"
			+ "        CASE WHEN geom_type = '\"Point\"'"
			+ "            THEN ST_YMin(shape) - " + padding + "            ELSE ST_YMin(shape)"
			+ "        END AS min_lat," + "        CASE WHEN geom_type = '\"Point\"'"
			+ "            THEN ST_YMax(shape) + " + padding + "            ELSE ST_YMax(shape)"
			+ "        END AS max_lat," + "        CASE WHEN geom_type = '\"Point\"'"
			+ "            THEN ST_XMin(shape) - " + padding + "            ELSE ST_XMin(shape)"
			+ "        END AS min_lon," + "        CASE WHEN geom_type = '\"Point\"'"
			+ "            THEN ST_XMax(shape) + " + padding + "            ELSE ST_XMax(shape)" + "        END AS max_lon"
			+ "    FROM geom" + "), "
			+ "grouped AS ("
			+ "    SELECT ROUND(o.decimalLatitude, 4) AS lat, ROUND(o.decimalLongitude, 4) AS lon,"
			+ "        COUNT(*) AS recordCount, COUNT(DISTINCT o.scientificName) AS speciesCount"
			+ "    FROM '%s' o, bbox"
			+ "    WHERE o.decimalLatitude  BETWEEN bbox.min_lat AND bbox.max_lat"
			+ "      AND o.decimalLongitude BETWEEN bbox.min_lon AND bbox.max_lon"
			+ "      AND o.decimalLatitude  IS NOT NULL" + "      AND o.decimalLongitude IS NOT NULL"
			+ "      AND o.scientificName IS NOT NULL"
			+ "      %%FILTERS%%"
			+ "    GROUP BY 1, 2" + "), "
			+ "flagged AS ("
			+ "    SELECT g.*,"
			+ "        CASE WHEN geom.geom_type IN ('\"Polygon\"', '\"MultiPolygon\"')"
			+ "            THEN ST_Intersects(geom.shape, ST_Point(g.lon, g.lat))"
			+ "            ELSE false"
			+ "        END AS insideGeometry"
			+ "    FROM grouped g, geom" + "), "
			+ "limited AS ("
			+ "    SELECT *,"
			+ "        COUNT(*) OVER () AS totalLocations,"
			+ "        SUM(recordCount) OVER ()::BIGINT AS totalRecords,"
			+ "        SUM(CASE WHEN insideGeometry THEN recordCount ELSE 0 END) OVER ()::BIGINT AS insideRecords"
			+ "    FROM flagged"
			+ "    ORDER BY insideGeometry DESC, recordCount DESC"
			+ "    LIMIT ?" + ") "
			+ "SELECT bbox.*, limited.* FROM bbox LEFT JOIN limited ON true"
			+ " ORDER BY limited.insideGeometry DESC, limited.recordCount DESC";
	}

	@Override
	public GBIFObservationResponse getObservations(String geoJson, Integer offset, Integer limit, String speciesGroup, String iucnCategory) {
		// Set default values
		if (offset == null || offset < 0) {
			offset = 0;
		}
		if (limit == null || limit <= 0) {
			limit = 10;
		}

		if (geoJson == null || geoJson.isEmpty()) {
			logger.warn("No geometry provided");
			return new GBIFObservationResponse(0L, offset, limit, new ArrayList<>());
		}

		try {
			logger.debug("Received GeoJSON: {}", geoJson);

			// Get parquet file path from configuration
			String parquetPath = ExternalOccurrencesConfig.getProperty("gbif_parquet_path");
			if (parquetPath == null || parquetPath.isEmpty()) {
				logger.error("GBIF parquet file path not configured");
				return new GBIFObservationResponse(0L, offset, limit, new ArrayList<>());
			}

			// Execute queries
			CountResult countResult = executeCountQuery(geoJson, parquetPath, speciesGroup, iucnCategory);
			List<SpeciesAggregation> aggregations = executeAggregationQuery(geoJson, parquetPath, limit, offset, speciesGroup, iucnCategory);

			logger.info("Found {} species aggregations (total species: {}, total occurrence records: {}) with speciesGroup filter: {}, iucnCategory filter: {}",
					aggregations.size(), countResult.totalSpecies, countResult.totalRecords, speciesGroup, iucnCategory);

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

	private CountResult executeCountQuery(String geoJson, String parquetPath, String speciesGroup, String iucnCategory) {
		try {
			return DuckDBUtil.withConnection(conn -> {
				CountResult result = new CountResult(0L, 0L);

				// Build the count query with parquet path and optional filters
				String baseQuery = String.format(buildCountQueryTemplate(GBIF_POINT_PADDING), parquetPath);
				int paramIndex = 2;
				if (speciesGroup != null && !speciesGroup.isEmpty()) {
					baseQuery = baseQuery + " AND o.species_group = ?";
				}
				if (iucnCategory != null && !iucnCategory.isEmpty()) {
					baseQuery = baseQuery + " AND o.iucnRedListCategory = ?";
				}

				logger.debug("Executing DuckDB count query with speciesGroup: {}, iucnCategory: {}", speciesGroup, iucnCategory);

				try (PreparedStatement stmt = conn.prepareStatement(baseQuery)) {
					// Set parameters
					stmt.setString(1, geoJson);
					if (speciesGroup != null && !speciesGroup.isEmpty()) {
						stmt.setString(paramIndex++, speciesGroup);
					}
					if (iucnCategory != null && !iucnCategory.isEmpty()) {
						stmt.setString(paramIndex++, iucnCategory);
					}

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

	private List<SpeciesAggregation> executeAggregationQuery(String geoJson, String parquetPath, Integer limit,
			Integer offset, String speciesGroup, String iucnCategory) {
		try {
			return DuckDBUtil.withConnection(conn -> {
				List<SpeciesAggregation> aggregations = new ArrayList<>();

				// Build the aggregation query with parquet path and optional filters
				String baseQuery = String.format(buildAggregationQueryTemplate(GBIF_POINT_PADDING), parquetPath);
				// Remove the LIMIT/OFFSET to add filters before them
				baseQuery = baseQuery.replace(" LIMIT ? OFFSET ?", "");
				String filterClause = "";
				if (speciesGroup != null && !speciesGroup.isEmpty()) {
					filterClause += " AND o.species_group = ?";
				}
				if (iucnCategory != null && !iucnCategory.isEmpty()) {
					filterClause += " AND o.iucnRedListCategory = ?";
				}
				if (!filterClause.isEmpty()) {
					baseQuery = baseQuery.replace(" GROUP BY o.scientificName", filterClause + " GROUP BY o.scientificName");
				}
				baseQuery = baseQuery + " LIMIT ? OFFSET ?";

				logger.debug("Executing DuckDB aggregation query with parquet path: {}, limit: {}, offset: {}, speciesGroup: {}, iucnCategory: {}",
						parquetPath, limit, offset, speciesGroup, iucnCategory);

				try (PreparedStatement stmt = conn.prepareStatement(baseQuery)) {
					// Set parameters
					int paramIndex = 1;
					stmt.setString(paramIndex++, geoJson);
					if (speciesGroup != null && !speciesGroup.isEmpty()) {
						stmt.setString(paramIndex++, speciesGroup);
					}
					if (iucnCategory != null && !iucnCategory.isEmpty()) {
						stmt.setString(paramIndex++, iucnCategory);
					}
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
	public SpeciesGroupAggregationResponse getSpeciesGroupAggregation(String geoJson) {
		if (geoJson == null || geoJson.isEmpty()) {
			logger.warn("No geometry provided");
			return new SpeciesGroupAggregationResponse(new ArrayList<>());
		}

		try {
			logger.debug("Received GeoJSON for species group aggregation: {}", geoJson);

			// Get parquet file path from configuration
			String parquetPath = ExternalOccurrencesConfig.getProperty("gbif_parquet_path");
			if (parquetPath == null || parquetPath.isEmpty()) {
				logger.error("GBIF parquet file path not configured");
				return new SpeciesGroupAggregationResponse(new ArrayList<>());
			}

			// Execute species group aggregation query
			List<SpeciesGroupAggregation> aggregations = executeSpeciesGroupAggregationQuery(geoJson, parquetPath);

			logger.info("Found {} species group aggregations", aggregations.size());

			return new SpeciesGroupAggregationResponse(aggregations);

		} catch (Exception e) {
			logger.error("Error querying species group aggregations", e);
			return new SpeciesGroupAggregationResponse(new ArrayList<>());
		}
	}

	private List<SpeciesGroupAggregation> executeSpeciesGroupAggregationQuery(String geoJson, String parquetPath) {
		try {
			return DuckDBUtil.withConnection(conn -> {
				List<SpeciesGroupAggregation> aggregations = new ArrayList<>();

				// Build the species group aggregation query with parquet path
				String query = String.format(buildSpeciesGroupAggregationQueryTemplate(GBIF_POINT_PADDING), parquetPath);

				logger.debug("Executing DuckDB species group aggregation query with parquet path: {}", parquetPath);

				try (PreparedStatement stmt = conn.prepareStatement(query)) {
					// Set parameters
					stmt.setString(1, geoJson);

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
	public IUCNAggregationResponse getIUCNAggregation(String geoJson) {
		if (geoJson == null || geoJson.isEmpty()) {
			logger.warn("No geometry provided");
			return new IUCNAggregationResponse(new ArrayList<>());
		}

		try {
			logger.debug("Received GeoJSON for IUCN aggregation: {}", geoJson);

			// Get parquet file path from configuration
			String parquetPath = ExternalOccurrencesConfig.getProperty("gbif_parquet_path");
			if (parquetPath == null || parquetPath.isEmpty()) {
				logger.error("GBIF parquet file path not configured");
				return new IUCNAggregationResponse(new ArrayList<>());
			}

			// Execute IUCN aggregation query
			List<IUCNAggregation> aggregations = executeIUCNAggregationQuery(geoJson, parquetPath);

			logger.info("Found {} IUCN category aggregations", aggregations.size());

			return new IUCNAggregationResponse(aggregations);

		} catch (Exception e) {
			logger.error("Error querying IUCN aggregations", e);
			return new IUCNAggregationResponse(new ArrayList<>());
		}
	}

	private List<IUCNAggregation> executeIUCNAggregationQuery(String geoJson, String parquetPath) {
		try {
			return DuckDBUtil.withConnection(conn -> {
				List<IUCNAggregation> aggregations = new ArrayList<>();

				// Build the IUCN aggregation query with parquet path
				String query = String.format(buildIUCNAggregationQueryTemplate(GBIF_POINT_PADDING), parquetPath);

				logger.debug("Executing DuckDB IUCN aggregation query with parquet path: {}", parquetPath);

				try (PreparedStatement stmt = conn.prepareStatement(query)) {
					// Set parameters
					stmt.setString(1, geoJson);

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
			String iucnCategory) {
		if (limit == null || limit <= 0) {
			limit = DEFAULT_LOCATION_LIMIT;
		}
		limit = Math.min(limit, MAX_LOCATION_LIMIT);

		OccurrenceLocationResponse empty = new OccurrenceLocationResponse(null, 0L, 0L, 0L, false, new ArrayList<>());

		if (geoJson == null || geoJson.isEmpty()) {
			logger.warn("No geometry provided");
			return empty;
		}

		String parquetPath = ExternalOccurrencesConfig.getProperty("gbif_parquet_path");
		if (parquetPath == null || parquetPath.isEmpty()) {
			logger.error("GBIF parquet file path not configured");
			return empty;
		}

		final int locationLimit = limit;
		try {
			return DuckDBUtil.withConnection(conn -> {
				String filterClause = "";
				if (speciesGroup != null && !speciesGroup.isEmpty()) {
					filterClause += " AND o.species_group = ?";
				}
				if (iucnCategory != null && !iucnCategory.isEmpty()) {
					filterClause += " AND o.iucnRedListCategory = ?";
				}
				String query = String.format(buildOccurrenceLocationQueryTemplate(GBIF_POINT_PADDING), parquetPath)
						.replace("%FILTERS%", filterClause);

				logger.debug("Executing DuckDB occurrence location query with limit: {}, speciesGroup: {}, iucnCategory: {}",
						locationLimit, speciesGroup, iucnCategory);

				try (PreparedStatement stmt = conn.prepareStatement(query)) {
					int paramIndex = 1;
					stmt.setString(paramIndex++, geoJson);
					if (speciesGroup != null && !speciesGroup.isEmpty()) {
						stmt.setString(paramIndex++, speciesGroup);
					}
					if (iucnCategory != null && !iucnCategory.isEmpty()) {
						stmt.setString(paramIndex++, iucnCategory);
					}
					stmt.setInt(paramIndex++, locationLimit);

					OccurrenceLocationResponse response = new OccurrenceLocationResponse(null, 0L, 0L, 0L, false,
							new ArrayList<>());

					try (ResultSet rs = stmt.executeQuery()) {
						while (rs.next()) {
							if (response.getBbox() == null) {
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
