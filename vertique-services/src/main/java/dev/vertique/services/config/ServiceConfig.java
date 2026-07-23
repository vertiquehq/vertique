// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.json.KeyedBy;
import dev.vertique.deploy.SupervisionConfig;
import jakarta.annotation.Nullable;
import java.util.List;

/**
 * Typed per-service configuration read from {@code services.contracts.{namespace}.{name}}.
 *
 * <p>Identity is the {@code (namespace, name)} pair. Both are injected during boundary parsing: the
 * {@code name} is the keyed-object key under the namespace group, and the {@code namespace} is the
 * constant namespace-group key injected as a fixed property — so the compact constructor sees both
 * populated before it validates them. The {@code operations} list is the keyed object
 * {@code operations.{operation}}, parsed via {@link KeyedBy @KeyedBy("operation")} which injects each
 * operation key into {@link ServiceOperationConfig#operation()}.
 *
 * <p>An empty {@code namespace} segment is permitted (the default namespace): the external config
 * uses the {@code _} sentinel key to address it, which {@link ServicesConfig} maps to the empty
 * string before parsing. The address path simply omits the namespace segment when it is empty. The
 * compact constructor therefore validates only {@code name} as non-blank; {@code namespace} may be
 * blank.
 *
 * @param namespace the service namespace segment (identity); injected from the namespace-group key
 *     ({@code _} maps to the empty default namespace)
 * @param name the service name (identity); injected from the keyed-object key
 * @param instances the number of verticle instances to deploy ({@code >= 1}; default {@code 1})
 * @param worker {@code true} to deploy on the worker thread pool (default {@code false})
 * @param sendTimeoutMs the per-service event bus send timeout override in milliseconds ({@code > 0}
 *     when present), or {@code null} when not overridden
 * @param supervision the supervision configuration (default {@link SupervisionConfig#DEFAULT})
 * @param operations the per-operation configuration overrides keyed by operation id (default empty)
 */
public record ServiceConfig(
        String namespace,
        String name,
        int instances,
        boolean worker,
        Long sendTimeoutMs,
        SupervisionConfig supervision,
        @KeyedBy("operation") List<ServiceOperationConfig> operations) {

    /**
     * Compact validator: {@code name} must be non-blank, {@code instances} must be at least
     * {@code 1}, and {@code sendTimeoutMs} (when present) must be {@code > 0}. The {@code namespace}
     * may be blank (the empty default namespace). Fails fast at startup so malformed config never
     * reaches runtime.
     *
     * @throws ConfigurationException if {@code name} is blank, {@code instances < 1}, or
     *     {@code sendTimeoutMs} is present and not {@code > 0}
     */
    public ServiceConfig {
        if (name == null || name.isBlank()) {
            throw new ConfigurationException("services.contracts." + namespace + ".<name> must be non-blank");
        }
        if (instances < 1) {
            throw new ConfigurationException(
                    "services.contracts." + namespace + "." + name + ".instances must be >= 1, got " + instances);
        }
        if (sendTimeoutMs != null && sendTimeoutMs <= 0) {
            throw new ConfigurationException("services.contracts." + namespace + "." + name
                    + ".sendTimeoutMs must be > 0, got " + sendTimeoutMs);
        }
        operations = operations != null ? List.copyOf(operations) : List.of();
    }

    /**
     * Jackson factory filling defaults for omitted properties: {@code instances=1}, {@code
     * worker=false}, {@code supervision=}{@link SupervisionConfig#DEFAULT}, and {@code operations}
     * empty. {@code namespace} and {@code name} are identity fields injected before parsing;
     * {@code name} is validated non-blank by the compact constructor, {@code namespace} may be blank.
     *
     * @param namespace the service namespace (injected identity); may be blank (empty default namespace)
     * @param name the service name (injected identity); must be present
     * @param instances number of instances; defaults to {@code 1} when {@code null}
     * @param worker worker threading flag; defaults to {@code false} when {@code null}
     * @param sendTimeoutMs per-service send timeout override; passed through (nullable)
     * @param supervision supervision config; defaults to {@link SupervisionConfig#DEFAULT} when
     *     {@code null}
     * @param operations per-operation overrides; defaults to empty when {@code null}
     * @return the deserialized config with defaults applied
     */
    @JsonCreator
    static ServiceConfig fromJson(
            @JsonProperty("namespace") @Nullable String namespace,
            @JsonProperty("name") @Nullable String name,
            @JsonProperty("instances") @Nullable Integer instances,
            @JsonProperty("worker") @Nullable Boolean worker,
            @JsonProperty("sendTimeoutMs") @Nullable Long sendTimeoutMs,
            @JsonProperty("supervision") @Nullable SupervisionConfig supervision,
            @JsonProperty("operations") @Nullable List<ServiceOperationConfig> operations) {
        return new ServiceConfig(
                namespace,
                name,
                instances != null ? instances : 1,
                worker != null ? worker : false,
                sendTimeoutMs,
                supervision != null ? supervision : SupervisionConfig.DEFAULT,
                operations != null ? operations : List.of());
    }
}
