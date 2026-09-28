package com.strandls.externalOccurrences.pojo.response;

import java.util.List;

import com.strandls.externalOccurrences.pojo.SpeciesAggregation;

public class GBIFObservationResponse {
	private Long totalCount; // Total count of unique scientificNames
	private Long totalOccurrenceRecords; // Total count of GBIF occurrence records
	private Integer offset;
	private Integer limit;
	private List<SpeciesAggregation> aggregations;

	public GBIFObservationResponse() {
	}

	public GBIFObservationResponse(Long totalCount, Integer offset, Integer limit,
			List<SpeciesAggregation> aggregations) {
		this(totalCount, 0L, offset, limit, aggregations);
	}

	public GBIFObservationResponse(Long totalCount, Long totalOccurrenceRecords, Integer offset, Integer limit,
			List<SpeciesAggregation> aggregations) {
		this.totalCount = totalCount;
		this.totalOccurrenceRecords = totalOccurrenceRecords;
		this.offset = offset;
		this.limit = limit;
		this.aggregations = aggregations;
	}

	public Long getTotalCount() {
		return totalCount;
	}

	public void setTotalCount(Long totalCount) {
		this.totalCount = totalCount;
	}

	public Long getTotalOccurrenceRecords() {
		return totalOccurrenceRecords;
	}

	public void setTotalOccurrenceRecords(Long totalOccurrenceRecords) {
		this.totalOccurrenceRecords = totalOccurrenceRecords;
	}

	public Integer getOffset() {
		return offset;
	}

	public void setOffset(Integer offset) {
		this.offset = offset;
	}

	public Integer getLimit() {
		return limit;
	}

	public void setLimit(Integer limit) {
		this.limit = limit;
	}

	public List<SpeciesAggregation> getAggregations() {
		return aggregations;
	}

	public void setAggregations(List<SpeciesAggregation> aggregations) {
		this.aggregations = aggregations;
	}
}
