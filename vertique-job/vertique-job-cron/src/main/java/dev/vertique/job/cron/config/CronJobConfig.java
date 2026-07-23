// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.cron.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.core.config.ConfigSecretRenderer;
import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;

/**
 * Typed per-job configuration read from {@code cron.jobs.{id}}.
 *
 * <p>The {@code id} identity is injected from the keyed-object key during boundary parsing (the
 * parent {@link CronConfig#jobs()} list is annotated
 * {@link dev.vertique.core.json.KeyedBy @KeyedBy("id")}). A single {@code cron.jobs.{id}} entry is
 * <em>either</em> an override for a discovered {@link dev.vertique.job.cron.CronJob @CronJob}
 * annotation (when an annotated job shares the id) <em>or</em> a config-only job (when it carries a
 * {@code target} and no matching annotation). {@code CronJobRegistrar} performs that distinction;
 * this record only carries the parsed fields.
 *
 * <p>The override-capable fields — {@code cron}, {@code timezone}, {@code mode},
 * {@code overlapPolicy}, {@code misfirePolicy}, {@code tracked}, and {@code target} — are deliberately
 * left {@code null} when absent (no eager defaulting). The registrar relies on {@code null} meaning
 * "not configured" so it can fall through to the annotation value (override path) or the config-only
 * default (config-only path). Eagerly defaulting these here would erase the distinction between
 * "operator set this value" and "operator omitted it", breaking the override precedence rules. Only
 * {@code enabled} (default {@code true}) and {@code maxAttempts} (default {@code 3}) carry concrete
 * defaults, since both paths apply the same default for those two.
 *
 * <p>{@code parameters} is an intentionally-open user payload (config rule R9): its values are a
 * domain-defined dictionary passed verbatim to the handler via
 * {@link dev.vertique.job.JobDispatchContext#parameters()}, and round-trips through
 * {@link dev.vertique.core.config.ConfigParser} with its values intact. Because that payload may carry
 * secrets, {@link #toString()} is overridden to redact it via the framework-wide
 * {@link ConfigSecretRenderer} (also covering {@code CronConfig.toString()}, which renders the job
 * list) while the live {@link #parameters()} accessor keeps the real values for runtime use.
 *
 * @param id the job identifier (identity; injected from the keyed-object key); never blank
 * @param enabled whether the job is enabled; defaults to {@code true} when absent
 * @param cron the cron expression override, or {@code null} to use the annotation's value
 * @param timezone the timezone override (IANA zone id), or {@code null} to use the annotation/UTC default
 * @param mode the execution mode override ({@code EVERY_INSTANCE}/{@code SINGLE_INSTANCE}), or
 *     {@code null} to use the annotation/{@code EVERY_INSTANCE} default
 * @param overlapPolicy the overlap policy override ({@code SKIP}/{@code QUEUE_ONE}), or {@code null}
 *     to use the annotation/{@code SKIP} default
 * @param misfirePolicy the misfire policy override ({@code FIRE_NOW}/{@code SKIP}/{@code FIRE_ALL}),
 *     or {@code null} to use the mode-based default
 * @param tracked the per-job tracked override, or {@code null} to inherit the global
 *     {@code cron.tracked} setting (precedence: per-job &gt; annotation &gt; global)
 * @param maxAttempts the maximum attempts for a config-only job; defaults to {@code 3} (annotated
 *     jobs use the annotation's {@code maxAttempts} instead — this field is not consulted for them)
 * @param target the target reference ({@code "service:..."} or {@code "eventbus:..."}); presence of
 *     a non-{@code null} {@code target} on an entry without a matching annotation marks a config-only
 *     job, or {@code null} for an annotation-override-only entry
 * @param parameters the open user payload passed to the handler (config rule R9); never {@code null}
 *     after construction (empty when no parameters configured)
 */
