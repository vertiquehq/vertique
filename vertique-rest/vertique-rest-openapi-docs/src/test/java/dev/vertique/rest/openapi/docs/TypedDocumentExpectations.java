// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.openapi.docs.TypedDocumentDeployment.Reply;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.typed.TypedDocumentApis;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.typed.TypedDocumentModule;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The callers, the expected outcome of every caller on every typed document, and the response
 * assertions the typed document policy tests share. Every expectation is a hand-written literal.
 */
final class TypedDocumentExpectations {

    /** The documents of the typed fixture, in the order they are checked. */
    static final List<String> DOCUMENTS = List.of(
            TypedDocumentApis.Open.NAME,
            TypedDocumentApis.Deny.NAME,
            TypedDocumentApis.Authenticated.NAME,
            TypedDocumentApis.Roles.NAME,
            TypedDocumentApis.Scopes.NAME,
            TypedDocumentApis.Action.NAME,
            TypedDocumentApis.Combined.NAME);

    /** The body of an anonymous or unauthenticated denial. */
    static final JsonObject PROBLEM_401 =
            new JsonObject("{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401}");

    /** The body of a denial of an authenticated caller. */
    static final JsonObject PROBLEM_403 =
            new JsonObject("{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403}");

    /** The body of a request failed closed because authorization could not decide. */
    static final JsonObject PROBLEM_503 =
            new JsonObject("{\"type\":\"about:blank\",\"title\":\"Service Unavailable\",\"status\":503}");

    /** The caller who sends no credential. */
    static final String ANONYMOUS = "anonymous";

    /** The caller whose bearer token no provider issued. */
    static final String FORGED = "forged";

    /** An authenticated caller with the {@code user} role and no scope. */
    static final String READER = "reader";

    /** A caller with the {@code admin} role and no scope. */
    static final String ADMIN_ONLY = "adminOnly";

    /** A caller with the {@code docs.read} scope and the {@code user} role. */
    static final String SCOPED = "scoped";

    /** A caller with the {@code admin} role and the {@code docs.read} scope. */
    static final String ENTITLED = "entitled";

    /** A caller with the role and the scope whom the application's authorizer never permits. */
    static final String BLOCKED = "blocked";

    /** How a caller fares on a document. */
    enum Outcome {
        /** The document is served: 200, or 304 for a conditional request. */
        SERVED,
        /** The caller is refused as unauthenticated. */
        UNAUTHORIZED,
        /** The caller is refused as authenticated but not permitted. */
        FORBIDDEN,
        /** Authorization could not decide, so the request fails closed as unavailable. */
        UNAVAILABLE,
        /** The document names no scheme, so the caller's credential is neither read nor asserted. */
        NOT_ASSERTED
    }

    /** The form of a document. */
    enum Form {
        JSON("openapi.json", "application/json"),
        YAML("openapi.yaml", "application/yaml");

        private final String file;
        private final String contentType;

        Form(String file, String contentType) {
            this.file = file;
            this.contentType = contentType;
        }

        String path(String document) {
            return "/apidocs/" + document + "/" + file;
        }

        String contentType() {
            return contentType;
        }
    }

    private TypedDocumentExpectations() {}

    /**
     * Mints every caller's token for a deployment.
     *
     * @param deployment the deployment whose provider signs the tokens
     * @return the bearer token of each caller by name, {@code null} for the anonymous caller
     */
    static Map<String, String> tokens(TypedDocumentDeployment deployment) {
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put(ANONYMOUS, null);
        tokens.put(FORGED, "forged.invalid.token");
        tokens.put(READER, deployment.token("reader", List.of("user"), null));
        tokens.put(ADMIN_ONLY, deployment.token("admin-only", List.of("admin"), null));
        tokens.put(SCOPED, deployment.token("scoped", List.of("user"), "other " + TypedDocumentApis.SCOPE));
        tokens.put(ENTITLED, deployment.token("alice", List.of("admin"), TypedDocumentApis.SCOPE));
        tokens.put(BLOCKED, deployment.token(TypedDocumentModule.BLOCKED, List.of("admin"), TypedDocumentApis.SCOPE));
        return tokens;
    }

