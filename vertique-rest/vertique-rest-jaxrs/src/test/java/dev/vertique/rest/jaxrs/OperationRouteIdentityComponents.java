// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import com.fasterxml.jackson.databind.ObjectMapper;
import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.rest.core.dagger.JaxRsResources;
import dev.vertique.rest.core.events.HttpRequestCompletedListener;
import dev.vertique.rest.core.events.RestRequestCompletedListener;
import dev.vertique.rest.core.middleware.Middleware;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.core.router.OperationHandlerContributor;
import dev.vertique.rest.core.security.SecurityPolicyValidator;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Dagger test component of {@link OperationRouteIdentityIT}'s component harness (T003: TP-001 to
 * TP-004, TP-015 and TP-016), after the {@code application.DeploymentComponents} pattern: one
 * {@code @Singleton} component over {@link RestModule} and this file's {@link SupportModule}, built
 * through a {@code @Component.Factory} that takes the application configuration as a
 * {@code @BindsInstance @VertxConfig JsonObject}, and provisioning {@link HttpVerticle}.
 *
 * <p>{@link RestModule} includes {@code RestCoreModule}, so the verticle carries the production ROOT
 * middlewares, the completion emitter among them, and sorts them itself; correlation ingress and the
 * body limit come from the configuration. {@code application.ApplicationTestSupportModule} is
 * package-private to its own package, so this component carries its own support bindings. It adds
 * no production code and no dependency.
 */
final class OperationRouteIdentityComponents {

    private OperationRouteIdentityComponents() {}

    /** The component harness: {@link RestModule} plus {@link SupportModule}. */
    @Singleton
    @Component(modules = {RestModule.class, SupportModule.class})
    interface RouteIdentityComponent {

        /**
         * Creates a new {@link HttpVerticle}, composing its router mounts again on each call.
         *
         * @return a new verticle instance
         */
        HttpVerticle httpVerticle();

        /** Factory taking the application configuration. */
        @Component.Factory
        interface Factory {

