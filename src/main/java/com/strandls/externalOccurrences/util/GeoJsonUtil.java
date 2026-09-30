package com.strandls.externalOccurrences.util;

import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * Normalises any GeoJSON object into a flat JSON array of geometries, which the
 * DuckDB queries read with {@code unnest(from_json(?, '["JSON"]'))}.
 */
public class GeoJsonUtil {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final Set<String> SIMPLE_TYPES = Set.of("Point", "MultiPoint", "LineString", "MultiLineString",
			"Polygon", "MultiPolygon");

	private GeoJsonUtil() {
	}

	/**
	 * Accepts a FeatureCollection, Feature, GeometryCollection or any single
	 * geometry and returns its geometries as a JSON array string.
	 *
	 * @throws IllegalArgumentException if the input is not valid GeoJSON, has no
	 *                                  geometry, or has coordinates outside WGS84
	 *                                  bounds
	 */
	public static String toGeometryArray(String geoJson) {
		if (geoJson == null || geoJson.isBlank()) {
			throw new IllegalArgumentException("GeoJSON body is required");
		}

		JsonNode root;
		try {
			root = MAPPER.readTree(geoJson);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("Invalid GeoJSON: " + e.getOriginalMessage());
		}

		ArrayNode geometries = MAPPER.createArrayNode();
		collect(root, geometries);
		if (geometries.isEmpty()) {
			throw new IllegalArgumentException("GeoJSON contains no geometry");
		}
		return geometries.toString();
	}

	private static void collect(JsonNode node, ArrayNode out) {
		if (node == null || !node.isObject()) {
			throw new IllegalArgumentException("Invalid GeoJSON: expected an object");
		}
		String type = node.path("type").asText();

		if ("FeatureCollection".equals(type)) {
			JsonNode features = node.path("features");
			if (!features.isArray()) {
				throw new IllegalArgumentException("Invalid GeoJSON: FeatureCollection has no features array");
			}
			for (JsonNode feature : features) {
				collect(feature, out);
			}
		} else if ("Feature".equals(type)) {
			JsonNode geometry = node.get("geometry");
			// A feature with a null geometry is valid GeoJSON and simply has no location
			if (geometry != null && !geometry.isNull()) {
				collect(geometry, out);
			}
		} else if ("GeometryCollection".equals(type)) {
			JsonNode members = node.path("geometries");
			if (!members.isArray()) {
				throw new IllegalArgumentException("Invalid GeoJSON: GeometryCollection has no geometries array");
			}
			for (JsonNode member : members) {
				collect(member, out);
			}
		} else if (SIMPLE_TYPES.contains(type)) {
			JsonNode coordinates = node.get("coordinates");
			if (coordinates == null || !coordinates.isArray()) {
				throw new IllegalArgumentException("Invalid GeoJSON: " + type + " has no coordinates");
			}
			validateCoordinates(coordinates);
			out.add(node);
		} else {
			throw new IllegalArgumentException("Unsupported GeoJSON type: '" + type + "'");
		}
	}

	/** Walks nested coordinate arrays down to positions and checks each [lon, lat]. */
	private static void validateCoordinates(JsonNode coordinates) {
		if (coordinates.size() > 0 && coordinates.get(0).isNumber()) {
			if (coordinates.size() < 2 || !coordinates.get(1).isNumber()) {
				throw new IllegalArgumentException("Invalid GeoJSON position: " + coordinates);
			}
			double lon = coordinates.get(0).asDouble();
			double lat = coordinates.get(1).asDouble();
			if (lon < -180 || lon > 180 || lat < -90 || lat > 90) {
				throw new IllegalArgumentException(
						"Coordinate out of range (expected [longitude, latitude]): " + coordinates);
			}
			return;
		}
		for (JsonNode child : coordinates) {
			if (!child.isArray()) {
				throw new IllegalArgumentException("Invalid GeoJSON coordinates: " + coordinates);
			}
			validateCoordinates(child);
		}
	}
}
