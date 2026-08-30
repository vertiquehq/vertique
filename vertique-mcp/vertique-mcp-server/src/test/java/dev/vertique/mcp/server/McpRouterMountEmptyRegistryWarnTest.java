// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.mcp.interceptor.McpRequestInterceptor;
import dev.vertique.mcp.interceptor.McpToolInterceptor;
import dev.vertique.mcp.lifecycle.McpRequestCompletedListener;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.mcp.tool.McpCancellationSignal;
import dev.vertique.mcp.tool.McpPreparedToolCall;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolAnnotations;
import dev.vertique.mcp.tool.McpToolDescriptor;
import dev.vertique.mcp.tool.McpToolInvoker;
import dev.vertique.mcp.tool.McpToolResult;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.rest.security.DefaultSecurityClaimMapper;
import dev.vertique.rest.security.IdentityResolutionMiddleware;
import dev.vertique.rest.security.SecurityPolicyEnforcer;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.resolver.SecurityIdentityResolutionContext;
import dev.vertique.security.resolver.SecurityIdentityResolver;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import io.vertx.core.Future;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * R41 — proves {@link McpRouterMount}'s empty-registry WARN: an unconditional, single WARN naming
 * both likely causes (the generated Dagger module not installed; {@code vertique-codegen-mcp} absent
 * from a pre-facade processor path) fires exactly when the composed {@link McpToolRegistry} publishes
 * no tools, and never fires for a registry that publishes at least one tool.
 *
 * <p>Builds the real {@link McpRouterMount} constructor call {@code McpServerModule#routerMount}
 * makes, mirroring {@link McpRouterMountRegistryVisibilityTest}'s fixture wiring.
 */
class McpRouterMountEmptyRegistryWarnTest {

    private static final String SERVER_NAME = "vertique-test";
    private static final String SERVER_VERSION = "1.0";
    private static final String CLOSED_OBJECT_SCHEMA = "{\"type\":\"object\",\"additionalProperties\":false}";
    private static final McpToolAnnotations ANNOTATIONS = new McpToolAnnotations(true, false, true, false);

    private Logger mountLogger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureMountLogs() {
        mountLogger = (Logger) LoggerFactory.getLogger(McpRouterMount.class);
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
    @DisplayName("an empty tool registry logs exactly one WARN naming both likely causes")
    void warnsOnceForEmptyRegistry() {
        McpToolRegistry emptyRegistry = McpToolRegistry.build(Set.of());

        newRouterMount(emptyRegistry);

        assertThat(emptyRegistryWarnings()).hasSize(1);
        assertThat(emptyRegistryWarnings().get(0))
                .contains("GeneratedMcpToolsModule")
                .contains("vertique-codegen-mcp");
    }

    @Test
    @DisplayName("a populated tool registry logs no empty-registry WARN")
    void noWarnForPopulatedRegistry() {
        McpToolRegistry populatedRegistry = McpToolRegistry.build(Set.of(new StubToolInvoker(
                descriptor("public.tool", new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null)))));

        newRouterMount(populatedRegistry);

        assertThat(emptyRegistryWarnings()).isEmpty();
    }

    private List<String> emptyRegistryWarnings() {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .filter(event -> event.getLoggerName().equals(McpRouterMount.class.getName()))
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("tool registry"))
                .toList();
    }

    /** Builds the real {@link McpRouterMount} constructor call {@code McpServerModule#routerMount} makes. */
    private static McpRouterMount newRouterMount(McpToolRegistry registry) {
        McpServerConfig config = McpServerConfig.builder()
                .enabled(true)
                .serverName(SERVER_NAME)
                .serverVersion(SERVER_VERSION)
                .build();
        HttpConfig httpConfig = HttpConfig.builder().idleTimeoutSeconds(30).build();
        RecordingSecurityRuntime securityRuntime = new RecordingSecurityRuntime();
        McpPolicyEnforcer policyEnforcer = new McpPolicyEnforcer(new SecurityPolicyEnforcer(
                Optional.empty(),
                Optional.empty(),
                Set.of(),
                new SecurityEventEmitter(Set.of()),
                NO_OP_CONTEXT_HOLDER,
                securityRuntime,
                Optional.empty()));
        McpRequestDispatcher dispatcher = new McpRequestDispatcher(
                config,
                securityRuntime,
                Set.<McpRequestLifecycleObserver>of(),
                Set.<McpRequestCompletedListener>of(),
                Set.<McpRequestInterceptor>of(),
                Set.<McpToolInterceptor>of(),
                httpConfig,
                registry,
                policyEnforcer,
                NO_OP_CONTEXT_HOLDER,
                new CorrelationContextFactory(Optional.empty()));
        IdentityResolutionMiddleware identityResolution = new IdentityResolutionMiddleware(
                Set.of(new AnonymousOnlyIdentityResolver()),
                Optional.of(new DefaultSecurityClaimMapper()),
                new SecurityEventEmitter(Set.of()),
                securityRuntime,
                NO_OP_CONTEXT_HOLDER);
        return new McpRouterMount(
                config, new McpServerConfigValidator(), dispatcher, Set.of(), identityResolution, httpConfig, registry);
    }

    private static McpToolDescriptor descriptor(String name, McpToolAccess access) {
        return new McpToolDescriptor(
                name, null, "R41 empty-registry-warn fixture tool.", ANNOTATIONS, CLOSED_OBJECT_SCHEMA, null, access);
    }

    /** A zero-argument tool invoker whose handler is never expected to run in this composition-only test. */
    private static final class StubToolInvoker implements McpToolInvoker {
        private final McpToolDescriptor descriptor;

        StubToolInvoker(McpToolDescriptor descriptor) {
            this.descriptor = descriptor;
        }

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
                    throw new AssertionError("this composition-only test never invokes a tool");
                }
            };
        }
    }

    /** Resolves the canonical anonymous identity from empty evidence only; never exercised by decide(). */
    private record AnonymousOnlyIdentityResolver() implements SecurityIdentityResolver {
        @Override
        public Future<Optional<SecurityIdentity>> resolve(SecurityIdentityResolutionContext context) {
            return Future.succeededFuture(Optional.of(SecurityIdentity.anonymous()));
        }
    }

    private static final ContextHolder NO_OP_CONTEXT_HOLDER = new ContextHolder() {
        @Override
        public <T> Optional<T> current(Class<T> type) {
            return Optional.empty();
        }

        @Override
        public <T extends ContextValue> Scope bind(Class<T> type, T value) {
            return () -> {};
        }
    };

    private static final class RecordingSecurityRuntime implements SecurityRuntime {
        private volatile SecurityContext bound;

        @Override
        public SecurityContext current() {
            return bound;
        }

        @Override
        public ContextHolder.Scope bindCurrent(SecurityContext context) {
            bound = context;
            return () -> {};
        }

        @Override
        public jakarta.ws.rs.core.SecurityContext toJaxRs(SecurityContext context, boolean secure) {
            return null;
        }
    }
}
