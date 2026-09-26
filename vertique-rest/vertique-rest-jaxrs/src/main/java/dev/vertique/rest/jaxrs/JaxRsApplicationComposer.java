// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.core.util.AnnotationResolver;
import dev.vertique.core.util.TypeResolver;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsApplicationRegistration;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsResourceEntry;
import jakarta.inject.Provider;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.DynamicFeature;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Feature;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Composes declared Jakarta REST {@link Application} registrations into one {@link JaxRsRouterMount}
 * per active application, on behalf of {@link RestModule}'s default-mount provider.
 *
 * <p>{@link #compose} runs only when at least one application registration is declared; with an
 * empty registration set, {@code RestModule.jaxRsRouterMount} keeps its zero-declaration body and
 * never calls {@link #compose}. Its composition-wide re-entry guard
 * ({@link #enterComposition(JaxRsConfig)}/{@link #exitComposition(JaxRsConfig)}) still wraps that
 * zero-declaration body, so a manually contributed resource that re-enters the same component's
 * composition is still caught even when no application is declared. {@link #compose} runs in this
 * order:
 *
 * <ol>
 *   <li>Validates every registration, active or inactive, without running any application code:
 *       rejects duplicate registrations of one application class, duplicate catalog entries of one
 *       resource class, a discovery-style registration (one that overrides neither
 *       {@code getClasses()} nor {@code getSingletons()}) that is not the sole registration, an
 *       annotation outside {@link ApplicationAnnotationAllowList}'s allow list anywhere in the
 *       registration's class hierarchy (the runtime backstop for the compile-time
 *       {@code ApplicationAnnotationValidator}, re-checking every registration by reflection so one
 *       the processor did not produce still fails startup), and a pair of active applications whose
 *       mount paths conflict ({@link JaxRsMountPaths}). Logs one informational line listing every
 *       registration. Any violation here aborts before any application is constructed or any
 *       resource is resolved; each allow-list violation carries the compile-time validator's
 *       message and is reported together with every other step-one problem under the aggregate
 *       exception.
 *   <li>Warns once, without echoing the configured value, when the routing base path is non-default
 *       (it is never applied to an application mount).
 *   <li>With no active registration, resolves and warns about the enabled generated resource
 *       catalog only, contributes no mount, and never resolves the manual resource contributions.
 *   <li>Resolves the manual resource contributions once, then constructs each active application.
 *       A {@link RuntimeException} or {@link LinkageError} escaping construction or membership
 *       resolution is rethrown as a {@link RestConfigurationException} naming the application; a
 *       composition already running on this thread — including one re-entered through a manually
 *       contributed resource's or a catalog entry's own construction, not only through an
 *       application's — is detected and named instead of recursing; a constructed instance that
 *       does not satisfy its own registration's declared type fails immediately.
 *   <li>A sole active discovery-style registration selects every enabled catalog entry and every
 *       manual resource.
 *   <li>An overriding registration's {@code getClasses()} decides its own membership, matched
 *       against the catalog and the manual contributions through {@link #sameSurface}; every
 *       membership problem across every application is collected and thrown together, before any
 *       selected resource is resolved.
 *   <li>Each selected catalog entry resolves at most once per composition and is shared across
 *       applications; its resolved instance must keep the entry's declared resource surface.
 *   <li>Builds one mount per active application, with its resources ordered by fully qualified
 *       class name, and logs one informational line per mount.
 *   <li>Warns once, only when the list is non-empty, naming every enabled catalog entry and manual
 *       resource that no application selected.
 * </ol>
 */
@Slf4j
final class JaxRsApplicationComposer {

    /** The routing configuration's default base path, applied only in zero-declaration mode. */
    private static final String DEFAULT_BASE_PATH = "/*";

    /**
     * Tracks, per thread, the identity of every component-scoped {@code @Singleton JaxRsConfig}
     * whose composition is currently running on that thread — one entry per {@code RestModule}
     * component nested inside another, never one entry per thread. A manually contributed
     * resource, a catalog entry, or an {@code Application} whose own construction or member
     * evaluation resolves the SAME component's {@code Set<RouterMount>} again is detected and named
     * instead of recursing until the stack overflows; resolving a DIFFERENT component's
     * {@code Set<RouterMount>} from within that construction succeeds, because {@link JaxRsConfig}
     * is {@code @Singleton}-scoped to its own Dagger component, so distinct components always hand
     * {@link #enterComposition(JaxRsConfig)} distinct instances. Compared by identity
     * ({@link IdentityHashMap}-backed), not {@code equals()}, since {@link JaxRsConfig} has none of
     * its own and object identity is exactly "the same component's config instance". Added by
     * {@link #enterComposition(JaxRsConfig)}, which fails when the instance is already present, and
     * always removed by the matching {@link #exitComposition(JaxRsConfig)} in the same caller's
     * {@code finally} block; a nested, rejected call never adds its instance, because
     * {@link #enterComposition(JaxRsConfig)} throws before returning, so only a call that actually
     * entered ever removes its own entry. The per-thread set itself is removed once it becomes
     * empty, so a thread reused from a pool never accumulates stale sets.
     */
    private static final ThreadLocal<Set<JaxRsConfig>> COMPOSING = new ThreadLocal<>();

    /**
     * Marks the registration whose construction or member evaluation is in progress on this thread,
     * so that a re-entry happening while an application is being evaluated still names that
     * application, not only the generic composition-in-progress message. Set only around the
     * registration currently under evaluation, and always cleared in that call's {@code finally}
     * block.
     */
    private static final ThreadLocal<GeneratedJaxRsApplicationRegistration> IN_PROGRESS = new ThreadLocal<>();

    private JaxRsApplicationComposer() {}

    /**
     * Detects a composition of {@code config}'s own component already running on this thread and
     * marks a new one starting. Called once by {@code RestModule.jaxRsRouterMount}, before either
     * its zero-declaration body or {@link #compose} runs, so the guard covers both branches — the
     * mistake a manually contributed resource, a catalog entry, or an {@code Application} can make
     * by depending on its OWN component's {@code Set<RouterMount>}. Nesting a composition of a
     * DIFFERENT component — a distinct {@code @Singleton JaxRsConfig} instance — inside this one
     * succeeds. A matching {@link #exitComposition(JaxRsConfig)} in the caller's
     * {@code finally} block always follows a successful call.
     *
     * @param config the component-scoped {@code @Singleton JaxRsConfig} identifying this
     *               composition's own component
     * @throws RestConfigurationException when a composition of this same component is already
     *     running on this thread, naming the application under construction when one is known, or
     *     the offending dependency otherwise
     */
    static void enterComposition(JaxRsConfig config) {
        Set<JaxRsConfig> composing = COMPOSING.get();
        if (composing == null) {
            composing = Collections.newSetFromMap(new IdentityHashMap<>());
            COMPOSING.set(composing);
        }
        if (!composing.add(config)) {
            GeneratedJaxRsApplicationRegistration reentered = IN_PROGRESS.get();
            String subject = reentered != null
                    ? appContext(reentered)
                    : "A manually contributed resource or generated resource catalog entry";
            throw new RestConfigurationException(subject
                    + " re-entered JAX-RS application composition while Set<RouterMount> was still being resolved"
                    + " on this thread; an Application constructor, its getClasses()/getSingletons(), or a"
                    + " manually contributed resource's or catalog entry's own construction must not depend on"
                    + " Set<RouterMount>");
        }
    }

    /**
     * Clears the composition-in-progress marker {@link #enterComposition(JaxRsConfig)} set for
     * {@code config}'s component. Must run only in the {@code finally} block of the same call that
     * successfully called {@link #enterComposition(JaxRsConfig)} with the same {@code config}; a
     * nested, rejected call never reaches its own {@code finally}, so only a composition that
     * actually entered ever clears its own entry. Removes the thread-local set entirely once it
     * becomes empty.
     *
     * @param config the same component-scoped {@code @Singleton JaxRsConfig} passed to the matching
     *               {@link #enterComposition(JaxRsConfig)} call
     */
    static void exitComposition(JaxRsConfig config) {
        Set<JaxRsConfig> composing = COMPOSING.get();
        if (composing == null) {
            return;
        }
        composing.remove(config);
        if (composing.isEmpty()) {
            COMPOSING.remove();
        }
    }

    /**
     * Composes {@code applications} into one mount per active application.
     *
     * @param factory      the JAX-RS router mount factory
     * @param applications every declared application registration, active or not; never empty
     * @param resources    the manual {@code @JaxRsResources} contributions, resolved once, lazily
     * @param catalog      the generated resource catalog, resolved lazily
     * @param config       the JAX-RS routing configuration
     * @return one mount per active application; empty when no registration is active
     * @throws RestConfigurationException when any registration, membership, construction, or
     *     resolution rule is violated
     */
    static Set<RouterMount> compose(
            JaxRsRouterMount.Factory factory,
            Set<GeneratedJaxRsApplicationRegistration> applications,
            Provider<Set<Object>> resources,
            Provider<Set<GeneratedJaxRsResourceEntry>> catalog,
            JaxRsConfig config) {
        // Re-entry into THIS component's composition is detected by RestModule.jaxRsRouterMount's
        // per-component guard (enterComposition(config)/exitComposition(config)), which wraps this
        // call and the zero-declaration body alike, so it is not repeated here.

        // --- Step 1: registration checks (no application code runs yet) ---

        List<GeneratedJaxRsApplicationRegistration> sortedRegistrations = applications.stream()
                .sorted(Comparator.comparing(GeneratedJaxRsApplicationRegistration::path)
                        .thenComparing(r -> r.type().getName()))
                .toList();
        String registrationSummary = sortedRegistrations.stream()
                .map(r -> r.type().getName() + " (" + r.path() + ", active=" + r.active() + ")")
                .collect(Collectors.joining(", "));

        Map<Class<?>, GeneratedJaxRsResourceEntry> entriesByType = new HashMap<>();
        Set<Class<?>> duplicateEntryTypes = new LinkedHashSet<>();
        for (GeneratedJaxRsResourceEntry entry : catalog.get()) {
            if (entriesByType.putIfAbsent(entry.type(), entry) != null) {
                duplicateEntryTypes.add(entry.type());
            }
        }

        List<String> stepOneViolations = new ArrayList<>();
        Map<Class<? extends Application>, List<GeneratedJaxRsApplicationRegistration>> byType = new LinkedHashMap<>();
        for (GeneratedJaxRsApplicationRegistration registration : sortedRegistrations) {
            byType.computeIfAbsent(registration.type(), key -> new ArrayList<>())
                    .add(registration);
        }
        byType.forEach((type, registrationsOfType) -> {
            if (registrationsOfType.size() > 1) {
                stepOneViolations.add("Two or more registrations declare application " + type.getName()
                        + "; declared registrations: " + registrationSummary);
            }
        });
        duplicateEntryTypes.forEach(
                type -> stepOneViolations.add("Two or more generated resource catalog entries exist for "
                        + type.getName() + "; declared registrations: " + registrationSummary));

        List<GeneratedJaxRsApplicationRegistration> discoveryRegistrations =
                sortedRegistrations.stream().filter(r -> !overrides(r.type())).toList();
        if (!discoveryRegistrations.isEmpty() && sortedRegistrations.size() > 1) {
            discoveryRegistrations.forEach(discovery -> stepOneViolations.add(appContext(discovery)
                    + " requests discovery membership (overrides neither getClasses() nor getSingletons()),"
                    + " which requires it to be the sole registered application; declared registrations: "
                    + registrationSummary));
        }

        // Step 1a: the annotation allow-list runtime backstop re-checks every registration's class
        // hierarchy by reflection, active or inactive, before any application is constructed. Each
        // violation carries the compile-time validator's message and is reported with every other
        // step-one problem.
        for (GeneratedJaxRsApplicationRegistration registration : sortedRegistrations) {
            stepOneViolations.addAll(ApplicationAnnotationAllowList.violations(registration.type()));
        }

        log.info("Declared JAX-RS application registrations: {}", registrationSummary);

        List<GeneratedJaxRsApplicationRegistration> activeRegistrations = sortedRegistrations.stream()
                .filter(GeneratedJaxRsApplicationRegistration::active)
                .toList();

        // Step 1b: path conflicts among active applications are rejected here.
        for (int i = 0; i < activeRegistrations.size(); i++) {
            GeneratedJaxRsApplicationRegistration first = activeRegistrations.get(i);
            String firstMountPath = mountPath(first.path());
            for (int j = i + 1; j < activeRegistrations.size(); j++) {
                GeneratedJaxRsApplicationRegistration second = activeRegistrations.get(j);
                String secondMountPath = mountPath(second.path());
                if (JaxRsMountPaths.conflict(firstMountPath, secondMountPath)) {
                    stepOneViolations.add(quotedAppContext(first) + " conflicts with " + quotedAppContext(second)
                            + ": their mount paths overlap");
                }
            }
        }

        if (!stepOneViolations.isEmpty()) {
            throw buildAggregateException(stepOneViolations);
        }

        // --- Step 2: the routing base path is not applied to application mounts ---

        if (!DEFAULT_BASE_PATH.equals(config.basePath())) {
            log.warn("jaxrs.basePath is configured, but it is not applied to application mounts in explicit"
                    + " mode; every declared Application is mounted at its own @ApplicationPath");
        }

        // --- Step 3: no active application ---

        if (activeRegistrations.isEmpty()) {
            String enabledNames = entriesByType.values().stream()
                    .filter(GeneratedJaxRsResourceEntry::enabled)
                    .map(entry -> entry.type().getName())
                    .sorted()
                    .collect(Collectors.joining(", "));
            log.warn(
                    "No JAX-RS application is active; the enabled generated resources ({}) are not selected,"
                            + " and manual @JaxRsResources contributions were not resolved",
                    enabledNames);
            return Set.of();
        }

        // --- Step 4 to 7: construct every active application and determine its membership ---

        Set<Object> manualResources = resources.get();

        List<String> membershipViolations = new ArrayList<>();
        List<AppSelection> selections = new ArrayList<>();
        for (GeneratedJaxRsApplicationRegistration registration : activeRegistrations) {
            evaluateApplication(registration, entriesByType, manualResources, membershipViolations, selections);
        }
        if (!membershipViolations.isEmpty()) {
            throw buildAggregateException(membershipViolations);
        }

        // --- Step 8 and 9: resolve selected resources once each, and mount every application ---

        Set<Class<?>> selectedEntryTypes = new HashSet<>();
        Set<Object> selectedManual = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<RouterMount> mounts = resolveAndMount(factory, selections, config, selectedEntryTypes, selectedManual);

        // --- Step 10: report enabled resources no application selected ---

        reportUnselected(entriesByType, manualResources, selectedEntryTypes, selectedManual);

        return Collections.unmodifiableSet(mounts);
    }

    /**
     * Evaluates one active registration: constructs the application (wrapped and re-entry-checked),
     * checks its constructed type, and determines its membership — either the sole discovery
     * application's full selection, or an overriding application's {@code getClasses()}-driven
     * selection. Adds the outcome to {@code selections}, or one or more messages to {@code violations}
     * per offending member; never both for the same listed member.
     *
     * <p>Every {@link RuntimeException} or {@link LinkageError} escaping {@code create()},
     * {@code getSingletons()}, or {@code getClasses()} is wrapped, naming this application; a
     * composition already in progress on this thread (including this application's own, re-entrant
     * construction) surfaces as a wrapped, nested {@link RestConfigurationException} this way too, so
     * it is never returned to the caller unwrapped. Only this method's own diagnostics — a
     * {@code null} instance from {@code create()}, and the type-check failure — thrown after
     * construction returns, are never wrapped.
     *
     * @param registration    the registration to evaluate
     * @param entriesByType   the generated resource catalog, keyed by declared type
     * @param manualResources the resolved manual {@code @JaxRsResources} contributions
     * @param violations      membership violation messages accumulate here
     * @param selections      this and every other evaluated application's selection accumulate here
     */
    private static void evaluateApplication(
            GeneratedJaxRsApplicationRegistration registration,
            Map<Class<?>, GeneratedJaxRsResourceEntry> entriesByType,
            Set<Object> manualResources,
            List<String> violations,
            List<AppSelection> selections) {
        IN_PROGRESS.set(registration);
        try {
            Application application;
            try {
                application = registration.create();
            } catch (RuntimeException | LinkageError e) {
                throw wrap(registration, e);
            }

            // The type check (and the null check below) are this method's own diagnostics, not
            // application code, so they are thrown unwrapped: neither must be re-caught and
            // re-wrapped by either catch above or below.
            if (application == null) {
                throw new RestConfigurationException(
                        appContext(registration) + " registration returned null instead of an instance");
            }
            if (!registration.type().isInstance(application)) {
                throw new RestConfigurationException(appContext(registration) + " registration constructed an"
                        + " instance of " + application.getClass().getName() + ", which is not an instance"
                        + " of " + registration.type().getName());
            }

            if (!overrides(registration.type())) {
                List<GeneratedJaxRsResourceEntry> entries = entriesByType.values().stream()
                        .filter(GeneratedJaxRsResourceEntry::enabled)
                        .toList();
                selections.add(new AppSelection(registration, entries, List.copyOf(manualResources)));
                return;
            }

            Set<Class<?>> classes;
            try {
                Set<Object> singletons = application.getSingletons();
                if (singletons != null && !singletons.isEmpty()) {
                    violations.add(appContext(registration)
                            + " returns one or more instances from getSingletons(), which is not supported");
                    return;
                }
                classes = application.getClasses();
            } catch (RuntimeException | LinkageError e) {
                throw wrap(registration, e);
            }
            if (classes == null) {
                classes = Set.of();
            }
            if (classes.isEmpty()) {
                violations.add(appContext(registration) + " has an empty getClasses(), which selects no resources");
                return;
            }

            List<GeneratedJaxRsResourceEntry> selectedEntries = new ArrayList<>();
            List<Object> selectedManual = new ArrayList<>();
            for (Class<?> listed : classes) {
                evaluateListedClass(
                        registration,
                        listed,
                        entriesByType,
                        manualResources,
                        violations,
                        selectedEntries,
                        selectedManual);
            }
            selections.add(new AppSelection(registration, selectedEntries, selectedManual));
        } finally {
            IN_PROGRESS.remove();
        }
    }

    /**
     * Evaluates one member listed by an overriding application's {@code getClasses()}, appending
     * exactly one violation message when it is rejected, or adding the resolved catalog entry or
     * manual instance to the application's selection when it is accepted.
     *
     * @param registration    the application's registration, for the violation message
     * @param listed          the listed member; may be {@code null}
     * @param entriesByType   the generated resource catalog, keyed by declared type
     * @param manualResources the resolved manual {@code @JaxRsResources} contributions
     * @param violations      a rejection message is appended here, if any
     * @param selectedEntries an accepted catalog entry is appended here
     * @param selectedManual  an accepted manual instance is appended here
     */
    private static void evaluateListedClass(
            GeneratedJaxRsApplicationRegistration registration,
            Class<?> listed,
            Map<Class<?>, GeneratedJaxRsResourceEntry> entriesByType,
            Set<Object> manualResources,
            List<String> violations,
            List<GeneratedJaxRsResourceEntry> selectedEntries,
            List<Object> selectedManual) {
        if (listed == null) {
            violations.add(appContext(registration) + " lists a null member in getClasses()");
            return;
        }
        if (isUnsupportedProviderOrFeature(listed)) {
            violations.add(appContext(registration) + " lists " + listed.getName() + " in getClasses(),"
                    + " which is a JAX-RS provider or feature type (@Provider, Feature, or DynamicFeature) and is"
                    + " not a supported resource member");
            return;
        }
        if (listed.isInterface() || Modifier.isAbstract(listed.getModifiers()) || !hasEffectivePath(listed)) {
            violations.add(appContext(registration) + " lists " + listed.getName() + " in getClasses(),"
                    + " which is not a concrete JAX-RS root resource (interface, abstract, or missing an"
                    + " effective @Path)");
            return;
        }

        GeneratedJaxRsResourceEntry catalogMatch = entriesByType.get(listed);
        List<Object> manualMatches = new ArrayList<>();
        List<Object> nonMatchingSubclasses = new ArrayList<>();
        for (Object instance : manualResources) {
            if (sameSurface(listed, instance.getClass())) {
                manualMatches.add(instance);
            } else if (listed.isInstance(instance)) {
                nonMatchingSubclasses.add(instance);
            }
        }

        int matchCount = (catalogMatch != null ? 1 : 0) + manualMatches.size();
        if (matchCount == 0) {
            if (nonMatchingSubclasses.size() == 1) {
                Class<?> subclass = nonMatchingSubclasses.get(0).getClass();
                violations.add(appContext(registration) + " lists " + listed.getName() + " in getClasses(),"
                        + " but its only bound instance is " + subclass.getName() + ", a subclass with a"
                        + " different resource surface; list " + subclass.getName() + " explicitly");
            } else {
                violations.add(appContext(registration) + " lists " + listed.getName() + " in getClasses(),"
                        + " but no generated resource entry or manual @JaxRsResources instance of that class is"
                        + " bound");
            }
            return;
        }
        if (matchCount > 1) {
            violations.add(appContext(registration) + " lists " + listed.getName() + " in getClasses(),"
                    + " which matches more than one bound resource (a generated catalog entry and a manual"
                    + " contribution, or two or more manual contributions)");
            return;
        }

        if (catalogMatch != null) {
            if (catalogMatch.enabled()) {
                selectedEntries.add(catalogMatch);
            }
            // A matching disabled entry excludes the listed class silently: nothing replaces it.
        } else {
            selectedManual.add(manualMatches.get(0));
        }
    }

    /**
     * Resolves every selection's chosen resources (each catalog entry at most once, shared across
     * applications) and builds one mount per application, logging one informational line per mount.
     *
     * @param factory            the JAX-RS router mount factory
     * @param selections          every active application's selection, in mounting order
     * @param config              the JAX-RS routing configuration
     * @param selectedEntryTypes  every resolved catalog entry's declared type accumulates here
     * @param selectedManualOut   every selected manual instance accumulates here
     * @return one mount per application, in mounting order
     * @throws RestConfigurationException when a resolved catalog instance is {@code null}, or does
     *     not keep its entry's declared resource surface
     */
    private static Set<RouterMount> resolveAndMount(
            JaxRsRouterMount.Factory factory,
            List<AppSelection> selections,
            JaxRsConfig config,
            Set<Class<?>> selectedEntryTypes,
            Set<Object> selectedManualOut) {
        Map<GeneratedJaxRsResourceEntry, Object> resolved = new HashMap<>();
        Set<RouterMount> mounts = new LinkedHashSet<>();
        for (AppSelection selection : selections) {
            Set<Object> resourceSet = Collections.newSetFromMap(new IdentityHashMap<>());
            for (GeneratedJaxRsResourceEntry entry : selection.entries()) {
                Object instance;
                if (resolved.containsKey(entry)) {
                    instance = resolved.get(entry);
                } else {
                    instance = entry.get();
                    if (instance == null) {
                        throw new RestConfigurationException("Generated resource entry for "
                                + entry.type().getName() + " returned null instead of an instance");
                    }
                    if (!sameSurface(entry.type(), instance.getClass())) {
                        throw new RestConfigurationException("Generated resource entry for "
                                + entry.type().getName() + " resolved to an instance of "
                                + instance.getClass().getName() + ", which does not have the same resource"
                                + " surface as " + entry.type().getName());
                    }
                    resolved.put(entry, instance);
                }
                resourceSet.add(instance);
                selectedEntryTypes.add(entry.type());
            }
            resourceSet.addAll(selection.manualInstances());
            selectedManualOut.addAll(selection.manualInstances());

            List<Object> ordered = new ArrayList<>(resourceSet);
            ordered.sort(Comparator.comparing(resource -> resource.getClass().getName()));
            Set<Object> orderedResources = new LinkedHashSet<>(ordered);

            String mountPath = mountPath(selection.registration().path());
            JaxRsRouterMount mount = factory.createApplicationMount(
                    mountPath,
                    config.openapiPath(),
                    orderedResources,
                    selection.registration().type());
            mounts.add(mount);

            log.info(
                    "Mounted JAX-RS application {} at {} with resources: {}",
                    selection.registration().type().getName(),
                    mountPath,
                    ordered.stream()
                            .map(resource -> resource.getClass().getName())
                            .toList());
        }
        return mounts;
    }

    /**
     * Warns once, only when the list is non-empty, naming every enabled catalog entry and manual
     * resource no active application selected, without resolving any of them ({@link
     * GeneratedJaxRsResourceEntry#get()} is never called for an unselected entry).
     *
     * @param entriesByType      the generated resource catalog, keyed by declared type
     * @param manualResources    the resolved manual {@code @JaxRsResources} contributions
     * @param selectedEntryTypes every catalog entry type at least one application selected
     * @param selectedManual     every manual instance at least one application selected
     */
    private static void reportUnselected(
            Map<Class<?>, GeneratedJaxRsResourceEntry> entriesByType,
            Set<Object> manualResources,
            Set<Class<?>> selectedEntryTypes,
            Set<Object> selectedManual) {
        List<String> unselected = new ArrayList<>();
        entriesByType.values().stream()
                .filter(GeneratedJaxRsResourceEntry::enabled)
                .filter(entry -> !selectedEntryTypes.contains(entry.type()))
                .map(entry -> entry.type().getName())
                .forEach(unselected::add);
        manualResources.stream()
                .filter(instance -> !selectedManual.contains(instance))
                .map(instance -> instance.getClass().getName())
                .forEach(unselected::add);
        if (!unselected.isEmpty()) {
            log.warn(
                    "The following JAX-RS resources were not selected by any Application: {}",
                    unselected.stream().sorted().collect(Collectors.joining(", ")));
        }
    }

    /**
     * Returns whether {@code type} is true when {@code actual} is exactly {@code declared}, or when
     * {@code actual} is a direct subclass of {@code declared} that declares no runtime-retained
     * annotation on itself or on any of its own non-synthetic, non-bridge methods or their
     * parameters, and adds no interface beyond those {@code declared} already implements — the shape
     * the framework's AOP proxy has, and the shape a hand-written subclass that adds routes,
     * security, audit, or profile annotations does not have.
     *
     * @param declared the class an application listed, or a catalog entry's declared type
     * @param actual   a candidate instance's runtime type
     * @return {@code true} when {@code actual} keeps {@code declared}'s resource surface
     */
    static boolean sameSurface(Class<?> declared, Class<?> actual) {
        if (actual == declared) {
            return true;
        }
        if (actual.getSuperclass() != declared) {
            return false;
        }
        // Class#getDeclaredAnnotations() (and the method and parameter equivalents below) return
        // only RUNTIME-retention annotations: a source- or class-retention annotation, such as
        // @Override, is never visible through reflection, so no further retention check is needed.
        if (actual.getDeclaredAnnotations().length > 0) {
            return false;
        }
        for (Method method : actual.getDeclaredMethods()) {
            if (method.isSynthetic() || method.isBridge()) {
                continue;
            }
            if (method.getDeclaredAnnotations().length > 0) {
                return false;
            }
            for (Annotation[] parameterAnnotations : method.getParameterAnnotations()) {
                if (parameterAnnotations.length > 0) {
                    return false;
                }
            }
        }
        Set<Class<?>> declaredInterfaces = TypeResolver.getAllInterfaces(declared);
        for (Class<?> addedInterface : actual.getInterfaces()) {
            if (!declaredInterfaces.contains(addedInterface)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns whether {@code type} declares a no-parameter {@code getClasses} or {@code getSingletons}
     * method somewhere from itself up to, but excluding, {@link Application}, which classifies its
     * registration as overriding rather than discovery. The walk always starts from the declared
     * registration type, never from a constructed instance's runtime class.
     *
     * @param type an application's declared type
     * @return {@code true} when the registration is overriding
     */
    private static boolean overrides(Class<? extends Application> type) {
        for (Class<?> current = type;
                current != null && current != Application.class;
                current = current.getSuperclass()) {
            if (declaresNoArgMethod(current, "getClasses") || declaresNoArgMethod(current, "getSingletons")) {
                return true;
            }
        }
        return false;
    }

    private static boolean declaresNoArgMethod(Class<?> type, String name) {
        try {
            type.getDeclaredMethod(name);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * Returns whether {@code type} is annotated {@code @jakarta.ws.rs.ext.Provider}, or is
     * assignable to {@link Feature} or {@link DynamicFeature}: JAX-RS provider and feature types this
     * composer does not support as resource members.
     *
     * @param type a listed member
     * @return {@code true} when {@code type} is an unsupported provider or feature
     */
    private static boolean isUnsupportedProviderOrFeature(Class<?> type) {
        return type.isAnnotationPresent(jakarta.ws.rs.ext.Provider.class)
                || Feature.class.isAssignableFrom(type)
                || DynamicFeature.class.isAssignableFrom(type);
    }

    /**
     * Returns whether {@code type} has an effective {@code @Path}, resolved across its superclass
     * chain and interface hierarchy.
     *
     * @param type a listed member
     * @return {@code true} when {@code type} has an effective {@code @Path}
     */
    private static boolean hasEffectivePath(Class<?> type) {
        return AnnotationResolver.resolveClassAnnotations(type).stream()
                .anyMatch(annotation -> annotation.annotationType() == Path.class);
    }

    /**
     * Returns {@code registration}'s application class and path, as the common prefix of every
     * message naming it.
     *
     * @param registration the registration to describe
     * @return {@code "Application <fully qualified name> at <path>"} — the fully qualified name,
     *     not the simple name, so the message is unambiguous
     */
    private static String appContext(GeneratedJaxRsApplicationRegistration registration) {
        return "Application " + registration.type().getName() + " at " + registration.path();
    }

    /**
     * Returns {@code registration}'s application class and path, like {@link #appContext}, but with
     * the path single-quoted, for step 1b's conflict messages.
     *
     * @param registration the registration to describe
     * @return {@code "Application <fully qualified name> at '<path>'"}
     */
    private static String quotedAppContext(GeneratedJaxRsApplicationRegistration registration) {
        return "Application " + registration.type().getName() + " at '" + registration.path() + "'";
    }

    /**
     * Wraps a {@link RuntimeException} or {@link LinkageError} that escaped {@code create()},
     * {@code getClasses()}, or {@code getSingletons()} while evaluating {@code registration}.
     *
     * @param registration the registration under evaluation
     * @param cause        the original throwable
     * @return the wrapping exception, naming {@code registration} and {@code cause}'s class — never
     *     {@code cause}'s own message, which may carry a configuration value — and carrying
     *     {@code cause} as its own cause, so the original message survives only there
     */
    private static RestConfigurationException wrap(
            GeneratedJaxRsApplicationRegistration registration, Throwable cause) {
        return new RestConfigurationException(
                appContext(registration) + " failed during composition: "
                        + cause.getClass().getName(),
                cause);
    }

    /**
     * Collects, sorts, and throws {@code violations} as one exception.
     *
     * @param violations one or more violation messages
     * @return the aggregated exception
     */
    private static RestConfigurationException buildAggregateException(List<String> violations) {
        String body = violations.stream().sorted().map(v -> "- " + v).collect(Collectors.joining("\n"));
        return new RestConfigurationException("Invalid JAX-RS application composition:\n" + body);
    }

    /**
     * Returns {@code registrationPath}'s mount path: {@code "/"} becomes {@code "/*"}; any other
     * path {@code p} becomes {@code p + "/*"}.
     *
     * @param registrationPath a registration's normalized path
     * @return the mount path
     */
    private static String mountPath(String registrationPath) {
        return "/".equals(registrationPath) ? "/*" : registrationPath + "/*";
    }

    /**
     * One active application's resolved membership: the generated catalog entries and manual
     * instances it selected, in no particular order (the final per-mount order is by fully qualified
     * class name, computed once every application has been evaluated).
     *
     * @param registration    the application's registration
     * @param entries         the catalog entries this application selected
     * @param manualInstances the manual instances this application selected
     */
    private record AppSelection(
            GeneratedJaxRsApplicationRegistration registration,
            List<GeneratedJaxRsResourceEntry> entries,
            List<Object> manualInstances) {}
}
