package com.strandls.externalOccurrences.pojo.response;

import java.util.List;

import com.strandls.externalOccurrences.pojo.MonthYearCount;

public class MonthYearResponse {
	/** earliest and latest year with records for the taxon, null when it has none */
	private Integer minYear;
	private Integer maxYear;
	/** record count per year and month inside the requested years */
	private List<MonthYearCount> counts;

	public MonthYearResponse() {
	}

	public MonthYearResponse(Integer minYear, Integer maxYear, List<MonthYearCount> counts) {
		this.minYear = minYear;
		this.maxYear = maxYear;
		this.counts = counts;
	}

	public Integer getMinYear() {
		return minYear;
	}

	public void setMinYear(Integer minYear) {
		this.minYear = minYear;
	}

	public Integer getMaxYear() {
		return maxYear;
	}

	public void setMaxYear(Integer maxYear) {
		this.maxYear = maxYear;
	}

	public List<MonthYearCount> getCounts() {
		return counts;
	}

	public void setCounts(List<MonthYearCount> counts) {
		this.counts = counts;
	}
}
