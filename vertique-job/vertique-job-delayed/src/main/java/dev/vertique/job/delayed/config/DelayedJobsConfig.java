// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.core.json.KeyedBy;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed model of the {@code delayedJob} configuration section.
 *
 * <p>This is the typed, validated result assembled at the Dagger provider boundary (see
 * {@code DelayedJobModule}); module internals depend on it (or on the {@link #queueIndex()}/
 * {@link #contractIndex()} indexes), never on the raw {@link JsonObject}. Both {@code queues} and
 * {@code contracts} are keyed objects ({@code delayedJob.queues.{name}},
 * {@code delayedJob.contracts.{name}}); each key is injected into the element's identity field via
 * {@link KeyedBy @KeyedBy("name")} during boundary parsing.
 *
 * <p>The top-level {@code delayedJob} section name is preserved (not renamed). Queue records carry
 * the six poller-tuning fields with defaults; contract records carry the three per-contract
 * overrides that win over the {@link dev.vertique.job.delayed.DelayedJobContract} annotation.
 *
 * @param queues the configured queues; identity per element is {@code name} (default empty)
 * @param contracts the configured contract overrides; identity per element is {@code name} (default
 *     empty)
 */
public record DelayedJobsConfig(
        @KeyedBy("name") List<DelayedJobQueueConfig> queues,
        @KeyedBy("name") List<DelayedJobContractConfig> contracts) {

    /**
     * Compact constructor copying the keyed-collection lists defensively for immutability.
     *
     * @param queues the queue list (defensively copied; {@code null} becomes empty)
     * @param contracts the contract list (defensively copied; {@code null} becomes empty)
     */
    public DelayedJobsConfig {
        queues = queues != null ? List.copyOf(queues) : List.of();
        contracts = contracts != null ? List.copyOf(contracts) : List.of();
    }

    /**
     * Jackson factory for the section: both lists default to empty when absent.
     *
     * @param queues the queue list; defaults to empty when {@code null}
     * @param contracts the contract list; defaults to empty when {@code null}
     * @return the deserialized config with defaults applied
     */
    @JsonCreator
    static DelayedJobsConfig fromJson(
            @JsonProperty("queues") @Nullable List<DelayedJobQueueConfig> queues,
            @JsonProperty("contracts") @Nullable List<DelayedJobContractConfig> contracts) {
        return new DelayedJobsConfig(queues != null ? queues : List.of(), contracts != null ? contracts : List.of());
    }

    /**
     * Parses the {@code delayedJob} section of a root config object into a typed
     * {@link DelayedJobsConfig} via the shared {@link ConfigParser} (which injects each
     * {@code queues}/{@code contracts} key into the element identity field), then validates each
     * queue's invariants so malformed config fails fast at startup.
     *
     * <p>Contract identity (non-blank {@code name}) is validated in the record's compact constructor
     * during parsing; queue identity and field invariants are validated here via
     * {@link DelayedJobQueueConfig#validate()} (a {@code @Builder}-built class cannot validate in a
     * compact constructor).
     *
     * @param rootConfig the root application config, qualified {@code @VertxConfig} at the boundary
     * @param parser the injected config parser
     * @return the parsed, validated delayed-job config
     * @throws dev.vertique.core.exception.ConfigurationException if any queue or contract is invalid
     */
    public static DelayedJobsConfig fromConfig(JsonObject rootConfig, ConfigParser parser) {
        JsonObject section = JsonConfigPaths.navigateObject(rootConfig, "delayedJob");
        DelayedJobsConfig parsed = parser.parse(section, DelayedJobsConfig.class);
        parsed.queues.forEach(DelayedJobQueueConfig::validate);
        return parsed;
    }

    /**
     * Builds an immutable {@code name -> DelayedJobQueueConfig} lookup index over {@link #queues()}.
     *
     * <p>Built after the per-record validation already performed at parse time; insertion order is
     * preserved.
     *
     * @return an immutable index keyed by queue name
     */
    public Map<String, DelayedJobQueueConfig> queueIndex() {
        Map<String, DelayedJobQueueConfig> index = new LinkedHashMap<>();
        for (DelayedJobQueueConfig queue : queues) {
            index.put(queue.name(), queue);
        }
        return Map.copyOf(index);
    }

    /**
     * Builds an immutable {@code name -> DelayedJobContractConfig} lookup index over
     * {@link #contracts()}.
     *
     * <p>Built after the per-record validation already performed at parse time; insertion order is
     * preserved.
     *
     * @return an immutable index keyed by contract name
     */
    public Map<String, DelayedJobContractConfig> contractIndex() {
        Map<String, DelayedJobContractConfig> index = new LinkedHashMap<>();
        for (DelayedJobContractConfig contract : contracts) {
            index.put(contract.name(), contract);
        }
        return Map.copyOf(index);
    }
}
