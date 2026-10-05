package com.strandls.externalOccurrences.pojo.response;

import java.util.List;
import java.util.Map;

import com.strandls.externalOccurrences.pojo.OccurrenceLocation;

public class OccurrenceLocationResponse {
	/** Bounding box that occurrences were searched in: [minLon, minLat, maxLon, maxLat] */
	private List<Double> bbox;
	/** The search area the records were counted in (the input grown by bufferKm), as a GeoJSON geometry */
	private Map<String, Object> searchArea;
	/** Buffer in km the search area was built with */
	private Double bufferKm;
	/** Records in the bounding box */
	private Long totalRecords;
	/** Records inside the search area */
	private Long insideRecords;
	/** Records inside the input's own polygons, without the buffer (0 when the input has no polygon) */
	private Long polygonRecords;
	private Long totalLocations;
	private Boolean truncated;
	private List<OccurrenceLocation> locations;

	public OccurrenceLocationResponse() {
	}

	public OccurrenceLocationResponse(List<Double> bbox, Long totalRecords, Long insideRecords, Long totalLocations,
			Boolean truncated, List<OccurrenceLocation> locations) {
		this.bbox = bbox;
		this.totalRecords = totalRecords;
		this.insideRecords = insideRecords;
		this.totalLocations = totalLocations;
		this.truncated = truncated;
		this.locations = locations;
	}

	public List<Double> getBbox() {
		return bbox;
	}

	public void setBbox(List<Double> bbox) {
		this.bbox = bbox;
	}

	public Map<String, Object> getSearchArea() {
		return searchArea;
	}

	public void setSearchArea(Map<String, Object> searchArea) {
		this.searchArea = searchArea;
	}

	public Double getBufferKm() {
		return bufferKm;
	}

	public void setBufferKm(Double bufferKm) {
		this.bufferKm = bufferKm;
	}

	public Long getTotalRecords() {
		return totalRecords;
	}

	public void setTotalRecords(Long totalRecords) {
		this.totalRecords = totalRecords;
	}

	public Long getInsideRecords() {
		return insideRecords;
	}

	public void setInsideRecords(Long insideRecords) {
		this.insideRecords = insideRecords;
	}

	public Long getPolygonRecords() {
		return polygonRecords;
	}

	public void setPolygonRecords(Long polygonRecords) {
		this.polygonRecords = polygonRecords;
	}

	public Long getTotalLocations() {
		return totalLocations;
	}

	public void setTotalLocations(Long totalLocations) {
		this.totalLocations = totalLocations;
	}

	public Boolean getTruncated() {
		return truncated;
	}

	public void setTruncated(Boolean truncated) {
		this.truncated = truncated;
	}

	public List<OccurrenceLocation> getLocations() {
		return locations;
	}

	public void setLocations(List<OccurrenceLocation> locations) {
		this.locations = locations;
	}
}
