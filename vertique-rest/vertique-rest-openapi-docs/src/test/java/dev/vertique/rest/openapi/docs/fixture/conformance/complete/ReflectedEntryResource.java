// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.complete;

import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.vertx.core.Future;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * The twin entry resource the reflective scanner describes: it has no generated companion.
 * Declaration for declaration, annotations included, it is {@link GeneratedEntryResource}, except for
 * its composite classes ({@link ReflectedPaging}, {@link ReflectedFilter}); see {@link
 * CompleteEntries} for the operations. Keep the two twins and the companion in step.
 */
@Path(CompleteEntries.RESOURCE_PATH)
@Tag(
        name = CompleteEntries.ENTRIES_TAG,
        description = "Entry operations",
        externalDocs = @ExternalDocumentation(url = "https://docs.example.test/entries"))
public class ReflectedEntryResource {

    /** Creates the resource. */
    public ReflectedEntryResource() {}

    /**
     * Reads one entry.
     *
     * @param entryId the entry identifier
     * @param view    the view, defaulting to {@value CompleteEntries#VIEW_DEFAULT}
     * @param traceId the caller's trace identifier
     * @param session the caller's session cookie
     * @param paging  the bean-param composite
     * @param filter  the request-params record
     * @return the entry
     */
    @GET
    @Path(CompleteEntries.ENTRY_PATH)
    @Operation(
            operationId = CompleteEntries.GET_ENTRY,
            summary = "Read an entry",
            description = "Returns one entry by its identifier",
            tags = {CompleteEntries.PUBLIC_TAG})
    @Tag(name = CompleteEntries.READ_TAG, description = "Reading operations")
    @SecurityRequirement(name = CompleteEntries.BEARER_AUTH, scopes = CompleteEntries.READ_SCOPE)
    public EntryView getEntry(
            @PathParam(CompleteEntries.ENTRY_ID)
                    @Pattern(regexp = CompleteEntries.ENTRY_ID_PATTERN)
                    @Parameter(description = CompleteEntries.ENTRY_ID_DESCRIPTION)
                    String entryId,
            @QueryParam(CompleteEntries.VIEW) @DefaultValue(CompleteEntries.VIEW_DEFAULT) String view,
            @HeaderParam(CompleteEntries.TRACE_HEADER)
                    @NotNull
                    @Parameter(description = CompleteEntries.TRACE_DESCRIPTION, example = CompleteEntries.TRACE_EXAMPLE)
                    String traceId,
            @CookieParam(CompleteEntries.SESSION_COOKIE) String session,
            @BeanParam ReflectedPaging paging,
            ReflectedFilter filter) {
        return new EntryView(entryId, view);
    }

    /**
     * Creates an entry.
     *
     * @param request the entry to create
     * @return the created entry
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = CompleteEntries.CREATE_ENTRY, summary = "Create an entry")
    @Tag(name = CompleteEntries.WRITE_TAG, description = "Writing operations")
    @ApiResponse(
            responseCode = "201",
            description = "The entry was created",
            headers =
                    @Header(
                            name = CompleteEntries.LOCATION_HEADER,
                            description = "Where the new entry is read",
                            schema = @Schema(implementation = String.class)),
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = EntryView.class),
                            examples =
                                    @ExampleObject(
                                            name = CompleteEntries.CREATED_EXAMPLE,
                                            summary = "A created entry",
                                            value = CompleteEntries.CREATED_EXAMPLE_VALUE)))
    @ApiResponse(responseCode = "400", description = "The entry was refused")
    @SecurityRequirement(name = CompleteEntries.BEARER_AUTH)
    @SecurityRequirement(name = CompleteEntries.API_KEY_AUTH)
    public EntryView createEntry(@RequestBody(description = "The entry to create") EntryRequest request) {
        return new EntryView("created", request.title);
    }

    /**
     * Archives one entry.
     *
     * @param entryId the entry identifier
     * @return the archive ticket, answered by its response producer
     */
    @DELETE
    @Path(CompleteEntries.ENTRY_PATH)
    @Operation(operationId = CompleteEntries.ARCHIVE_ENTRY, summary = "Archive an entry")
    @SecurityRequirement(name = CompleteEntries.API_KEY_AUTH)
    public ArchiveTicket archiveEntry(
            @PathParam(CompleteEntries.ENTRY_ID) @Parameter(description = CompleteEntries.ENTRY_ID_DESCRIPTION)
                    String entryId) {
        return new ArchiveTicket();
    }

    /**
     * Starts an export of every entry.
     *
     * @param format the export format, defaulting to {@value CompleteEntries#FORMAT_DEFAULT}
     * @return the export ticket, answered by its response producer
     */
    @POST
    @Path(CompleteEntries.EXPORTS_PATH)
    @Operation(
            operationId = CompleteEntries.EXPORT_ENTRIES,
            description = "Starts an export of every entry",
            deprecated = true)
    @SecurityRequirement(name = CompleteEntries.BEARER_AUTH, scopes = CompleteEntries.EXPORT_SCOPE)
    public Future<ExportTicket> exportEntries(
            @QueryParam(CompleteEntries.FORMAT) @DefaultValue(CompleteEntries.FORMAT_DEFAULT) String format) {
        return Future.succeededFuture(new ExportTicket());
    }

    /**
     * Answers that the service is live; hidden from the document, its route still answers.
     *
     * @return {@value CompleteEntries#LIVE_BODY}
     */
    @GET
    @Path(CompleteEntries.LIVE_PATH)
    @Produces(MediaType.TEXT_PLAIN)
    @Hidden
    @Operation(operationId = CompleteEntries.LIVE_STATUS)
    public String liveStatus() {
        return CompleteEntries.LIVE_BODY;
    }
}
