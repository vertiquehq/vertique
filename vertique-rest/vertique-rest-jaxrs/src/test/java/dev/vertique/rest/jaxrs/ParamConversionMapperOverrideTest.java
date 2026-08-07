// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.rest.core.ProblemDetail;
import dev.vertique.rest.core.convert.ParamConversionException;
import dev.vertique.rest.core.convert.ParamSource;
import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that an application-registered {@link ExceptionMapper} for the framework
 * {@link ParamConversionException} overrides the default {@code 400} response body/status when an
 * inbound parameter fails conversion (PRD-REST-018 slice 1.3, AC-4).
 *
 * <p><b>RED rationale (compile-RED, then behavior-RED once it compiles):</b> this test needs two
 * not-yet-existing pieces — a {@code TestFactories.builder().exceptionMapperRegistry(...)} hook so the
 * mount uses a registry carrying the app mapper, and an inbound binding path that actually throws
 * {@link ParamConversionException} on a malformed value. It is written against the <em>intended</em>
 * wiring so it compile-fails today; once slice 1.3 lands the {@code exceptionMapperRegistry} hook and
 * threads conversion through the resolver, it becomes a behavior assertion (custom 422 returned).
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class ParamConversionMapperOverrideTest {

    private HttpServer server;
    private HttpClient client;

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        Future<?> serverClose = server != null ? server.close() : Future.succeededFuture();
        Future<?> clientClose = client != null ? client.close() : Future.succeededFuture();
        Future.join(serverClose, clientClose).onComplete(ar -> ctx.completeNow());
    }

    /** Resource with a single {@code @PathParam UUID} that must convert on dispatch. */
    @Path("/conv")
    public static class UuidResource {

        /**
         * Echoes the UUID path id (only reached on a valid value).
         *
         * @param id the UUID path param
         * @return the id echoed back
         */
        @GET
        @Path("/{id}")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "convOverrideUuid")
        public String uuid(@PathParam("id") UUID id) {
            return "id=" + id;
        }
    }

    /**
     * App mapper overriding the default {@code 400} with a recognizable {@code 422}, contributed the
     * normal way as an {@link ExceptionMapper} of the framework {@link ParamConversionException}. It
     * also captures the exception it receives so the test can assert the conversion context
     * (parameter name / source / target type) reached the mapper intact (FR-015-08a).
     */
    public static final class AppParamConversionMapper implements ExceptionMapper<ParamConversionException> {
        private final AtomicReference<ParamConversionException> received;

        AppParamConversionMapper(AtomicReference<ParamConversionException> received) {
            this.received = received;
        }

        @Override
        public Response toResponse(ParamConversionException exception) {
            received.set(exception);
            return Response.status(422)
                    .entity(ProblemDetail.of(422, "custom-param-conversion"))
                    .type("application/problem+json")
                    .build();
        }
    }

    @Test
    @DisplayName("An app ExceptionMapper<ParamConversionException> overrides the default 400 with 422")
    void appExceptionMapperOverridesDefault(Vertx vertx, VertxTestContext ctx) {
        // Sanity-touch the framework exception so the import is load-bearing even before wiring exists.
        ParamConversionException probe =
                new ParamConversionException("probe", "id", ParamSource.PATH, UUID.class, new RuntimeException());
        assertEquals("id", probe.paramName());

        AtomicReference<ParamConversionException> received = new AtomicReference<>();
        DefaultExceptionMapper defaultMapper = RestModule.defaultExceptionMapper();
        ExceptionMapperRegistry registry =
                new ExceptionMapperRegistry(defaultMapper, Set.of(new AppParamConversionMapper(received)));

        // INTENDED-but-not-yet-existing hook: TestFactories must let the mount use a custom registry.
        JaxRsRouterMount.Factory factory =
                TestFactories.builder().exceptionMapperRegistry(registry).build();
        JaxRsRouterMount mount = factory.create("/*", "openapi.json", Set.of(new UuidResource()));

        mount.createRouter(vertx)
                .compose(apiRouter -> {
                    Router root = Router.router(vertx);
                    root.route("/*").subRouter(apiRouter);
                    return vertx.createHttpServer().requestHandler(root).listen(0, "127.0.0.1");
                })
                .onComplete(ctx.succeeding(s -> {
                    server = s;
                    client = vertx.createHttpClient();
                    client.request(HttpMethod.GET, s.actualPort(), "127.0.0.1", "/conv/not-a-uuid")
                            .compose(req -> req.send())
                            .compose(resp -> resp.body().map(b -> resp.statusCode() + "|" + b.toString()))
                            .onComplete(ctx.succeeding(statusAndBody -> {
                                ctx.verify(() -> {
                                    assertEquals(
                                            "422",
                                            statusAndBody.substring(0, statusAndBody.indexOf('|')),
                                            "the app mapper must override the default 400 for a conversion failure");
                                    ParamConversionException seen = received.get();
                                    assertEquals("id", seen.paramName(), "mapper must receive the real param name");
                                    assertEquals(
                                            ParamSource.PATH,
                                            seen.source(),
                                            "mapper must receive the real param source");
                                    assertEquals(
                                            UUID.class, seen.targetType(), "mapper must receive the real target type");
                                });
                                ctx.completeNow();
                            }));
                }));
    }
}
