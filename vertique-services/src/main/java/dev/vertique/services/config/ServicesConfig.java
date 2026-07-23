// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.config;

import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed model of the {@code services} configuration section.
 *
 * <p>The external shape nests the per-service config under a {@code contracts} object two levels
 * deep — {@code services.contracts.{namespace}.{name}} — with a sibling global
 * {@code services.sendTimeoutMs} scalar. This record is the typed, validated result assembled at the
 * Dagger provider boundary (see {@code DispatchModule}); module internals depend on it (or on its
 * {@link #index()}), never on the raw {@link JsonObject}.
 *
 * <p>Each {@code {namespace}} key under {@code contracts} is a group of named services; within a
 * group every {@code {name}} key maps to a {@link ServiceConfig}. The reserved key {@code _} (see
 * {@link #DEFAULT_NAMESPACE_KEY}) addresses the empty default namespace — it is mapped to the empty
 * string before parsing. The boundary parser injects the {@code name} (the keyed-object key) and the
 * {@code namespace} (the namespace-group key, as a fixed property) into each entry's JSON before
 * {@link ServiceConfig}'s validating compact constructor runs, so both identity fields are present at
 * construction. Operations within a service are the keyed object {@code operations.{operation}}.
 *
 * @param sendTimeoutMs the global {@code services.sendTimeoutMs} scalar in milliseconds, or
 *     {@code null} when absent
 * @param services the flat list of all configured services across every namespace group; identity per
 *     element is {@code (namespace, name)}
 */
public record ServicesConfig(Long sendTimeoutMs, List<ServiceConfig> services) {

    /**
     * The {@code sendTimeoutMs} key is a global scalar sibling of the {@code contracts} object, not a
     * service group — it is read directly from the {@code services} section.
     */
    private static final String SEND_TIMEOUT_KEY = "sendTimeoutMs";

    /**
     * The sub-object under {@code services} that holds the per-namespace service groups.
     */
    private static final String CONTRACTS_KEY = "contracts";

    /**
     * The reserved namespace-group key that addresses the empty default namespace. The external
     * config cannot use an empty JSON key, so {@code _} stands in for {@code ""} and is mapped to the
     * empty string before parsing.
     */
    private static final String DEFAULT_NAMESPACE_KEY = "_";

    /**
     * Compact constructor validating the global send timeout and copying the services list
     * defensively for immutability. When {@code sendTimeoutMs} is present it must be {@code > 0};
     * an absent ({@code null}) value is permitted and means "not configured". Fails fast at startup
     * so a malformed global timeout never reaches runtime.
     *
     * @param sendTimeoutMs the global send timeout scalar in milliseconds ({@code > 0} when present),
     *     or {@code null} when absent
     * @param services the list of service configs (defensively copied; {@code null} becomes empty)
     * @throws ConfigurationException if {@code sendTimeoutMs} is present and not {@code > 0}
     */
    public ServicesConfig {
        if (sendTimeoutMs != null && sendTimeoutMs <= 0) {
            throw new ConfigurationException("services.sendTimeoutMs must be > 0, got " + sendTimeoutMs);
        }
        services = services != null ? List.copyOf(services) : List.of();
    }

    /**
     * Identity key for a service: the {@code (namespace, name)} pair. Duplicate identities cannot
     * arise from JSON object keys (the source loader collapses duplicate keys), so the index is built
     * after per-record validation without further duplicate rejection.
     *
     * @param namespace the service namespace segment (empty string for the default namespace)
     * @param name the service name
     */
    public record ServiceKey(String namespace, String name) {}

    /**
     * Parses the {@code services} section of a root config object into a typed {@link ServicesConfig}.
     *
     * <p>Reads the global {@code sendTimeoutMs} scalar directly from the {@code services} section,
     * then navigates into the {@code contracts} sub-object and, for every namespace-group key, parses
     * the group's {@code {name}} entries into {@link ServiceConfig} via
     * {@link ConfigParser#parseKeyedObject(JsonObject, String, Class, Map)} — injecting {@code name}
     * (the entry key) and {@code namespace} (the namespace-group key, as a fixed property) — and
     * flattens every group into a single list. The reserved key {@code _} maps to the empty default
     * namespace ({@code ""}).
     *
     * <p>Malformed structure fails fast: a {@code contracts} value that is not a JSON object, or any
     * namespace-group key bound to a non-object (scalar, array, or explicit null), throws a
     * path-bearing {@link ConfigurationException} rather than being silently skipped and defaulted.
     *
     * @param rootConfig the root application config, qualified {@link VertxConfig} at the boundary
     * @param parser the injected config parser
     * @return the parsed, validated services config
     * @throws ConfigurationException if {@code contracts} or any namespace group is present but not a
     *     JSON object, or if any parsed record fails its own validation
     */
    public static ServicesConfig fromConfig(JsonObject rootConfig, ConfigParser parser) {
        JsonObject section = JsonConfigPaths.navigateObject(rootConfig, "services");
        Long globalSendTimeoutMs = section.getLong(SEND_TIMEOUT_KEY);

        JsonObject contracts = JsonConfigPaths.navigateObject(section, CONTRACTS_KEY);

        List<ServiceConfig> services = new ArrayList<>();
        for (String nsKey : contracts.fieldNames()) {
            Object nsValue = contracts.getValue(nsKey);
            // A namespace group that is present but not a JSON object (scalar, array, or explicit
            // null) is malformed config: fail fast rather than silently skip it and fall back to
            // defaults.
            if (!(nsValue instanceof JsonObject nsGroup)) {
                throw new ConfigurationException("services.contracts." + nsKey
                        + " must be a JSON object, got "
                        + (nsValue == null ? "null" : nsValue.getClass().getSimpleName()));
            }
            String namespace = DEFAULT_NAMESPACE_KEY.equals(nsKey) ? "" : nsKey;
            services.addAll(
                    parser.parseKeyedObject(nsGroup, "name", ServiceConfig.class, Map.of("namespace", namespace)));
        }
        return new ServicesConfig(globalSendTimeoutMs, services);
    }

    /**
     * Builds an immutable {@code (namespace, name) -> ServiceConfig} lookup index over
     * {@link #services()}.
     *
     * <p>Built after the per-record validation already performed at parse time; insertion order is
     * preserved.
     *
     * @return an immutable index keyed by {@link ServiceKey}
     */
    public Map<ServiceKey, ServiceConfig> index() {
        Map<ServiceKey, ServiceConfig> index = new LinkedHashMap<>();
        for (ServiceConfig service : services) {
            index.put(new ServiceKey(service.namespace(), service.name()), service);
        }
        return Map.copyOf(index);
    }
}
