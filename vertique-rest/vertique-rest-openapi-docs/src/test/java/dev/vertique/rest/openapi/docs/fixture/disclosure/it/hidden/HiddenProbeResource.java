// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.it.hidden;

import dev.vertique.rest.core.request.RequestParams;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * The resource of {@link HiddenProbeApi} and {@link ProtectedHiddenProbeApi}: one read operation
 * whose inputs are hidden in every way a binding can be hidden, beside one visible query parameter,
 * and one write operation whose body is hidden.
 *
 * <p>Every hidden name carries the {@code Zx} suffix; the visible one, {@value #PAGE}, does not. The
 * read operation echoes the bound hidden values, so a request proves each one still binds.
 */
@Path(HiddenProbeResource.ROUTE)
public class HiddenProbeResource {

    /** The route of both operations, relative to the mount. */
    public static final String ROUTE = "/probe";

    /** The operation id of {@link #listProbeZx}. */
    public static final String LIST_OPERATION_ID = "listProbeZx";

    /** The operation id of {@link #createProbeZx}. */
    public static final String CREATE_OPERATION_ID = "createProbeZx";

    /** The hidden query parameter declared directly on the method, with an authored pattern. */
    public static final String DEBUG = "debugZx";

    /** The authored pattern of {@value #DEBUG}: digits only. */
    public static final String DEBUG_PATTERN = "^[0-9]+$";

    /** The one visible query parameter. */
    public static final String PAGE = "page";

    /** The hidden header bound by a field marked {@code @Hidden}. */
    public static final String TRACE = "X-Trace-Zx";

    /** The hidden query parameter bound by a record component marked {@code @Schema(hidden = true)}. */
    public static final String INTERNAL = "internalZx";

    /** An unmarked query field of a composite parameter marked {@code @Schema(hidden = true)}. */
    public static final String CURSOR = "cursorZx";

    /** An unmarked header field of a composite parameter marked {@code @Schema(hidden = true)}. */
    public static final String PAGE_HEADER = "X-Page-Zx";

    /** The unmarked query component of a record parameter marked {@code @Parameter(hidden = true)}. */
    public static final String WINDOW = "windowZx";

    /** The unmarked query field of a composite whose type is marked {@code @Hidden}. */
    public static final String AUDIT = "auditZx";

    /** The separator between the echoed values. */
    public static final String SEPARATOR = ",";

    /** Creates the resource. */
    public HiddenProbeResource() {}

    /**
     * Handles {@code GET /probe} and echoes the bound hidden values.
     *
     * @param debugZx  query {@value #DEBUG}, hidden on the parameter, digits only
     * @param page     query {@value #PAGE}, visible
     * @param trace    a composite whose header field is hidden on the field
     * @param internal a record composite whose query component is hidden on the component
     * @param cursor   a composite hidden on the parameter, whose fields carry no marker
     * @param window   a record composite hidden on the parameter, whose component carries no marker
     * @param audit    a composite whose type is hidden, whose field carries no marker
     * @return the hidden values in parameter order, joined by {@value #SEPARATOR}: {@value #DEBUG},
     *     {@value #TRACE}, {@value #INTERNAL}, {@value #CURSOR}, {@value #PAGE_HEADER}, {@value
     *     #WINDOW}, and {@value #AUDIT}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String listProbeZx(
            @QueryParam(DEBUG) @Parameter(hidden = true) @Pattern(regexp = DEBUG_PATTERN) String debugZx,
            @QueryParam(PAGE) String page,
            @BeanParam ProbeTraceZx trace,
            ProbeInternalZx internal,
            @BeanParam @Schema(hidden = true) ProbeCursorZx cursor,
            @Parameter(hidden = true) ProbeWindowZx window,
            @BeanParam ProbeAuditZx audit) {
        return String.join(
                SEPARATOR,
                String.valueOf(debugZx),
                String.valueOf(trace.traceZx),
                String.valueOf(internal.internalZx()),
                String.valueOf(cursor.cursorZx),
                String.valueOf(cursor.pageZx),
                String.valueOf(window.windowZx()),
                String.valueOf(audit.auditZx));
    }

    /**
     * Handles {@code POST /probe}; answers with an empty response.
     *
     * @param note the body, hidden on the parameter
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public void createProbeZx(@Parameter(hidden = true) ProbeNoteZx note) {
        // Nothing to do: the request proves the hidden body still binds and is validated.
    }

    /** A composite whose one header field is marked {@code @Hidden}. */
    public static class ProbeTraceZx {

        /** Header {@value HiddenProbeResource#TRACE}, hidden on the field. */
        @HeaderParam(TRACE)
        @Hidden
        public String traceZx;

        /** Creates the composite. */
        public ProbeTraceZx() {}
    }

    /**
     * A record composite whose one query component is marked {@code @Schema(hidden = true)}.
     *
     * @param internalZx query {@value HiddenProbeResource#INTERNAL}, hidden on the component
     */
    @RequestParams
    public record ProbeInternalZx(
            @QueryParam(INTERNAL) @Schema(hidden = true) String internalZx) {}

    /** A composite whose fields carry no marker; the parameter binding it is hidden. */
    public static class ProbeCursorZx {

        /** Query {@value HiddenProbeResource#CURSOR}, unmarked. */
        @QueryParam(CURSOR)
        public String cursorZx;

        /** Header {@value HiddenProbeResource#PAGE_HEADER}, unmarked. */
        @HeaderParam(PAGE_HEADER)
        public String pageZx;

        /** Creates the composite. */
        public ProbeCursorZx() {}
    }

    /**
     * A record composite whose component carries no marker; the parameter binding it is hidden.
     *
     * @param windowZx query {@value HiddenProbeResource#WINDOW}, unmarked
     */
    @RequestParams
    public record ProbeWindowZx(@QueryParam(WINDOW) String windowZx) {}

    /** A composite whose type is marked {@code @Hidden} and whose field carries no marker. */
    @Hidden
    public static class ProbeAuditZx {

        /** Query {@value HiddenProbeResource#AUDIT}, unmarked. */
        @QueryParam(AUDIT)
        public String auditZx;

        /** Creates the composite. */
        public ProbeAuditZx() {}
    }

    /** The hidden body: one member with an authored size limit. */
    public static class ProbeNoteZx {

        /** The note text, at most ten characters. */
        @Size(max = 10)
        public String text;

        /** Creates the body. */
        public ProbeNoteZx() {}
    }
}
