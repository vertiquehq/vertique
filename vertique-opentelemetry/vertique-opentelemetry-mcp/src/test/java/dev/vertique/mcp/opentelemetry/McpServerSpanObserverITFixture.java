// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.opentelemetry;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.context.ContextRuntimeModule;
import dev.vertique.core.VertxModule;
import dev.vertique.correlation.CorrelationContextModule;
import dev.vertique.mcp.server.McpServerConfig;
import dev.vertique.mcp.server.McpServerModule;
import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.mcp.tool.McpCancellationSignal;
import dev.vertique.mcp.tool.McpPreparedToolCall;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolAnnotations;
import dev.vertique.mcp.tool.McpToolDescriptor;
import dev.vertique.mcp.tool.McpToolInvoker;
import dev.vertique.mcp.tool.McpToolResult;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.core.security.RouteAuthHandler;
import dev.vertique.rest.security.AuthModule;
import dev.vertique.rest.security.RestAuthenticationEvidence;
import dev.vertique.rest.security.SecurityModule;
import dev.vertique.security.AuthenticationEvidence;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.verification.CustomVerificationSource;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxBuilder;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.impl.UserContextInternal;
import io.vertx.tracing.opentelemetry.OpenTelemetryOptions;
import io.vertx.tracing.opentelemetry.OpenTelemetryTracingFactory;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Framework wiring for {@code McpServerSpanObserverIT} (T022 TP-002): builds a real, traced Vert.x
 * instance with an in-memory span exporter, composes a real port-0 MCP server through the exact
 * Dagger modules an application installs ({@code McpServerModule} + {@link McpOpenTelemetryModule}
 * over the real REST-security identity stack), and exposes the one bearer-authenticated fixture tool
 * the test calls. Nothing decisive lives here.
 */
final class McpServerSpanObserverITFixture {

    static final String TOOL_NAME = "otel.observer.echo";
    static final String LOOPBACK = "127.0.0.1";
    static final String REQUEST_PATH = "/mcp/";
    static final String VALID_BEARER = "Bearer valid-alice";

    private McpServerSpanObserverITFixture() {}

