// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.rest.core.router.MountCompositionValidator;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.core.security.SecurityPolicyViolation;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsApplicationRegistration;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The rest-jaxrs {@link MountCompositionValidator} contribution: rejects an application mount that
 * conflicts with a hand-built JAX-RS mount, and rejects two operations on any JAX-RS mounts that
 * share an operationId without sharing the same owner, once one or more {@code
 * jakarta.ws.rs.core.Application} registrations are declared. Only when its own checks find no
 * violation does it mark every application mount instance in the given list validated, so {@link
 * JaxRsRouterMount#createRouter} accepts it.
 *
 * <p>An application mount is a {@link JaxRsRouterMount} whose {@link
 * JaxRsRouterMount#applicationType()} is non-{@code null}; a non-application {@link
 * JaxRsRouterMount} is a hand-built JAX-RS mount. A mount that is not a {@link JaxRsRouterMount} at
 * all is ignored entirely.
 *
 * <ol>
 *   <li><strong>Path conflicts.</strong> Runs only when at least one application mount is present.
 *       For every application mount and every hand-built JAX-RS mount, reports a violation when
 *       {@link JaxRsMountPaths#conflict} holds between their paths, or the hand-built mount's path
 *       is a router pattern ({@link JaxRsMountPaths#isRouterPattern}), which conflicts with every
 *       application regardless of any literal prefix relation. A pair of application mounts is
 *       never reported here — {@link JaxRsApplicationComposer} already rejects that pairing before
 *       this validator ever runs.
 *   <li><strong>Cross-mount operationId collisions.</strong> Runs whenever one or more application
 *       registrations are declared, even when none is active. Scans every {@link JaxRsRouterMount}'s
 *       resources with {@link ResourceScanner}'s accumulating overload, discarding the scanner's own
 *       violations (the registrar remains the reporter of declaration problems), and reports every
 *       pair of operations on two different mounts that share an operationId unless they share the
 *       same owner: the same normalized resource class, method name, and parameter types. The
 *       normalized class is the resource instance's own class, or that class's superclass when
 *       {@link JaxRsApplicationComposer#sameSurface} holds between them.
 *   <li><strong>Validated mark.</strong> Only when the checks above found no violation, marks every
 *       application mount instance in the given list validated.
 * </ol>
 */
final class JaxRsApplicationMountValidator implements MountCompositionValidator {

    private final Set<GeneratedJaxRsApplicationRegistration> registrations;

    /**
     * Creates the validator.
     *
     * @param registrations the declared application registration set; empty in zero-declaration
     *                      mode, in which case this validator reports no violation and marks
     *                      nothing (there is never an application mount to mark)
     */
    JaxRsApplicationMountValidator(Set<GeneratedJaxRsApplicationRegistration> registrations) {
        this.registrations = registrations;
    }

    /** {@inheritDoc} */
    @Override
    public List<String> validate(List<RouterMount> mounts) {
        List<JaxRsRouterMount> applicationMounts = new ArrayList<>();
        List<JaxRsRouterMount> handBuiltMounts = new ArrayList<>();
        for (RouterMount mount : mounts) {
            if (mount instanceof JaxRsRouterMount jaxRsRouterMount) {
                if (jaxRsRouterMount.applicationType() != null) {
                    applicationMounts.add(jaxRsRouterMount);
                } else {
                    handBuiltMounts.add(jaxRsRouterMount);
                }
            }
        }

        List<String> violations = new ArrayList<>();
        if (!applicationMounts.isEmpty()) {
            violations.addAll(pathConflictViolations(applicationMounts, handBuiltMounts));
        }
        if (!registrations.isEmpty()) {
            violations.addAll(operationIdViolations(mounts));
        }

        List<String> sortedViolations = violations.stream().sorted().toList();
        if (sortedViolations.isEmpty()) {
            for (JaxRsRouterMount applicationMount : applicationMounts) {
                applicationMount.markValidated();
            }
        }
        return sortedViolations;
    }

    /**
     * Reports every application mount that conflicts with a hand-built JAX-RS mount, in either
     * direction, or whose hand-built counterpart has a router-pattern path.
     *
     * @param applicationMounts every application mount in the composition
     * @param handBuiltMounts   every hand-built (non-application) {@link JaxRsRouterMount} in the
     *                          composition
     * @return the path conflict violations, unsorted
     */
    private static List<String> pathConflictViolations(
            List<JaxRsRouterMount> applicationMounts, List<JaxRsRouterMount> handBuiltMounts) {
        List<String> violations = new ArrayList<>();
        for (JaxRsRouterMount applicationMount : applicationMounts) {
            for (JaxRsRouterMount handBuiltMount : handBuiltMounts) {
                if (JaxRsMountPaths.conflict(applicationMount.mountPath(), handBuiltMount.mountPath())
                        || JaxRsMountPaths.isRouterPattern(handBuiltMount.mountPath())) {
                    violations.add(
                            "Application " + applicationMount.applicationType().getName() + " at '"
                                    + applicationMount.mountPath() + "' conflicts with hand-built JAX-RS mount '"
                                    + handBuiltMount.mountPath() + "': their mount paths overlap");
                }
            }
        }
        return violations;
    }

    /**
     * Reports every pair of operations, scanned from two different {@link JaxRsRouterMount}s, that
     * share an operationId without sharing the same owner.
     *
     * @param mounts every mount in the composition, in mounting order
     * @return the operationId collision violations, unsorted
     */
    private static List<String> operationIdViolations(List<RouterMount> mounts) {
        ResourceScanner scanner = new ResourceScanner(new SecurityPolicyBuilder());
        List<SecurityPolicyViolation> discardedScannerViolations = new ArrayList<>();

        List<Operation> operations = new ArrayList<>();
        for (RouterMount mount : mounts) {
            if (!(mount instanceof JaxRsRouterMount jaxRsRouterMount)) {
                continue;
            }
            for (Object resource : jaxRsRouterMount.orderedResources()) {
                for (ResourceMethodMeta meta : scanner.scanResource(resource, discardedScannerViolations)) {
                    operations.add(Operation.of(meta, jaxRsRouterMount));
                }
            }
        }

        Map<String, List<Operation>> byOperationId =
                operations.stream().collect(Collectors.groupingBy(Operation::operationId));

        List<String> violations = new ArrayList<>();
        for (List<Operation> group : byOperationId.values()) {
            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    Operation a = group.get(i);
                    Operation b = group.get(j);
                    if (a.mount() != b.mount() && !a.owner().equals(b.owner())) {
                        violations.add("Duplicate operationId '" + a.operationId() + "' across mounts: " + a.label()
                                + " and " + b.label());
                    }
                }
            }
        }
        return violations;
    }

    /**
     * One operation scanned from one {@link JaxRsRouterMount}: its operationId, its owner, and the
     * mount it was scanned from — the identity {@link #operationIdViolations} compares to find a
     * cross-mount collision.
     *
     * @param operationId the scanned operation's operationId
     * @param owner       the operation's normalized owner
     * @param mount       the mount this operation was scanned from
     */
    private record Operation(String operationId, OwnerKey owner, JaxRsRouterMount mount) {

        /**
         * Builds the operation scanned from {@code meta} on {@code mount}.
         *
         * @param meta  the scanned method metadata
         * @param mount the mount {@code meta} was scanned from
         * @return the operation
         */
        static Operation of(ResourceMethodMeta meta, JaxRsRouterMount mount) {
            return new Operation(meta.operationId(), OwnerKey.of(meta.resourceInstance(), meta.method()), mount);
        }

        /**
         * Names this operation by its normalized owner class's fully qualified name, its method name,
         * its parameter types, and its mount's quoted path.
         *
         * @return the display label for this operation
         */
        String label() {
            String parameterTypeNames =
                    owner.parameterTypes().stream().map(Class::getName).collect(Collectors.joining(", "));
            return owner.resourceClass().getName() + "." + owner.methodName() + "(" + parameterTypeNames + ") at '"
                    + mount.mountPath() + "'";
        }
    }

    /**
     * An operation's owner: the normalized resource class, the method name, and the
     * parameter types. Two operations with an equal owner never collide, even when they share an
     * operationId — the method name and parameter types stand in for the reflective {@code Method}
     * object, so an AOP proxy's override counts as the bean's own method.
     *
     * @param resourceClass  the normalized owner class
     * @param methodName     the operation method's name
     * @param parameterTypes the operation method's declared parameter types
     */
    private record OwnerKey(Class<?> resourceClass, String methodName, List<Class<?>> parameterTypes) {

        /**
         * Builds the owner key for the operation {@code method} declares on {@code resourceInstance}.
         *
         * @param resourceInstance the resource instance an operation was scanned from
         * @param method           the scanned resource method
         * @return the owner key
         */
        static OwnerKey of(Object resourceInstance, Method method) {
            return new OwnerKey(
                    normalizedClass(resourceInstance), method.getName(), List.of(method.getParameterTypes()));
        }

        /**
         * Returns {@code resourceInstance}'s own class, or that class's superclass when {@link
         * JaxRsApplicationComposer#sameSurface} holds between them (the AOP-proxy predicate).
         *
         * @param resourceInstance the resource instance an operation was scanned from
         * @return the normalized owner class
         */
        private static Class<?> normalizedClass(Object resourceInstance) {
            Class<?> actual = resourceInstance.getClass();
            Class<?> superclass = actual.getSuperclass();
            return superclass != null && JaxRsApplicationComposer.sameSurface(superclass, actual) ? superclass : actual;
        }
    }
}
