package com.strandls.externalOccurrences.pojo.response;

import java.util.List;

import com.strandls.externalOccurrences.pojo.OccurrenceLocation;

public class OccurrenceLocationResponse {
	/** Bounding box that occurrences were searched in: [minLon, minLat, maxLon, maxLat] */
	private List<Double> bbox;
	private Long totalRecords;
	private Long insideRecords;
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
