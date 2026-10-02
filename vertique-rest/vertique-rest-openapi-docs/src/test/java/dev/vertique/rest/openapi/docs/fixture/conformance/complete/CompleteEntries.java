// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.complete;

/**
 * The facts the two twin entry resources share: {@link ReflectedEntryResource}, which the reflective
 * scanner describes, and {@link GeneratedEntryResource}, which its hand-written generated-shape
 * companion describes. Both declare the same operations, with the same operation ids, paths,
 * bindings, and annotations, in the same order:
 *
 * <ul>
 *   <li>{@code GET /entries/{entryId}} ({@value #GET_ENTRY}): path, query, header, and cookie
 *       parameters, a {@code @BeanParam} bean and a {@code @RequestParams} record, an inferred JSON
 *       response, and a scoped requirement for {@value #BEARER_AUTH};
 *   <li>{@code POST /entries} ({@value #CREATE_ENTRY}): a JSON request body whose published schema
 *       is redacted, declared {@code 201} and {@code 400} responses with a header and a named
 *       example, and two scopeless alternatives, {@value #BEARER_AUTH} then {@value #API_KEY_AUTH};
 *   <li>{@code DELETE /entries/{entryId}} ({@value #ARCHIVE_ENTRY}): a return type bound to a
 *       response producer, and a scopeless requirement for {@value #API_KEY_AUTH};
 *   <li>{@code POST /entries/exports} ({@value #EXPORT_ENTRIES}): a {@code Future} of another type
 *       bound to a response producer, and a scoped requirement for {@value #BEARER_AUTH};
 *   <li>{@code GET /entries/status/live} ({@value #LIVE_STATUS}): hidden from the document, open to
 *       every caller, answering {@code 200} with the plain text {@value #LIVE_BODY}.
 * </ul>
 */
public final class CompleteEntries {

    /** The resource path both twins declare. */
    public static final String RESOURCE_PATH = "/entries";

    /** The method path of the operations on one entry. */
    public static final String ENTRY_PATH = "/{entryId}";

    /** The method path of the export operation. */
    public static final String EXPORTS_PATH = "/exports";

    /** The method path of the hidden operation; no templated route of the resource reaches it. */
    public static final String LIVE_PATH = "/status/live";

    /** The route template of the operations on one entry, relative to the mount. */
    public static final String ENTRY_ROUTE = RESOURCE_PATH + ENTRY_PATH;

    /** The route template of the export operation, relative to the mount. */
    public static final String EXPORTS_ROUTE = RESOURCE_PATH + EXPORTS_PATH;

    /** The route template of the hidden operation, relative to the mount. */
    public static final String LIVE_ROUTE = RESOURCE_PATH + LIVE_PATH;

    /** The operation id of the entry read. */
    public static final String GET_ENTRY = "getEntry";

    /** The operation id of the entry creation. */
    public static final String CREATE_ENTRY = "createEntry";

    /** The operation id of the entry archive. */
    public static final String ARCHIVE_ENTRY = "archiveEntry";

    /** The operation id of the export. */
    public static final String EXPORT_ENTRIES = "exportEntries";

    /** The operation id of the hidden operation. */
    public static final String LIVE_STATUS = "liveStatus";

    /** The plain-text body the hidden operation answers with. */
    public static final String LIVE_BODY = "live";

    /** The path parameter of the operations on one entry. */
    public static final String ENTRY_ID = "entryId";

    /** The regular expression of the entry identifier's {@code @Pattern}. */
    public static final String ENTRY_ID_PATTERN = "^[a-z0-9-]+$";

    /** The description of the entry identifier. */
    public static final String ENTRY_ID_DESCRIPTION = "The entry identifier";

    /** The query parameter selecting the view of the entry read. */
    public static final String VIEW = "view";

    /** The raw {@code @DefaultValue} of {@value #VIEW}. */
    public static final String VIEW_DEFAULT = "full";

    /** The header carrying the caller's trace identifier. */
    public static final String TRACE_HEADER = "X-Trace-Id";

    /** The description of {@value #TRACE_HEADER}. */
    public static final String TRACE_DESCRIPTION = "The caller's trace identifier";

    /** The example of {@value #TRACE_HEADER}; it does not parse as JSON, so it stays a string. */
    public static final String TRACE_EXAMPLE = "trace-1";

    /** The cookie carrying the caller's session. */
    public static final String SESSION_COOKIE = "session";

    /** The bean's query parameter. */
    public static final String PAGE = "page";

    /** The raw {@code @DefaultValue} of {@value #PAGE}. */
    public static final String PAGE_DEFAULT = "1";

    /** The description of {@value #PAGE}. */
    public static final String PAGE_DESCRIPTION = "The page to read";

    /** The bean's header parameter. */
    public static final String PAGE_SIZE_HEADER = "X-Page-Size";

    /** The record's query parameter. */
    public static final String SORT = "sort";

    /** The description of {@value #SORT}. */
    public static final String SORT_DESCRIPTION = "The sort order";

    /** The record's cookie parameter. */
    public static final String LOCALE_COOKIE = "locale";

    /** The query parameter of the export operation. */
    public static final String FORMAT = "format";

    /** The raw {@code @DefaultValue} of {@value #FORMAT}. */
    public static final String FORMAT_DEFAULT = "csv";

    /** The scheme described as HTTP bearer with format {@code JWT}. */
    public static final String BEARER_AUTH = "bearerAuth";

    /** The scheme described as an API key in the {@value #API_KEY_HEADER} header. */
    public static final String API_KEY_AUTH = "apiKeyAuth";

    /** The header {@value #API_KEY_AUTH} is carried in. */
    public static final String API_KEY_HEADER = "X-Api-Key";

    /** The scope the entry read requires. */
    public static final String READ_SCOPE = "entries.read";

    /** The scope the export requires. */
    public static final String EXPORT_SCOPE = "entries.export";

    /** The class-level tag of both twins. */
    public static final String ENTRIES_TAG = "entries";

    /** The method-level tag of the entry read. */
    public static final String READ_TAG = "read";

    /** The method-level tag of the entry creation. */
    public static final String WRITE_TAG = "write";

    /** The tag named only in the entry read's {@code @Operation(tags)}; it adds no root tag. */
    public static final String PUBLIC_TAG = "public";

    /** The header the declared {@code 201} response of the entry creation carries. */
    public static final String LOCATION_HEADER = "Location";

    /** The name of the named example of the declared {@code 201} response. */
    public static final String CREATED_EXAMPLE = "created";

    /** The value of the named example; it parses as a JSON object. */
    public static final String CREATED_EXAMPLE_VALUE = "{\"id\":\"e-1\",\"title\":\"First\"}";

    /** The {@code info.title} both declarations carry. */
    public static final String TITLE = "Complete entries";

    /** The {@code info.version} both declarations carry. */
    public static final String VERSION = "2.1";

    /** The {@code info.description} both declarations carry. */
    public static final String DESCRIPTION = "Every documented feature of one resource set";

    private CompleteEntries() {}
}
