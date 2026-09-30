package com.strandls.externalOccurrences.service;

import com.strandls.externalOccurrences.pojo.response.GBIFObservationResponse;
import com.strandls.externalOccurrences.pojo.response.IUCNAggregationResponse;
import com.strandls.externalOccurrences.pojo.response.OccurrenceLocationResponse;
import com.strandls.externalOccurrences.pojo.response.SpeciesGroupAggregationResponse;

public interface GBIFObservationService {

	/**
	 * Query GBIF observations from parquet file based on a GeoJSON geometry with pagination
	 *
	 * @param geoJson Any GeoJSON (FeatureCollection, Feature, GeometryCollection or geometry)
	 * @param offset The offset for pagination (default 0)
	 * @param limit The limit for pagination (default 10)
	 * @param speciesGroup Optional species group filter
	 * @param iucnCategory Optional IUCN Red List Category filter
	 * @param bufferKm Buffer around every geometry part in km; null uses gbif_buffer_km
	 * @return Paginated response containing GBIF observations
	 */
	GBIFObservationResponse getObservations(String geoJson, Integer offset, Integer limit, String speciesGroup,
			String iucnCategory, Double bufferKm);

	/**
	 * Query GBIF observations aggregated by species group for a GeoJSON geometry
	 *
	 * @param geoJson Any GeoJSON (FeatureCollection, Feature, GeometryCollection or geometry)
	 * @param bufferKm Buffer around every geometry part in km; null uses gbif_buffer_km
	 * @return Response containing species group aggregations with total and unique counts
	 */
	SpeciesGroupAggregationResponse getSpeciesGroupAggregation(String geoJson, Double bufferKm);

	/**
	 * Query GBIF observations aggregated by IUCN Red List Category for a GeoJSON geometry
	 *
	 * @param geoJson Any GeoJSON (FeatureCollection, Feature, GeometryCollection or geometry)
	 * @param bufferKm Buffer around every geometry part in km; null uses gbif_buffer_km
	 * @return Response containing IUCN category aggregations with total and unique counts
	 */
	IUCNAggregationResponse getIUCNAggregation(String geoJson, Double bufferKm);

	/**
	 * Query GBIF occurrences in the bounding box of the search area, grouped by
	 * location, flagging whether each location falls inside the search area
	 *
	 * @param geoJson Any GeoJSON (FeatureCollection, Feature, GeometryCollection or geometry)
	 * @param limit Max number of locations to return; locations inside the geometry come first
	 * @param speciesGroup Optional species group filter
	 * @param iucnCategory Optional IUCN Red List Category filter
	 * @param bufferKm Buffer around every geometry part in km; null uses gbif_buffer_km
	 * @return Response containing occurrence locations and record totals
	 */
	OccurrenceLocationResponse getOccurrenceLocations(String geoJson, Integer limit, String speciesGroup,
			String iucnCategory, Double bufferKm);
}
