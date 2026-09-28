// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication;

import jakarta.annotation.Nullable;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * INTERNAL, component-scoped {@code @Singleton} view over every declared {@code @RestApplication}:
 * for each one, its name, declaring type, active flag, mount path as registered, effective OpenAPI
 * contract location, and the setting that decided that location. Outside the maturity promise: not
 * an application contract, and public only so sibling framework modules — the JAX-RS application
 * composer, the mount composition validator, and the OpenAPI documentation module — can read it.
 *
 * <p>Built once per component from {@code Set<GeneratedRestApplicationRegistration>} and the parsed
 * {@code jaxrs.applications} configuration. Every declared application is listed, active or not;
 * {@link #all()} is ordered by name so its order never depends on registration or configuration
 * order.
 */
public final class RestApplications {

    private final List<Entry> all;
    private final Map<String, Entry> byName;

    /**
     * Builds the view from the given entries, sorted by {@link Entry#name()}.
     *
     * @param entries every declared application's entry, in any order; the view sorts a defensive
     *                copy by name
     */
    public RestApplications(List<Entry> entries) {
        List<Entry> sorted =
                entries.stream().sorted(Comparator.comparing(Entry::name)).toList();
        this.all = sorted;
        Map<String, Entry> index = new LinkedHashMap<>();
        for (Entry entry : sorted) {
            index.put(entry.name(), entry);
        }
        this.byName = Map.copyOf(index);
    }

    /**
     * Finds a declared application by name.
     *
     * @param name the application name to look up
     * @return the matching entry, or {@link Optional#empty()} when no declared application carries
     *     this name
     */
    public Optional<Entry> byName(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /**
     * Returns every declared application, active or not, ordered by name.
     *
     * @return the unmodifiable, by-name-ordered list of every declared application's entry
     */
    public List<Entry> all() {
        return all;
    }

    /**
     * One declared application's identity and effective composition facts.
     *
     * @param name                 the application name
     * @param declaringType        the declaring interface
     * @param active                whether this registration's evaluated conditional activation
     *                              currently matches
     * @param mountPath             the application's mount path as registered, with a trailing
     *                              {@code "/*"} ({@code "/*"} alone for the root path {@code "/"})
     * @param effectiveOpenapiPath the effective OpenAPI contract location, or {@code null}
     * @param contractOrigin       the setting that decided {@code effectiveOpenapiPath}
     */
    public record Entry(
            String name,
            Class<?> declaringType,
            boolean active,
            String mountPath,
            @Nullable String effectiveOpenapiPath,
            ContractOrigin contractOrigin) {}

    /**
     * The setting that decided an entry's {@link Entry#effectiveOpenapiPath()}, in precedence
     * order: a configured {@code jaxrs.applications.<name>.openapiPath} outranks the declaration's
     * own {@code @RestApplication.openapiPath}, which outranks the global {@code
     * jaxrs.openapiPath}.
     */
    public enum ContractOrigin {

        /** The location came from {@code jaxrs.applications.<name>.openapiPath}. */
        CONFIGURATION,

        /** The location came from the declaration's own non-empty {@code @RestApplication.openapiPath}. */
        ANNOTATION,

        /** The location came from the global {@code jaxrs.openapiPath}. */
        GLOBAL
    }
}
