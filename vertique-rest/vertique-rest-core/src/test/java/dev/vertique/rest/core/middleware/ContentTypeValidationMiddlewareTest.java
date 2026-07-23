// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ContentTypeValidationMiddleware}.
 *
 * <p>Verifies middleware scope, ordering, and Content-Type validation logic
 * for POST, PUT, PATCH, GET, and DELETE requests. Accepted types include
 * application/* (all subtypes), multipart/form-data, and text/*.
 */
class ContentTypeValidationMiddlewareTest {

    private final ContentTypeValidationMiddleware middleware = new ContentTypeValidationMiddleware();

    // --- Middleware contract ---

    @Test
    @DisplayName("Should have API scope")
    void shouldHaveApiScope() {
        assertEquals(MiddlewareScope.API, middleware.scope());
    }

    @Test
    @DisplayName("Should have priority 20")
    void shouldHaveOrder20() {
        assertEquals(20, middleware.priority());
    }

    // --- POST validation ---

    @Test
    @DisplayName("Should call ctx.fail(415) for POST with no Content-Type")
    void shouldRejectPostWithoutContentType() {
        RoutingContext ctx = mockContext(HttpMethod.POST, null);

        middleware.handle(ctx);

        verify(ctx).fail(eq(415), any(jakarta.ws.rs.NotSupportedException.class));
        verify(ctx, never()).next();
    }

    @Test
    @DisplayName("Should call ctx.next() for POST with application/json Content-Type")
    void shouldAcceptPostWithApplicationJson() {
        RoutingContext ctx = mockContext(HttpMethod.POST, "application/json");

        middleware.handle(ctx);

        verify(ctx).next();
        verify(ctx, never()).fail(anyInt(), any(Throwable.class));
    }

    @Test
    @DisplayName("Should call ctx.next() for POST with JSON subtype application/vnd.api+json")
    void shouldAcceptPostWithJsonSubtype() {
        RoutingContext ctx = mockContext(HttpMethod.POST, "application/vnd.api+json");

        middleware.handle(ctx);

        verify(ctx).next();
        verify(ctx, never()).fail(anyInt(), any(Throwable.class));
    }

    @Test
    @DisplayName("Should call ctx.next() for POST with multipart/form-data Content-Type")
    void shouldAcceptPostWithMultipartFormData() {
        RoutingContext ctx = mockContext(HttpMethod.POST, "multipart/form-data; boundary=----boundary");

        middleware.handle(ctx);

        verify(ctx).next();
        verify(ctx, never()).fail(anyInt(), any(Throwable.class));
    }

    @Test
    @DisplayName("Should call ctx.next() for POST with application/x-www-form-urlencoded Content-Type")
    void shouldAcceptPostWithFormUrlEncoded() {
        RoutingContext ctx = mockContext(HttpMethod.POST, "application/x-www-form-urlencoded");

        middleware.handle(ctx);

        verify(ctx).next();
        verify(ctx, never()).fail(anyInt(), any(Throwable.class));
    }

    @Test
    @DisplayName("Should call ctx.next() for POST with text/plain Content-Type")
    void shouldAcceptPostWithTextPlain() {
        RoutingContext ctx = mockContext(HttpMethod.POST, "text/plain");

        middleware.handle(ctx);

        verify(ctx).next();
        verify(ctx, never()).fail(anyInt(), any(Throwable.class));
    }

    @Test
    @DisplayName("Should call ctx.next() for POST with application/octet-stream Content-Type")
    void shouldAcceptPostWithOctetStream() {
        RoutingContext ctx = mockContext(HttpMethod.POST, "application/octet-stream");

        middleware.handle(ctx);

        verify(ctx).next();
        verify(ctx, never()).fail(anyInt(), any(Throwable.class));
    }

    // --- GET passthrough ---

    @Test
    @DisplayName("Should call ctx.next() for GET with no Content-Type")
    void shouldPassGetWithoutContentType() {
        RoutingContext ctx = mockContext(HttpMethod.GET, null);

        middleware.handle(ctx);

        verify(ctx).next();
        verify(ctx, never()).fail(anyInt(), any(Throwable.class));
    }

    // --- PUT validation ---

    @Test
    @DisplayName("Should call ctx.next() for PUT with application/xml Content-Type")
    void shouldAcceptPutWithApplicationXml() {
        RoutingContext ctx = mockContext(HttpMethod.PUT, "application/xml");

        middleware.handle(ctx);

        verify(ctx).next();
        verify(ctx, never()).fail(anyInt(), any(Throwable.class));
    }

    @Test
    @DisplayName("Should call ctx.next() for PUT with application/x-protobuf Content-Type")
    void shouldAcceptPutWithProtobuf() {
        RoutingContext ctx = mockContext(HttpMethod.PUT, "application/x-protobuf");

        middleware.handle(ctx);

        verify(ctx).next();
        verify(ctx, never()).fail(anyInt(), any(Throwable.class));
    }

    // --- PATCH validation ---

