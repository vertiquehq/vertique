// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client.meta;

import dev.vertique.core.codegen.MethodMetadata;
import dev.vertique.resilience.annotation.ResilienceAnnotations;
import dev.vertique.rest.client.HttpClientResponse;
import io.vertx.core.Expectation;
import io.vertx.core.http.HttpResponseHead;
import jakarta.annotation.Nullable;
import java.lang.reflect.Type;
import java.util.List;

/**
 * Immutable metadata for a single JAX-RS client interface method discovered at startup.
 *
 * <p>Produced by {@link ClientInterfaceScanner} and cached per interface in
 * {@link dev.vertique.rest.client.RestClientFactory}. The proxy invocation handler uses this
 * descriptor to build and dispatch the outgoing HTTP request.
 *
 * <p>This record <em>composes</em> a neutral {@link MethodMetadata} view rather than carrying a live
 * {@link java.lang.reflect.Method}: the method's name, declaring type, and annotations are reached
 * through {@link #methodMetadata()}. The {@code Map<Method, ClientMethodMeta>} produced by
 * {@link ClientInterfaceScanner} remains keyed by the reflected {@code Method}.
 *
 * @param methodMetadata the composed neutral method-metadata view (name, declaring type,
 *     annotations); must not be {@code null}
 * @param httpMethod the HTTP verb string (e.g. {@code "GET"}, {@code "POST"})
 * @param pathTemplate the URI path template combining class-level and method-level {@code @Path}
 *     values (e.g. {@code "/users/{id}"})
 * @param params ordered list of parameter descriptors corresponding to the method's parameters
 * @param responseType the full generic type {@code T} unwrapped from {@code Future<T>}; used for
 *     Jackson deserialization; {@code Void.class} when the method returns {@code Future<Void>}
 * @param rawResponseType the raw class of the response type; for generic responses this is the
 *     erased raw type (e.g. {@code List.class} for {@code Future<List<Item>>})
 * @param returnsVoid {@code true} when the method returns {@code Future<Void>} and no response
 *     body deserialization is performed
 * @param returnsRawResponse {@code true} when the method returns {@code Future<HttpClientResponse>}
 *     and the proxy hands back the raw {@link HttpClientResponse} wrapper
 * @param returnsOptional {@code true} when the method returns {@code Future<Optional<T>>}; the
 *     proxy maps 404 responses to {@code Optional.empty()} and 2xx responses to
 *     {@code Optional.of(body)}
 * @param consumesMediaType the {@code Content-Type} header to send; resolved from {@code @Consumes}
 *     with a default of {@code "application/json"}
 * @param producesMediaType the {@code Accept} header to send; resolved from {@code @Produces}
 *     with a default of {@code "application/json"}
 * @param expectation the per-method response status expectation derived from {@code @ExpectedStatus};
 *     {@code null} means no method-level expectation (fall back to builder default)
 * @param resilience per-method resilience configuration (circuit breaker and timeout overrides);
 *     {@code null} means no method-level resilience settings
 * @param hasUrlParam {@code true} if a parameter annotated with {@code @Url} is present, meaning
 *     the full request URI is supplied per-invocation and no base URL + path resolution occurs
 */
public record ClientMethodMeta(
        MethodMetadata methodMetadata,
        String httpMethod,
        String pathTemplate,
        List<ClientParamMeta> params,
        Type responseType,
        Class<?> rawResponseType,
        boolean returnsVoid,
        boolean returnsRawResponse,
        boolean returnsOptional,
        String consumesMediaType,
        String producesMediaType,
        @Nullable Expectation<HttpResponseHead> expectation,
        ResilienceAnnotations resilienceAnnotations,
        boolean hasUrlParam) {

    /** Returns the canonical resilience declarations resolved for this method. */
    public ResilienceAnnotations resilienceAnnotations() {
        return resilienceAnnotations;
    }

    /**
     * Returns the full generic response type {@code T} unwrapped from {@code Future<T>} (or {@code X}
     * from {@code Future<Optional<X>>}).
     *
     * <p>This is an alias for {@link #responseType()} — the domain {@link Type} retained from the
     * unwrapped future, used for Jackson deserialization. It is a reflection-free domain field, not a
     * reflective accessor on the composed {@link MethodMetadata} view.
     *
     * @return the unwrapped generic response type
     */
    public Type responseGenericType() {
        return responseType;
    }
}
