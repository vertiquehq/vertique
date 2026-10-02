// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.corpus;

/**
 * The names, paths, and {@code info} of the four corpus applications. Each application is declared
 * twice, once with a public document and once with a protected one, by interfaces in this package;
 * both declarations of one application share its name and path, and each runs in its own Dagger
 * component, so the names never meet in one composition.
 *
 * <ul>
 *   <li>{@value #CORPUS}: the request-body corpus of creator, setter, and builder bound types,
 *       aliases, profiles, recursive and mutually referencing graphs, map values, a self-referencing
 *       map, a member-level closure, and {@code Optional}-valued extras;
 *   <li>{@value #PATTERNS}: a case-insensitively bound body, a body member whose authored pattern
 *       carries the {@code DOTALL} and {@code COMMENTS} flags, and an operation whose path and query
 *       parameters carry authored patterns;
 *   <li>{@value #RESPONSES}: response bodies under a non-default output profile, a dynamic and an
 *       explicitly declared response, a type whose hiding markers the generator honors, and a hidden
 *       operation beside a visible one;
 *   <li>{@value #SCHEMES}: one operation per described security scheme kind (HTTP bearer, API key
 *       in a header, a cookie, and the query, OAuth 2, OpenID Connect, and mutual TLS).
 * </ul>
 */
public final class CorpusDocuments {

    /** The name and document name of the request-body corpus application. */
    public static final String CORPUS = "corpus";

    /** The path of {@link #CORPUS}. */
    public static final String CORPUS_PATH = "/api/corpus";

    /** The name and document name of the authored and generated patterns application. */
    public static final String PATTERNS = "patterns";

    /** The path of {@link #PATTERNS}. */
    public static final String PATTERNS_PATH = "/api/patterns";

    /** The name and document name of the responses application. */
    public static final String RESPONSES = "responses";

    /** The path of {@link #RESPONSES}. */
    public static final String RESPONSES_PATH = "/api/responses";

    /** The name and document name of the security scheme kinds application. */
    public static final String SCHEMES = "schemes";

    /** The path of {@link #SCHEMES}. */
    public static final String SCHEMES_PATH = "/api/schemes";

    /** The {@code info.version} every corpus declaration states. */
    public static final String VERSION = "1.0";

    /** The scheme guarding every protected corpus document. */
    public static final String BEARER_AUTH = "bearerAuth";

    private CorpusDocuments() {}
}
