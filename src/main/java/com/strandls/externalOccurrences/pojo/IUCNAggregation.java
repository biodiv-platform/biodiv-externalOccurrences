package com.strandls.externalOccurrences.pojo;

public class IUCNAggregation {
	private String iucnRedListCategory;
	private Long totalCount;
	private Long uniqueSpeciesCount;

	public IUCNAggregation() {
	}

	public IUCNAggregation(String iucnRedListCategory, Long totalCount, Long uniqueSpeciesCount) {
		this.iucnRedListCategory = iucnRedListCategory;
		this.totalCount = totalCount;
		this.uniqueSpeciesCount = uniqueSpeciesCount;
	}

	public String getIucnRedListCategory() {
		return iucnRedListCategory;
	}

	public void setIucnRedListCategory(String iucnRedListCategory) {
		this.iucnRedListCategory = iucnRedListCategory;
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
