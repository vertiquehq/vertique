// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.rest.client.exception.RestClientException;
import dev.vertique.rest.client.meta.ClientMethodMeta;
import dev.vertique.rest.core.convert.ParamConversionResolver;
import io.vertx.core.Future;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * JDK {@link InvocationHandler} that implements declarative REST client proxies.
 *
 * <p>This handler is a thin adapter: it maps the reflective {@link #invoke} call to a
 * {@link RestRequestBuilder} populated via {@link RestClientRequestFactory}, then delegates the
 * full HTTP dispatch pipeline to {@link DefaultRestClientDispatcher}.
 *
 * <p>The 14-step dispatch pipeline (interceptors, resilience, exception mapping, response
 * decoding) lives entirely in {@link DefaultRestClientDispatcher} and is shared with generated
 * static proxies (Pass B).
 */
final class RestClientProxy implements InvocationHandler {

    private final Map<Method, ClientMethodMeta> methodMetas;
    private final String clientName;

    /** Builds HTTP request components (path, query, headers, body) from metadata and args. */
    private final RestClientRequestFactory requestFactory;

    /** Runs the full HTTP dispatch pipeline shared with generated proxies. */
    private final RestClientDispatcher dispatcher;

    /**
     * Creates a new proxy handler that delegates to a pre-built dispatcher.
     *
     * <p>This constructor is used by {@link RestClientBuilder#build(Class)} when the dispatcher
     * is created externally (e.g., so it can be shared with a generated proxy on a first-try
     * basis before falling back to this reflective path).
     *
     * @param dispatcher the pre-built dispatch pipeline
     * @param methodMetas the pre-scanned method metadata map
     * @param objectMapper the Jackson ObjectMapper for request serialisation
     * @param beanParamAccessorRegistry the registry for resolving bean-param field accessors
     * @param clientName the logical name of the REST client (for diagnostic messages)
     * @param resolver the effective parameter conversion resolver for outbound serialization
     */
    RestClientProxy(
            RestClientDispatcher dispatcher,
            Map<Method, ClientMethodMeta> methodMetas,
            ObjectMapper objectMapper,
            BeanParamAccessorRegistry beanParamAccessorRegistry,
            String clientName,
            ParamConversionResolver resolver) {
        this.dispatcher = dispatcher;
        this.methodMetas = methodMetas;
        this.clientName = clientName;
        this.requestFactory = new RestClientRequestFactory(objectMapper, beanParamAccessorRegistry, resolver);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        if (method.getDeclaringClass() == Object.class) {
            return handleObjectMethod(proxy, method, args);
        }

        ClientMethodMeta meta = methodMetas.get(method);
        if (meta == null) {
            return Future.failedFuture(new RestClientException("No metadata found for method: " + method.getName()));
        }

        Object[] effectiveArgs = args != null ? args : new Object[0];
        try {
            RestRequestBuilder request = requestFactory.buildRequestBuilder(meta, effectiveArgs);
            return dispatcher.send(request, meta);
        } catch (Exception e) {
            return Future.failedFuture(new RestClientException(
                    "Failed to build request for " + meta.methodMetadata().name(), e));
        }
    }

    // --- Utilities ---

    /**
     * Handles {@link Object} methods (equals, hashCode, toString) on the proxy.
     *
     * @param proxy the proxy instance
     * @param method the Object method being invoked
     * @param args the method arguments
     * @return the result of the Object method
     */
    private Object handleObjectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "RestClientProxy[" + clientName + "]";
            default ->
                throw new UnsupportedOperationException(
                        "Unsupported Object method on REST client proxy: " + method.getName());
        };
    }
}
