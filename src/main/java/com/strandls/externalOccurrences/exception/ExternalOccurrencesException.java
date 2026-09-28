package com.strandls.externalOccurrences.exception;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

public class ExternalOccurrencesException extends Exception {

	private static final long serialVersionUID = 1L;

	public ExternalOccurrencesException(Exception e) {
		if (e instanceof IllegalArgumentException) {
			throw new WebApplicationException(
					Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build());
		} else {
			throw new WebApplicationException(
					Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build());
		}
	}
}
