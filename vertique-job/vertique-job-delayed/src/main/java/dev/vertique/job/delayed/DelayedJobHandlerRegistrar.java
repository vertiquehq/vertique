// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed;

import dev.vertique.job.delayed.exception.DelayedJobRegistrationException;
import dev.vertique.services.ServiceContractRegistry;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Scans all registered service implementations for methods annotated with
 * {@link DelayedJobHandlerMethod} and builds a handler-name-to-address mapping used by
 * {@link DelayedJobPoller} to know where to dispatch each job type.
 *
 * <p>Called once at startup via {@link #scan()}. Two sources of handler registrations are merged:
 * <ol>
 *   <li><strong>Annotation-based:</strong> service implementation methods annotated with
 *       {@link DelayedJobHandlerMethod}.</li>
 *   <li><strong>Contributor-based:</strong> {@link ServiceContractRegistry} entries with
 *       {@code type == "delayed-job"}, contributed by {@link DelayedJobContractContributor} for typed
 *       {@link DelayedJobExecutor} implementations.</li>
 * </ol>
 *
 * <p>Validates that:
 * <ul>
 *   <li>The {@link DelayedJobHandlerMethod} annotation is on the <em>implementation</em> method
 *       (not the contract interface).</li>
 *   <li>The handler name is not blank.</li>
 *   <li>No duplicate handler names are registered across both sources.</li>
 *   <li>Annotation-based entries correspond to a registered operation in the service contract
 *       registry.</li>
 * </ul>
 *
 * <p>After calling {@link #scan()}, the mapping is available via {@link #handlerAddresses()}.
 */
@Slf4j
public class DelayedJobHandlerRegistrar {

    private final ServiceContractRegistry registry;
    private Map<String, String> handlerAddresses = Collections.emptyMap();

    /**
     * Creates a new registrar.
     *
     * @param registry the service contract registry to scan for {@link DelayedJobHandlerMethod} annotations
     */
    public DelayedJobHandlerRegistrar(ServiceContractRegistry registry) {
        this.registry = registry;
    }

    /**
     * Scans all service implementations for {@link DelayedJobHandlerMethod} annotations and
     * merges contributor-based entries from {@link ServiceContractRegistry} entries with
     * {@code type == "delayed-job"}, building the complete handler-name-to-address mapping.
     *
     * @throws DelayedJobRegistrationException if any validation violations are found
     */
    public void scan() {
        List<String> violations = new ArrayList<>();
        Set<String> registeredNames = new HashSet<>();
        Map<String, String> addresses = new HashMap<>();

        // --- Scan: annotation-based and contributor-based handlers in a single pass ---

        for (ServiceContractRegistry.ContractEntry<?> contractEntry : registry.entries()) {
            if ("delayed-job".equals(contractEntry.namespace())) {
                // Contributor-based: typed DelayedJobExecutor registered via @DelayedJobContract
                String handlerName = contractEntry.name();
                ServiceMethodMeta executeMeta = contractEntry.operations().get("execute");
                if (executeMeta == null) {
                    // No execute operation registered — unexpected for a job contract entry
                    log.warn(
                            "Skipping delayed job handler '{}': contract entry has no 'execute' operation"
                                    + " (contributed by {})",
                            handlerName,
                            contractEntry.contract().getName());
                    continue;
                }

                if (registeredNames.contains(handlerName)) {
                    violations.add("Duplicate delayed job handler name '"
                            + handlerName
                            + "': conflicts between @DelayedJobHandlerMethod and @DelayedJobContract registrations");
                    continue;
                }

                addresses.put(handlerName, executeMeta.address());
                registeredNames.add(handlerName);

                log.info(
                        "Registered typed delayed job handler '{}' → {} [address={}]",
                        handlerName,
                        contractEntry.contract().getSimpleName(),
                        executeMeta.address());
            } else {
                // Annotation-based: service methods annotated with @DelayedJobHandlerMethod

                // Validate that @DelayedJobHandlerMethod is not placed on contract interface methods
                for (Method ifaceMethod : contractEntry.contract().getMethods()) {
                    if (ifaceMethod.getDeclaringClass() == Object.class) {
                        continue;
                    }
                    if (ifaceMethod.isAnnotationPresent(DelayedJobHandlerMethod.class)) {
                        violations.add(
                                "Place @DelayedJobHandlerMethod on the implementation method, not the contract interface: "
                                        + contractEntry.contract().getSimpleName()
                                        + "."
                                        + ifaceMethod.getName()
                                        + "()");
                    }
                }

                // Scan implementation methods
                Object impl = contractEntry.serviceInstance();
                Class<?> implClass = impl.getClass();
                for (Method implMethod : implClass.getDeclaredMethods()) {
                    DelayedJobHandlerMethod annotation = implMethod.getAnnotation(DelayedJobHandlerMethod.class);
                    if (annotation == null) {
                        continue;
                    }

                    String handlerName = annotation.value();
                    if (handlerName == null || handlerName.isBlank()) {
                        violations.add("@DelayedJobHandlerMethod on "
                                + implClass.getSimpleName()
                                + "."
                                + implMethod.getName()
                                + "() has blank handler name");
                        continue;
                    }

                    // Check for duplicate handler names
                    if (registeredNames.contains(handlerName)) {
                        violations.add("Duplicate @DelayedJobHandlerMethod name '"
                                + handlerName
                                + "' found on "
                                + implClass.getSimpleName()
                                + "."
                                + implMethod.getName()
                                + "()");
                        continue;
                    }

                    // Find the service method meta for this implementation method
                    ServiceMethodMeta meta = findMetaForMethod(contractEntry, implMethod);
                    if (meta == null) {
                        violations.add("@DelayedJobHandlerMethod on "
                                + implClass.getSimpleName()
                                + "."
                                + implMethod.getName()
                                + "() does not correspond to any registered operation on contract "
                                + contractEntry.contract().getSimpleName());
                        continue;
                    }

                    addresses.put(handlerName, meta.address());
                    registeredNames.add(handlerName);

                    log.info(
                            "Registered delayed job handler '{}' → {}.{}() [address={}]",
                            handlerName,
                            implClass.getSimpleName(),
                            implMethod.getName(),
                            meta.address());
                }
            }
        }

        if (!violations.isEmpty()) {
            throw new DelayedJobRegistrationException(violations);
        }

        this.handlerAddresses = Collections.unmodifiableMap(addresses);
        log.info("DelayedJobHandlerRegistrar scan complete: {} handler(s) registered", addresses.size());
    }

    /**
     * Returns an unmodifiable mapping of handler names to event bus addresses.
     * Only valid after {@link #scan()} has been called.
     *
     * @return handler name to event bus address mapping
     */
    public Map<String, String> handlerAddresses() {
        return handlerAddresses;
    }

    /**
     * Finds the {@link ServiceMethodMeta} for the given implementation method by name lookup.
     *
     * @param contractEntry the contract entry to search
     * @param implMethod    the implementation method to match
     * @return the matching meta, or {@code null} if not found
     */
    private ServiceMethodMeta findMetaForMethod(
            ServiceContractRegistry.ContractEntry<?> contractEntry, Method implMethod) {
        ServiceMethodMeta meta = contractEntry.operations().get(implMethod.getName());
        if (meta != null) {
            return meta;
        }
        for (ServiceMethodMeta m : contractEntry.operations().values()) {
            if (m.method().name().equals(implMethod.getName())) {
                return m;
            }
        }
        return null;
    }
}
