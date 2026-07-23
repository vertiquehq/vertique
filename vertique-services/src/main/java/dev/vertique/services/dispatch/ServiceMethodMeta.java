// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.dispatch;

import dev.vertique.core.codegen.ParameterMetadata;
import dev.vertique.core.resilience.ResilienceAnnotations;
import jakarta.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;

/**
 * Immutable metadata for a single service operation, derived from the contract interface.
 *
 * <p>Built by {@link ServiceRegistrar} during startup and used by {@link ServiceMethodInvoker}
 * and {@link ServiceClientFactory} at runtime.
 *
 * <p>The distinction between {@code method}/{@code params} and {@code handlerMethod}/{@code handlerParams}
 * supports the {@code ServiceHandler<C>} pattern: {@code method} is always the contract interface method
 * (used for client-side proxy dispatch), while {@code handlerMethod} is the method to invoke reflectively
 * on the server side (may differ from {@code method} in the handler pattern). For the direct-implementation
 * pattern, both pairs are equal.
 *
 * @param serviceInstance the service implementation instance (or handler instance in the handler pattern)
 * @param method descriptor identifying the contract interface method (used for client-side address
 *     resolution and metadata)
 * @param handlerMethod descriptor identifying the method to invoke reflectively on the server side;
 *     equals {@code method} for the direct-implementation pattern
 * @param address full event bus address: {@code services/{namespace}/{name}/{operation}} or
 *     {@code services/{name}/{operation}}
 * @param stableTargetId durable dot-delimited identity for this operation (e.g.
 *     {@code {namespace}.{name}.{operationId}} or {@code {name}.{operationId}}); {@code null} for
 *     non-service entries such as delayed-job contributors where the address is set explicitly
 * @param namespace service namespace from {@link dev.vertique.services.ServiceContract#namespace()}
 * @param name service name from {@link dev.vertique.services.ServiceContract#value()}
 * @param operation operation id from {@link dev.vertique.services.ServiceOperation}
 * @param payloadType the payload parameter type, or {@code null} if no payload parameter
 * @param returnType the unwrapped return type (T from {@code Future<T>})
 * @param params parameter metadata for the contract method (used by client-side proxy for argument extraction)
 * @param handlerParams parameter metadata for the handler method; equals {@code params} for the
 *     direct-implementation pattern
 * @param resilienceAnnotations resilience annotations resolved from the contract interface
 * @param methodAnnotations all annotations resolved from the contract method and its hierarchy,
 *     populated at boot time for use by {@link dev.vertique.services.interceptor.ServiceInterceptor}
 *     callbacks
 * @param classAnnotations all annotations resolved from the contract interface and its hierarchy,
 *     populated at boot time for use by {@link dev.vertique.services.interceptor.ServiceInterceptor}
 *     callbacks
 * @param oneWay {@code true} if the operation uses one-way (fire-and-forget) messaging with no
 *     reply expected
 */
