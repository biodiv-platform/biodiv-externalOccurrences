package com.strandls.externalOccurrences.controller;

import com.strandls.externalOccurrences.ApiConstants;
import com.strandls.externalOccurrences.exception.ExternalOccurrencesException;
import com.strandls.externalOccurrences.pojo.response.GBIFObservationResponse;
import com.strandls.externalOccurrences.pojo.response.IUCNAggregationResponse;
import com.strandls.externalOccurrences.pojo.response.OccurrenceLocationResponse;
import com.strandls.externalOccurrences.pojo.response.SpeciesGroupAggregationResponse;
import com.strandls.externalOccurrences.service.GBIFObservationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

/**
 * GBIF occurrence queries for a geometry. The request body is any GeoJSON
 * (FeatureCollection, Feature, GeometryCollection or a single geometry). Points
 * are grown by pointBufferKm, lines and polygons by bufferKm, and the search
 * area is their union.
 */
@Tag(name = "GBIF Observations")
@Path(ApiConstants.V1 + ApiConstants.GBIF)
public class GBIFObservationController {

	private static final String POINT_BUFFER_KM_DESCRIPTION = "Buffer in km around points (a circle around each). "
			+ "0 matches only records at the exact coordinates. Defaults to gbif_point_buffer_km, max gbif_max_buffer_km";
	private static final String BUFFER_KM_DESCRIPTION = "Buffer in km around lines and polygons: a corridor around lines, "
			+ "the same shape grown outward for polygons. 0 matches polygons exactly. "
			+ "Defaults to gbif_buffer_km, max gbif_max_buffer_km";
	private static final String GEOJSON_DESCRIPTION = "Any GeoJSON: FeatureCollection, Feature, GeometryCollection or a single geometry";

	@Inject
	private GBIFObservationService gbifObservationService;

	@POST
	@Path(ApiConstants.OBSERVATIONS)
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Get GBIF observations for a geometry", description = "Returns paginated GBIF observations based on the geometry", responses = {
			@ApiResponse(responseCode = "200", description = "Success", content = @Content(schema = @Schema(implementation = GBIFObservationResponse.class))),
			@ApiResponse(responseCode = "400", description = "Invalid GeoJSON or parameter out of range", content = @Content(schema = @Schema(implementation = String.class))),
			@ApiResponse(responseCode = "500", description = "Could not get the data", content = @Content(schema = @Schema(implementation = String.class))) })
	public Response getGBIFObservations(@QueryParam("offset") Integer offset, @QueryParam("limit") Integer limit,
			@QueryParam("speciesGroup") String speciesGroup, @QueryParam("iucnCategory") String iucnCategory,
			@Parameter(description = POINT_BUFFER_KM_DESCRIPTION) @QueryParam("pointBufferKm") Double pointBufferKm,
			@Parameter(description = BUFFER_KM_DESCRIPTION) @QueryParam("bufferKm") Double bufferKm, @Parameter(description = GEOJSON_DESCRIPTION) String geoJson)
			throws ExternalOccurrencesException {
		try {
			GBIFObservationResponse response = gbifObservationService.getObservations(geoJson, offset, limit,
					speciesGroup, iucnCategory, pointBufferKm, bufferKm);
			return Response.status(Status.OK).entity(response).build();
		} catch (Exception e) {
			throw new ExternalOccurrencesException(e);
		}
	}

	@POST
	@Path(ApiConstants.SPECIES_GROUP_AGGREGATION)
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Get species group aggregation for a geometry", description = "Returns aggregated counts by species group based on the geometry", responses = {
			@ApiResponse(responseCode = "200", description = "Success", content = @Content(schema = @Schema(implementation = SpeciesGroupAggregationResponse.class))),
			@ApiResponse(responseCode = "400", description = "Invalid GeoJSON or parameter out of range", content = @Content(schema = @Schema(implementation = String.class))),
			@ApiResponse(responseCode = "500", description = "Could not get the data", content = @Content(schema = @Schema(implementation = String.class))) })
	public Response getSpeciesGroupAggregation(@Parameter(description = POINT_BUFFER_KM_DESCRIPTION) @QueryParam("pointBufferKm") Double pointBufferKm,
			@Parameter(description = BUFFER_KM_DESCRIPTION) @QueryParam("bufferKm") Double bufferKm,
			@Parameter(description = GEOJSON_DESCRIPTION) String geoJson) throws ExternalOccurrencesException {
		try {
			SpeciesGroupAggregationResponse response = gbifObservationService.getSpeciesGroupAggregation(geoJson,
					pointBufferKm, bufferKm);
			return Response.status(Status.OK).entity(response).build();
		} catch (Exception e) {
			throw new ExternalOccurrencesException(e);
		}
	}

	@POST
	@Path(ApiConstants.IUCN_AGGREGATION)
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Get IUCN Red List Category aggregation for a geometry", description = "Returns aggregated counts by IUCN Red List Category based on the geometry", responses = {
			@ApiResponse(responseCode = "200", description = "Success", content = @Content(schema = @Schema(implementation = IUCNAggregationResponse.class))),
			@ApiResponse(responseCode = "400", description = "Invalid GeoJSON or parameter out of range", content = @Content(schema = @Schema(implementation = String.class))),
			@ApiResponse(responseCode = "500", description = "Could not get the data", content = @Content(schema = @Schema(implementation = String.class))) })
	public Response getIUCNAggregation(@Parameter(description = POINT_BUFFER_KM_DESCRIPTION) @QueryParam("pointBufferKm") Double pointBufferKm,
			@Parameter(description = BUFFER_KM_DESCRIPTION) @QueryParam("bufferKm") Double bufferKm,
			@Parameter(description = GEOJSON_DESCRIPTION) String geoJson) throws ExternalOccurrencesException {
		try {
			IUCNAggregationResponse response = gbifObservationService.getIUCNAggregation(geoJson, pointBufferKm, bufferKm);
			return Response.status(Status.OK).entity(response).build();
		} catch (Exception e) {
			throw new ExternalOccurrencesException(e);
		}
	}

	@POST
	@Path(ApiConstants.OCCURRENCE_LOCATIONS)
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Get GBIF occurrence locations for a geometry", description = "Returns GBIF occurrences inside the search area grouped by location, flagging those inside the input's own polygons", responses = {
			@ApiResponse(responseCode = "200", description = "Success", content = @Content(schema = @Schema(implementation = OccurrenceLocationResponse.class))),
			@ApiResponse(responseCode = "400", description = "Invalid GeoJSON or parameter out of range", content = @Content(schema = @Schema(implementation = String.class))),
			@ApiResponse(responseCode = "500", description = "Could not get the data", content = @Content(schema = @Schema(implementation = String.class))) })
	public Response getOccurrenceLocations(@QueryParam("limit") Integer limit,
			@QueryParam("speciesGroup") String speciesGroup, @QueryParam("iucnCategory") String iucnCategory,
			@Parameter(description = POINT_BUFFER_KM_DESCRIPTION) @QueryParam("pointBufferKm") Double pointBufferKm,
			@Parameter(description = BUFFER_KM_DESCRIPTION) @QueryParam("bufferKm") Double bufferKm, @Parameter(description = GEOJSON_DESCRIPTION) String geoJson)
			throws ExternalOccurrencesException {
		try {
			OccurrenceLocationResponse response = gbifObservationService.getOccurrenceLocations(geoJson, limit,
					speciesGroup, iucnCategory, pointBufferKm, bufferKm);
			return Response.status(Status.OK).entity(response).build();
		} catch (Exception e) {
			throw new ExternalOccurrencesException(e);
		}
	}
}