            /**
             * Creates the component bound to the given configuration.
             *
             * @param config the application configuration
             * @return the constructed component
             */
            RouteIdentityComponent create(@BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * The component's support bindings: a {@link ConfigParser} over a private Jackson mapper, the
     * unsecured {@link SecurityPolicyValidator} stand-in {@code JaxRsRouterMount.Factory} accepts,
     * {@link OperationRouteIdentityIT.IdentityResource} as the one {@code @JaxRsResources} resource, and
     * the IT's fixtures, each contributed {@code @IntoSet} and writing into the IT's static captures:
     *
     * <ul>
     *   <li>the {@link OperationRouteIdentityIT.CredentialScheme} security scheme;</li>
     *   <li>the {@link OperationRouteIdentityIT.DescriptorCapture} contributor and the
     *       {@link OperationRouteIdentityIT.DenyingAuthorization} stand-in (TP-001's 403);</li>
     *   <li>the capturing listeners {@link OperationRouteIdentityIT.RestEventCapture},
     *       {@link OperationRouteIdentityIT.OperationIdentityReader} and
     *       {@link OperationRouteIdentityIT.HttpEventCapture};</li>
     *   <li>the four ROOT test middlewares: {@link OperationRouteIdentityIT.CaseBarrier},
     *       {@link OperationRouteIdentityIT.RootRejecter}, {@link OperationRouteIdentityIT.HolderRemover}
     *       and {@link OperationRouteIdentityIT.ShortCircuit}.</li>
     * </ul>
     */
    @Module
    static final class SupportModule {

        private SupportModule() {}

        /**
         * Provides the unsecured {@link SecurityPolicyValidator} stand-in. {@code null} is a legal
         * value here: {@code JaxRsRouterMount.Factory}'s constructor parameter is {@code @Nullable}.
         *
         * @return {@code null}
         */
        @Provides
        @Nullable
        static SecurityPolicyValidator securityPolicyValidator() {
            return null;
        }

        /**
         * Provides a minimal {@link ConfigParser} backed by a private, isolated Jackson mapper.
         *
         * @return the config parser
         */
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

        /**
         * Contributes the resource under test to the default JAX-RS mount.
         *
         * @return a new {@link OperationRouteIdentityIT.IdentityResource}
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object identityResource() {
            return new OperationRouteIdentityIT.IdentityResource();
        }

        /**
         * Contributes the credential scheme the secured operations declare.
         *
         * @return the scheme
         */
        @Provides
        @IntoSet
        static SecuritySchemeHandler credentialScheme() {
            return new OperationRouteIdentityIT.CredentialScheme();
        }

        /**
         * Contributes the descriptor-capturing contributor, writing into
         * {@link OperationRouteIdentityIT#REGISTERED}.
         *
         * @return the contributor
         */
        @Provides
        @IntoSet
        static OperationHandlerContributor descriptorCapture() {
            return new OperationRouteIdentityIT.DescriptorCapture(OperationRouteIdentityIT.REGISTERED);
        }

        /**
         * Contributes the authorization stand-in behind TP-001's 403 row. Its own descriptor record
         * is not read; {@link OperationRouteIdentityIT.DescriptorCapture}'s is.
         *
         * @return the contributor
         */
        @Provides
        @IntoSet
        static OperationHandlerContributor denyingAuthorization() {
            return new OperationRouteIdentityIT.DenyingAuthorization(new ConcurrentHashMap<>());
        }

        /**
         * Contributes the capturing REST listener, writing into
         * {@link OperationRouteIdentityIT#REST_EVENTS}.
         *
         * @return the listener
         */
        @Provides
        @IntoSet
        static RestRequestCompletedListener restEventCapture() {
            return new OperationRouteIdentityIT.RestEventCapture(OperationRouteIdentityIT.REST_EVENTS);
        }

        /**
         * Contributes TP-004's identity-reading REST listener, writing into
         * {@link OperationRouteIdentityIT#IDENTITY_READS}.
         *
         * @return the listener
         */
        @Provides
        @IntoSet
        static RestRequestCompletedListener operationIdentityReader() {
            return new OperationRouteIdentityIT.OperationIdentityReader(OperationRouteIdentityIT.IDENTITY_READS);
        }

        /**
         * Contributes the capturing HTTP listener, writing into
         * {@link OperationRouteIdentityIT#HTTP_EVENTS}.
         *
         * @return the listener
         */
        @Provides
        @IntoSet
        static HttpRequestCompletedListener httpEventCapture() {
            return new OperationRouteIdentityIT.HttpEventCapture(OperationRouteIdentityIT.HTTP_EVENTS);
        }

        /**
         * Contributes the per-request barrier, ordered before correlation ingress.
         *
         * @return the middleware
         */
        @Provides
        @IntoSet
        static Middleware caseBarrier() {
            return new OperationRouteIdentityIT.CaseBarrier(
                    OperationRouteIdentityIT.BARRIERS, OperationRouteIdentityIT.CaseBarrier.BEFORE_CORRELATION_INGRESS);
        }

        /**
         * Contributes TP-002's 429 rejecter.
         *
         * @return the middleware
         */
        @Provides
        @IntoSet
        static Middleware rootRejecter() {
            return new OperationRouteIdentityIT.RootRejecter();
        }

        /**
         * Contributes TP-003's holder remover, writing into
         * {@link OperationRouteIdentityIT#HOLDER_REMOVALS}.
         *
         * @return the middleware
         */
        @Provides
        @IntoSet
        static Middleware holderRemover() {
            return new OperationRouteIdentityIT.HolderRemover(OperationRouteIdentityIT.HOLDER_REMOVALS);
        }

        /**
         * Contributes TP-015's short circuit.
         *
         * @return the middleware
         */
        @Provides
        @IntoSet
        static Middleware shortCircuit() {
            return new OperationRouteIdentityIT.ShortCircuit(OperationRouteIdentityIT.BARRIERS);
        }
    }
}
