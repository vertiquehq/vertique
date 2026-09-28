// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.jaxrs.publication.ApiDocsInstalled;
import dev.vertique.rest.jaxrs.publication.RestApplications;
import dev.vertique.rest.jaxrs.publication.RestApplications.ContractOrigin;
import dev.vertique.rest.jaxrs.publication.RestApplications.Entry;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Builds the {@link RestApplications} view: the aggregated checks this view's construction requires
 * — once per component, even with zero registrations, before any resource resolves — and the
 * derivation of each declared application's mount path, effective OpenAPI contract location, and
 * contract origin. Package-private: {@code RestModule}'s {@code @Singleton}
 * {@code restApplications} provider is this class's sole caller. This class's logger is the
 * view's own log, used by the INFO line reported when the OpenAPI documentation module is absent.
 */
@Slf4j
final class RestApplicationsBuilder {

    private static final String INVALID_COMPOSITION_HEADER = "Invalid JAX-RS application composition:";

    private RestApplicationsBuilder() {}

    /**
     * Builds the view from {@code registrations}, the parsed {@code jaxrs.applications} entries,
     * the global OpenAPI contract default, and whether the OpenAPI documentation module is present.
     *
     * @param registrations          every declared native application registration, active or not
     * @param configuredApplications the parsed {@code jaxrs.applications} entries, in the
     *                                configuration section's order
     * @param config                 the JAX-RS routing configuration, supplying the global
     *                                {@code jaxrs.openapiPath} default
     * @param apiDocsInstalled       present when the OpenAPI documentation module is included in
     *                                this component
     * @return the built view
     * @throws RestConfigurationException when a name is duplicated across registrations, a name is
     *     reserved ({@code none} or {@code null}), a declaring interface's runtime
     *     {@code @RestApplication.name()} does not match its registration (or the annotation is
     *     missing), a {@code discover = true} registration is not the sole declared registration, or
     *     a configured {@code jaxrs.applications.<name>} matches no registration
     */
    static RestApplications build(
            Set<GeneratedRestApplicationRegistration> registrations,
            List<RestApplicationConfig> configuredApplications,
            JaxRsConfig config,
            Optional<ApiDocsInstalled> apiDocsInstalled) {
        List<GeneratedRestApplicationRegistration> sorted = registrations.stream()
                .sorted(Comparator.comparing(GeneratedRestApplicationRegistration::name))
                .toList();

        List<String> violations = new ArrayList<>();
        checkDuplicateNames(sorted, violations);
        checkReservedNames(sorted, violations);
        checkNameIdentity(sorted, violations);
        checkSoleDiscovery(sorted, violations);
        checkConfiguredNames(sorted, configuredApplications, violations);

        if (!violations.isEmpty()) {
            throw buildAggregateException(violations);
        }

        Map<String, RestApplicationConfig> configByName = new LinkedHashMap<>();
        for (RestApplicationConfig entryConfig : configuredApplications) {
            configByName.put(entryConfig.name(), entryConfig);
        }

        List<Entry> entries = sorted.stream()
                .map(registration -> toEntry(registration, configByName.get(registration.name()), config))
                .toList();

        logApiDocsNotInstalled(sorted, apiDocsInstalled);

        return new RestApplications(entries);
    }

    /**
     * Refuses a name shared by two or more registrations, active or not, naming the name and every
     * declaring interface.
     */
    private static void checkDuplicateNames(
            List<GeneratedRestApplicationRegistration> sorted, List<String> violations) {
        Map<String, List<GeneratedRestApplicationRegistration>> byName = new LinkedHashMap<>();
        for (GeneratedRestApplicationRegistration registration : sorted) {
            byName.computeIfAbsent(registration.name(), key -> new ArrayList<>())
                    .add(registration);
        }
        byName.forEach((name, registrationsOfName) -> {
            if (registrationsOfName.size() > 1) {
                String declaringTypes = registrationsOfName.stream()
                        .map(registration -> registration.declaringType().getName())
                        .sorted()
                        .collect(Collectors.joining(", "));
                violations.add(
                        "Application name '" + name + "' is declared by more than one registration: " + declaringTypes);
            }
        });
    }

    /**
     * Refuses the reserved names {@code none} and {@code null}, whether the registration is active
     * or not. The factory already refuses them for a registration it produces; this defends a
     * registration built by hand, bypassing the factory.
     */
    private static void checkReservedNames(List<GeneratedRestApplicationRegistration> sorted, List<String> violations) {
        for (GeneratedRestApplicationRegistration registration : sorted) {
            String name = registration.name();
            if ("none".equals(name) || "null".equals(name)) {
                violations.add("Application name '" + name + "' declared by "
                        + registration.declaringType().getName() + " is reserved");
            }
        }
    }

    /**
     * Refuses a declaring interface whose runtime {@code @RestApplication.name()} differs from its
     * registration's name, or that carries no {@code @RestApplication} at runtime, naming both
     * names or the missing annotation.
     */
    private static void checkNameIdentity(List<GeneratedRestApplicationRegistration> sorted, List<String> violations) {
        for (GeneratedRestApplicationRegistration registration : sorted) {
            Class<?> declaringType = registration.declaringType();
            RestApplication annotation = declaringType.getAnnotation(RestApplication.class);
            if (annotation == null) {
                violations.add("Application declared by " + declaringType.getName() + " registers as '"
                        + registration.name() + "', but it carries no runtime @RestApplication annotation");
                continue;
            }
            if (!annotation.name().equals(registration.name())) {
                violations.add("Application declared by " + declaringType.getName() + " registers as '"
                        + registration.name() + "', but its runtime @RestApplication.name() is '"
                        + annotation.name() + "'");
            }
        }
    }

