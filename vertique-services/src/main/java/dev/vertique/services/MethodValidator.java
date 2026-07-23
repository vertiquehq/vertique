// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import dev.vertique.security.SecurityContext;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates service contract methods and their implementations.
 *
 * <p>Validates overloads, implementation completeness, handler method matching,
 * payload parameter alignment, and return type consistency.
 */
final class MethodValidator {

    private MethodValidator() {}

    /**
     * Detects overloaded method names on the contract interface and records violations.
     *
     * @param contract the contract interface to check
     * @param violations mutable list to collect violations into
     */
    static void detectOverloads(Class<?> contract, List<ServiceRegistrationViolation> violations) {
        Map<String, Integer> methodCounts = new HashMap<>();
        for (Method method : contract.getMethods()) {
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }
            methodCounts.merge(method.getName(), 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : methodCounts.entrySet()) {
            if (entry.getValue() > 1) {
                violations.add(ServiceRegistrationViolation.ofMethod(
                        contract, entry.getKey(), "Overloaded methods are not allowed on contract interfaces"));
            }
        }
    }

    /**
     * Validates that the implementation class provides all methods declared on the contract interface.
     *
     * <p>For the direct-implementation pattern, verifies the exact method signatures match.
     * For the handler pattern, delegates to {@link #validateHandlerMethods} which allows
     * additional framework-injectable parameters.
     *
     * @param impl the service implementation instance
     * @param contract the contract interface
     * @param violations mutable list to collect violations into
     */
    static void validateImplMethods(Object impl, Class<?> contract, List<ServiceRegistrationViolation> violations) {
        Class<?> implClass = impl.getClass();

        if (ContractDiscovery.isHandlerPattern(impl)) {
            validateHandlerMethods(implClass, contract, violations);
        } else {
            // Direct-impl: exact-signature validation
            for (Method contractMethod : contract.getMethods()) {
                if (contractMethod.getDeclaringClass() == Object.class) {
                    continue;
                }
                try {
                    implClass.getMethod(contractMethod.getName(), contractMethod.getParameterTypes());
                } catch (NoSuchMethodException e) {
                    violations.add(ServiceRegistrationViolation.ofMethod(
                            contract,
                            contractMethod.getName(),
                            implClass.getSimpleName() + " does not implement method " + contractMethod.getName()));
                }
            }
        }
    }

    /**
     * Validates that the handler class provides methods matching all contract operations.
     *
     * <p>For each contract method, exactly one handler method of the same name must exist.
     * Payload parameters must match in order and type; extra parameters must be framework-injectable.
     * The full generic return type must match the contract exactly.
     *
     * @param implClass the handler class
     * @param contract the contract interface
     * @param violations mutable list to collect violations into
     */
    static void validateHandlerMethods(
            Class<?> implClass, Class<?> contract, List<ServiceRegistrationViolation> violations) {
        for (Method contractMethod : contract.getMethods()) {
            if (contractMethod.getDeclaringClass() == Object.class) {
                continue;
            }

            List<Method> candidates = findHandlerMethodCandidates(implClass, contractMethod.getName());

            if (candidates.isEmpty()) {
                violations.add(ServiceRegistrationViolation.ofMethod(
                        contract,
                        contractMethod.getName(),
                        implClass.getSimpleName() + " does not provide handler method for "
                                + contractMethod.getName()));
                continue;
            }

            if (candidates.size() > 1) {
                violations.add(ServiceRegistrationViolation.ofMethod(
                        contract,
                        contractMethod.getName(),
                        implClass.getSimpleName() + " has " + candidates.size()
                                + " overloaded methods named '" + contractMethod.getName()
                                + "' — exactly one handler method per operation is required"));
                continue;
            }

            Method handlerMethod = candidates.get(0);

            // Validate payload parameters match in order and type
            List<Class<?>> contractPayloads = extractPayloadTypes(contractMethod);
            List<Class<?>> handlerPayloads = extractPayloadTypes(handlerMethod);
            if (!contractPayloads.equals(handlerPayloads)) {
                violations.add(ServiceRegistrationViolation.ofMethod(
                        contract,
                        contractMethod.getName(),
                        "Handler method payload parameters do not match contract: expected " + contractPayloads
                                + " but found " + handlerPayloads));
            }

            // Validate full generic return type match
            validateHandlerReturnType(contract, contractMethod, handlerMethod, violations);
        }
    }

    /**
     * Validates that the handler method's generic return type matches the contract method's.
     *
     * <p>Since handler classes do not implement the contract interface, the compiler cannot enforce
     * return type consistency. This validation catches mismatches like {@code Future<String>} vs
     * {@code Future<Integer>}.
     *
     * @param contract the contract interface (for violation reporting)
     * @param contractMethod the contract method
     * @param handlerMethod the handler method
     * @param violations mutable list to collect violations into
     */
    static void validateHandlerReturnType(
            Class<?> contract,
            Method contractMethod,
            Method handlerMethod,
            List<ServiceRegistrationViolation> violations) {
        Type contractReturn = contractMethod.getGenericReturnType();
        Type handlerReturn = handlerMethod.getGenericReturnType();
        if (!contractReturn.equals(handlerReturn)) {
            violations.add(ServiceRegistrationViolation.ofMethod(
                    contract,
                    contractMethod.getName(),
                    "Handler method return type mismatch: contract declares " + contractReturn
                            + " but handler declares " + handlerReturn));
        }
    }

    /**
     * Finds all public methods with the given name on the handler class, excluding Object methods.
     *
     * @param implClass the handler class
     * @param methodName the method name to search for
     * @return list of matching methods (may be empty)
     */
    static List<Method> findHandlerMethodCandidates(Class<?> implClass, String methodName) {
        List<Method> candidates = new ArrayList<>();
        for (Method m : implClass.getMethods()) {
            if (m.getDeclaringClass() == Object.class) {
                continue;
            }
            if (m.getName().equals(methodName)) {
                candidates.add(m);
            }
        }
        return candidates;
    }

    /**
     * Finds the single handler method matching a contract method by name.
     * Returns {@code null} if no method or multiple methods are found (violations reported
     * separately by {@link #validateHandlerMethods}).
     *
     * @param implClass the handler class
     * @param contractMethod the contract method to match
     * @return the handler method, or {@code null} if not uniquely resolvable
     */
    static Method findHandlerMethod(Class<?> implClass, Method contractMethod) {
        List<Method> candidates = findHandlerMethodCandidates(implClass, contractMethod.getName());
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    /**
     * Extracts payload (non-injectable) parameter types from a method, in declaration order.
     *
     * @param method the method to inspect
     * @return ordered list of payload parameter types
     */
    static List<Class<?>> extractPayloadTypes(Method method) {
        List<Class<?>> payloads = new ArrayList<>();
        for (Class<?> paramType : method.getParameterTypes()) {
            if (!isInjectableType(paramType)) {
                payloads.add(paramType);
            }
        }
        return payloads;
    }

    /**
     * Returns {@code true} if the parameter type is a framework-injectable type.
     *
     * <p>Injectable types include {@link SecurityContext} (injected from the dispatch-scoped
     * context local) and types annotated with
     * {@link dev.vertique.core.eventbus.DispatchContextValue} (injected from
     * {@link dev.vertique.core.eventbus.DispatchMetadata#dispatchContext()}).
     *
     * @param type the parameter type to check
     * @return {@code true} if the type is auto-injectable by the framework
     */
    static boolean isInjectableType(Class<?> type) {
        return SecurityContext.class.isAssignableFrom(type)
                || type.isAnnotationPresent(dev.vertique.core.eventbus.DispatchContextValue.class);
    }
}
