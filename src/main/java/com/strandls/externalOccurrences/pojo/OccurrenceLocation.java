package com.strandls.externalOccurrences.pojo;

/**
 * GBIF occurrences grouped by (rounded) coordinate. Many records share the same
 * location (e.g. eBird hotspots), so grouping keeps the payload small.
 */
public class OccurrenceLocation {
	private Double latitude;
	private Double longitude;
	private Long recordCount;
	private Long speciesCount;
	/** Any record here is inside the search area (the input grown by the buffer) */
	private Boolean insideGeometry;
	/** Any record here is inside the input's own polygons, without the buffer */
	private Boolean insidePolygon;

	public OccurrenceLocation() {
	}

	public OccurrenceLocation(Double latitude, Double longitude, Long recordCount, Long speciesCount,
			Boolean insideGeometry, Boolean insidePolygon) {
		this.latitude = latitude;
		this.longitude = longitude;
		this.recordCount = recordCount;
		this.speciesCount = speciesCount;
		this.insideGeometry = insideGeometry;
		this.insidePolygon = insidePolygon;
	}

	public Double getLatitude() {
		return latitude;
	}

	public void setLatitude(Double latitude) {
		this.latitude = latitude;
	}

	public Double getLongitude() {
		return longitude;
	}

	public void setLongitude(Double longitude) {
		this.longitude = longitude;
	}

	public Long getRecordCount() {
		return recordCount;
	}

	public void setRecordCount(Long recordCount) {
		this.recordCount = recordCount;
	}

	public Long getSpeciesCount() {
		return speciesCount;
	}

	public void setSpeciesCount(Long speciesCount) {
		this.speciesCount = speciesCount;
	}

	public Boolean getInsideGeometry() {
		return insideGeometry;
	}

	public void setInsideGeometry(Boolean insideGeometry) {
		this.insideGeometry = insideGeometry;
	}

	public Boolean getInsidePolygon() {
		return insidePolygon;
	}

	public void setInsidePolygon(Boolean insidePolygon) {
		this.insidePolygon = insidePolygon;
	}
}
