// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import dev.vertique.resilience.annotation.ResilienceAnnotations;
import dev.vertique.services.dispatch.ServiceMethodDescriptor;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamSource;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scans a set of service implementations, validates them against their contract interfaces,
 * and builds {@link ServiceMethodMeta} entries for use at runtime.
 *
 * <p>Called once at startup by {@link ServiceContractRegistry#build(Set)}. All validation
 * violations are collected before failing, enabling complete error reporting in a single pass.
 *
 * <p>Two dispatch patterns are supported:
 * <ul>
 *   <li><strong>Direct-implementation pattern</strong>: the class directly implements the
 *       {@link ServiceContract}-annotated contract interface.</li>
 *   <li><strong>Handler pattern</strong>: the class implements {@link ServiceHandler}{@code <C>}
 *       where {@code C} is the contract interface. Handler methods may declare additional
 *       framework-injectable parameters (e.g., {@link dev.vertique.security.SecurityContext})
 *       beyond those in the contract.</li>
 * </ul>
 *
 * <p>Validation rules enforced:
 * <ul>
 *   <li>Each implementation must have exactly one {@link ServiceContract}-annotated interface
 *       (directly or via {@link ServiceHandler}).</li>
 *   <li>For the handler pattern, the type parameter of {@link ServiceHandler} must be resolvable
 *       to a concrete class and must be annotated with {@link ServiceContract}.</li>
 *   <li>For the handler pattern, the class must not also directly implement the contract interface.</li>
 *   <li>For the handler pattern, the class must not implement additional {@link ServiceContract}-annotated
 *       interfaces beyond the resolved contract.</li>
 *   <li>No duplicate contracts across different implementations.</li>
 *   <li>No overloaded method names on the contract interface.</li>
 *   <li>Each method must return {@code Future<T>} or {@code Future<Void>}.</li>
 *   <li>At most one payload parameter per method.</li>
 *   <li>Allowed parameter types: one payload, {@link dev.vertique.security.SecurityContext}.</li>
 *   <li>{@link dev.vertique.core.eventbus.DispatchEnvelope} parameters are not allowed on contract or handler
 *       method interfaces.</li>
 *   <li>No duplicate operation ids within a contract (including {@link ServiceOperation} values).</li>
 *   <li>No duplicate event bus addresses across all registered contracts.</li>
 *   <li>Implementation must provide all contract methods.</li>
 * </ul>
 */
public class ServiceRegistrar {

    /**
     * Scans service implementations and builds metadata entries grouped by contract interface.
     *
     * @param services set of service implementation instances to scan
     * @return map of contract interface to list of operation metadata entries, in method declaration order
     * @throws ServiceRegistrationException if any validation violations are found
     */
    public Map<Class<?>, List<ServiceMethodMeta>> scan(Set<Object> services) {
        List<ServiceRegistrationViolation> violations = new ArrayList<>();
        Map<Class<?>, List<ServiceMethodMeta>> result = new HashMap<>();
        Map<Class<?>, Object> seenContracts = new HashMap<>();

        for (Object impl : services) {
            Class<?> contract = ContractDiscovery.findContract(impl, violations);
            if (contract == null) {
                continue;
            }

            // Check for duplicate contracts
            if (seenContracts.containsKey(contract)) {
                violations.add(ServiceRegistrationViolation.ofType(
                        contract,
                        "Duplicate contract: both "
                                + seenContracts.get(contract).getClass().getSimpleName()
                                + " and "
                                + impl.getClass().getSimpleName()
                                + " implement "
                                + contract.getSimpleName()));
                continue;
            }
            seenContracts.put(contract, impl);

            ServiceContract annotation = contract.getAnnotation(ServiceContract.class);
            List<ServiceMethodMeta> metas = buildMethodMetas(impl, contract, annotation, violations);
            result.put(contract, metas);
        }

        // Detect duplicate addresses across all contracts
        Map<String, String> seenAddresses = new HashMap<>();
        for (Map.Entry<Class<?>, List<ServiceMethodMeta>> entry : result.entrySet()) {
            for (ServiceMethodMeta meta : entry.getValue()) {
                String label =
                        entry.getKey().getSimpleName() + "." + meta.method().name();
                String previous = seenAddresses.put(meta.address(), label);
                if (previous != null) {
                    violations.add(ServiceRegistrationViolation.ofMethod(
                            entry.getKey(),
                            meta.method().name(),
                            "Duplicate address '" + meta.address() + "': conflicts with " + previous));
                }
            }
        }

        if (!violations.isEmpty()) {
            throw new ServiceRegistrationException(violations);
        }

        return result;
    }

    // --- Method Metadata Building ---

    /**
     * Builds {@link ServiceMethodMeta} for every method on the contract interface.
     *
     * @param impl the service implementation instance
     * @param contract the contract interface
     * @param annotation the {@link ServiceContract} annotation on the contract
     * @param violations mutable list to collect violations into
     * @return list of method metadata, in method declaration order
     */
    private List<ServiceMethodMeta> buildMethodMetas(
            Object impl, Class<?> contract, ServiceContract annotation, List<ServiceRegistrationViolation> violations) {
        // namespace may be blank (absent) — normalized to empty string so downstream consumers
        // see a consistent representation rather than whitespace-only values
        String namespace = annotation.namespace().isBlank() ? "" : annotation.namespace();
        String name = annotation.value();

        if (name.isBlank()) {
            violations.add(ServiceRegistrationViolation.ofType(contract, "@ServiceContract.value() must not be blank"));
            return List.of();
        }

        // Detect overloaded methods
        MethodValidator.detectOverloads(contract, violations);

        // Validate impl provides all contract methods
        MethodValidator.validateImplMethods(impl, contract, violations);

        List<ServiceMethodMeta> metas = new ArrayList<>();
        for (Method method : contract.getMethods()) {
            // Skip methods declared on Object
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }

            ServiceMethodMeta meta = buildMethodMeta(impl, contract, method, namespace, name, violations);
            if (meta != null) {
                metas.add(meta);
            }
        }

        // Detect duplicate resolved operation ids
        Map<String, String> seenOperations = new HashMap<>();
        for (ServiceMethodMeta meta : metas) {
            String previous = seenOperations.put(meta.operation(), meta.method().name());
            if (previous != null) {
                violations.add(ServiceRegistrationViolation.ofMethod(
                        contract,
                        meta.method().name(),
                        "Duplicate operation id '" + meta.operation()
                                + "': both " + previous + " and "
                                + meta.method().name() + " resolve to the same operation"));
            }
        }

        return metas;
    }

    /**
     * Builds a single {@link ServiceMethodMeta} for one contract method.
     *
     * @param impl the service implementation instance
     * @param contract the contract interface
     * @param method the method to process
     * @param namespace the service namespace from {@link ServiceContract#namespace()}
     * @param name the service name from {@link ServiceContract#value()}
     * @param violations mutable list to collect violations into
     * @return the metadata, or {@code null} if validation errors prevent building
     */
    private ServiceMethodMeta buildMethodMeta(
            Object impl,
            Class<?> contract,
            Method method,
            String namespace,
            String name,
            List<ServiceRegistrationViolation> violations) {
        // Resolve operation name (for address) and optional stable operation id (for target resolution).
        // @ServiceOperation is optional — if absent, method name is used for the address segment
        // and no stable target id is generated.
        String stableOperationId;
        try {
            stableOperationId = OperationIdResolver.resolveStableOperationId(method);
        } catch (IllegalStateException e) {
            violations.add(ServiceRegistrationViolation.ofMethod(contract, method.getName(), e.getMessage()));
            return null;
        }
        String operation = stableOperationId != null ? stableOperationId : method.getName();

        String address = ServiceAddressing.buildAddress(namespace, name, operation);
        String stableTargetId = stableOperationId != null
                ? ServiceAddressing.buildStableTargetId(namespace, name, stableOperationId)
                : null;

        // Return type validation
        Class<?> returnType = ReturnTypeResolver.resolve(contract, method, violations);

        // Parameter classification
        List<ParamMeta> params = ParameterClassifier.classifyParams(contract, method, violations);

        // Resilience annotations (method-level overrides type-level)
        ResilienceAnnotations resilienceAnnotations = PolicyResolver.resolveResilience(contract, method);

        // Annotation resolution for interceptor metadata (resolved once at boot)
        List<Annotation> methodAnnotations = PolicyResolver.resolveMethodAnnotations(method);
        List<Annotation> classAnnotations = PolicyResolver.resolveClassAnnotations(contract);

        // One-way detection and validation
        boolean oneWay = method.isAnnotationPresent(OneWay.class);
        if (oneWay && returnType != null && returnType != Void.class) {
            violations.add(ServiceRegistrationViolation.ofMethod(
                    contract,
                    method.getName(),
                    "@OneWay methods must return Future<Void>, found Future<" + returnType.getSimpleName() + ">"));
        }

        // Payload type
        Class<?> payloadType = params.stream()
                .filter(p -> p.source() == ParamSource.PAYLOAD)
                .map(ParamMeta::type)
                .findFirst()
                .orElse(null);

        // If return type resolution failed, returnType may be null — use Void.class as sentinel
        if (returnType == null) {
            returnType = Void.class;
        }

        // Handler pattern: resolve handler method and build handler-level params
        if (ContractDiscovery.isHandlerPattern(impl)) {
            Method handlerMethod = MethodValidator.findHandlerMethod(impl.getClass(), method);
            if (handlerMethod == null) {
                // Validation errors already reported by validateHandlerMethods
                return null;
            }
            List<ParamMeta> handlerParams =
                    ParameterClassifier.classifyHandlerParams(contract, handlerMethod, violations);
            return new ServiceMethodMeta(
                    impl,
                    ServiceMethodDescriptor.of(method),
                    ServiceMethodDescriptor.of(handlerMethod),
                    address,
                    stableTargetId,
                    namespace,
                    name,
                    operation,
                    payloadType,
                    returnType,
                    params,
                    handlerParams,
                    resilienceAnnotations,
                    methodAnnotations,
                    classAnnotations,
                    oneWay);
        }

        // Direct-impl pattern: handler method == contract method
        return ServiceMethodMeta.ofDirect(
                impl,
                ServiceMethodDescriptor.of(method),
                address,
                stableTargetId,
                namespace,
                name,
                operation,
                payloadType,
                returnType,
                params,
                resilienceAnnotations,
                methodAnnotations,
                classAnnotations,
                oneWay);
    }
}