    /**
     * Returns how each caller fares on each document, written by hand: the public document serves
     * every caller; the deny document refuses every caller, 401 before authentication and 403 after;
     * the other documents serve exactly the callers their policy permits, and the forged credential and
     * the missing one are refused as unauthenticated everywhere a scheme guards.
     *
     * @return the outcomes by document, then by caller
     */
    static Map<String, Map<String, Outcome>> outcomes() {
        Map<String, Map<String, Outcome>> table = new LinkedHashMap<>();
        table.put(
                TypedDocumentApis.Open.NAME,
                row(
                        Outcome.SERVED,
                        Outcome.NOT_ASSERTED,
                        Outcome.SERVED,
                        Outcome.SERVED,
                        Outcome.SERVED,
                        Outcome.SERVED,
                        Outcome.SERVED));
        table.put(
                TypedDocumentApis.Deny.NAME,
                row(
                        Outcome.UNAUTHORIZED,
                        Outcome.UNAUTHORIZED,
                        Outcome.FORBIDDEN,
                        Outcome.FORBIDDEN,
                        Outcome.FORBIDDEN,
                        Outcome.FORBIDDEN,
                        Outcome.FORBIDDEN));
        table.put(
                TypedDocumentApis.Authenticated.NAME,
                row(
                        Outcome.UNAUTHORIZED,
                        Outcome.UNAUTHORIZED,
                        Outcome.SERVED,
                        Outcome.SERVED,
                        Outcome.SERVED,
                        Outcome.SERVED,
                        Outcome.SERVED));
        table.put(
                TypedDocumentApis.Roles.NAME,
                row(
                        Outcome.UNAUTHORIZED,
                        Outcome.UNAUTHORIZED,
                        Outcome.FORBIDDEN,
                        Outcome.SERVED,
                        Outcome.FORBIDDEN,
                        Outcome.SERVED,
                        Outcome.SERVED));
        table.put(
                TypedDocumentApis.Scopes.NAME,
                row(
                        Outcome.UNAUTHORIZED,
                        Outcome.UNAUTHORIZED,
                        Outcome.FORBIDDEN,
                        Outcome.FORBIDDEN,
                        Outcome.SERVED,
                        Outcome.SERVED,
                        Outcome.SERVED));
        table.put(
                TypedDocumentApis.Action.NAME,
                row(
                        Outcome.UNAUTHORIZED,
                        Outcome.UNAUTHORIZED,
                        Outcome.SERVED,
                        Outcome.SERVED,
                        Outcome.SERVED,
                        Outcome.SERVED,
                        Outcome.FORBIDDEN));
        table.put(
                TypedDocumentApis.Combined.NAME,
                row(
                        Outcome.UNAUTHORIZED,
                        Outcome.UNAUTHORIZED,
                        Outcome.FORBIDDEN,
                        Outcome.FORBIDDEN,
                        Outcome.FORBIDDEN,
                        Outcome.SERVED,
                        Outcome.FORBIDDEN));
        return table;
    }

    /** Builds one document's row in the order anonymous, forged, reader, adminOnly, scoped, entitled, blocked. */
    private static Map<String, Outcome> row(
            Outcome anonymous,
            Outcome forged,
            Outcome reader,
            Outcome adminOnly,
            Outcome scoped,
            Outcome entitled,
            Outcome blocked) {
        Map<String, Outcome> row = new LinkedHashMap<>();
        row.put(ANONYMOUS, anonymous);
        row.put(FORGED, forged);
        row.put(READER, reader);
        row.put(ADMIN_ONLY, adminOnly);
        row.put(SCOPED, scoped);
        row.put(ENTITLED, entitled);
        row.put(BLOCKED, blocked);
        return row;
    }

    /**
     * Returns whether a caller reaches the action gate of a document, so the application's authorizer
     * evaluates the document's action for it exactly once per request: the callers the local role and
     * scope predicates let through, on a document that requires the action.
     *
     * @param document the document
     * @param caller   the caller
     * @return {@code true} when the authorizer is asked
     */
    static boolean evaluatesAction(String document, String caller) {
        boolean authenticated = !caller.equals(ANONYMOUS) && !caller.equals(FORGED);
        return switch (document) {
            case "action" -> authenticated;
            case "combined" -> caller.equals(ENTITLED) || caller.equals(BLOCKED);
            default -> false;
        };
    }

    /** The {@code Cache-Control} a served response of a document carries. */
    static String cacheControl(String document) {
        return document.equals(TypedDocumentApis.Open.NAME) ? "no-cache" : "private, no-store";
    }

    /** The {@code Vary} a served response of a document carries, or {@code null} for none. */
    static String vary(String document) {
        return document.equals(TypedDocumentApis.Open.NAME) ? null : "Authorization";
    }

