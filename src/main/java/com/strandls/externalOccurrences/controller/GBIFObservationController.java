package com.strandls.externalOccurrences.controller;

import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import com.strandls.externalOccurrences.ApiConstants;
import com.strandls.externalOccurrences.exception.ExternalOccurrencesException;
import com.strandls.externalOccurrences.pojo.response.GBIFObservationResponse;
import com.strandls.externalOccurrences.pojo.response.IUCNAggregationResponse;
import com.strandls.externalOccurrences.pojo.response.OccurrenceLocationResponse;
import com.strandls.externalOccurrences.pojo.response.SpeciesGroupAggregationResponse;
import com.strandls.externalOccurrences.service.GBIFObservationService;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;

/**
 * GBIF occurrence queries for a geometry. The request body is any GeoJSON
 * (FeatureCollection, Feature, GeometryCollection or a single geometry). Every
 * geometry part is grown by bufferKm and the search area is their union.
 */
@Api("GBIF Observations")
@Path(ApiConstants.V1 + ApiConstants.GBIF)
public class GBIFObservationController {

	private static final String BUFFER_KM_DESCRIPTION = "Buffer in km around every geometry part: a circle around points, "
			+ "a corridor around lines, the same shape grown outward for polygons. 0 matches polygons exactly. "
			+ "Defaults to gbif_buffer_km, max gbif_max_buffer_km";

	@Inject
	private GBIFObservationService gbifObservationService;

	@POST
	@Path(ApiConstants.OBSERVATIONS)
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@ApiOperation(value = "Get GBIF observations for a geometry", notes = "Returns paginated GBIF observations based on the geometry", response = GBIFObservationResponse.class)
	@ApiResponses(value = { @ApiResponse(code = 404, message = "Could not get the data", response = String.class) })
	public Response getGBIFObservations(@QueryParam("offset") Integer offset, @QueryParam("limit") Integer limit,
			@QueryParam("speciesGroup") String speciesGroup, @QueryParam("iucnCategory") String iucnCategory,
			@ApiParam(value = BUFFER_KM_DESCRIPTION) @QueryParam("bufferKm") Double bufferKm, @ApiParam(name = "geoJson") String geoJson)
			throws ExternalOccurrencesException {
		try {
			GBIFObservationResponse response = gbifObservationService.getObservations(geoJson, offset, limit,
					speciesGroup, iucnCategory, bufferKm);
			return Response.status(Status.OK).entity(response).build();
		} catch (Exception e) {
			throw new ExternalOccurrencesException(e);
		}
	}

	@POST
	@Path(ApiConstants.SPECIES_GROUP_AGGREGATION)
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@ApiOperation(value = "Get species group aggregation for a geometry", notes = "Returns aggregated counts by species group based on the geometry", response = SpeciesGroupAggregationResponse.class)
	@ApiResponses(value = { @ApiResponse(code = 404, message = "Could not get the data", response = String.class) })
	public Response getSpeciesGroupAggregation(@ApiParam(value = BUFFER_KM_DESCRIPTION) @QueryParam("bufferKm") Double bufferKm,
			@ApiParam(name = "geoJson") String geoJson) throws ExternalOccurrencesException {
		try {
			SpeciesGroupAggregationResponse response = gbifObservationService.getSpeciesGroupAggregation(geoJson,
					bufferKm);
			return Response.status(Status.OK).entity(response).build();
		} catch (Exception e) {
			throw new ExternalOccurrencesException(e);
		}
	}

	@POST
	@Path(ApiConstants.IUCN_AGGREGATION)
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@ApiOperation(value = "Get IUCN Red List Category aggregation for a geometry", notes = "Returns aggregated counts by IUCN Red List Category based on the geometry", response = IUCNAggregationResponse.class)
	@ApiResponses(value = { @ApiResponse(code = 404, message = "Could not get the data", response = String.class) })
	public Response getIUCNAggregation(@ApiParam(value = BUFFER_KM_DESCRIPTION) @QueryParam("bufferKm") Double bufferKm,
			@ApiParam(name = "geoJson") String geoJson) throws ExternalOccurrencesException {
		try {
			IUCNAggregationResponse response = gbifObservationService.getIUCNAggregation(geoJson, bufferKm);
			return Response.status(Status.OK).entity(response).build();
		} catch (Exception e) {
			throw new ExternalOccurrencesException(e);
		}
	}

	@POST
	@Path(ApiConstants.OCCURRENCE_LOCATIONS)
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@ApiOperation(value = "Get GBIF occurrence locations for a geometry", notes = "Returns GBIF occurrences in the bounding box of the search area grouped by location, flagging those inside the search area", response = OccurrenceLocationResponse.class)
	@ApiResponses(value = { @ApiResponse(code = 404, message = "Could not get the data", response = String.class) })
	public Response getOccurrenceLocations(@QueryParam("limit") Integer limit,
			@QueryParam("speciesGroup") String speciesGroup, @QueryParam("iucnCategory") String iucnCategory,
			@ApiParam(value = BUFFER_KM_DESCRIPTION) @QueryParam("bufferKm") Double bufferKm, @ApiParam(name = "geoJson") String geoJson)
			throws ExternalOccurrencesException {
		try {
			OccurrenceLocationResponse response = gbifObservationService.getOccurrenceLocations(geoJson, limit,
					speciesGroup, iucnCategory, bufferKm);
			return Response.status(Status.OK).entity(response).build();
		} catch (Exception e) {
			throw new ExternalOccurrencesException(e);
		}
	}
}