    /**
     * Refuses a {@code discover = true} registration that is not the sole declared registration,
     * whether either registration is active or not.
     */
    private static void checkSoleDiscovery(List<GeneratedRestApplicationRegistration> sorted, List<String> violations) {
        if (sorted.size() <= 1) {
            return;
        }
        String declaredSummary = sorted.stream()
                .map(registration -> registration.name() + " ("
                        + registration.declaringType().getName() + ", active=" + registration.active() + ")")
                .collect(Collectors.joining(", "));
        for (GeneratedRestApplicationRegistration registration : sorted) {
            if (registration.discover()) {
                violations.add("Application '" + registration.name() + "' ("
                        + registration.declaringType().getName()
                        + ") requests discovery membership (discover = true), which requires it to be the sole"
                        + " declared application; declared applications: " + declaredSummary);
            }
        }
    }

    /**
     * Refuses a configured {@code jaxrs.applications.<name>} whose name matches no declared
     * registration, active or not, naming the configuration path and never its value.
     */
    private static void checkConfiguredNames(
            List<GeneratedRestApplicationRegistration> sorted,
            List<RestApplicationConfig> configuredApplications,
            List<String> violations) {
        Set<String> registeredNames = new HashSet<>();
        for (GeneratedRestApplicationRegistration registration : sorted) {
            registeredNames.add(registration.name());
        }
        Set<String> unknownPaths = new TreeSet<>();
        for (RestApplicationConfig entryConfig : configuredApplications) {
            if (!registeredNames.contains(entryConfig.name())) {
                unknownPaths.add("jaxrs.applications." + entryConfig.name());
            }
        }
        for (String path : unknownPaths) {
            violations.add("Configuration '" + path + "' does not match any declared application name");
        }
    }

    /**
     * Derives one registration's entry: its mount path, and its effective OpenAPI contract
     * location by precedence — a configured {@code jaxrs.applications.<name>.openapiPath} when
     * non-null, else the registration's own non-empty {@code openapiPath}, else the global
     * {@code jaxrs.openapiPath}.
     */
    private static Entry toEntry(
            GeneratedRestApplicationRegistration registration, RestApplicationConfig configEntry, JaxRsConfig config) {
        String mountPath = mountPath(registration.path());
        String effectiveOpenapiPath;
        ContractOrigin origin;
        if (configEntry != null && configEntry.openapiPath() != null) {
            effectiveOpenapiPath = configEntry.openapiPath();
            origin = ContractOrigin.CONFIGURATION;
        } else if (!registration.openapiPath().isEmpty()) {
            effectiveOpenapiPath = registration.openapiPath();
            origin = ContractOrigin.ANNOTATION;
        } else {
            effectiveOpenapiPath = config.openapiPath();
            origin = ContractOrigin.GLOBAL;
        }
        return new Entry(
                registration.name(),
                registration.declaringType(),
                registration.active(),
                mountPath,
                effectiveOpenapiPath,
                origin);
    }

    /**
     * Derives a mount path from a registration's normalized path: {@code "/*"} for the root path
     * {@code "/"}, otherwise the path plus {@code "/*"}.
     */
    private static String mountPath(String registrationPath) {
        return "/".equals(registrationPath) ? "/*" : registrationPath + "/*";
    }

    /**
     * Logs one INFO line per active registration whose declaring interface carries an annotation
     * named {@link ApiDocsInstalled#ANNOTATION_NAME}, only when {@code apiDocsInstalled} is empty.
     * Runs once per component, since this builder runs once per {@code @Singleton RestApplications}
     * resolution.
     */
    private static void logApiDocsNotInstalled(
            List<GeneratedRestApplicationRegistration> sorted, Optional<ApiDocsInstalled> apiDocsInstalled) {
        if (apiDocsInstalled.isPresent()) {
            return;
        }
        for (GeneratedRestApplicationRegistration registration : sorted) {
            if (!registration.active()) {
                continue;
            }
            if (carriesApiDocsAnnotation(registration.declaringType())) {
                log.info(
                        "Application '{}' ({}) carries @ApiDocs, but the OpenAPI documentation module is not"
                                + " included in this component; no documentation route is published for it",
                        registration.name(),
                        registration.declaringType().getName());
            }
        }
    }

    /**
     * Returns whether {@code declaringType} carries a runtime-visible annotation whose type name
     * equals {@link ApiDocsInstalled#ANNOTATION_NAME}, read by name so this module never depends on
     * the docs module that declares the real annotation.
     */
    private static boolean carriesApiDocsAnnotation(Class<?> declaringType) {
        for (Annotation annotation : declaringType.getDeclaredAnnotations()) {
            if (annotation.annotationType().getName().equals(ApiDocsInstalled.ANNOTATION_NAME)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Collects, sorts, and throws {@code violations} as one exception under
     * {@value #INVALID_COMPOSITION_HEADER}.
     */
    private static RestConfigurationException buildAggregateException(List<String> violations) {
        String body = violations.stream().sorted().map(v -> "- " + v).collect(Collectors.joining("\n"));
        return new RestConfigurationException(INVALID_COMPOSITION_HEADER + "\n" + body);
    }
}
