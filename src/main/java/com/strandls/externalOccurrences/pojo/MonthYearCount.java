package com.strandls.externalOccurrences.pojo;

public class MonthYearCount {
	private Integer year;
	/** 1 to 12 */
	private Integer month;
	private Long count;

	public MonthYearCount() {
	}

	public MonthYearCount(Integer year, Integer month, Long count) {
		this.year = year;
		this.month = month;
		this.count = count;
	}

	public Integer getYear() {
		return year;
	}

	public void setYear(Integer year) {
		this.year = year;
	}

	public Integer getMonth() {
		return month;
	}

	public void setMonth(Integer month) {
		this.month = month;
	}

	public Long getCount() {
		return count;
	}

	public void setCount(Long count) {
		this.count = count;
	}
}
