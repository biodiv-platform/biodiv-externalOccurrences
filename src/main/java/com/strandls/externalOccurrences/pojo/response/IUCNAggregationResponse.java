package com.strandls.externalOccurrences.pojo.response;

import java.util.List;

import com.strandls.externalOccurrences.pojo.IUCNAggregation;

public class IUCNAggregationResponse {
	private List<IUCNAggregation> aggregations;

	public IUCNAggregationResponse() {
	}

	public IUCNAggregationResponse(List<IUCNAggregation> aggregations) {
		this.aggregations = aggregations;
	}

	public List<IUCNAggregation> getAggregations() {
		return aggregations;
	}

	public void setAggregations(List<IUCNAggregation> aggregations) {
		this.aggregations = aggregations;
	}
}
