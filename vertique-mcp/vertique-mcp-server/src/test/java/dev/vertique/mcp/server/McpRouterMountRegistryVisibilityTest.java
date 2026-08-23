// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.exception.ConfigurationException;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the P04 remediation for issue W5 — {@code McpServerConfigValidator}'s registry-visibility
 * rule (§4.5) is reachable from the one real production entry point, {@link McpRouterMount}'s
 * constructor, not merely from a test fixture that calls {@code McpServerConfigValidator} directly.
 *
 * <p>Before the fix, {@code McpServerModule#routerMount} did not depend on {@link McpToolRegistry} at
 * all, so {@code McpRouterMount}'s constructor called only the two-argument {@code validate(config,
 * routeAuthHandlers)} overload — an enabled, no-scheme mount publishing a {@code @RolesAllowed} tool
 * would start successfully and serve that tool exclusively to anonymous callers, since an
 * unconfigured scheme can never satisfy a {@link McpAccessMode#RESTRICTED} requirement.
 *
 * <p>Constructs {@link McpRouterMount} directly — the exact constructor {@code McpServerModule}
 * calls — with a real {@link McpToolRegistry}, mirroring {@link McpToolCallIT}'s fixture wiring.
 */
class McpRouterMountRegistryVisibilityTest {

    private static final String SERVER_NAME = "vertique-test";
    private static final String SERVER_VERSION = "1.0";
    private static final String CLOSED_OBJECT_SCHEMA = "{\"type\":\"object\",\"additionalProperties\":false}";
    private static final McpToolAnnotations ANNOTATIONS = new McpToolAnnotations(true, false, true, false);

    @Test
    @DisplayName("refuses to start an unconfigured-scheme mount publishing a @RolesAllowed tool")
    void shouldRejectUnconfiguredSchemeWithRestrictedTool() {
        McpToolRegistry restrictedRegistry = McpToolRegistry.build(Set.of(new StubToolInvoker(
                descriptor("restricted.tool", new McpToolAccess(McpAccessMode.RESTRICTED, List.of("ops"), null)))));

        assertThatThrownBy(() -> newRouterMount(restrictedRegistry))
                .as("DECISIVE: the real McpRouterMount constructor — the one McpServerModule#routerMount "
                        + "calls — must itself reach the registry-visibility rule, not merely a test fixture "
                        + "calling McpServerConfigValidator directly")
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("mcp.authenticationScheme");
    }

    @Test
    @DisplayName("still starts an unconfigured-scheme mount publishing only public and deny-all tools")
    void shouldAcceptUnconfiguredSchemeWithPublicAndDenyAllToolsOnly() {
        McpToolRegistry openRegistry = McpToolRegistry.build(Set.of(
                new StubToolInvoker(
                        descriptor("public.tool", new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null))),
                new StubToolInvoker(
                        descriptor("closed.tool", new McpToolAccess(McpAccessMode.DENY_ALL, List.of(), null)))));

        assertThatCode(() -> newRouterMount(openRegistry)).doesNotThrowAnyException();
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
                policyEnforcer);
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
                name, null, "W5 registry-visibility fixture tool.", ANNOTATIONS, CLOSED_OBJECT_SCHEMA, null, access);
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