    /** Returns whether a response is a denial: 401 or 403. */
    static boolean denial(Outcome outcome) {
        return outcome == Outcome.UNAUTHORIZED || outcome == Outcome.FORBIDDEN;
    }

    // --- Assertions ---

    /**
     * Asserts a served response: the document's bytes for an unconditional {@code GET}, no body for
     * {@code HEAD}, and {@code 304} with no body for a conditional request, each with the document's
     * entity tag, {@code Cache-Control} and {@code Vary}.
     *
     * @param row         the request's description
     * @param document    the document
     * @param form        the form requested
     * @param method      the method
     * @param conditional whether the request carried a matching {@code If-None-Match}
     * @param reply       the response
     * @param baseline    the first read of the form by a caller the policy permits
     */
    static void assertServed(
            String row,
            String document,
            Form form,
            HttpMethod method,
            boolean conditional,
            Reply reply,
            Reply baseline) {
        String tag = baseline.header("ETag");
        assertNotNull(tag, row + ": the baseline carries an entity tag");
        assertEquals(conditional ? 304 : 200, reply.status(), row + ": status");
        assertEquals(tag, reply.header("ETag"), row + ": the form's entity tag");
        assertEquals(List.of(cacheControl(document)), reply.headers().getAll("Cache-Control"), row + ": Cache-Control");
        String vary = vary(document);
        assertEquals(vary == null ? List.of() : List.of(vary), reply.headers().getAll("Vary"), row + ": Vary");
        if (conditional || method == HttpMethod.HEAD) {
            assertEquals(0, reply.body().length(), row + ": no body");
        } else {
            assertEquals(form.contentType(), reply.header("Content-Type"), row + ": content type");
            assertEquals(baseline.body(), reply.body(), row + ": the same bytes as the baseline");
        }
    }

    /**
     * Asserts a denial: the status, no entity tag, never {@code 200} or {@code 304}, no document
     * content, and for {@code GET} exactly the problem body with {@code no-store} and no {@code Vary}.
     *
     * @param row     the request's description
     * @param outcome the denial expected
     * @param method  the method
     * @param reply   the response
     */
    static void assertDenied(String row, Outcome outcome, HttpMethod method, Reply reply) {
        int status = outcome == Outcome.UNAUTHORIZED ? 401 : outcome == Outcome.UNAVAILABLE ? 503 : 403;
        assertEquals(status, reply.status(), row + ": status");
        assertNoDocumentContent(row, reply);
        if (method == HttpMethod.GET) {
            assertEquals(
                    outcome == Outcome.UNAUTHORIZED
                            ? PROBLEM_401
                            : outcome == Outcome.UNAVAILABLE ? PROBLEM_503 : PROBLEM_403,
                    json(reply),
                    row + ": problem");
            assertEquals("no-store", reply.header("Cache-Control"), row + ": Cache-Control");
            assertEquals(List.of(), reply.headers().getAll("Vary"), row + ": no Vary");
        } else {
            assertEquals(0, reply.body().length(), row + ": HEAD has no body");
        }
    }

    /**
     * Asserts a response that is neither a success nor a revalidation and carries no document.
     *
     * @param row   the request's description
     * @param reply the response
     */
    static void assertNeverServed(String row, Reply reply) {
        assertFalse(reply.status() == 200, row + ": never 200, body: " + reply.text());
        assertFalse(reply.status() == 304, row + ": never 304");
        assertNoDocumentContent(row, reply);
    }

    /** Asserts the response carries no document bytes, operation content, or entity tag. */
    static void assertNoDocumentContent(String row, Reply reply) {
        String body = reply.text();
        assertFalse(body.contains("openapi"), row + ": no document content: " + body);
        assertFalse(body.contains("/items"), row + ": no operation path: " + body);
        assertNull(reply.header("ETag"), row + ": no entity tag");
    }

    /** Parses a JSON object body, or returns {@code null} when it is not one. */
    static JsonObject json(Reply reply) {
        Buffer body = reply.body();
        try {
            return body.toJsonValue() instanceof JsonObject object ? object : null;
        } catch (DecodeException notJson) {
            return null;
        }
    }

    /** Asserts a response is an OpenAPI document: a JSON object, or YAML text, that names its version. */
    static void assertIsDocument(String row, Reply reply) {
        JsonObject document = json(reply);
        boolean json = document != null && document.containsKey("openapi");
        boolean yaml = document == null && reply.text().lines().anyMatch(line -> line.startsWith("openapi:"));
        assertTrue(json || yaml, row + ": the document's bytes are served");
    }
}
