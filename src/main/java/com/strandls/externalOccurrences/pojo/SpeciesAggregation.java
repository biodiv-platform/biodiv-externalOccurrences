package com.strandls.externalOccurrences.pojo;

public class SpeciesAggregation {
	private String scientificName;
	private Long count;
	private String iucnRedListCategory;
	private String speciesGroup;
	private String taxonKey;
	private String iucnLink;
	private Long taxonId;
	private Long speciesId;

	public SpeciesAggregation() {
	}

	public SpeciesAggregation(String scientificName, Long count, String iucnRedListCategory, String speciesGroup, String taxonKey, String iucnLink, Long taxonId, Long speciesId) {
		this.scientificName = scientificName;
		this.count = count;
		this.iucnRedListCategory = iucnRedListCategory;
		this.speciesGroup = speciesGroup;
		this.taxonKey = taxonKey;
		this.iucnLink = iucnLink;
		this.taxonId = taxonId;
		this.speciesId = speciesId;
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

	/** @return the IBP taxon matched to the GBIF name, or null */
	public Long getTaxonId() {
		return taxonId;
	}

	public void setTaxonId(Long taxonId) {
		this.taxonId = taxonId;
	}

	/** @return the IBP species page of {@link #getTaxonId()}, or null */
	public Long getSpeciesId() {
		return speciesId;
	}

	public void setSpeciesId(Long speciesId) {
		this.speciesId = speciesId;
	}
}
