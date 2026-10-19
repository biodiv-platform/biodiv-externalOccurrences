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
	 * @param pointBufferKm Buffer around points in km; null uses gbif_point_buffer_km
	 * @param bufferKm Buffer around lines and polygons in km; null uses gbif_buffer_km
	 * @return Paginated response containing GBIF observations
	 */
	GBIFObservationResponse getObservations(String geoJson, Integer offset, Integer limit, String speciesGroup,
			String iucnCategory, Double pointBufferKm, Double bufferKm);

	/**
	 * Query GBIF observations aggregated by species group for a GeoJSON geometry
	 *
	 * @param geoJson Any GeoJSON (FeatureCollection, Feature, GeometryCollection or geometry)
	 * @param pointBufferKm Buffer around points in km; null uses gbif_point_buffer_km
	 * @param bufferKm Buffer around lines and polygons in km; null uses gbif_buffer_km
	 * @return Response containing species group aggregations with total and unique counts
	 */
	SpeciesGroupAggregationResponse getSpeciesGroupAggregation(String geoJson, Double pointBufferKm, Double bufferKm);

	/**
	 * Query GBIF observations aggregated by IUCN Red List Category for a GeoJSON geometry
	 *
	 * @param geoJson Any GeoJSON (FeatureCollection, Feature, GeometryCollection or geometry)
	 * @param pointBufferKm Buffer around points in km; null uses gbif_point_buffer_km
	 * @param bufferKm Buffer around lines and polygons in km; null uses gbif_buffer_km
	 * @return Response containing IUCN category aggregations with total and unique counts
	 */
	IUCNAggregationResponse getIUCNAggregation(String geoJson, Double pointBufferKm, Double bufferKm);

	/**
	 * Query GBIF occurrences inside the search area, grouped by location,
	 * flagging whether each location falls inside the input's own polygons
	 *
	 * @param geoJson Any GeoJSON (FeatureCollection, Feature, GeometryCollection or geometry)
	 * @param limit Max number of locations to return; locations inside the polygons come first
	 * @param speciesGroup Optional species group filter
	 * @param iucnCategory Optional IUCN Red List Category filter
	 * @param pointBufferKm Buffer around points in km; null uses gbif_point_buffer_km
	 * @param bufferKm Buffer around lines and polygons in km; null uses gbif_buffer_km
	 * @return Response containing occurrence locations and record totals
	 */
	OccurrenceLocationResponse getOccurrenceLocations(String geoJson, Integer limit, String speciesGroup,
			String iucnCategory, Double pointBufferKm, Double bufferKm);
}
