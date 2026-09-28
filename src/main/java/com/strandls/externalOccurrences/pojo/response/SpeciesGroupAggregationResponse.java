package com.strandls.externalOccurrences.pojo.response;

import java.util.List;

import com.strandls.externalOccurrences.pojo.SpeciesGroupAggregation;

public class SpeciesGroupAggregationResponse {
	private List<SpeciesGroupAggregation> aggregations;

	public SpeciesGroupAggregationResponse() {
	}

	public SpeciesGroupAggregationResponse(List<SpeciesGroupAggregation> aggregations) {
		this.aggregations = aggregations;
	}

	public List<SpeciesGroupAggregation> getAggregations() {
		return aggregations;
	}

	public void setAggregations(List<SpeciesGroupAggregation> aggregations) {
		this.aggregations = aggregations;
	}
}
