package com.strandls.externalOccurrences.service;

import com.strandls.externalOccurrences.pojo.response.GBIFObservationResponse;
import com.strandls.externalOccurrences.pojo.response.IUCNAggregationResponse;
import com.strandls.externalOccurrences.pojo.response.OccurrenceLocationResponse;
import com.strandls.externalOccurrences.pojo.response.SpeciesGroupAggregationResponse;

public interface GBIFObservationService {

	/**
	 * Query GBIF observations from parquet file based on a GeoJSON geometry with pagination
	 *
	 * @param geoJson GeoJSON FeatureCollection; the first feature's geometry is used
	 * @param offset The offset for pagination (default 0)
	 * @param limit The limit for pagination (default 10)
	 * @param speciesGroup Optional species group filter
	 * @param iucnCategory Optional IUCN Red List Category filter
	 * @return Paginated response containing GBIF observations
	 */
	GBIFObservationResponse getObservations(String geoJson, Integer offset, Integer limit, String speciesGroup, String iucnCategory);

	/**
	 * Query GBIF observations aggregated by species group for a GeoJSON geometry
	 *
	 * @param geoJson GeoJSON FeatureCollection; the first feature's geometry is used
	 * @return Response containing species group aggregations with total and unique counts
	 */
	SpeciesGroupAggregationResponse getSpeciesGroupAggregation(String geoJson);

	/**
	 * Query GBIF observations aggregated by IUCN Red List Category for a GeoJSON geometry
	 *
	 * @param geoJson GeoJSON FeatureCollection; the first feature's geometry is used
	 * @return Response containing IUCN category aggregations with total and unique counts
	 */
	IUCNAggregationResponse getIUCNAggregation(String geoJson);

	/**
	 * Query GBIF occurrences for a GeoJSON geometry, grouped by location, flagging
	 * whether each location falls inside the geometry (polygons only)
	 *
	 * @param geoJson GeoJSON FeatureCollection; the first feature's geometry is used
	 * @param limit Max number of locations to return; locations inside the geometry come first
	 * @param speciesGroup Optional species group filter
	 * @param iucnCategory Optional IUCN Red List Category filter
	 * @return Response containing occurrence locations and record totals
	 */
	OccurrenceLocationResponse getOccurrenceLocations(String geoJson, Integer limit, String speciesGroup, String iucnCategory);
}
