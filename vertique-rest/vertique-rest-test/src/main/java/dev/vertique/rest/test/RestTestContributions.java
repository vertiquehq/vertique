// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.test;

import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.rest.core.context.RestContextResolver;
import dev.vertique.rest.core.interceptor.RequestInterceptor;
import dev.vertique.rest.core.middleware.Middleware;
import dev.vertique.rest.core.request.RequestBodyDecoder;
import dev.vertique.rest.core.response.ResponseBodyEncoder;
import dev.vertique.rest.jaxrs.validation.FileContentVerifier;
import jakarta.ws.rs.ext.ExceptionMapper;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * The test-only extensions a consumer adds to the framework REST graph assembled by
 * {@link RestTestFixtureModule}.
 *
 * <p>An instance is bound into a consumer's test {@code @Component} with {@code @BindsInstance}; the
 * fixture module then unions each set into the corresponding framework multibinding. Every component
 * is defensively copied, so an instance is immutable and safe to share across tests.
 *
 * <p><b>Contributions are additive — there is no replacement API, by design.</b> The framework's own
 * encoders, decoders, mappers, and resolvers are always present alongside whatever a test
 * contributes. A test that needs to take over a framework default does so by contributing an
 * extension that <em>out-ranks</em> it: {@link dev.vertique.core.extension.OrderedExtension} sorts
 * lower {@code priority()} first, and framework defaults sit at {@code 999}–{@code 1100}, so a
 * contribution at the default priority of {@code 0} wins. This is the override model
 * {@link ResponseBodyEncoder} documents for production code, and the fixture deliberately offers no
 * second one.
 *
 * <p>Ordering is never decided here. The sets are unordered; the framework's own providers
 * ({@code RestModule.sortedResponseBodyEncoders} and {@code RestModule.sortedRequestBodyDecoders})
 * produce the ordered lists the runtime consumes, exactly as they do in production.
 *
 * <pre>{@code
 * RestTestContributions contributions = RestTestContributions.builder()
 *         .addMiddleware(new RejectEverythingMiddleware())
 *         .addResponseBodyEncoder(new StreamedUploadEncoder()) // priority 900 — out-ranks the defaults
 *         .build();
 * }</pre>
 *
 * @param middlewares          router-level middlewares to add to {@code Set<Middleware>}
 * @param requestInterceptors  request interceptors to add to {@code Set<RequestInterceptor>}
 * @param responseBodyEncoders response body encoders to add to {@code Set<ResponseBodyEncoder>}
 * @param requestBodyDecoders  request body decoders to add to {@code Set<RequestBodyDecoder>}
 * @param exceptionMappers     JAX-RS exception mappers to add to {@code Set<ExceptionMapper<?>>}
 * @param jsonMapperProfiles   JSON mapper profiles to add to {@code Set<JsonMapperProfile>}
 * @param fileContentVerifiers file-content verifiers to add to {@code Set<FileContentVerifier>}
 * @param contextResolvers     REST context resolvers to add to {@code Set<RestContextResolver>}
 */
