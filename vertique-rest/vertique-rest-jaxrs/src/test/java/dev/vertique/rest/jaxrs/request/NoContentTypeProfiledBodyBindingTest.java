// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.exc.StreamReadException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.vertique.core.exception.ValidationException;
import dev.vertique.json.VertxJsonSupport;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.routing.StubOperationDescriptor;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.RoutingContext;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit test proving {@link DefaultBoundRequest} routes a missing-{@code Content-Type}, JSON-shaped
 * request body through the resolved profile mapper's strict first parse rather than the lenient
 * Vert.x JSON-shape probe (FR-JSON-024A).
 *
 * <p>This exercises {@code DefaultBoundRequest.bindBody}'s missing-content-type branch directly: when
 * a resolved profile mapper is stashed on the routing context under
 * {@link BoundRequest#KEY_RESOLVED_BODY_MAPPER} and the body's first significant byte is {@code '{'}
 * or {@code '['}, {@code bindBody} deliberately pre-empts the lenient
 * {@code JsonRequestBodyDecoder}-style shape probe (whose {@code canDecode} returns {@code true} for a
 * {@code null} content type) and routes the body through {@code bindProfiledJsonBody} instead, so the
 * profile's strict parser features (e.g. {@code STRICT_DUPLICATE_DETECTION}) run on the FIRST PARSE
 * even though no {@code Content-Type} header was sent.
 *
 * <p><b>Why this is a unit test and not an integration test.</b> The equivalent HTTP-level scenario —
 * a POST carrying a body with no {@code Content-Type} header — is unreachable against a
 * production-faithful mount: {@code ContentTypeValidationMiddleware} (API-scoped, priority 20, an
 * unconditional {@code @IntoSet} framework default) rejects such a request with {@code 415} before it
 * ever reaches the router-level binder. A prior integration test in {@code vertique-rest-validation}
 * (built from a hand-rolled mount that omitted that middleware) asserted {@code 400} for this scenario;
 * that assertion documented behavior no real deployment exhibits. The branch itself is still real and
 * reachable — from any mount assembled without API-scoped middleware — so its coverage lives here,
 * exercising {@link DefaultBoundRequest}'s constructor directly with no HTTP and no middleware in the
 * path.
 *
 * <p>Because this test is that IT's sanctioned replacement, its proof strength is load-bearing: it
 * asserts the frozen rejection <em>message</em>
 * ({@code dev.vertique.rest.jaxrs.ProfileBodyMaterialization#rejection}) and the retained Jackson
 * cause, not merely that some {@code ValidationException} escaped the constructor.
 */
class NoContentTypeProfiledBodyBindingTest {

    /**
     * Builds a strict profile {@link ObjectMapper} with Vert.x JSON support (so a {@code JsonObject}
     * first parse succeeds) and {@code STRICT_DUPLICATE_DETECTION} enabled — the same feature the
     * original {@code strict-test} profile used, so a duplicate JSON key is rejected during the first
     * parse.
     *
     * @return the strict profile mapper
     */
    private static ObjectMapper strictDuplicateKeyProfileMapper() {
        return JsonMapper.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .addModule(VertxJsonSupport.module())
                .build();
    }

    /**
     * Builds a mocked {@link RoutingContext} whose request carries {@code rawBody} with NO {@code
     * Content-Type} header, and which stashes {@code profileMapper} under
     * {@link BoundRequest#KEY_RESOLVED_BODY_MAPPER} — the missing-content-type, profile-active
     * combination {@link DefaultBoundRequest#bindBody} pre-empts to the profile mapper.
     *
     * @param rawBody       the raw request body bytes
     * @param profileMapper the resolved profile mapper to stash on the context
     * @return the mocked routing context
     */
    private static RoutingContext noContentTypeProfiledContext(Buffer rawBody, ObjectMapper profileMapper) {
        RoutingContext ctx = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(ctx.request()).thenReturn(request);
        when(ctx.pathParams()).thenReturn(Map.of());
        when(ctx.queryParams()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        when(request.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        when(request.cookies()).thenReturn(Set.of());
        when(request.getHeader("Content-Type")).thenReturn(null);
        when(ctx.<ObjectMapper>get(BoundRequest.KEY_RESOLVED_BODY_MAPPER)).thenReturn(profileMapper);
        RequestBody body = mock(RequestBody.class);
        when(body.buffer()).thenReturn(rawBody);
        when(ctx.body()).thenReturn(body);
        return ctx;
    }

    /** Builds a no-param, no-body operation descriptor; the binder's body path never reads it. */
    private static JaxRsOperationDescriptor noParamsOp() {
        return StubOperationDescriptor.builder()
                .operationId("op")
                .httpMethod("POST")
                .routeTemplate("/test")
                .build();
    }

    @Test
    @DisplayName(
            "A duplicate-key JSON body with no Content-Type is still rejected by the profile mapper's strict first parse")
    void noContentTypeDuplicateKeyBody_rejectedByProfileMapper() {
        // given: no Content-Type header, a profile mapper with STRICT_DUPLICATE_DETECTION stashed on
        // the context, and a '{'-leading body carrying a duplicate "name" key.
        ObjectMapper profileMapper = strictDuplicateKeyProfileMapper();
        Buffer rawBody = Buffer.buffer("{\"name\":\"a\",\"name\":\"b\"}");
        RoutingContext ctx = noContentTypeProfiledContext(rawBody, profileMapper);

        // when/then: binding must reject the body via the profile mapper's strict first parse (400),
        // not bind it leniently through the default Vert.x JSON path.
        ValidationException rejection = assertThrows(
                ValidationException.class,
                () -> new DefaultBoundRequest(ctx, noParamsOp()),
                "a duplicate-key body with no Content-Type must still be rejected by the profile mapper's strict first parse");

        // The message pins the rejection to the profile pre-empt site specifically: it is the frozen,
        // value-free message ProfileBodyMaterialization.rejection produces, so any OTHER
        // ValidationException raised inside DefaultBoundRequest (param binding, a decoder, a future
        // guard) fails this assertion rather than silently satisfying the test.
        assertEquals(
                "Request body rejected by JSON profile",
                rejection.getMessage(),
                "the rejection must come from the profile mapper's first parse, not from any other binder path");

        // And the underlying Jackson rejection is retained as the cause — proving the strict parse ran
        // rather than the body being rejected before it reached the profile mapper.
        assertInstanceOf(
                StreamReadException.class,
                rejection.getCause(),
                "STRICT_DUPLICATE_DETECTION must be what rejected the body");
    }
}
