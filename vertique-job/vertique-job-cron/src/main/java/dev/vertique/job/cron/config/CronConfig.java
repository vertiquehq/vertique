// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.cron.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.json.KeyedBy;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed model of the {@code cron} configuration section.
 *
 * <p>This is the typed, validated result assembled at the Dagger provider boundary (see
 * {@code CronModule}/{@code CronPersistenceModule}); module internals — notably
 * {@code CronJobRegistrar} — depend on it (or on the {@link #jobIndex()}), never on the raw root
 * {@link JsonObject}. Jobs are a keyed object ({@code cron.jobs.{id}}); each key is injected into the
 * element record's identity field via {@link KeyedBy @KeyedBy("id")}.
 *
 * <p>The complex resolution rules — annotation-override vs config-only distinction, the per-job &gt;
 * annotation &gt; global {@code tracked} precedence, {@code SINGLE_INSTANCE}/{@code repository}
 * cross-field constraints, mode-based {@code misfirePolicy} defaulting, and target parsing — stay in
 * {@code CronJobRegistrar.scan}. This record only carries the parsed fields and the global
 * {@code tracked} flag; it performs no cross-field validation beyond per-record identity checks.
 *
 * @param tracked the global default for whether executions are persisted; defaults to {@code true}
 *     when absent
 * @param jobs the configured jobs; identity per element is {@code id} (default empty)
 */
public record CronConfig(boolean tracked, @KeyedBy("id") List<CronJobConfig> jobs) {

    /**
     * Compact constructor copying the keyed-collection list defensively for immutability.
     *
     * @param tracked the global tracked flag
     * @param jobs the job list (defensively copied; {@code null} becomes empty)
     */
    public CronConfig {
        jobs = jobs != null ? List.copyOf(jobs) : List.of();
    }

    /**
     * Jackson factory for the section: {@code tracked} defaults to {@code true} and {@code jobs}
     * defaults to empty when absent.
     *
     * @param tracked the global tracked flag; defaults to {@code true} when {@code null}
     * @param jobs the job list; defaults to empty when {@code null}
     * @return the deserialized config with defaults applied
     */
    @JsonCreator
    static CronConfig fromJson(
            @JsonProperty("tracked") @Nullable Boolean tracked,
            @JsonProperty("jobs") @Nullable List<CronJobConfig> jobs) {
        return new CronConfig(tracked != null ? tracked : true, jobs != null ? jobs : List.of());
    }

    /**
     * Parses the {@code cron} section of a root config object into a typed {@link CronConfig} via the
     * shared {@link ConfigParser} (which injects each {@code jobs} key into the element identity
     * field). Fails fast with a {@link ConfigurationException} naming the offending path when the
     * {@code cron} section, the {@code cron.jobs} map, or any {@code cron.jobs.{id}} entry is present
     * but not a JSON object.
     *
     * @param rootConfig the root application config, qualified {@code @VertxConfig} at the boundary
     * @param parser the injected config parser
     * @return the parsed, validated cron config
     * @throws ConfigurationException if {@code cron}, {@code cron.jobs}, or a {@code cron.jobs.{id}}
     *     entry is present but not a JSON object, or an entry cannot be deserialized
     */
    public static CronConfig fromConfig(JsonObject rootConfig, ConfigParser parser) {
        // navigateObject throws a path-bearing ConfigurationException if 'cron' or 'jobs' is a
        // present-but-non-object value (e.g. a scalar), preserving the malformed-shape fail-fast.
        JsonObject cronSection = JsonConfigPaths.navigateObject(rootConfig, "cron");
        JsonObject jobsSection = JsonConfigPaths.navigateObject(cronSection, "jobs");
        // Detect a per-job scalar entry explicitly so the message carries the full
        // 'cron.jobs.<id>' path (the keyed deserializer would only name the bare entry key).
        for (String jobId : jobsSection.fieldNames()) {
            Object entry = jobsSection.getValue(jobId);
            if (!(entry instanceof JsonObject)) {
                throw new ConfigurationException("Config path 'cron.jobs." + jobId + "' must be a JSON object, got "
                        + (entry == null ? "null" : entry.getClass().getSimpleName()));
            }
        }
        return parser.parse(cronSection, CronConfig.class);
    }

    /**
     * Builds an immutable {@code id -> CronJobConfig} lookup index over {@link #jobs()}.
     *
     * <p>Built after the per-record identity validation already performed at parse time; insertion
     * order is preserved. {@code CronJobRegistrar} uses this to look up the override entry for an
     * annotation-discovered job by id.
     *
     * @return an immutable index keyed by job id
     */
    public Map<String, CronJobConfig> jobIndex() {
        Map<String, CronJobConfig> index = new LinkedHashMap<>();
        for (CronJobConfig job : jobs) {
            index.put(job.id(), job);
        }
        return Map.copyOf(index);
    }
}