public record RestTestContributions(
        Set<Middleware> middlewares,
        Set<RequestInterceptor> requestInterceptors,
        Set<ResponseBodyEncoder> responseBodyEncoders,
        Set<RequestBodyDecoder> requestBodyDecoders,
        Set<ExceptionMapper<?>> exceptionMappers,
        Set<JsonMapperProfile> jsonMapperProfiles,
        Set<FileContentVerifier> fileContentVerifiers,
        Set<RestContextResolver> contextResolvers) {

    /** The shared empty instance returned by {@link #none()}. */
    private static final RestTestContributions NONE = builder().build();

    /**
     * Canonical constructor. Defensively copies every set so the record is immutable regardless of
     * what the caller retains a reference to.
     *
     * @throws NullPointerException if any set, or any element of a set, is {@code null}
     */
    public RestTestContributions {
        middlewares = copyOf(middlewares, "middlewares");
        requestInterceptors = copyOf(requestInterceptors, "requestInterceptors");
        responseBodyEncoders = copyOf(responseBodyEncoders, "responseBodyEncoders");
        requestBodyDecoders = copyOf(requestBodyDecoders, "requestBodyDecoders");
        exceptionMappers = copyOf(exceptionMappers, "exceptionMappers");
        jsonMapperProfiles = copyOf(jsonMapperProfiles, "jsonMapperProfiles");
        fileContentVerifiers = copyOf(fileContentVerifiers, "fileContentVerifiers");
        contextResolvers = copyOf(contextResolvers, "contextResolvers");
    }

    /**
     * Returns a new, empty {@link Builder}.
     *
     * @return a fresh builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the contributions instance that adds nothing — the graph is then exactly production's.
     *
     * @return the shared empty contributions instance
     */
    public static RestTestContributions none() {
        return NONE;
    }

    /**
     * Copies a caller-supplied set into an immutable one, naming the component when it is
     * {@code null}.
     *
     * @param <T>       the element type
     * @param values    the caller-supplied set
     * @param component the record component name, used in the failure message
     * @return an immutable copy
     * @throws NullPointerException if {@code values} or any of its elements is {@code null}
     */
    private static <T> Set<T> copyOf(Set<T> values, String component) {
        return Set.copyOf(Objects.requireNonNull(values, component + " must not be null"));
    }

    // --- Builder ---

    /**
     * Accumulates contributions. Not thread-safe; build one per test graph.
     *
     * <p>Each {@code addX} method appends to the corresponding set and returns {@code this}. There is
     * deliberately no {@code replaceX} counterpart — see the {@link RestTestContributions} javadoc.
     */
    public static final class Builder {

        private final Set<Middleware> middlewares = new LinkedHashSet<>();
        private final Set<RequestInterceptor> requestInterceptors = new LinkedHashSet<>();
        private final Set<ResponseBodyEncoder> responseBodyEncoders = new LinkedHashSet<>();
        private final Set<RequestBodyDecoder> requestBodyDecoders = new LinkedHashSet<>();
        private final Set<ExceptionMapper<?>> exceptionMappers = new LinkedHashSet<>();
        private final Set<JsonMapperProfile> jsonMapperProfiles = new LinkedHashSet<>();
        private final Set<FileContentVerifier> fileContentVerifiers = new LinkedHashSet<>();
        private final Set<RestContextResolver> contextResolvers = new LinkedHashSet<>();

        /** Use {@link RestTestContributions#builder()}. */
        private Builder() {}

        /**
         * Adds a router-level middleware.
         *
         * @param middleware the middleware; must not be {@code null}
         * @return this builder
         */
        public Builder addMiddleware(Middleware middleware) {
            middlewares.add(Objects.requireNonNull(middleware, "middleware must not be null"));
            return this;
        }

        /**
         * Adds a request interceptor.
         *
         * @param interceptor the interceptor; must not be {@code null}
         * @return this builder
         */
        public Builder addRequestInterceptor(RequestInterceptor interceptor) {
            requestInterceptors.add(Objects.requireNonNull(interceptor, "interceptor must not be null"));
            return this;
        }

        /**
         * Adds a response body encoder. To take precedence over a framework default, give it a
         * {@code priority()} below {@code 999}.
         *
         * @param encoder the encoder; must not be {@code null}
         * @return this builder
         */
        public Builder addResponseBodyEncoder(ResponseBodyEncoder encoder) {
            responseBodyEncoders.add(Objects.requireNonNull(encoder, "encoder must not be null"));
            return this;
        }

        /**
         * Adds a request body decoder. To take precedence over a framework default, give it a
         * {@code priority()} below {@code 1000}.
         *
         * @param decoder the decoder; must not be {@code null}
         * @return this builder
         */
        public Builder addRequestBodyDecoder(RequestBodyDecoder decoder) {
            requestBodyDecoders.add(Objects.requireNonNull(decoder, "decoder must not be null"));
            return this;
        }

        /**
         * Adds a JAX-RS exception mapper. It joins the framework's {@code DefaultExceptionMapper} in
         * the {@code ExceptionMapperRegistry} rather than replacing it.
         *
         * @param mapper the mapper; must not be {@code null}
         * @return this builder
         */
        public Builder addExceptionMapper(ExceptionMapper<?> mapper) {
            exceptionMappers.add(Objects.requireNonNull(mapper, "mapper must not be null"));
            return this;
        }

        /**
         * Adds a named JSON mapper profile resolvable through {@code @JsonProfile} and
         * {@code jaxrs.jsonProfile}.
         *
         * @param profile the profile; must not be {@code null}
         * @return this builder
         */
        public Builder addJsonMapperProfile(JsonMapperProfile profile) {
            jsonMapperProfiles.add(Objects.requireNonNull(profile, "profile must not be null"));
            return this;
        }

        /**
         * Adds a file-content verifier. Verifiers only run under a validation strategy that declares
         * {@code runsFileVerifiers()}.
         *
         * @param verifier the verifier; must not be {@code null}
         * @return this builder
         */
        public Builder addFileContentVerifier(FileContentVerifier verifier) {
            fileContentVerifiers.add(Objects.requireNonNull(verifier, "verifier must not be null"));
            return this;
        }

        /**
         * Adds a REST context resolver. It joins the framework's built-in resolvers; at the default
         * priority of {@code 0} it runs ahead of all of them.
         *
         * @param resolver the resolver; must not be {@code null}
         * @return this builder
         */
        public Builder addContextResolver(RestContextResolver resolver) {
            contextResolvers.add(Objects.requireNonNull(resolver, "resolver must not be null"));
            return this;
        }

        /**
         * Builds an immutable snapshot of everything added so far. The builder stays usable
         * afterwards.
         *
         * @return the contributions
         */
        public RestTestContributions build() {
            return new RestTestContributions(
                    middlewares,
                    requestInterceptors,
                    responseBodyEncoders,
                    requestBodyDecoders,
                    exceptionMappers,
                    jsonMapperProfiles,
                    fileContentVerifiers,
                    contextResolvers);
        }
    }
}
