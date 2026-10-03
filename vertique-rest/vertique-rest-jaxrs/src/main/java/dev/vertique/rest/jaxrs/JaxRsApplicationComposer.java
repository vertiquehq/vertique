// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.core.util.AnnotationResolver;
import dev.vertique.core.util.TypeResolver;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.application.RestApplications;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsResourceEntry;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import jakarta.inject.Provider;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.DynamicFeature;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Composes declared {@code @RestApplication} registrations into one {@link JaxRsRouterMount} per
 * active application, on behalf of {@link RestModule}'s router-mount provider.
 *
 * <p>{@link #compose} runs only when at least one registration is declared; with an empty
 * registration set, {@code RestModule.jaxRsRouterMount} keeps its zero-declaration body and never
 * calls {@link #compose}. Its composition-wide re-entry guard
 * ({@link #enterComposition(JaxRsConfig)}/{@link #exitComposition(JaxRsConfig)}) still wraps that
 * zero-declaration body, so a manually contributed resource that re-enters the same component's
 * composition is still caught even when no application is declared. Every identity, activity, mount
 * path, and contract-location check — including the runtime name-equality check — already ran when
 * the {@link RestApplications} view was built; this class never repeats them. {@link
 * #compose} runs in this order:
 *
 * <ol>
 *   <li>Validates every registration, active or inactive, without resolving any resource: rejects
 *       duplicate generated resource catalog entries of one resource class, an annotation outside
 *       {@link ApplicationAnnotationAllowList}'s allow list anywhere in the declaring interface's
 *       scope (the runtime backstop for the compile-time {@code ApplicationAnnotationValidator}, so
 *       a registration the processor did not produce still fails startup), and a pair of active
 *       registrations whose view mount paths conflict ({@link JaxRsMountPaths}). Logs one
 *       informational line listing every registration. Any violation here aborts before any manual
 *       resource or catalog entry resolves; each violation is reported together with every other
 *       step-one problem under the aggregate exception.
 *   <li>Warns once, without echoing the configured value, when the routing base path is non-default
 *       (it is never applied to an application mount).
 *   <li>With no active registration, resolves and warns about the enabled generated resource
 *       catalog only, contributes no mount, and never resolves the manual resource contributions.
 *   <li>Resolves the manual resource contributions once, then determines each active registration's
 *       membership: a sole discovery registration (already the only declared registration, per the
 *       view) selects every enabled catalog entry and every manual resource; an explicit
 *       registration's {@link GeneratedRestApplicationRegistration#resources()} decide its own
 *       membership, matched against the catalog and the manual contributions through
 *       {@link #sameSurface}; every membership problem across every registration is collected and
 *       thrown together, before any selected resource is resolved.
 *   <li>Each selected catalog entry resolves at most once per composition and is shared across
 *       applications; its resolved instance must keep the entry's declared resource surface.
 *   <li>Builds one mount per active registration, with its resources ordered by fully qualified
 *       class name, at the view's mount path and effective OpenAPI contract location, and logs one
 *       informational line per mount.
 *   <li>Warns once, only when the list is non-empty, naming every enabled catalog entry and manual
 *       resource that no registration selected.
 * </ol>
 */
@Slf4j
final class JaxRsApplicationComposer {

    /** The routing configuration's default base path, applied only in zero-declaration mode. */
    private static final String DEFAULT_BASE_PATH = "/*";

    /**
     * Tracks, per thread, the identity of every component-scoped {@code @Singleton JaxRsConfig}
     * whose composition is currently running on that thread — one entry per {@code RestModule}
     * component nested inside another, never one entry per thread. A manually contributed resource
     * or a generated resource catalog entry whose own construction resolves the SAME component's
     * {@code Set<RouterMount>} again is detected and named instead of recursing until the stack
     * overflows; resolving a DIFFERENT component's {@code Set<RouterMount>} from within that
     * construction succeeds, because {@link JaxRsConfig} is {@code @Singleton}-scoped to its own
     * Dagger component, so distinct components always hand
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

    private JaxRsApplicationComposer() {}

    /**
     * Detects a composition of {@code config}'s own component already running on this thread and
     * marks a new one starting. Called once by {@code RestModule.jaxRsRouterMount}, before either
     * its zero-declaration body or {@link #compose} runs, so the guard covers both branches — the
     * mistake a manually contributed resource or a catalog entry can make by depending on its OWN
     * component's {@code Set<RouterMount>}. Nesting a composition of a DIFFERENT component — a
     * distinct {@code @Singleton JaxRsConfig} instance — inside this one succeeds. A matching
     * {@link #exitComposition(JaxRsConfig)} in the caller's {@code finally} block always follows a
     * successful call.
     *
     * @param config the component-scoped {@code @Singleton JaxRsConfig} identifying this
     *               composition's own component
     * @throws RestConfigurationException when a composition of this same component is already
     *     running on this thread
     */
    static void enterComposition(JaxRsConfig config) {
        Set<JaxRsConfig> composing = COMPOSING.get();
        if (composing == null) {
            composing = Collections.newSetFromMap(new IdentityHashMap<>());
            COMPOSING.set(composing);
        }
        if (!composing.add(config)) {
            throw new RestConfigurationException(
                    "A manually contributed resource or generated resource catalog entry re-entered JAX-RS"
                            + " composition while Set<RouterMount> was still being resolved on this thread; a"
                            + " manually contributed resource's or catalog entry's own construction must not"
                            + " depend on Set<RouterMount>");
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
     * Composes {@code registrations} into one mount per active registration.
     *
     * @param factory       the JAX-RS router mount factory
     * @param view          this component's {@link RestApplications} view, already built (and
     *                      already checked) from {@code registrations}
     * @param registrations every declared native application registration, active or not; never
     *                      empty
     * @param resources     the manual {@code @JaxRsResources} contributions, resolved once, lazily
     * @param catalog       the generated resource catalog, resolved lazily
     * @param config        the JAX-RS routing configuration
     * @return one mount per active registration; empty when no registration is active
     * @throws RestConfigurationException when a step-one, membership, or resolution rule is
     *     violated
     */
    static Set<RouterMount> compose(
            JaxRsRouterMount.Factory factory,
            RestApplications view,
            Set<GeneratedRestApplicationRegistration> registrations,
            Provider<Set<Object>> resources,
            Provider<Set<GeneratedJaxRsResourceEntry>> catalog,
            JaxRsConfig config) {
        // Re-entry into THIS component's composition is detected by RestModule.jaxRsRouterMount's
        // per-component guard (enterComposition(config)/exitComposition(config)), which wraps this
        // call and the zero-declaration body alike, so it is not repeated here.

        // --- Step 1: registration checks (no resource resolves yet) ---

        List<GeneratedRestApplicationRegistration> sortedRegistrations = registrations.stream()
                .sorted(Comparator.comparing(GeneratedRestApplicationRegistration::name))
                .toList();
        String registrationSummary = sortedRegistrations.stream()
                .map(r -> {
                    RestApplications.Entry entry = view.byName(r.name()).orElseThrow();
                    return entry.name() + " (" + entry.declaringType().getName() + ") at " + r.path() + ", active="
                            + entry.active();
                })
                .collect(Collectors.joining(", "));

        Map<Class<?>, GeneratedJaxRsResourceEntry> entriesByType = new HashMap<>();
        Set<Class<?>> duplicateEntryTypes = new LinkedHashSet<>();
        for (GeneratedJaxRsResourceEntry entry : catalog.get()) {
            if (entriesByType.putIfAbsent(entry.type(), entry) != null) {
                duplicateEntryTypes.add(entry.type());
            }
        }

        List<String> stepOneViolations = new ArrayList<>();
        duplicateEntryTypes.forEach(
                type -> stepOneViolations.add("Two or more generated resource catalog entries exist for "
                        + type.getName() + "; declared registrations: " + registrationSummary));

        // Step 1a: the annotation allow-list runtime backstop re-checks every registration's
        // declaring interface and superinterfaces by reflection, active or inactive, before any
        // resource resolves. Each violation applies the same rule as the compile-time validator, with its own
        // message, and is reported with every other step-one problem.
        for (GeneratedRestApplicationRegistration registration : sortedRegistrations) {
            RestApplications.Entry entry = view.byName(registration.name()).orElseThrow();
            stepOneViolations.addAll(ApplicationAnnotationAllowList.violations(entry.name(), entry.declaringType()));
        }

        log.info("Declared JAX-RS application registrations: {}", registrationSummary);

        List<GeneratedRestApplicationRegistration> activeRegistrations = sortedRegistrations.stream()
                .filter(r -> view.byName(r.name()).orElseThrow().active())
                .toList();

        // Step 1b: path conflicts among active registrations, compared by the view's mount path.
        for (int i = 0; i < activeRegistrations.size(); i++) {
            RestApplications.Entry firstEntry =
                    view.byName(activeRegistrations.get(i).name()).orElseThrow();
            for (int j = i + 1; j < activeRegistrations.size(); j++) {
                RestApplications.Entry secondEntry =
                        view.byName(activeRegistrations.get(j).name()).orElseThrow();
                if (JaxRsMountPaths.conflict(firstEntry.mountPath(), secondEntry.mountPath())) {
                    stepOneViolations.add(appContext(firstEntry) + " at '" + firstEntry.mountPath()
                            + "' conflicts with " + appContext(secondEntry) + " at '" + secondEntry.mountPath()
                            + "': their mount paths overlap");
                }
            }
        }

        if (!stepOneViolations.isEmpty()) {
            throw buildAggregateException(stepOneViolations);
        }

        // --- Step 2: the routing base path is not applied to application mounts ---

        if (!DEFAULT_BASE_PATH.equals(config.basePath())) {
            log.warn("jaxrs.basePath is configured, but it is not applied to application mounts when one or more"
                    + " @RestApplication declarations are present; every declared application is mounted at its"
                    + " own @RestApplication path");
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

        // --- Step 4 to 6: determine every active registration's membership ---

        Set<Object> manualResources = resources.get();

        List<String> membershipViolations = new ArrayList<>();
        List<AppSelection> selections = new ArrayList<>();
        for (GeneratedRestApplicationRegistration registration : activeRegistrations) {
            evaluateApplication(registration, entriesByType, manualResources, membershipViolations, selections);
        }
        if (!membershipViolations.isEmpty()) {
            throw buildAggregateException(membershipViolations);
        }

        // --- Step 7 and 8: resolve selected resources once each, and mount every registration ---

        Set<Class<?>> selectedEntryTypes = new HashSet<>();
        Set<Object> selectedManual = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<RouterMount> mounts = resolveAndMount(factory, view, selections, selectedEntryTypes, selectedManual);

        // --- Step 9: report enabled resources no registration selected ---

        reportUnselected(entriesByType, manualResources, selectedEntryTypes, selectedManual);

        return Collections.unmodifiableSet(mounts);
    }

    /**
     * Determines one active registration's membership: a sole discovery registration selects every
     * enabled catalog entry and every manual resource; an explicit registration's
     * {@link GeneratedRestApplicationRegistration#resources()} are matched one by one. Adds the
     * outcome to {@code selections}, or one or more messages to {@code violations} per offending
     * listed member; never both for the same listed member.
     *
     * @param registration    the registration to evaluate
     * @param entriesByType   the generated resource catalog, keyed by declared type
     * @param manualResources the resolved manual {@code @JaxRsResources} contributions
     * @param violations      membership violation messages accumulate here
     * @param selections      this and every other evaluated registration's selection accumulate here
     */
    private static void evaluateApplication(
            GeneratedRestApplicationRegistration registration,
            Map<Class<?>, GeneratedJaxRsResourceEntry> entriesByType,
            Set<Object> manualResources,
            List<String> violations,
            List<AppSelection> selections) {
        if (registration.discover()) {
            List<GeneratedJaxRsResourceEntry> entries = entriesByType.values().stream()
                    .filter(GeneratedJaxRsResourceEntry::enabled)
                    .toList();
            selections.add(new AppSelection(registration, entries, List.copyOf(manualResources)));
            return;
        }

        List<GeneratedJaxRsResourceEntry> selectedEntries = new ArrayList<>();
        List<Object> selectedManual = new ArrayList<>();
        for (Class<?> listed : registration.resources()) {
            evaluateListedClass(
                    registration, listed, entriesByType, manualResources, violations, selectedEntries, selectedManual);
        }
        selections.add(new AppSelection(registration, selectedEntries, selectedManual));
    }

    /**
     * Evaluates one member listed by an explicit registration's {@link
     * GeneratedRestApplicationRegistration#resources()}, appending exactly one violation message
     * when it is rejected, or adding the resolved catalog entry or manual instance to the
     * registration's selection when it is accepted.
     *
     * @param registration    the registration under evaluation, for the violation message
     * @param listed          the listed member
     * @param entriesByType   the generated resource catalog, keyed by declared type
     * @param manualResources the resolved manual {@code @JaxRsResources} contributions
     * @param violations      a rejection message is appended here, if any
     * @param selectedEntries an accepted catalog entry is appended here
     * @param selectedManual  an accepted manual instance is appended here
     */
    private static void evaluateListedClass(
            GeneratedRestApplicationRegistration registration,
            Class<?> listed,
            Map<Class<?>, GeneratedJaxRsResourceEntry> entriesByType,
            Set<Object> manualResources,
            List<String> violations,
            List<GeneratedJaxRsResourceEntry> selectedEntries,
            List<Object> selectedManual) {
        if (isUnsupportedProviderOrFeature(listed)) {
            violations.add(appContext(registration) + " lists " + listed.getName() + ", which is a JAX-RS"
                    + " provider or feature type (@Provider, Feature, or DynamicFeature) and is not a supported"
                    + " resource member");
            return;
        }
        if (listed.isInterface() || Modifier.isAbstract(listed.getModifiers()) || !hasEffectivePath(listed)) {
            violations.add(appContext(registration) + " lists " + listed.getName() + ", which is not a concrete"
                    + " JAX-RS root resource (interface, abstract, or missing an effective @Path)");
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
                violations.add(appContext(registration) + " lists " + listed.getName() + ", but its only bound"
                        + " instance is " + subclass.getName() + ", a subclass with a different resource"
                        + " surface; list " + subclass.getName() + " explicitly");
            } else {
                violations.add(appContext(registration) + " lists " + listed.getName() + ", but no generated"
                        + " resource entry or manual @JaxRsResources instance of that class is bound");
            }
            return;
        }
        if (matchCount > 1) {
            violations.add(appContext(registration) + " lists " + listed.getName() + ", which matches more than"
                    + " one bound resource (a generated catalog entry and a manual contribution, or two or more"
                    + " manual contributions)");
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
     * registrations) and builds one mount per registration, at the view's mount path and effective
     * OpenAPI contract location, logging one informational line per mount.
     *
     * @param factory            the JAX-RS router mount factory
     * @param view               this component's {@link RestApplications} view
     * @param selections         every active registration's selection, in mounting order
     * @param selectedEntryTypes every resolved catalog entry's declared type accumulates here
     * @param selectedManualOut  every selected manual instance accumulates here
     * @return one mount per registration, in mounting order
     * @throws RestConfigurationException when a resolved catalog instance is {@code null}, or does
     *     not keep its entry's declared resource surface
     */
    private static Set<RouterMount> resolveAndMount(
            JaxRsRouterMount.Factory factory,
            RestApplications view,
            List<AppSelection> selections,
            Set<Class<?>> selectedEntryTypes,
            Set<Object> selectedManualOut) {
        Map<GeneratedJaxRsResourceEntry, Object> resolved = new HashMap<>();
        Set<RouterMount> mounts = new LinkedHashSet<>();
        for (AppSelection selection : selections) {
            GeneratedRestApplicationRegistration registration = selection.registration();
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

            RestApplications.Entry entry = view.byName(registration.name()).orElseThrow();
            JaxRsRouterMount mount = factory.createApplicationMount(
                    entry.mountPath(),
                    entry.effectiveOpenapiPath(),
                    orderedResources,
                    entry.name(),
                    entry.declaringType());
            mounts.add(mount);

            log.info(
                    "Mounted JAX-RS application '{}' ({}) at {} with resources: {}",
                    entry.name(),
                    entry.declaringType().getName(),
                    entry.mountPath(),
                    ordered.stream()
                            .map(resource -> resource.getClass().getName())
                            .toList());
        }
        return mounts;
    }

    /**
     * Warns once, only when the list is non-empty, naming every enabled catalog entry and manual
     * resource no active registration selected, without resolving any of them ({@link
     * GeneratedJaxRsResourceEntry#get()} is never called for an unselected entry).
     *
     * @param entriesByType      the generated resource catalog, keyed by declared type
     * @param manualResources    the resolved manual {@code @JaxRsResources} contributions
     * @param selectedEntryTypes every catalog entry type at least one registration selected
     * @param selectedManual     every manual instance at least one registration selected
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
     * @param declared the class a registration listed, or a catalog entry's declared type
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
     * Returns {@code registration}'s application name and declaring interface, as the common prefix
     * of every message naming it.
     *
     * @param registration the registration to describe
     * @return {@code "Application '<name>' (<declaring interface's fully qualified name>)"}
     */
    private static String appContext(GeneratedRestApplicationRegistration registration) {
        return "Application '" + registration.name() + "' ("
                + registration.declaringType().getName() + ")";
    }

    /**
     * Returns {@code entry}'s application name and declaring interface, as the common prefix of
     * every message naming it, read from the view rather than a registration wherever a view entry
     * is already in hand.
     *
     * @param entry the view entry to describe
     * @return {@code "Application '<name>' (<declaring interface's fully qualified name>)"}
     */
    private static String appContext(RestApplications.Entry entry) {
        return "Application '" + entry.name() + "' (" + entry.declaringType().getName() + ")";
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
     * One active registration's resolved membership: the generated catalog entries and manual
     * instances it selected, in no particular order (the final per-mount order is by fully qualified
     * class name, computed once every registration has been evaluated).
     *
     * @param registration    the registration
     * @param entries         the catalog entries this registration selected
     * @param manualInstances the manual instances this registration selected
     */
    private record AppSelection(
            GeneratedRestApplicationRegistration registration,
            List<GeneratedJaxRsResourceEntry> entries,
            List<Object> manualInstances) {}
}
