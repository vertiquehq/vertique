// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.router.MountCompositionValidator;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.core.security.SecurityPolicyViolation;
import dev.vertique.rest.jaxrs.publication.RestApplications;
import dev.vertique.rest.jaxrs.publication.RestApplications.ContractOrigin;
import dev.vertique.rest.jaxrs.publication.RestApplications.Entry;
import dev.vertique.rest.jaxrs.validation.RequestValidationStrategy;
import java.lang.reflect.Method;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The rest-jaxrs {@link MountCompositionValidator} contribution: rejects an application mount that
 * conflicts with a hand-built JAX-RS mount, rejects two operations on any JAX-RS mounts that share
 * an operationId without sharing the same owner once one or more applications are declared, rejects
 * a non-null application name carried by more than one application mount in the given list,
 * and — under a strategy reporting {@link
 * RequestValidationStrategy#resolvesOperationsFromMountContract()} — rejects a JAX-RS mount whose
 * OpenAPI contract location cannot be parsed. Only when its own checks find no violation does it
 * mark every application mount instance in the given list validated, so {@link
 * JaxRsRouterMount#createRouter} accepts it.
 *
 * <p>An application mount is a {@link JaxRsRouterMount} whose {@link JaxRsRouterMount#declaringType()}
 * is non-{@code null}; a non-application {@link JaxRsRouterMount} is a hand-built JAX-RS mount. A
 * mount that is not a {@link JaxRsRouterMount} at all is ignored entirely.
 *
 * <ol>
 *   <li><strong>Path conflicts.</strong> Runs only when at least one application mount is present.
 *       For every application mount and every hand-built JAX-RS mount, reports a violation when
 *       {@link JaxRsMountPaths#conflict} holds between their paths, or the hand-built mount's path
 *       is a router pattern ({@link JaxRsMountPaths#isRouterPattern}), which conflicts with every
 *       application regardless of any literal prefix relation. Every pair of application mounts is
 *       compared the same way too, the pairs ordered deterministically by mount path (never by the
 *       given list's order): {@link JaxRsApplicationComposer} step 1 already rejects a conflicting
 *       pair of active applications within one composition, before either of their mounts is ever
 *       built, but a {@code Set<RouterMount>} merged from more than one composition is a shape the
 *       composer's own resolution never sees, so this validator rejects that pairing too.
 *   <li><strong>Repeated application name.</strong> Runs only when at least one application
 *       mount is present. Reports a violation for every pair of application mounts in the given list
 *       that carry the same non-null {@link JaxRsRouterMount#applicationName()}, naming the name and
 *       both mount paths, the pairs ordered deterministically by mount path. A single composition's
 *       own view already refuses a repeated name before any mount is built; this check exists for a
 *       {@code Set<RouterMount>} merged from more than one composition, a shape no single
 *       composition's own resolution ever sees.
 *   <li><strong>Cross-mount operationId collisions.</strong> Runs whenever this validator instance's
 *       own view lists one or more declared applications, even when none is active, or the given
 *       mounts list itself contains an application mount — a list merged from more than one
 *       composition can hold an application mount this validator instance's own view does not
 *       reflect. Scans every {@link JaxRsRouterMount}'s resources with {@link ResourceScanner}'s
 *       accumulating overload, discarding the scanner's own violations (the registrar remains the
 *       reporter of declaration problems), and reports every pair of operations on two different
 *       mounts that share an operationId unless they share the same owner: the same normalized
 *       resource class, method name, and parameter types. The normalized class is the resource
 *       instance's own class, or that class's superclass when {@link
 *       JaxRsApplicationComposer#sameSurface} holds between them.
 *   <li><strong>Unparseable contract location.</strong> Runs under the same gate as the operationId
 *       scan, and only when the registered {@link RequestValidationStrategy} whose {@link
 *       RequestValidationStrategy#id()} equals the configured {@code jaxrs.validationStrategy}
 *       reports {@link RequestValidationStrategy#resolvesOperationsFromMountContract()}; no
 *       registered strategy carrying the configured id silently means no parse, never a thrown
 *       failure. Every JAX-RS mount's non-null {@code meta().openapiPath()} is normalized with
 *       {@link Path#of(String, String...)} and {@link Path#normalize()}; a value that cannot be
 *       parsed ({@link InvalidPathException}) is reported as a violation naming the mount — an
 *       application by its name and the setting its location came from, a hand-built mount by its
 *       path — and never the raw value.
 *   <li><strong>Validated mark.</strong> Only when the checks above found no violation, marks every
 *       application mount instance in the given list validated.
 * </ol>
 */
final class JaxRsApplicationMountValidator implements MountCompositionValidator {

    private final RestApplications view;
    private final JaxRsConfig config;
    private final Set<RequestValidationStrategy> strategies;

    /**
     * Creates the validator.
     *
     * @param view       this validator instance's own composition's application view; empty in
     *                   zero-declaration mode. An empty view alone does not silence the operationId
     *                   scan: {@link #validate} also runs it whenever the mounts it is given contain
     *                   an application mount, which can happen when mounts are merged from more than
     *                   one composition
     * @param config     the JAX-RS routing configuration, carrying the configured
     *                   {@code jaxrs.validationStrategy} id
     * @param strategies the registered request-validation strategies, resolved by id against
     *                   {@code config}'s configured id
     */
    JaxRsApplicationMountValidator(
            RestApplications view, JaxRsConfig config, Set<RequestValidationStrategy> strategies) {
        this.view = view;
        this.config = config;
        this.strategies = strategies;
    }

    /** {@inheritDoc} */
    @Override
    public List<String> validate(List<RouterMount> mounts) {
        List<JaxRsRouterMount> applicationMounts = new ArrayList<>();
        List<JaxRsRouterMount> handBuiltMounts = new ArrayList<>();
        List<JaxRsRouterMount> allJaxRsMounts = new ArrayList<>();
        for (RouterMount mount : mounts) {
            if (mount instanceof JaxRsRouterMount jaxRsRouterMount) {
                allJaxRsMounts.add(jaxRsRouterMount);
                if (isApplicationMount(jaxRsRouterMount)) {
                    applicationMounts.add(jaxRsRouterMount);
                } else {
                    handBuiltMounts.add(jaxRsRouterMount);
                }
            }
        }

        List<String> violations = new ArrayList<>();
        if (!applicationMounts.isEmpty()) {
            violations.addAll(pathConflictViolations(applicationMounts, handBuiltMounts));
            violations.addAll(applicationPairConflictViolations(applicationMounts));
            violations.addAll(repeatedApplicationNameViolations(applicationMounts));
        }
        if (!view.all().isEmpty() || !applicationMounts.isEmpty()) {
            violations.addAll(operationIdViolations(mounts));
            if (resolvesLocationsFromMountContract()) {
                violations.addAll(locationParseViolations(allJaxRsMounts));
            }
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
     * Returns whether {@code mount} is an application mount: {@link JaxRsRouterMount#declaringType()}
     * being non-{@code null} qualifies it.
     *
     * @param mount the mount to classify
     * @return {@code true} when {@code mount} was built for a declared {@code @RestApplication}
     */
    private static boolean isApplicationMount(JaxRsRouterMount mount) {
        return mount.declaringType() != null;
    }

    /**
     * Returns the display name for an application mount's declared identity: {@link
     * JaxRsRouterMount#applicationIdentityLabel()}. Only called for a mount {@link
     * #isApplicationMount} accepted.
     *
     * @param mount the application mount to name
     * @return the declared application's identity display name, naming both the application and its
     *     declaring interface
     */
    private static String identityName(JaxRsRouterMount mount) {
        return mount.applicationIdentityLabel();
    }

    /**
     * Reports every application mount that conflicts with a hand-built JAX-RS mount, in either
     * direction, or whose hand-built counterpart has a router-pattern path.
     *
     * <p>The reason named in each violation distinguishes the two rules: a genuine literal path
     * overlap ({@link JaxRsMountPaths#conflict}) is reported as "their mount paths overlap"; a
     * hand-built mount whose path is a router pattern, which conflicts with every application
     * regardless of any literal prefix relation, is reported as "a router-pattern mount path
     * conflicts with every application mount" instead — even when the two paths do not literally
     * overlap.
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
                boolean overlaps = JaxRsMountPaths.conflict(applicationMount.mountPath(), handBuiltMount.mountPath());
                boolean routerPattern = JaxRsMountPaths.isRouterPattern(handBuiltMount.mountPath());
                if (overlaps || routerPattern) {
                    String reason = overlaps
                            ? "their mount paths overlap"
                            : "a router-pattern mount path conflicts with every application mount";
                    violations.add("Application " + identityName(applicationMount) + " at '"
                            + applicationMount.mountPath() + "' conflicts with hand-built JAX-RS mount '"
                            + handBuiltMount.mountPath() + "': " + reason);
                }
            }
        }
        return violations;
    }

    /**
     * Reports every pair of application mounts whose paths conflict per {@link
     * JaxRsMountPaths#conflict}, the pairs ordered deterministically by mount path, breaking a tie by
     * identity — application name, then declaring type — so the report never depends on the given
     * list's own order.
     *
     * <p>{@link JaxRsApplicationComposer} step 1 already rejects a conflicting pair of active
     * applications within one composition, before either of their mounts is ever built, so a pair
     * this method reports never came from a single composition's own resolution. It exists for
     * mounts merged from more than one composition into one {@code Set<RouterMount>} — a shape the
     * composer's own resolution never sees, because this validator is the only check that runs over
     * the merged whole {@code HttpVerticle.start} hands it.
     *
     * @param applicationMounts every application mount in the composition
     * @return the application-pair path conflict violations, unsorted
     */
    private static List<String> applicationPairConflictViolations(List<JaxRsRouterMount> applicationMounts) {
        List<JaxRsRouterMount> sorted = applicationMounts.stream()
                .sorted(Comparator.comparing(JaxRsRouterMount::mountPath)
                        .thenComparing(JaxRsApplicationMountValidator::identityName))
                .toList();
        List<String> violations = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            JaxRsRouterMount first = sorted.get(i);
            for (int j = i + 1; j < sorted.size(); j++) {
                JaxRsRouterMount second = sorted.get(j);
                if (JaxRsMountPaths.conflict(first.mountPath(), second.mountPath())) {
                    violations.add("Application " + identityName(first) + " at '" + first.mountPath()
                            + "' conflicts with application "
                            + identityName(second) + " at '"
                            + second.mountPath() + "': their mount paths overlap");
                }
            }
        }
        return violations;
    }

    /**
     * Reports every pair of application mounts in the given list that carry the same non-null
     * {@link JaxRsRouterMount#applicationName()}, the pairs ordered deterministically by
     * mount path so the report never depends on the given list's own order.
     *
     * <p>A single composition's own view already refuses a repeated application name before any
     * mount is ever built ({@code RestApplicationsBuilder}), so a pair this method reports never
     * came from a single composition's own resolution — it exists for a {@code Set<RouterMount>}
     * merged from more than one composition, a shape no single composition's own view ever reflects.
     *
     * @param applicationMounts every application mount in the composition, each carrying a non-null
     *                          {@link JaxRsRouterMount#applicationName()}
     * @return the repeated-application-name violations, unsorted
     */
    private static List<String> repeatedApplicationNameViolations(List<JaxRsRouterMount> applicationMounts) {
        Map<String, List<JaxRsRouterMount>> byApplicationName =
                applicationMounts.stream().collect(Collectors.groupingBy(JaxRsRouterMount::applicationName));
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, List<JaxRsRouterMount>> entry : byApplicationName.entrySet()) {
            List<JaxRsRouterMount> sorted = entry.getValue().stream()
                    .sorted(Comparator.comparing(JaxRsRouterMount::mountPath))
                    .toList();
            for (int i = 0; i < sorted.size(); i++) {
                for (int j = i + 1; j < sorted.size(); j++) {
                    violations.add("Application name '" + entry.getKey()
                            + "' is carried by more than one application mount: '"
                            + sorted.get(i).mountPath()
                            + "' and '" + sorted.get(j).mountPath() + "'");
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
     * Returns whether the registered {@link RequestValidationStrategy} whose {@link
     * RequestValidationStrategy#id()} equals the configured {@code jaxrs.validationStrategy} reports
     * {@link RequestValidationStrategy#resolvesOperationsFromMountContract()}.
     *
     * <p>Unlike {@code RequestValidationStrategySelector.select}, this lookup never throws when no
     * registered strategy carries the configured id — {@link JaxRsRouterMount#createRouter}'s own
     * fail-fast selection is the sole reporter of that misconfiguration; this validator silently
     * treats an unmatched id as "the flag is not reported", so no location is ever parsed under it.
     *
     * @return {@code true} when a registered strategy carries the configured id and reports the flag
     */
    private boolean resolvesLocationsFromMountContract() {
        return strategies.stream()
                .filter(strategy -> strategy.id().equals(config.validationStrategy()))
                .findFirst()
                .map(RequestValidationStrategy::resolvesOperationsFromMountContract)
                .orElse(false);
    }

    /**
     * Reports every JAX-RS mount whose non-null {@code meta().openapiPath()} cannot be parsed as a
     * path ({@link Path#of(String, String...)} then {@link Path#normalize()} throwing {@link
     * InvalidPathException}). A mount with a {@code null} {@code openapiPath()} is skipped.
     *
     * @param jaxRsMounts every {@link JaxRsRouterMount} in the given mounts list, application and
     *                    hand-built alike
     * @return the unparseable-location violations, unsorted
     */
    private List<String> locationParseViolations(List<JaxRsRouterMount> jaxRsMounts) {
        List<String> violations = new ArrayList<>();
        for (JaxRsRouterMount mount : jaxRsMounts) {
            String openapiPath = mount.meta().openapiPath();
            if (openapiPath == null) {
                continue;
            }
            try {
                Path.of(openapiPath).normalize();
            } catch (InvalidPathException e) {
                violations.add(unparseableLocationViolation(mount));
            }
        }
        return violations;
    }

    /**
     * Builds the violation for a JAX-RS mount whose OpenAPI contract location could not be parsed,
     * naming the mount — an application by its name and the setting its location came from, a
     * hand-built mount by its path — and never the raw, unparseable value.
     *
     * @param mount the mount whose {@code meta().openapiPath()} failed to parse
     * @return the violation message
     */
    private String unparseableLocationViolation(JaxRsRouterMount mount) {
        String applicationName = mount.applicationName();
        if (applicationName == null) {
            return "OpenAPI contract location for hand-built JAX-RS mount '" + mount.mountPath()
                    + "' cannot be parsed as a path";
        }
        Optional<Entry> entry = view.byName(applicationName);
        String setting = entry.map(e -> contractSettingName(applicationName, e.contractOrigin()))
                .orElse("the configuration, annotation, or global setting that supplied its location");
        return "OpenAPI contract location for application '" + applicationName + "' (" + setting
                + ") cannot be parsed as a path";
    }

    /**
     * Names the setting that supplied an application's effective OpenAPI contract location, per
     * {@link ContractOrigin}.
     *
     * @param applicationName the application's name
     * @param origin          the setting that decided the application's effective contract location
     * @return the setting's display name; never the location's value
     */
    private static String contractSettingName(String applicationName, ContractOrigin origin) {
        return switch (origin) {
            case CONFIGURATION -> "jaxrs.applications." + applicationName + ".openapiPath";
            case ANNOTATION -> "the @RestApplication annotation's openapiPath";
            case GLOBAL -> "jaxrs.openapiPath";
        };
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