    /**
     * Builds an {@link OpenTelemetrySdk} with {@link Sampler#alwaysOn()} and W3C propagation, wires it
     * into a new Vert.x instance, starts a real port-0 MCP server on it (bound and connected only on
     * {@value #LOOPBACK}), and returns everything the test owns for cleanup.
     */
    static Started start() throws Exception {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        VertxOptions options = new VertxOptions().setTracingOptions(new OpenTelemetryOptions());
        VertxBuilder builder = Vertx.builder().with(options).withTracer(new OpenTelemetryTracingFactory(sdk));
        Vertx vertx = builder.build();

        TestComponent component = DaggerMcpServerSpanObserverITFixture_TestComponent.builder()
                .vertxModule(new VertxModule(vertx, new JsonObject()))
                .build();

        Router router = Router.router(vertx);
        router.route().handler(new RequestContextLifecycle());
        for (RouterMount mount : component.routerMounts()) {
            router.route(mount.mountPath()).subRouter(await(mount.createRouter(vertx)));
        }
        HttpServer server =
                await(vertx.createHttpServer().requestHandler(router).listen(0, LOOPBACK));
        return new Started(server, server.actualPort(), vertx, sdk, exporter);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    /** Everything the test owns and must close, plus the bound loopback port to call. */
    record Started(HttpServer server, int port, Vertx vertx, OpenTelemetrySdk sdk, InMemorySpanExporter exporter) {}

    // --- Dagger composition ---

    /**
     * The exact module set an application installs to expose MCP with OpenTelemetry enrichment over
     * the real REST-security identity stack, plus this fixture's own tool and bearer-auth handler.
     */
    @Singleton
    @Component(
            modules = {
                VertxModule.class,
                ContextRuntimeModule.class,
                CorrelationContextModule.class,
                SecurityModule.class,
                AuthModule.class,
                McpServerModule.class,
                McpOpenTelemetryModule.class,
                FixtureModule.class
            })
    interface TestComponent {
        Set<RouterMount> routerMounts();

        @Component.Builder
        interface Builder {
            Builder vertxModule(VertxModule vertxModule);

            TestComponent build();
        }
    }

    /** Supplies the bindings only this fixture needs: config, HTTP timeouts, tool, and bearer auth. */
    @Module
    static final class FixtureModule {

        @Provides
        @Singleton
        static McpServerConfig config() {
            return McpServerConfig.builder()
                    .enabled(true)
                    .serverName("vertique-otel-test")
                    .serverVersion("1.0")
                    .authenticationScheme("bearer")
                    .build();
        }

        @Provides
        @Singleton
        static HttpConfig httpConfig() {
            return HttpConfig.builder().idleTimeoutSeconds(60).build();
        }

        @Provides
        @IntoSet
        static McpToolInvoker echoTool() {
            return new EchoToolInvoker();
        }

        @Provides
        @IntoSet
        static RouteAuthHandler bearerAuthHandler() {
            return new BearerRouteAuthHandler();
        }
    }

    /** The one fixture tool: returns a fixed structured result satisfying its own output schema. */
    private static final class EchoToolInvoker implements McpToolInvoker {
        private final McpToolDescriptor descriptor = new McpToolDescriptor(
                TOOL_NAME,
                null,
                "T022 TP-002 fixture tool.",
                new McpToolAnnotations(true, false, true, false),
                "{\"type\":\"object\"}",
                "{\"type\":\"object\",\"properties\":{\"status\":{\"type\":\"string\"}},\"required\":[\"status\"]}",
                new McpToolAccess(McpAccessMode.PERMIT_ALL, java.util.List.of(), null));

        @Override
        public McpToolDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public McpPreparedToolCall prepare(Map<String, Object> arguments, McpCancellationSignal cancellation) {
            return new McpPreparedToolCall() {
                @Override
                public Map<String, Object> normalizedArguments() {
                    return Map.of();
                }

                @Override
                public Future<McpToolResult<?>> invoke() {
                    return Future.succeededFuture(McpToolResult.structured(Map.of("status", "ok")));
                }
            };
        }
    }

    /**
     * A hand-written optional-authentication bearer scheme: the one valid bearer authenticates
     * principal {@code alice}; anything else fails 401 without calling {@code next()}. Duplicated
     * per-IT-file, matching every sibling MCP IT's own {@code BearerRouteAuthHandler}.
     */
    private static final class BearerRouteAuthHandler implements RouteAuthHandler {

        @Override
        public String schemeName() {
            return "bearer";
        }

        @Override
        public io.vertx.core.Handler<RoutingContext> createHandler() {
            return context -> {
                if (!VALID_BEARER.equals(context.request().getHeader("Authorization"))) {
                    context.fail(401);
                    return;
                }
                authenticateAsAlice(context);
            };
        }

        @Override
        public Optional<io.vertx.core.Handler<RoutingContext>> createOptionalHandler() {
            return Optional.of(context -> {
                String credential = context.request().getHeader("Authorization");
                if (credential == null) {
                    context.next();
                    return;
                }
                if (!VALID_BEARER.equals(credential)) {
                    context.fail(401);
                    return;
                }
                authenticateAsAlice(context);
            });
        }

        private static void authenticateAsAlice(RoutingContext context) {
            RestAuthenticationEvidence.append(
                    context,
                    new AuthenticationEvidence(
                            DefaultAuthMethod.jwt(),
                            Optional.of("alice"),
                            Instant.now(),
                            Optional.empty(),
                            new CustomVerificationSource("test", Map.of()),
                            Map.of("sub", "alice")));
            ((UserContextInternal) context.userContext()).setUser(User.create(new JsonObject().put("sub", "alice")));
            context.next();
        }
    }
}