    @Test
    @DisplayName("Should call ctx.fail(415) for PATCH with image/png Content-Type")
    void shouldRejectPatchWithImageContentType() {
        RoutingContext ctx = mockContext(HttpMethod.PATCH, "image/png");

        middleware.handle(ctx);

        verify(ctx).fail(eq(415), any(jakarta.ws.rs.NotSupportedException.class));
        verify(ctx, never()).next();
    }

    // --- DELETE passthrough ---

    @Test
    @DisplayName("Should call ctx.next() for DELETE with no Content-Type")
    void shouldPassDeleteWithoutContentType() {
        RoutingContext ctx = mockContext(HttpMethod.DELETE, null);

        middleware.handle(ctx);

        verify(ctx).next();
        verify(ctx, never()).fail(anyInt(), any(Throwable.class));
    }

    // --- Empty-body passthrough ---

    @Test
    @DisplayName("Should call ctx.next() for POST with Content-Length: 0 and no Content-Type")
    void shouldPassThroughPostWithContentLengthZeroAndNoContentType() {
        RoutingContext ctx = mockContext(HttpMethod.POST, null, "0", null);

        middleware.handle(ctx);

        verify(ctx).next();
        verify(ctx, never()).fail(anyInt(), any(Throwable.class));
    }

    @Test
    @DisplayName("Should call ctx.next() for POST with no Content-Length, no Transfer-Encoding and no Content-Type")
    void shouldPassThroughPostWithNoContentLengthNoTransferEncodingNoContentType() {
        RoutingContext ctx = mockContext(HttpMethod.POST, null, null, null);

        middleware.handle(ctx);

        verify(ctx).next();
        verify(ctx, never()).fail(anyInt(), any(Throwable.class));
    }

    @Test
    @DisplayName("Should call ctx.fail(415) for POST with Content-Length: 42 and no Content-Type")
    void shouldRejectPostWithContentLengthAndNoContentType() {
        RoutingContext ctx = mockContext(HttpMethod.POST, null, "42", null);

        middleware.handle(ctx);

        verify(ctx).fail(eq(415), any(jakarta.ws.rs.NotSupportedException.class));
        verify(ctx, never()).next();
    }

    // --- Slice-10 cleanup: no stale OpenAPI-router delegation comments ---

    @Test
    @DisplayName(
            "RouterReferencingCommentsRemovedFromMiddleware — source must not contain 'OpenAPI router' or 'generated spec' delegation text")
    void routerReferencingCommentsRemovedFromMiddleware() throws IOException, URISyntaxException {
        // Locate the source file by walking from the class-file location
        java.net.URL classUrl = ContentTypeValidationMiddleware.class.getResource(
                "/dev/vertique/rest/core/middleware/ContentTypeValidationMiddleware.class");
        Objects.requireNonNull(classUrl, "Cannot locate class resource for ContentTypeValidationMiddleware");

        // Navigate from target/classes/... up to the module root, then to the source file
        Path classPath = Path.of(classUrl.toURI());
        // target/classes/dev/vertique/rest/core/middleware/ContentTypeValidationMiddleware.class
        // → go up 7 levels to reach module root, then into src/main/java/...
        Path moduleRoot = classPath;
        for (int i = 0; i < 8; i++) {
            moduleRoot = moduleRoot.getParent();
        }
        Path sourceFile = moduleRoot.resolve(
                "src/main/java/dev/vertique/rest/core/middleware/ContentTypeValidationMiddleware.java");

        String source = Files.readString(sourceFile);
        assertFalse(
                source.contains("OpenAPI router"),
                "ContentTypeValidationMiddleware must not reference 'OpenAPI router' (stale delegation comment)");
        assertFalse(
                source.contains("generated spec"),
                "ContentTypeValidationMiddleware must not reference 'generated spec' (stale delegation comment)");
    }

    // --- Helpers ---

    /**
     * Builds a mocked {@link RoutingContext} whose request returns the given
     * HTTP method and {@code Content-Type} header value. {@code Content-Length}
     * and {@code Transfer-Encoding} are {@code null} (absent).
     *
     * @param method      the HTTP method to simulate
     * @param contentType the value to return from {@code getHeader("Content-Type")},
     *                    or {@code null} to simulate a missing header
     * @return the mocked routing context
     */
    private RoutingContext mockContext(HttpMethod method, String contentType) {
        return mockContext(method, contentType, "1", null);
    }

    /**
     * Builds a mocked {@link RoutingContext} with full control over body-presence headers.
     *
     * @param method           the HTTP method to simulate
     * @param contentType      the {@code Content-Type} header value, or {@code null}
     * @param contentLength    the {@code Content-Length} header value, or {@code null}
     * @param transferEncoding the {@code Transfer-Encoding} header value, or {@code null}
     * @return the mocked routing context
     */
    private RoutingContext mockContext(
            HttpMethod method, String contentType, String contentLength, String transferEncoding) {
        RoutingContext ctx = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(ctx.request()).thenReturn(request);
        when(request.method()).thenReturn(method);
        when(request.getHeader("Content-Type")).thenReturn(contentType);
        when(request.getHeader("Content-Length")).thenReturn(contentLength);
        when(request.getHeader("Transfer-Encoding")).thenReturn(transferEncoding);
        return ctx;
    }
}