public record ServiceMethodMeta(
        Object serviceInstance,
        ServiceMethodDescriptor method,
        ServiceMethodDescriptor handlerMethod,
        String address,
        @Nullable String stableTargetId,
        String namespace,
        String name,
        String operation,
        Class<?> payloadType,
        Class<?> returnType,
        List<ParamMeta> params,
        List<ParamMeta> handlerParams,
        ResilienceAnnotations resilienceAnnotations,
        List<Annotation> methodAnnotations,
        List<Annotation> classAnnotations,
        boolean oneWay) {

    // --- Factory Methods ---

    /**
     * Creates metadata for the direct-implementation pattern where the handler method is the
     * contract method itself.
     *
     * @param serviceInstance the service implementation instance
     * @param method descriptor identifying the contract interface method (also used for invocation)
     * @param address full event bus address
     * @param stableTargetId durable dot-delimited identity for this operation; may be {@code null}
     *     for non-service entries
     * @param namespace service namespace
     * @param name service name
     * @param operation operation id
     * @param payloadType payload parameter type, or {@code null}
     * @param returnType unwrapped return type
     * @param params parameter metadata
     * @param resilienceAnnotations resolved resilience annotations
     * @param methodAnnotations annotations resolved from the contract method and its hierarchy
     * @param classAnnotations annotations resolved from the contract interface and its hierarchy
     * @param oneWay {@code true} if the operation uses one-way (fire-and-forget) messaging
     * @return metadata where {@code handlerMethod == method} and {@code handlerParams == params}
     */
    public static ServiceMethodMeta ofDirect(
            Object serviceInstance,
            ServiceMethodDescriptor method,
            String address,
            @Nullable String stableTargetId,
            String namespace,
            String name,
            String operation,
            Class<?> payloadType,
            Class<?> returnType,
            List<ParamMeta> params,
            ResilienceAnnotations resilienceAnnotations,
            List<Annotation> methodAnnotations,
            List<Annotation> classAnnotations,
            boolean oneWay) {
        return new ServiceMethodMeta(
                serviceInstance,
                method,
                method,
                address,
                stableTargetId,
                namespace,
                name,
                operation,
                payloadType,
                returnType,
                params,
                params,
                resilienceAnnotations,
                methodAnnotations,
                classAnnotations,
                oneWay);
    }

    // --- Resolution Helpers ---

    /**
     * Resolves the contract method for reflective access or diagnostics.
     *
     * @return the resolved contract {@link Method}
     */
    public Method resolveMethod() {
        return method.resolve();
    }

    /**
     * Resolves the handler method for server-side reflective invocation.
     *
     * @return the resolved handler {@link Method}
     */
    public Method resolveHandlerMethod() {
        return handlerMethod.resolve();
    }

    // --- Nested Types ---

    /**
     * Metadata for a single method parameter.
     *
     * @param name the parameter name (for diagnostics)
     * @param source the source of the parameter value
     * @param type the parameter type
     * @param lookupKey the key used to look up the value from the dispatch context map when
     *     {@code source} is {@link ParamSource#DISPATCH_CONTEXT}; {@code null} for
     *     {@link ParamSource#PAYLOAD}. For {@link dev.vertique.security.SecurityContext}
     *     parameters and subtypes, this is always {@code SecurityContext.class.getName()} — the
     *     key under which {@link dev.vertique.core.eventbus.DispatchEnvelope} stores the SC in its dispatch
     *     context map, regardless of the declared parameter type.
     */
    public record ParamMeta(String name, ParamSource source, Class<?> type, String lookupKey) {

        /**
         * Compact constructor that validates {@code lookupKey} is non-null for
         * {@link ParamSource#DISPATCH_CONTEXT} parameters.
         */
        public ParamMeta {
            if (source == ParamSource.DISPATCH_CONTEXT && lookupKey == null) {
                throw new IllegalArgumentException(
                        "lookupKey must not be null for DISPATCH_CONTEXT parameter: " + name);
            }
        }

        /**
         * Convenience constructor for parameters that do not require a dispatch-context lookup key
         * (e.g. {@link ParamSource#PAYLOAD}).
         *
         * @param name the parameter name (for diagnostics)
         * @param source the source of the parameter value
         * @param type the parameter type
         */
        public ParamMeta(String name, ParamSource source, Class<?> type) {
            this(name, source, type, null);
        }

        /**
         * Exposes this parameter's identity as the neutral {@link ParameterMetadata} SPI view,
         * composing (not replacing) the record's domain-specific shape.
         *
         * <p>The view delegates {@link ParameterMetadata#name() name()} and
         * {@link ParameterMetadata#type() type()} to this record's accessors. The supplied
         * {@code index} is positional — services does not store the parameter index on
         * {@code ParamMeta}, so the caller passes the parameter's position within the enclosing
         * {@code List<ParamMeta>} ({@link ServiceMethodMeta#params()} /
         * {@link ServiceMethodMeta#handlerParams()}).
         *
         * <p>Services tracks no per-parameter annotations, so the view reports every annotation as
         * absent. The generic-type accessor is the SPI's opt-in reflective-accessor group, which is
         * unused for service parameter metadata and therefore throws
         * {@link UnsupportedOperationException}. The domain-only fields {@link #source()} and
         * {@link #lookupKey()} remain directly on {@code ParamMeta} and are not folded into the view.
         *
         * @param index the parameter's positional index within its method's parameter list
         * @return a {@link ParameterMetadata} view over this parameter's name, type, and index
         */
        public ParameterMetadata asParameterMetadata(int index) {
            return new ParamMetaParameterMetadata(this, index);
        }
    }

    /**
     * Reflection-free {@link ParameterMetadata} view over a {@link ParamMeta}, supplying the
     * parameter's index positionally and reporting no annotations.
     *
     * <p>This is the slice-0.4 superset proof that the neutral {@code dev.vertique.core.codegen}
     * SPI composes with the services parameter model: {@link #name()} and {@link #type()} delegate
     * to the wrapped {@code ParamMeta}, while the domain-specific {@code source}/{@code lookupKey}
     * fields stay on {@code ParamMeta} itself. The reflective {@link #genericType()} accessor is
     * outside the reflection-free core and unused here, so it throws.
     *
     * @param param the wrapped parameter metadata supplying name and type
     * @param index the parameter's positional index within its method's parameter list
     */
    private record ParamMetaParameterMetadata(ParamMeta param, int index) implements ParameterMetadata {

        @Override
        public String name() {
            return param.name();
        }

        @Override
        public Class<?> type() {
            return param.type();
        }

        @Override
        public <A extends Annotation> Optional<A> findAnnotation(Class<A> type) {
            return Optional.empty();
        }

        @Override
        public boolean hasAnnotation(Class<? extends Annotation> type) {
            return false;
        }

        @Override
        public Type genericType() {
            throw new UnsupportedOperationException(
                    "genericType(): the reflective-accessor group is not supported for service parameter metadata");
        }
    }

    /**
     * Source of a method parameter value during dispatch.
     */
    public enum ParamSource {
        /** The {@code DispatchEnvelope<T>} payload, deserialized/cast to the parameter type. */
        PAYLOAD,
        /**
         * A typed dispatch context value from {@link dev.vertique.core.eventbus.DispatchMetadata#dispatchContext()},
         * auto-injected during handler invocation. The value is looked up by
         * {@link ParamMeta#lookupKey()}.
         *
         * <p>Both {@link dev.vertique.security.SecurityContext} parameters and
         * {@link dev.vertique.core.eventbus.DispatchContextValue}-annotated context types use this
         * source. For SecurityContext, the lookup key is always
         * {@code SecurityContext.class.getName()} regardless of the declared subtype.
         */
        DISPATCH_CONTEXT
    }
}
