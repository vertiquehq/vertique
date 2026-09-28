// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.synthetic;

import com.fasterxml.jackson.databind.ObjectMapper;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.rest.core.interceptor.ErrorInterceptor;
import dev.vertique.rest.core.router.OperationHandlerContributor;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.core.routing.SecuritySchemeRegistry;
import dev.vertique.rest.core.security.AuthEnforcementCapability;
import dev.vertique.rest.core.security.SecurityPolicyValidator;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import dev.vertique.rest.jaxrs.JaxRsRouterMount;
import dev.vertique.rest.jaxrs.publication.SyntheticOperations;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dagger fixture module for {@link SyntheticOperationIT}: the stub {@code bearerAuth} scheme, the
 * {@link AuthEnforcementCapability} marker, the four stub contributors (declared here out of
 * priority order: {@code authz100}, {@code probe400}, {@code app60}, {@code probe45}), the counting
 * {@link ErrorInterceptor}, and the four mounts of the IT fixture. Dagger's set multibinding does
 * not preserve declaration order, so the declaration order guarantees nothing: the IT relies on
 * the installer's own priority sorting, and the installer unit proof's insertion-ordered set is the
 * deterministic proof of that sorting. Every collaborator
 * {@code JaxRsRouterMount.Factory} requires but the real security module supplies is stood in for
 * here, mirroring {@code application.ApplicationTestSupportModule} (T002).
 */
@Module
final class SyntheticFixtureModule {

    private SyntheticFixtureModule() {}

    @Provides
    @Singleton
    static TraceRecorder traceRecorder() {
        return new TraceRecorder();
    }

    @Provides
    @Singleton
    static CountingErrorInterceptor countingErrorInterceptor() {
        return new CountingErrorInterceptor();
    }

    @Provides
    @IntoSet
    static ErrorInterceptor errorInterceptor(CountingErrorInterceptor interceptor) {
        return interceptor;
    }

    /**
     * The unsecured {@link SecurityPolicyValidator} stand-in {@code JaxRsRouterMount.Factory}
     * requires. {@code null} is a legal value: the factory's constructor parameter is
     * {@code @Nullable}.
     *
     * @return {@code null}
     */
    @Provides
    @Nullable
    static SecurityPolicyValidator securityPolicyValidator() {
        return null;
    }

    @Provides
    static AuthEnforcementCapability authEnforcementCapability() {
        return AuthEnforcementCapability.INSTANCE;
    }

    @Provides
    @IntoSet
    static SecuritySchemeHandler bearerAuthSchemeHandler() {
        return new SecuritySchemeHandler() {
            @Override
            public String schemeName() {
                return SyntheticDocsMount.SCHEME;
            }

            @Override
            public void configure(SecuritySchemeRegistry registry) {
                registry.authenticationHandler(ctx -> {
                    String user = ctx.request().getHeader(TestAuthentication.USER_HEADER);
                    if (user == null) {
                        ctx.fail(401);
                        return;
                    }
                    List<String> roles =
                            TestAuthentication.parseRoles(ctx.request().getHeader(TestAuthentication.ROLES_HEADER));
                    TestAuthentication.putRoles(ctx, roles);
                    ctx.next();
                });
            }
        };
    }

    // --- Contributors (declared 100, 400, 60, 45; the set does not keep this order, so the
    // installer's priority sorting alone yields the IT's contributor order) ---

    @Provides
    @Singleton
    static Authz100Contributor authz100Contributor(TraceRecorder trace) {
        return new Authz100Contributor(trace);
    }

    @Provides
    @IntoSet
    static OperationHandlerContributor authz100(Authz100Contributor contributor) {
        return contributor;
    }

    @Provides
    @IntoSet
    static OperationHandlerContributor probe400(TraceRecorder trace) {
        return new TracingProbeContributor(400, "probe400", trace);
    }

    @Provides
    @IntoSet
    static OperationHandlerContributor app60(TraceRecorder trace) {
        return new App60Contributor(trace);
    }

    @Provides
    @IntoSet
    static OperationHandlerContributor probe45(TraceRecorder trace) {
        return new TracingProbeContributor(45, "probe45", trace);
    }

    // --- Mounts ---

    @Provides
    @IntoSet
    static RouterMount syntheticDocsMount(SyntheticOperations operations, TraceRecorder trace) {
        return new SyntheticDocsMount(operations, trace);
    }

    @Provides
    @Singleton
    static TwinResource twinResource(TraceRecorder trace) {
        return new TwinResource(trace);
    }

    @Provides
    @IntoSet
    static RouterMount twinMount(JaxRsRouterMount.Factory factory, TwinResource resource) {
        return factory.create("/api/mgmt/*", "openapi.json", Set.of(resource));
    }

    @Provides
    @Singleton
    static LaterApidocsResource laterApidocsResource() {
        return new LaterApidocsResource();
    }

    @Provides
    @IntoSet
    static RouterMount laterApidocsMount(JaxRsRouterMount.Factory factory, LaterApidocsResource resource) {
        return factory.create("/apidocs/*", "openapi.json", Set.of(resource));
    }

    @Provides
    @Singleton
    static CatchAllFailingMount catchAllFailingMount() {
        return new CatchAllFailingMount();
    }

    @Provides
    @IntoSet
    static RouterMount catchAllMount(CatchAllFailingMount mount) {
        return mount;
    }

    // --- Support bindings ---

    @Provides
    static ConfigParser configParser() {
        return new ConfigParser() {
            private final ObjectMapper mapper = new ObjectMapper();

            @Override
            public <T> T parse(JsonObject section, Class<T> type) {
                JsonObject json = section != null ? section : new JsonObject();
                try {
                    return mapper.readValue(json.encode(), type);
                } catch (Exception e) {
                    throw new ConfigurationException("failed to parse test config into " + type.getName(), e);
                }
            }

            @Override
            public <T> List<T> parseKeyedObject(JsonObject section, String identityProp, Class<T> elementType) {
                throw new UnsupportedOperationException("not needed by this suite");
            }

            @Override
            public <T> List<T> parseKeyedObject(
                    JsonObject section, String identityProp, Class<T> elementType, Map<String, Object> fixedProps) {
                throw new UnsupportedOperationException("not needed by this suite");
            }
        };
    }
}
