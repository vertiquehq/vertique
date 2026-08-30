// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.opentelemetry;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.Multibinds;
import dev.vertique.context.ContextRuntimeModule;
import dev.vertique.core.VertxModule;
import dev.vertique.correlation.CorrelationContextModule;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
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
import dev.vertique.rest.security.SecurityModule;
import io.opentelemetry.api.OpenTelemetry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxBuilder;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.tracing.opentelemetry.OpenTelemetryOptions;
import io.vertx.tracing.opentelemetry.OpenTelemetryTracingFactory;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Framework wiring for {@link McpTraceCompositionTest} (T022 TP-003): builds the omitting graph (no
 * {@link McpOpenTelemetryModule} anywhere in its module list) and the installed graph (the exact
 * production module set, contributing {@link McpServerSpanObserver}), each servable over a Vertx
 * instance the test itself wires with either a no-op or a recording OpenTelemetry tracer. Nothing
 * decisive lives here.
 */
final class McpTraceCompositionTestFixture {

    static final String TOOL_NAME = "otel.composition.echo";
    static final String LOOPBACK = "127.0.0.1";
    static final String REQUEST_PATH = "/mcp/";

    private McpTraceCompositionTestFixture() {}

    /** Builds a Vertx instance whose tracer is wired to {@code openTelemetry} (no-op or a real SDK). */
    static Vertx buildVertx(OpenTelemetry openTelemetry) {
        VertxOptions options = new VertxOptions().setTracingOptions(new OpenTelemetryOptions());
        VertxBuilder builder = Vertx.builder().with(options).withTracer(new OpenTelemetryTracingFactory(openTelemetry));
        return builder.build();
    }

    /** Starts the omitting graph's server: no {@link McpOpenTelemetryModule} anywhere in its modules. */
    static Started startOmitting(Vertx vertx) throws Exception {
        OmittingComponent component = DaggerMcpTraceCompositionTestFixture_OmittingComponent.builder()
                .vertxModule(new VertxModule(vertx, new JsonObject()))
                .build();
        return start(vertx, component.routerMounts(), component.lifecycleObservers());
    }

    /** Starts the installed graph's server: the exact production module set including {@link McpOpenTelemetryModule}. */
    static Started startWithObserver(Vertx vertx) throws Exception {
        WithObserverComponent component = DaggerMcpTraceCompositionTestFixture_WithObserverComponent.builder()
                .vertxModule(new VertxModule(vertx, new JsonObject()))
                .build();
        return start(vertx, component.routerMounts(), component.lifecycleObservers());
    }

    private static Started start(
            Vertx vertx, Set<RouterMount> mounts, Set<McpRequestLifecycleObserver> lifecycleObservers)
            throws Exception {
        Router router = Router.router(vertx);
        router.route().handler(new RequestContextLifecycle());
        for (RouterMount mount : mounts) {
            router.route(mount.mountPath()).subRouter(await(mount.createRouter(vertx)));
        }
        HttpServer server =
                await(vertx.createHttpServer().requestHandler(router).listen(0, LOOPBACK));
        return new Started(server, server.actualPort(), lifecycleObservers);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    /** The started server, its bound loopback port, and the graph's resolved observer set. */
    record Started(HttpServer server, int port, Set<McpRequestLifecycleObserver> lifecycleObservers) {}

    // --- Dagger composition ---

    /**
     * Supplies the bindings every graph needs outside {@code McpServerModule}: an enabled,
     * anonymous-only (no {@code authenticationScheme}) configuration, HTTP liveness, and the one
     * fixture tool. No {@link RouteAuthHandler} is contributed — {@code AuthModule}'s own {@code
     * @Multibinds} declaration resolves an empty set, so every call is anonymous.
     */
    @Module
    abstract static class FixtureModule {
        private FixtureModule() {}

        @Multibinds
        abstract Set<RouteAuthHandler> routeAuthHandlers();

        @Provides
        @Singleton
        static McpServerConfig config() {
            return McpServerConfig.builder()
                    .enabled(true)
                    .serverName("vertique-otel-composition-test")
                    .serverVersion("1.0")
                    .build();
        }

        @Provides
        @Singleton
        static HttpConfig httpConfig() {
            return HttpConfig.builder().idleTimeoutSeconds(60).build();
        }

        @Provides
        @dagger.multibindings.IntoSet
        static McpToolInvoker echoTool() {
            return new EchoToolInvoker();
        }
    }

    /**
     * The omitting graph: {@link McpOpenTelemetryModule} is absent from this list by construction —
     * this component's source carries no import of, or reference to, any {@code
     * dev.vertique.mcp.opentelemetry} type other than this file itself and {@link
     * McpTraceCompositionTestFixture}, so it structurally cannot contribute {@link
     * McpServerSpanObserver}.
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
                FixtureModule.class
            })
    interface OmittingComponent {
        Set<RouterMount> routerMounts();

        Set<McpRequestLifecycleObserver> lifecycleObservers();

        @Component.Builder
        interface Builder {
            Builder vertxModule(VertxModule vertxModule);

            OmittingComponent build();
        }
    }

    /** The installed graph: the exact production module set, including {@link McpOpenTelemetryModule}. */
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
    interface WithObserverComponent {
        Set<RouterMount> routerMounts();

        Set<McpRequestLifecycleObserver> lifecycleObservers();

        @Component.Builder
        interface Builder {
            Builder vertxModule(VertxModule vertxModule);

            WithObserverComponent build();
        }
    }

    /** The one fixture tool every graph installs: a fixed structured result satisfying its own output schema. */
    private static final class EchoToolInvoker implements McpToolInvoker {
        private final McpToolDescriptor descriptor = new McpToolDescriptor(
                TOOL_NAME,
                null,
                "T022 TP-003 fixture tool.",
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
}
