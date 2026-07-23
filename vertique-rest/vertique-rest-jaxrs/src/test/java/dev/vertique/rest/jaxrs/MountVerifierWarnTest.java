// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.JsonConfig;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.context.RestContextResolution;
import dev.vertique.rest.core.response.ResponseBodyEncoder;
import dev.vertique.rest.jaxrs.validation.FileContentVerifier;
import dev.vertique.rest.jaxrs.validation.FileVerificationResult;
import dev.vertique.rest.jaxrs.validation.NoneValidationStrategy;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import dev.vertique.rest.jaxrs.validation.RequestValidationStrategy;
import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.ext.web.RoutingContext;
import io.vertx.junit5.VertxExtension;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

/** Tests the per-mount warning when bound file verifiers are inactive under the selected strategy. */
@ExtendWith(VertxExtension.class)
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class MountVerifierWarnTest {

    private Logger mountLogger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureMountLogs() {
        mountLogger = (Logger) LoggerFactory.getLogger(JaxRsRouterMount.class);
        previousLevel = mountLogger.getLevel();
        mountLogger.setLevel(Level.WARN);
        appender = new ListAppender<>();
        appender.start();
        mountLogger.addAppender(appender);
    }

    @AfterEach
    void releaseMountLogs() {
        mountLogger.detachAppender(appender);
        appender.stop();
        mountLogger.setLevel(previousLevel);
    }

    @Test
    @DisplayName("Each inactive mount warns exactly once when file verifiers are bound")
    void warnOncePerMountWhenStrategyWontRunVerifiers(Vertx vertx) throws Exception {
        RequestValidationStrategy strategy = new NoneValidationStrategy();
        Set<FileContentVerifier> verifiers = Set.of(new AcceptingVerifier(), new AcceptingVerifier());
        JaxRsRouterMount.Factory factory = buildFactory(strategy, verifiers);

        createRouter(factory, "/inactive-a", vertx);
        createRouter(factory, "/inactive-b", vertx);

        assertEquals(
                List.of(
                        "Mount /inactive-a: 2 FileContentVerifier(s) bound but validation strategy 'none' does not run "
                                + "file verifiers — file content verification is INACTIVE for this mount",
                        "Mount /inactive-b: 2 FileContentVerifier(s) bound but validation strategy 'none' does not run "
                                + "file verifiers — file content verification is INACTIVE for this mount"),
                verifierWarnings());
    }

    @Test
    @DisplayName("A verifier-capable web-validation strategy emits no inactive-verifier warning")
    void noWarnUnderWebValidation(Vertx vertx) throws Exception {
        RequestValidationStrategy strategy = new VerifierCapableWebValidationStrategy();
        Set<FileContentVerifier> verifiers = Set.of(new AcceptingVerifier());
        JaxRsRouterMount.Factory factory = buildFactory(strategy, verifiers);

        createRouter(factory, "/active-a", vertx);
        createRouter(factory, "/active-b", vertx);

        assertTrue(verifierWarnings().isEmpty(), "verifier-capable mounts must remain silent");
    }

    private void createRouter(JaxRsRouterMount.Factory factory, String mountPath, Vertx vertx) throws Exception {
        factory.create(mountPath, "openapi.json", Set.of(new PingResource()))
                .createRouter(vertx)
                .toCompletionStage()
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
    }

    private List<String> verifierWarnings() {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .filter(event -> event.getLoggerName().equals(JaxRsRouterMount.class.getName()))
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("FileContentVerifier(s) bound"))
                .toList();
    }

    private static JaxRsRouterMount.Factory buildFactory(
            RequestValidationStrategy strategy, Set<FileContentVerifier> verifiers) {
        DefaultExceptionMapper defaultMapper = RestModule.defaultExceptionMapper();
        ExceptionMapperRegistry registry = new ExceptionMapperRegistry(defaultMapper, Set.of());
        RestExceptionMapper restExceptionMapper = new RestExceptionMapper();
        RestContextResolution restContextResolution = new RestContextResolution(Set.of());
        List<ResponseBodyEncoder> encoders = List.of(new StringBodyEncoder(), new JsonBodyEncoder());
        DefaultResponseSerializer responseSerializer = new DefaultResponseSerializer(List.of(), encoders);
        HttpConfig httpConfig = HttpConfig.builder().build();
        JaxRsConfig jaxRsConfig =
                JaxRsConfig.builder().validationStrategy(strategy.id()).build();

        return new JaxRsRouterMount.Factory(
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                restExceptionMapper,
                registry,
                Set.of(),
                responseSerializer,
                restContextResolution,
                dev.vertique.rest.jaxrs.convert.ConversionContexts.defaultResolver(),
                null,
                Optional.empty(),
                List.of(),
                encoders,
                httpConfig,
                jaxRsConfig,
                new DefaultJsonMapperProfileRegistry(Set.of()),
                JsonConfig.defaults(),
                Optional.empty(),
                Optional.empty(),
                Set.of(),
                Optional.empty(),
                Optional.empty(),
                verifiers,
                Set.of(strategy),
                Optional.empty());
    }

    /** Minimal resource making each mount traverse strategy selection and route registration. */
    @Path("/ping")
    public static class PingResource {

        @GET
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "mountWarningPing")
        public String ping() {
            return "pong";
        }
    }

    /** Strategy double representing web-validation's file-verifier capability. */
    private static final class VerifierCapableWebValidationStrategy implements RequestValidationStrategy {

        @Override
        public String id() {
            return "web-validation";
        }

        @Override
        public boolean runsFileVerifiers() {
            return true;
        }

        @Override
        public Optional<Handler<RoutingContext>> gateFor(
                dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor op, OperationSchemas schemas) {
            return Optional.empty();
        }
    }

    /** Distinct accepting verifier instance used to prove the bound-verifier count. */
    private static final class AcceptingVerifier implements FileContentVerifier {
        @Override
        public Future<FileVerificationResult> verify(io.vertx.ext.web.FileUpload part) {
            return Future.succeededFuture(FileVerificationResult.accepted());
        }
    }
}
