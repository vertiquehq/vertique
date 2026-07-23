// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.core.resilience.ResilienceAnnotations;
import dev.vertique.core.util.AnnotationResolver;
import dev.vertique.services.dispatch.ServiceMethodDescriptor;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamSource;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.ThreadingModel;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builder API for creating {@link ServiceContractRegistry.ContractEntry} records.
 *
 * <p>{@link ServiceContractContributor} implementations use this builder to describe
 * service endpoints without constructing raw {@link ServiceMethodMeta} by hand. The
 * builder centralizes validation and isolates contributors from internal metadata types.
 *
 * <p>For service contracts, leave {@code namespace} and {@code name} set — the builder derives
 * addresses and stable target ids via {@link ServiceAddressing}. For non-service entries
 * (e.g., delayed-job contributors), call {@link OperationBuilder#address(String)} to set
 * the exact event bus address directly, bypassing {@link ServiceAddressing}; the stable
 * target id will be {@code null} for such operations.
 *
 * <pre>{@code
 * ContractEntry<?> entry = ServiceContractEntries.deployable()
 *     .contract(MyExecutor.class)
 *     .serviceInstance(executor)
 *     .namespace("job").name("my-handler")
 *     .operation("execute")
 *         .method(executeMethod)
 *         .payloadType(MyPayload.class)
 *         .returnType(Void.class)
 *         .param("payload", ParamSource.PAYLOAD, MyPayload.class)
 *         .param("ctx", ParamSource.DISPATCH_CONTEXT, JobContext.class)
 *         .done()
 *     .deploymentOptions(config, "services", "job", "my-handler")
 *     .build();
 * }</pre>
 */
public final class ServiceContractEntries {

    private ServiceContractEntries() {}

    /**
     * Starts building a deployable contract entry.
     *
     * @return a new entry builder
     */
    public static EntryBuilder deployable() {
        return new EntryBuilder();
    }

    // --- EntryBuilder ---

    /**
     * Builder for a single {@link ServiceContractRegistry.ContractEntry}.
     */
    public static final class EntryBuilder {

        private Class<?> contract;
        private Object serviceInstance;
        private String namespace;
        private String name;
        private final Map<String, OperationBuilder> operations = new LinkedHashMap<>();
        private DeploymentOptions deploymentOptions;

        private EntryBuilder() {}

        /**
         * Sets the contract key class. For contributor-backed entries this is typically
         * the implementation class (not a shared interface).
         *
         * @param contract the contract key class
         * @return this builder
         */
        public EntryBuilder contract(Class<?> contract) {
            this.contract = contract;
            return this;
        }

        /**
         * Sets the service implementation instance to invoke.
         *
         * @param serviceInstance the instance that handles dispatch calls
         * @return this builder
         */
        public EntryBuilder serviceInstance(Object serviceInstance) {
            this.serviceInstance = serviceInstance;
            return this;
        }

        /**
         * Sets the service namespace segment used in address and stable target id construction.
         *
         * <p>Optional — when absent (null or empty) the namespace segment is omitted from both
         * the address and stable target id. Non-service entries that use
         * {@link OperationBuilder#address(String)} may still set a namespace value to act as a
         * discriminator for framework consumers (e.g., {@code "delayed-job"} for the
         * delayed-job contributor).
         *
         * @param namespace the service namespace (e.g. {@code "integration"}, {@code "delayed-job"})
         * @return this builder
         */
        public EntryBuilder namespace(String namespace) {
            this.namespace = namespace;
            return this;
        }

        /**
         * Sets the service name segment (second part of the event bus address).
         *
         * @param name the service name
         * @return this builder
         */
        public EntryBuilder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Begins defining an operation on this entry.
         *
         * @param operationName the operation name (used as the key in the operations map)
         * @return an operation builder; call {@link OperationBuilder#done()} to return to this builder
         */
        public OperationBuilder operation(String operationName) {
            OperationBuilder ob = new OperationBuilder(this, operationName);
            operations.put(operationName, ob);
            return ob;
        }

        /**
         * Sets deployment options derived from hierarchical config at the given path segments.
         *
         * <p>Reads {@code instances} (default {@code 1}) and {@code worker} (default {@code false})
         * from the config subtree reached by traversing {@code pathSegments}.
         *
         * @param config the root configuration
         * @param pathSegments path to the deployment config subtree
         * @return this builder
         */
        public EntryBuilder deploymentOptions(JsonObject config, String... pathSegments) {
            JsonObject section = JsonConfigPaths.navigateObject(config, pathSegments);
            int instances = section.getInteger("instances", 1);
            boolean worker = section.getBoolean("worker", false);
            this.deploymentOptions = new DeploymentOptions().setInstances(instances);
            if (worker) {
                this.deploymentOptions.setThreadingModel(ThreadingModel.WORKER);
            }
            return this;
        }

        /**
         * Sets explicit deployment options.
         *
         * @param deploymentOptions the deployment options to use
         * @return this builder
         */
        public EntryBuilder deploymentOptions(DeploymentOptions deploymentOptions) {
            this.deploymentOptions = deploymentOptions;
            return this;
        }

        /**
         * Builds the contract entry, validating that all required fields are set.
         *
         * <p>Address and stable contract id are derived via {@link ServiceAddressing} unless an
         * operation-level {@link OperationBuilder#address(String)} override was used, in which case
         * the address is taken verbatim and {@code stableContractId} is {@code null}.
         *
         * @return the built contract entry
         * @throws IllegalStateException if required fields are missing or no operations were defined
         */
        public ServiceContractRegistry.ContractEntry<?> build() {
            if (contract == null) {
                throw new IllegalStateException("contract is required");
            }
            if (serviceInstance == null) {
                throw new IllegalStateException("serviceInstance is required");
            }
            if (name == null || name.isBlank()) {
                throw new IllegalStateException("name is required");
            }
            if (operations.isEmpty()) {
                throw new IllegalStateException("at least one operation is required");
            }
            if (deploymentOptions == null) {
                deploymentOptions = new DeploymentOptions();
            }

            // Determine whether any operation uses an explicit address override
            boolean hasExplicitAddress = operations.values().stream().anyMatch(ob -> ob.explicitAddress != null);

            String baseAddress;
            String stableContractId;
            if (hasExplicitAddress) {
                // Non-service entry — no services/ prefix, no stable contract id
                baseAddress = (namespace != null && !namespace.isBlank() ? namespace + "/" : "") + name;
                stableContractId = null;
            } else {
                baseAddress = ServiceAddressing.buildBaseAddress(namespace, name);
                stableContractId = ServiceAddressing.buildStableContractId(namespace, name);
            }

            Map<String, ServiceMethodMeta> metaMap = new LinkedHashMap<>();

            for (Map.Entry<String, OperationBuilder> e : operations.entrySet()) {
                ServiceMethodMeta meta = e.getValue().buildMeta(serviceInstance, namespace, name);
                metaMap.put(e.getKey(), meta);
            }

            return buildEntry(
                    contract,
                    serviceInstance,
                    namespace != null ? namespace : "",
                    name,
                    baseAddress,
                    stableContractId,
                    Map.copyOf(metaMap),
                    deploymentOptions);
        }

        @SuppressWarnings("unchecked")
        private static <T> ServiceContractRegistry.ContractEntry<T> buildEntry(
                Class<?> contract,
                Object serviceInstance,
                String namespace,
                String name,
                String baseAddress,
                @Nullable String stableContractId,
                Map<String, ServiceMethodMeta> operations,
                DeploymentOptions deploymentOptions) {
            return new ServiceContractRegistry.ContractEntry<>(
                    (Class<T>) contract,
                    serviceInstance,
                    namespace,
                    name,
                    baseAddress,
                    stableContractId,
                    operations,
                    deploymentOptions);
        }
    }

    // --- OperationBuilder ---

    /**
     * Builder for a single operation within an entry.
     *
     * <p>Obtain an instance via {@link EntryBuilder#operation(String)} and return to the
     * parent builder via {@link #done()}.
     */
    public static final class OperationBuilder {

        private final EntryBuilder parent;
        private final String operationName;
        private Method method;
        private Class<?> payloadType;
        private Class<?> returnType;
        private final List<ParamMeta> params = new ArrayList<>();
        private boolean oneWay;
        private ResilienceAnnotations resilienceAnnotations;

        /** Explicit address override, bypassing {@link ServiceAddressing}. */
        @Nullable
        private String explicitAddress;

        // --- Optional overrides for handler-pattern support (CG-005) ---

        /**
         * Explicit handler method override. When set, {@link ServiceMethodMeta#handlerMethod()}
         * uses this instead of defaulting to {@code method}. {@code null} means "use method".
         */
        @Nullable
        private Method handlerMethodOverride;

        /**
         * Explicitly accumulated handler params. Non-null only when {@link #handlerParamsExplicit}
         * is {@code true} (i.e. at least one {@link #handlerParam} call was made).
         */
        private List<ParamMeta> handlerParams;

        /**
         * Set to {@code true} the first time {@link #handlerParam} is called, so that an empty
         * explicit list is distinguishable from "not called" (which defaults to {@code params}).
         */
        private boolean handlerParamsExplicit;

        /**
         * Explicit method-annotation list override. {@code null} means "auto-resolve from method".
         */
        @Nullable
        private List<Annotation> methodAnnotationsOverride;

        /**
         * Explicit class-annotation list override. {@code null} means "auto-resolve from
         * {@code serviceInstance.getClass()}".
         */
        @Nullable
        private List<Annotation> classAnnotationsOverride;

        OperationBuilder(EntryBuilder parent, String operationName) {
            this.parent = parent;
            this.operationName = operationName;
        }

        /**
         * Sets the method to invoke reflectively on the service instance.
         *
         * @param method the method handle
         * @return this builder
         */
        public OperationBuilder method(Method method) {
            this.method = method;
            return this;
        }

        /**
         * Sets an explicit event bus address for this operation, bypassing the default
         * {@link ServiceAddressing#buildAddress(String, String, String)} construction.
         *
         * <p>Use this for non-service entries that live outside the {@code services/} namespace
         * (e.g., delayed-job operations at {@code jobs/delayed/{name}/execute}). When an explicit
         * address is set, {@link ServiceMethodMeta#stableTargetId()} is {@code null} for this
         * operation and the operation is excluded from the registry's target index.
         *
         * @param address the exact event bus address to use
         * @return this builder
         */
        public OperationBuilder address(String address) {
            this.explicitAddress = address;
            return this;
        }

        /**
         * Sets the payload parameter type.
         *
         * @param payloadType the payload class, or {@code null} if no payload parameter
         * @return this builder
         */
        public OperationBuilder payloadType(Class<?> payloadType) {
            this.payloadType = payloadType;
            return this;
        }

        /**
         * Sets the unwrapped return type ({@code T} from {@code Future<T>}).
         *
         * @param returnType the return type class
         * @return this builder
         */
        public OperationBuilder returnType(Class<?> returnType) {
            this.returnType = returnType;
            return this;
        }

        /**
         * Adds a parameter to this operation.
         *
         * <p>For {@link ParamSource#DISPATCH_CONTEXT} parameters, the lookup key is derived
         * automatically: {@link dev.vertique.security.SecurityContext} subtypes resolve under
         * {@code SecurityContext.class.getName()} (the canonical FQCN key) and are subtype-checked
         * with {@code param.type().isInstance(...)}; all other types use {@code type.getName()}.
         *
         * @param paramName the parameter name (for diagnostics)
         * @param source the source from which the parameter value is resolved at dispatch time
         * @param type the parameter type
         * @return this builder
         */
        public OperationBuilder param(String paramName, ParamSource source, Class<?> type) {
            String lookupKey = source == ParamSource.DISPATCH_CONTEXT ? deriveLookupKey(type) : null;
            params.add(new ParamMeta(paramName, source, type, lookupKey));
            return this;
        }

        /**
         * Derives the dispatch context map lookup key for the given parameter type.
         *
         * @param type the parameter type
         * @return the lookup key
         */
        private static String deriveLookupKey(Class<?> type) {
            if (dev.vertique.security.SecurityContext.class.isAssignableFrom(type)) {
                return dev.vertique.security.SecurityContext.class.getName();
            }
            return type.getName();
        }

        /**
         * Marks this operation as one-way (fire-and-forget).
         *
         * @return this builder
         */
        public OperationBuilder oneWay() {
            this.oneWay = true;
            return this;
        }

        /**
         * Sets explicit resilience annotations for this operation. If not called,
         * resilience annotations are resolved automatically:
         * <ul>
         *   <li>If {@link #handlerMethod(Method)} was called (handler-pattern): resolves from
         *       the contract method's declaring class ({@code method.getDeclaringClass()}).</li>
         *   <li>Otherwise (direct-impl): resolves from {@code serviceInstance.getClass()}.</li>
         * </ul>
         *
         * @param resilienceAnnotations the resilience annotations to use
         * @return this builder
         */
        public OperationBuilder resilienceAnnotations(ResilienceAnnotations resilienceAnnotations) {
            this.resilienceAnnotations = resilienceAnnotations;
            return this;
        }

        /**
         * Sets the handler method to invoke reflectively on the server side.
         *
         * <p>When set, {@link ServiceMethodMeta#handlerMethod()} uses this descriptor instead of
         * defaulting to {@code method}. Use this for the handler-pattern where the handler class
         * method differs from the contract interface method (e.g. extra {@code SecurityContext}
         * parameter). When not called, defaults to {@code method} (current behavior).
         *
         * @param handlerMethod the handler-side method handle
         * @return this builder
         */
        public OperationBuilder handlerMethod(Method handlerMethod) {
            this.handlerMethodOverride = handlerMethod;
            return this;
        }

        /**
         * Adds a handler-side parameter to this operation.
         *
         * <p>Repeated calls accumulate into a separate handler-params list that is independent of
         * the contract-side {@link #param(String, ParamSource, Class)} list. When this method is
         * never called, {@link ServiceMethodMeta#handlerParams()} defaults to
         * {@link ServiceMethodMeta#params()} (current behavior).
         *
         * <p>Lookup-key derivation for {@link ParamSource#DISPATCH_CONTEXT} follows the same rules
         * as {@link #param(String, ParamSource, Class)}: {@link dev.vertique.security.SecurityContext}
         * subtypes resolve under the canonical {@code SecurityContext.class.getName()} key with an
         * {@code isInstance} subtype filter; all other types use {@code type.getName()}.
         *
         * @param paramName the parameter name (for diagnostics)
         * @param source the source from which the parameter value is resolved at dispatch time
         * @param type the parameter type
         * @return this builder
         */
        public OperationBuilder handlerParam(String paramName, ParamSource source, Class<?> type) {
            if (!handlerParamsExplicit) {
                handlerParams = new ArrayList<>();
                handlerParamsExplicit = true;
            }
            String lookupKey = source == ParamSource.DISPATCH_CONTEXT ? deriveLookupKey(type) : null;
            handlerParams.add(new ParamMeta(paramName, source, type, lookupKey));
            return this;
        }

        /**
         * Sets the method-level annotations for this operation verbatim (immutable copy stored).
         *
         * <p>When set, {@link ServiceMethodMeta#methodAnnotations()} returns this list instead of
         * the default auto-resolution via
         * {@link AnnotationResolver#resolveMethodAnnotations(Method)}. When not called, the default
         * auto-resolution from {@code method} is used (current behavior).
         *
         * <p>Use this when the annotations should be derived from a contract interface method rather
         * than the handler method (e.g. in codegen-driven contributors).
         *
         * @param methodAnnotations the annotations to use verbatim; an immutable copy is stored
         * @return this builder
         */
        public OperationBuilder methodAnnotations(List<Annotation> methodAnnotations) {
            this.methodAnnotationsOverride = List.copyOf(methodAnnotations);
            return this;
        }

        /**
         * Sets the class-level annotations for this operation verbatim (immutable copy stored).
         *
         * <p>When set, {@link ServiceMethodMeta#classAnnotations()} returns this list instead of
         * the default auto-resolution described below. When not called, the default
         * auto-resolution is used:
         * <ul>
         *   <li>If {@link #handlerMethod(Method)} was called (handler-pattern): resolves from
         *       the contract method's declaring class ({@code method.getDeclaringClass()}).</li>
         *   <li>Otherwise (direct-impl): resolves from {@code serviceInstance.getClass()}.</li>
         * </ul>
         *
         * <p>Use this when the annotations must come from a specific class regardless of the
         * handler-vs-direct-impl distinction (e.g., in codegen-driven contributors).
         *
         * @param classAnnotations the annotations to use verbatim; an immutable copy is stored
         * @return this builder
         */
        public OperationBuilder classAnnotations(List<Annotation> classAnnotations) {
            this.classAnnotationsOverride = List.copyOf(classAnnotations);
            return this;
        }

        /**
         * Finishes this operation definition and returns to the parent entry builder.
         *
         * @return the parent {@link EntryBuilder}
         */
        public EntryBuilder done() {
            return parent;
        }

        /**
         * Builds the {@link ServiceMethodMeta} for this operation.
         *
         * <p>When an explicit {@link #address(String)} was set, uses it directly and sets
         * {@code stableTargetId = null}. Otherwise, derives the address and stable target id
         * via {@link ServiceAddressing}.
         *
         * @param serviceInstance the service implementation instance
         * @param namespace the service namespace segment (may be null or blank for no-namespace entries)
         * @param name the service name segment
         * @return the built metadata record
         * @throws IllegalStateException if required fields are missing
         */
        ServiceMethodMeta buildMeta(Object serviceInstance, @Nullable String namespace, String name) {
            if (method == null) {
                throw new IllegalStateException("method is required for operation '" + operationName + "'");
            }
            if (returnType == null) {
                throw new IllegalStateException("returnType is required for operation '" + operationName + "'");
            }

            String address;
            String stableTargetId;
            if (explicitAddress != null) {
                address = explicitAddress;
                stableTargetId = null;
            } else {
                address = ServiceAddressing.buildAddress(namespace, name, operationName);
                stableTargetId = ServiceAddressing.buildStableTargetId(namespace, name, operationName);
            }

            // Prefer explicitly set annotations; fall back to AnnotationResolver auto-resolution.
            // When handlerMethodOverride is set the handler class differs from the contract's
            // declaring class, so we derive class-level annotations from the contract interface
            // (method.getDeclaringClass()) rather than the handler impl (serviceInstance.getClass()).
            // Direct-impl callers (handlerMethodOverride == null) keep the existing behavior.
            Class<?> annotationClass =
                    handlerMethodOverride != null ? method.getDeclaringClass() : serviceInstance.getClass();

            List<Annotation> methodAnnotations = this.methodAnnotationsOverride != null
                    ? this.methodAnnotationsOverride
                    : AnnotationResolver.resolveMethodAnnotations(method);
            List<Annotation> classAnnotations = this.classAnnotationsOverride != null
                    ? this.classAnnotationsOverride
                    : AnnotationResolver.resolveClassAnnotations(annotationClass);
            List<ParamMeta> immutableParams = List.copyOf(params);

            // Resolve resilience: explicit > auto-resolve from contract annotations > NONE.
            // When handlerMethodOverride is set, resolve from the contract method's declaring class
            // so that contract-level @Timeout/@Retry/@CircuitBreaker are not silently dropped.
            ResilienceAnnotations effectiveResilience = this.resilienceAnnotations != null
                    ? this.resilienceAnnotations
                    : ResilienceAnnotations.resolve(annotationClass, method);

            ServiceMethodDescriptor descriptor = ServiceMethodDescriptor.of(method);

            // Handler descriptor: explicit override or fall back to the contract descriptor
            ServiceMethodDescriptor handlerDescriptor = this.handlerMethodOverride != null
                    ? ServiceMethodDescriptor.of(this.handlerMethodOverride)
                    : descriptor;

            // Handler params: explicit list (when handlerParam was called at least once) or fall back to params
            List<ParamMeta> effectiveHandlerParams =
                    handlerParamsExplicit ? List.copyOf(handlerParams) : immutableParams;

            return new ServiceMethodMeta(
                    serviceInstance,
                    descriptor,
                    handlerDescriptor,
                    address,
                    stableTargetId,
                    namespace != null ? namespace : "",
                    name,
                    operationName,
                    payloadType,
                    returnType,
                    immutableParams,
                    effectiveHandlerParams,
                    effectiveResilience,
                    methodAnnotations,
                    classAnnotations,
                    oneWay);
        }
    }
}
