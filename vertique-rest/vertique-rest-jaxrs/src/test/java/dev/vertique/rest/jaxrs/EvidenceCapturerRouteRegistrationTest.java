// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.capture.HttpOperationMeta;
import dev.vertique.rest.core.capture.RestServerRequestEvidenceCapturer;
import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link JaxRsRouteRegistrar} offers every registered route to each
 * {@link RestServerRequestEvidenceCapturer} at router build, with the descriptor its requests will
 * carry, and turns a capturer's rejection into a collected startup violation (issue #638).
 */
class EvidenceCapturerRouteRegistrationTest {

    private final JaxRsRouteRegistrar registrar = new JaxRsRouteRegistrar();
    private Vertx vertx;
    private Router router;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        router = Router.router(vertx);
    }

    @AfterEach
    void tearDown() {
        vertx.close();
    }

    /** Contract whose default method is one of the resource's routes. */
    interface Lookup {
        @GET
        @Path("/{id}")
        @Operation(operationId = "lookupById")
        default String lookup(@PathParam("id") String id) {
            return id;
        }
    }

    @Path("/hooked")
    static class HookedResource implements Lookup {}

    @Path("/second")
    static class SecondResource {
        @GET
        @Operation(operationId = "second")
        public String second() {
            return "second";
        }
    }

    /** Records every route it is offered and never captures. */
    static final class RecordingCapturer implements RestServerRequestEvidenceCapturer {
        final List<HttpOperationMeta> offered = new ArrayList<>();

        @Override
        public void validateRoute(HttpOperationMeta meta) {
            offered.add(meta);
        }

        @Override
        public void captureRequest(RoutingContext ctx, HttpOperationMeta meta) {}
    }

    /** Rejects every route, as an adapter does for a policy it cannot resolve. */
    static final class RejectingCapturer implements RestServerRequestEvidenceCapturer {
        @Override
        public void validateRoute(HttpOperationMeta meta) {
            throw new IllegalStateException("unknown policy 'nope'");
        }

        @Override
        public void captureRequest(RoutingContext ctx, HttpOperationMeta meta) {}
    }

    @Test
    @DisplayName("each capturer validates each route with the descriptor its requests will carry")
    void capturerOfferedEachRoute() throws NoSuchMethodException {
        RecordingCapturer capturer = new RecordingCapturer();

        RegistrarTestSupport.registerAllWithCapturers(
                registrar,
                Set.of(new HookedResource()),
                router,
                RegistrarTestSupport.TEST_MOUNT_META,
                List.of(capturer));

        assertEquals(1, capturer.offered.size(), capturer.offered.toString());
        HttpOperationMeta meta = capturer.offered.get(0);
        assertEquals(Lookup.class.getMethod("lookup", String.class), meta.method());
        assertEquals(HookedResource.class, meta.resourceClass());
        assertEquals("lookupById", meta.operationId());
        assertEquals("/hooked/{id}", meta.routeTemplate());
    }

    @Test
    @DisplayName("rejections are collected across routes, and every capturer still validates every route")
    void rejectionsCollectedAcrossRoutesAndCapturers() {
        RecordingCapturer recording = new RecordingCapturer();

        RouteRegistrationException ex = assertThrows(
                RouteRegistrationException.class,
                () -> RegistrarTestSupport.registerAllWithCapturers(
                        registrar,
                        Set.of(new HookedResource(), new SecondResource()),
                        router,
                        RegistrarTestSupport.TEST_MOUNT_META,
                        List.of(new RejectingCapturer(), recording)));

        assertEquals(
                Set.of("lookupById", "second"),
                ex.violations().stream()
                        .map(RouteRegistrationViolation::operationId)
                        .collect(java.util.stream.Collectors.toSet()),
                ex.violations().toString());
        assertEquals(2, recording.offered.size(), "a rejection must not stop the other capturers or routes");
    }

    @Test
    @DisplayName("a capturer's rejection is a collected EVIDENCE_CAPTURE_REJECTED startup violation")
    void capturerRejection_failsStartup() {
        RouteRegistrationException ex = assertThrows(
                RouteRegistrationException.class,
                () -> RegistrarTestSupport.registerAllWithCapturers(
                        registrar,
                        Set.of(new HookedResource()),
                        router,
                        RegistrarTestSupport.TEST_MOUNT_META,
                        List.of(new RejectingCapturer())));

        assertEquals(1, ex.violations().size(), ex.violations().toString());
        RouteRegistrationViolation violation = ex.violations().get(0);
        assertEquals(RouteRegistrationViolation.ViolationType.EVIDENCE_CAPTURE_REJECTED, violation.type());
        assertEquals("lookupById", violation.operationId());
        assertTrue(
                violation.message().contains(RejectingCapturer.class.getName())
                        && violation.message().contains("IllegalStateException: unknown policy 'nope'"),
                violation.message());
    }
}
