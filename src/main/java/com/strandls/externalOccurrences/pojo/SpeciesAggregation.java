package com.strandls.externalOccurrences.pojo;

public class SpeciesAggregation {
	private String scientificName;
	private Long count;
	private String iucnRedListCategory;
	private String speciesGroup;
	private String taxonKey;
	private String iucnLink;

	public SpeciesAggregation() {
	}

	public SpeciesAggregation(String scientificName, Long count, String iucnRedListCategory, String speciesGroup, String taxonKey, String iucnLink) {
		this.scientificName = scientificName;
		this.count = count;
		this.iucnRedListCategory = iucnRedListCategory;
		this.speciesGroup = speciesGroup;
		this.taxonKey = taxonKey;
		this.iucnLink = iucnLink;
	}

	public String getScientificName() {
		return scientificName;
	}

	public void setScientificName(String scientificName) {
		this.scientificName = scientificName;
	}

	public Long getCount() {
		return count;
	}

	public void setCount(Long count) {
		this.count = count;
	}

	public String getIucnRedListCategory() {
		return iucnRedListCategory;
	}

	public void setIucnRedListCategory(String iucnRedListCategory) {
		this.iucnRedListCategory = iucnRedListCategory;
	}

	public String getSpeciesGroup() {
		return speciesGroup;
	}

	public void setSpeciesGroup(String speciesGroup) {
		this.speciesGroup = speciesGroup;
	}

	public String getTaxonKey() {
		return taxonKey;
	}

	public void setTaxonKey(String taxonKey) {
		this.taxonKey = taxonKey;
	}

	public String getIucnLink() {
		return iucnLink;
	}

	public void setIucnLink(String iucnLink) {
		this.iucnLink = iucnLink;
	}
}
