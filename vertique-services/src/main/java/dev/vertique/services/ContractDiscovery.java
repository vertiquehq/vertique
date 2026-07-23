// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import dev.vertique.core.util.TypeResolver;
import java.util.ArrayList;
import java.util.List;

/**
 * Discovers the {@link ServiceContract}-annotated contract interface for a service implementation.
 *
 * <p>Supports the direct-implementation pattern (class implements the contract interface)
 * and the handler pattern ({@link ServiceHandler}{@code <C>}).
 */
final class ContractDiscovery {

    private ContractDiscovery() {}

    /**
     * Finds the single {@link ServiceContract}-annotated contract interface for the given instance.
     *
     * <p>Checks the handler pattern ({@link ServiceHandler}{@code <C>}) first. If the instance
     * implements {@link ServiceHandler}, the type parameter {@code C} is resolved and validated.
     * Otherwise, falls back to scanning the class hierarchy for a directly implemented
     * {@link ServiceContract}-annotated interface.
     *
     * <p>Adds a violation and returns {@code null} if the contract cannot be found or is ambiguous.
     *
     * @param impl the service implementation instance
     * @param violations mutable list to collect violations into
     * @return the contract interface, or {@code null} if not found or ambiguous
     */
    static Class<?> findContract(Object impl, List<ServiceRegistrationViolation> violations) {
        Class<?> implClass = impl.getClass();

        // Check if impl uses the ServiceHandler<C> pattern
        if (impl instanceof ServiceHandler<?>) {
            Class<?> handlerContract = TypeResolver.resolveTypeArgument(implClass, ServiceHandler.class);
            if (handlerContract == null) {
                violations.add(ServiceRegistrationViolation.ofImpl(implClass.getName()
                        + " implements ServiceHandler but the contract type parameter"
                        + " could not be resolved (raw type or unresolved type variable)"));
                return null;
            }
            if (!handlerContract.isAnnotationPresent(ServiceContract.class)) {
                violations.add(ServiceRegistrationViolation.ofImpl(implClass.getName() + " implements ServiceHandler<"
                        + handlerContract.getSimpleName()
                        + "> but " + handlerContract.getSimpleName()
                        + " is not annotated with @ServiceContract"));
                return null;
            }
            // Reject double-pattern: handler also directly implements the contract interface
            if (handlerContract.isAssignableFrom(implClass)) {
                violations.add(
                        ServiceRegistrationViolation.ofImpl(implClass.getName() + " implements both ServiceHandler<"
                                + handlerContract.getSimpleName() + "> and "
                                + handlerContract.getSimpleName()
                                + " directly — use one pattern, not both"));
                return null;
            }
            // Reject handler that also declares additional @ServiceContract interfaces
            List<Class<?>> additionalContracts = new ArrayList<>();
            for (Class<?> iface : TypeResolver.getAllInterfaces(implClass)) {
                if (iface != handlerContract && iface.isAnnotationPresent(ServiceContract.class)) {
                    additionalContracts.add(iface);
                }
            }
            if (!additionalContracts.isEmpty()) {
                violations.add(ServiceRegistrationViolation.ofImpl(implClass.getName() + " implements ServiceHandler<"
                        + handlerContract.getSimpleName()
                        + "> but also implements additional @ServiceContract interface(s): "
                        + additionalContracts.stream()
                                .map(Class::getSimpleName)
                                .reduce((a, b) -> a + ", " + b)
                                .orElse("")));
                return null;
            }
            return handlerContract;
        }

        // Direct-implementation pattern: scan for @ServiceContract-annotated interfaces
        List<Class<?>> contracts = new ArrayList<>();

        for (Class<?> iface : TypeResolver.getAllInterfaces(implClass)) {
            if (iface.isAnnotationPresent(ServiceContract.class)) {
                contracts.add(iface);
            }
        }

        if (contracts.isEmpty()) {
            violations.add(ServiceRegistrationViolation.ofImpl(
                    implClass.getName() + " does not implement any @ServiceContract-annotated interface"));
            return null;
        }

        if (contracts.size() > 1) {
            violations.add(ServiceRegistrationViolation.ofImpl(
                    implClass.getName() + " implements multiple @ServiceContract interfaces: "
                            + contracts.stream()
                                    .map(Class::getSimpleName)
                                    .reduce((a, b) -> a + ", " + b)
                                    .orElse("")));
            return null;
        }

        return contracts.get(0);
    }

    /**
     * Returns {@code true} if the implementation uses the handler pattern.
     *
     * @param impl the service implementation instance
     * @return {@code true} if the instance implements {@link ServiceHandler}
     */
    static boolean isHandlerPattern(Object impl) {
        return impl instanceof ServiceHandler<?>;
    }
}
