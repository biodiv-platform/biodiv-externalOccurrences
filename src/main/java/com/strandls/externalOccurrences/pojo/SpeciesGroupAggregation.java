package com.strandls.externalOccurrences.pojo;

public class SpeciesGroupAggregation {
	private String speciesGroup;
	private Long totalCount;
	private Long uniqueSpeciesCount;

	public SpeciesGroupAggregation() {
	}

	public SpeciesGroupAggregation(String speciesGroup, Long totalCount, Long uniqueSpeciesCount) {
		this.speciesGroup = speciesGroup;
		this.totalCount = totalCount;
		this.uniqueSpeciesCount = uniqueSpeciesCount;
	}

	public String getSpeciesGroup() {
		return speciesGroup;
	}

	public void setSpeciesGroup(String speciesGroup) {
		this.speciesGroup = speciesGroup;
	}

	public Long getTotalCount() {
		return totalCount;
	}

	public void setTotalCount(Long totalCount) {
		this.totalCount = totalCount;
	}

	public Long getUniqueSpeciesCount() {
		return uniqueSpeciesCount;
	}

	public void setUniqueSpeciesCount(Long uniqueSpeciesCount) {
		this.uniqueSpeciesCount = uniqueSpeciesCount;
	}
}