public record CronJobConfig(
        String id,
        boolean enabled,
        String cron,
        String timezone,
        String mode,
        String overlapPolicy,
        String misfirePolicy,
        Boolean tracked,
        int maxAttempts,
        String target,
        JsonObject parameters) {

    /**
     * Compact validator: {@code id} must be non-blank (it is the injected keyed-object identity), and
     * {@code parameters} is normalized to a non-{@code null} empty object.
     *
     * @throws ConfigurationException if {@code id} is {@code null} or blank
     */
    public CronJobConfig {
        if (id == null || id.isBlank()) {
            throw new ConfigurationException("cron.jobs.<id> must be non-blank");
        }
        parameters = parameters != null ? parameters : new JsonObject();
    }

    /**
     * Jackson factory filling the two defaulted scalars ({@code enabled=true}, {@code maxAttempts=3})
     * for omitted properties and passing every override-capable field through unchanged (so absent
     * means {@code null}). {@code parameters} defaults to an empty object when absent.
     *
     * @param id the job id injected from the keyed-object key (validated in the compact constructor)
     * @param enabled the enabled flag; defaults to {@code true} when {@code null}
     * @param cron the cron override; passed through (nullable)
     * @param timezone the timezone override; passed through (nullable)
     * @param mode the execution-mode override; passed through (nullable)
     * @param overlapPolicy the overlap-policy override; passed through (nullable)
     * @param misfirePolicy the misfire-policy override; passed through (nullable)
     * @param tracked the per-job tracked override; passed through (nullable — inherits global)
     * @param maxAttempts the config-only max attempts; defaults to {@code 3} when {@code null}
     * @param target the target reference; passed through (nullable)
     * @param parameters the open user payload; defaults to an empty object when {@code null}
     * @return the deserialized config with the two scalar defaults applied
     */
    @JsonCreator
    static CronJobConfig fromJson(
            @JsonProperty("id") @Nullable String id,
            @JsonProperty("enabled") @Nullable Boolean enabled,
            @JsonProperty("cron") @Nullable String cron,
            @JsonProperty("timezone") @Nullable String timezone,
            @JsonProperty("mode") @Nullable String mode,
            @JsonProperty("overlapPolicy") @Nullable String overlapPolicy,
            @JsonProperty("misfirePolicy") @Nullable String misfirePolicy,
            @JsonProperty("tracked") @Nullable Boolean tracked,
            @JsonProperty("maxAttempts") @Nullable Integer maxAttempts,
            @JsonProperty("target") @Nullable String target,
            @JsonProperty("parameters") @Nullable JsonObject parameters) {
        return new CronJobConfig(
                id,
                enabled != null ? enabled : true,
                cron,
                timezone,
                mode,
                overlapPolicy,
                misfirePolicy,
                tracked,
                maxAttempts != null ? maxAttempts : 3,
                target,
                parameters != null ? parameters : new JsonObject());
    }

    // --- Log-safe rendering ---

    /**
     * Renders this job config with the open {@code parameters} payload redacted so a log line or
     * exception message never leaks a secret. {@code parameters} is an intentionally-open user payload
     * (config rule R9) that may carry secrets at any depth — including under array indices. The default
     * record {@code toString()} (and {@code CronConfig.toString()}, which renders the job list) would
     * render it verbatim; this override delegates the {@code parameters} bag to the framework-wide
     * {@link ConfigSecretRenderer#redactBag(JsonObject)}, which masks credential-bearing keys at every
     * depth. All other fields are rendered normally. The live {@link #parameters()} accessor is
     * untouched.
     *
     * @return a log-safe string rendering of this job config
     */
    @Override
    public String toString() {
        return "CronJobConfig[id=" + id + ", enabled=" + enabled + ", cron=" + cron + ", timezone=" + timezone
                + ", mode=" + mode + ", overlapPolicy=" + overlapPolicy + ", misfirePolicy=" + misfirePolicy
                + ", tracked=" + tracked + ", maxAttempts=" + maxAttempts + ", target=" + target + ", parameters="
                + ConfigSecretRenderer.redactBag(parameters) + "]";
    }
}
