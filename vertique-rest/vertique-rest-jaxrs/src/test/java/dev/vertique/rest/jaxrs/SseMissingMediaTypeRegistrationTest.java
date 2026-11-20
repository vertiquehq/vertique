// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.rest.core.sse.SseEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.Vertx;
import io.vertx.core.streams.ReadStream;
import io.vertx.ext.web.Router;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Registering a method that returns {@code ReadStream<SseEvent>} without declaring
 * {@code @Produces("text/event-stream")} must fail startup rather than fail at request time.
 */
class SseMissingMediaTypeRegistrationTest {

    private JaxRsRouteRegistrar registrar;
    private Vertx vertx;
    private Router router;

    @BeforeEach
    void setUp() {
        registrar = new JaxRsRouteRegistrar();
        vertx = Vertx.vertx();
        router = Router.router(vertx);
    }

    @AfterEach
    void tearDown() {
        vertx.close();
    }

    @Path("/stream")
    static class StreamWithoutMediaTypeResource {
        @GET
        @Operation(operationId = "unannotatedStream")
        public ReadStream<SseEvent> stream() {
            return null;
        }
    }

    @Test
    @DisplayName("Should reject a ReadStream<SseEvent> return type without text/event-stream")
    void shouldRejectStreamReturnTypeWithoutEventStreamMediaType() {
        RouteRegistrationException ex = assertThrows(
                RouteRegistrationException.class,
                () -> RegistrarTestSupport.registerAll(
                        registrar,
                        Set.of(new StreamWithoutMediaTypeResource()),
                        router,
                        RegistrarTestSupport.TEST_MOUNT_META,
                        List.of(),
                        List.of(),
                        null,
                        false,
                        List.of(),
                        List.of(),
                        "OFF",
                        null,
                        null,
                        null,
                        false));

        assertEquals(
                List.of(RouteRegistrationViolation.ViolationType.INVALID_SSE_DECLARATION),
                ex.violations().stream().map(RouteRegistrationViolation::type).toList());
        assertEquals("unannotatedStream", ex.violations().get(0).operationId());
    }
}
