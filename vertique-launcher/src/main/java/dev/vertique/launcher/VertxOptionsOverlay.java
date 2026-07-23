// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.launcher;

import dev.vertique.core.config.JsonConfigPaths;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pure function that applies the {@code vertx.options} section from a resolved config tree onto a
 * base {@link VertxOptions} instance, with explicit sysprop and CLI override layers.
 *
 * <p>Precedence rule (lowest to highest):
 * <ol>
 *   <li>{@code base} — the fully-mutated {@link VertxOptions} as provided by the upstream launcher
 *       after {@code processVertxOptions} runs (includes cluster flags, metrics/tracer SPI
 *       conversions, and {@code vertx.options.*} system properties from the first upstream pass).
 *   <li>{@code vertx.options} tree section — values from the resolved bootstrap config tree,
 *       applied on top of base. This lets operators set pool sizes, timeouts, etc. in config files
 *       without CLI flags.
 *   <li>CLI {@code --options} JSON — re-applied on top of the tree so that explicit operator
 *       overrides survive the tree merge.
 *   <li>{@code vertx.options.*} system properties — re-applied last, matching upstream semantics
 *       where system properties override everything including the {@code --options} JSON. This
 *       enables emergency runtime overrides without a config file edit.
 * </ol>
 *
 * <p>When the {@code vertx.options} section is absent or empty, the original instance is returned
 * unchanged (same reference — zero-cost path that preserves non-JSON-representable programmatic
 * options, and avoids redundant work since sysprops and CLI are already live in the base via
 * upstream processing).
 *
 * <p>When the section is present, a new {@link VertxOptions} is constructed via a two-step merge:
 * <ol>
 *   <li>{@code base + tree + CLI} are merged and validated eagerly (any malformed tree/CLI section
 *       aborts startup with a {@link dev.vertique.core.exception.ConfigurationException}).</li>
 *   <li>Each {@code vertx.options.*} sysprop key is applied <em>individually</em> (warn-and-skip):
 *       if adding a single key causes {@link VertxOptions} construction to throw
 *       {@link RuntimeException} (e.g. an unknown enum constant for a TimeUnit or similar field),
 *       the key is logged at WARN level — naming the property key and failure class, never the
 *       value — and
 *       skipped. This matches the upstream {@code configureFromSystemProperties} behaviour where
 *       individual bad property values are warned-and-skipped rather than aborting startup.
 *       <br>The tree and CLI layers remain fail-fast: a bad value in a config file or {@code --options}
 *       JSON still aborts startup (the {@code base + tree + CLI} construction is not per-key).</li>
 * </ol>
 *
 * <p>Because the produced instance round-trips through JSON, any programmatically-set option that
 * has no JSON representation is not preserved on the present-section path. The absent-section path
 * exists precisely to handle that case with zero cost.
 *
 * <p>{@link io.vertx.core.metrics.MetricsOptions} and {@link io.vertx.core.tracing.TracingOptions}
 * retain their source JSON precisely to survive generic reconstruction — Vert.x keeps "a copy of
 * the original json" in those objects so that JSON-representable provider config survives the
 * present-section rebuild for conventional providers ({@code MicrometerMetricsOptions} and
 * {@code OpenTelemetryOptions} override {@code toJson()}). Non-JSON-representable programmatic
 * state (e.g. a pre-built {@code MeterRegistry} instance) is the documented absent-section-path
 * limitation; those contributors should operate on the absent-section path or re-apply their
 * programmatic state in a post-build hook.
 *
 * <p>Throws {@link dev.vertique.core.exception.ConfigurationException} when the {@code vertx}
 * section exists but is not a JSON object, or when the {@code vertx.options} section exists but
 * is not a JSON object — both are delegated to {@link JsonConfigPaths#navigateObject}.
 *
 * @see VertiqueApplication
 */
final class VertxOptionsOverlay {

    private static final Logger log = LoggerFactory.getLogger(VertxOptionsOverlay.class);

    // Section key constants — match VertiqueApplication
    private static final String VERTX_SECTION = "vertx";
    private static final String OPTIONS_SUBSECTION = "options";

    private VertxOptionsOverlay() {}

    /**
     * Applies the {@code vertx.options} subtree from {@code resolvedTree} onto {@code base}, with
     * {@code cliOptionsJson} and {@code sysPropsJson} at higher precedence (in that order).
     *
     * <p>When the {@code vertx → options} section is absent or empty, {@code base} is returned
     * unchanged (same instance). When present, a new {@link VertxOptions} is returned with the
     * values merged in ascending precedence order: base &lt; tree &lt; CLI &lt; sysprops.
     *
     * <p>The tree and CLI layers are applied together in a single {@link VertxOptions} construction
     * (fail-fast: any malformed value aborts startup). Each sysprop key is applied individually
     * with warn-and-skip: if a single key causes a {@link RuntimeException} during
     * {@link VertxOptions} construction it is logged at {@code WARN} level (naming the property
     * key and the failure class, never the value) and skipped, matching the upstream
     * {@code configureFromSystemProperties} warn-and-skip behaviour.
     *
     * <p>Precedence rationale:
     * <ul>
     *   <li>System properties are applied last, matching upstream {@code processVertxOptions}
     *       behaviour where {@code vertx.options.*} system properties override the {@code --options}
     *       JSON. This enables emergency runtime overrides without config file edits.</li>
     *   <li>CLI {@code --options} is re-applied above the tree so that an explicit operator CLI
     *       override survives the config-tree merge.</li>
     *   <li>The skip-WARN names the offending property key and the failure class but not the
     *       value: upstream's coercion warning also omits the value, and raw values could carry
     *       control characters into log lines.</li>
     * </ul>
     *
     * @param base           the fully-mutated {@link VertxOptions} instance from the upstream
     *                       launcher (includes cluster flags, sysprops, and metrics/tracer SPI
     *                       conversions already applied by {@code processVertxOptions}); never
     *                       {@code null}
     * @param resolvedTree   the fully-resolved bootstrap config tree; never {@code null}
     * @param sysPropsJson   a {@link JsonObject} built from {@code vertx.options.*} system
     *                       properties (highest precedence); never {@code null}
     * @param cliOptionsJson the parsed {@code --options} JSON (CLI precedence, above tree but below
     *                       sysprops); never {@code null}
     * @return the effective {@link VertxOptions} — the same {@code base} instance when the
     *         {@code vertx.options} section is absent or empty, otherwise a new instance
     * @throws dev.vertique.core.exception.ConfigurationException if the {@code vertx} or
     *         {@code vertx.options} key is present but bound to a non-object value
     */
    static VertxOptions apply(
            VertxOptions base, JsonObject resolvedTree, JsonObject sysPropsJson, JsonObject cliOptionsJson) {
        JsonObject treeOptionsJson = JsonConfigPaths.navigateObject(resolvedTree, VERTX_SECTION, OPTIONS_SUBSECTION);
        if (treeOptionsJson.isEmpty()) {
            return base;
        }

        // Build the base+tree+CLI merged JSON and validate it EAGERLY (fail-fast: a malformed
        // tree/CLI value must abort startup before the sysprop loop can mask it with a valid
        // value for the same key, and so per-key sysprop failures are never misattributed to a
        // tree/CLI defect).
        JsonObject merged = base.toJson().mergeIn(treeOptionsJson.copy(), true).mergeIn(cliOptionsJson, true);
        new VertxOptions(merged);

        // Apply sysprop keys individually with warn-and-skip on coercion errors.
        // This mirrors upstream configureFromSystemProperties behaviour.
        if (!sysPropsJson.isEmpty()) {
            merged = applyPerKeyWithWarnSkip(merged, sysPropsJson);
        }

        return new VertxOptions(merged);
    }

    /**
     * Applies each key from {@code sysPropsJson} onto {@code baseJson} one at a time.
     *
     * <p>For each key, a candidate {@link JsonObject} is built by merging the single key onto the
     * current accumulated JSON, then a {@link VertxOptions} construction is attempted. On success,
     * the candidate becomes the new accumulated JSON. On {@link RuntimeException} (e.g. unknown
     * enum constant), the key is logged at {@code WARN} level and skipped.
     *
     * @param baseJson     the accumulated merged JSON (base + tree + CLI); never {@code null}
     * @param sysPropsJson the sysprop overrides to apply key-by-key; never {@code null} or empty
     * @return the accumulated JSON after applying all valid sysprop keys
     */
    private static JsonObject applyPerKeyWithWarnSkip(JsonObject baseJson, JsonObject sysPropsJson) {
        JsonObject accumulated = baseJson;
        for (String key : sysPropsJson.fieldNames()) {
            JsonObject candidate = accumulated.copy().put(key, sysPropsJson.getValue(key));
            try {
                new VertxOptions(candidate);
                accumulated = candidate;
            } catch (RuntimeException e) {
                // Value deliberately omitted: upstream's coercion warning also names only the
                // field, and raw values could carry control characters into log lines.
                log.warn(
                        "Skipping vertx.options.{} — value is not valid for VertxOptions ({});"
                                + " startup continues with this key ignored",
                        key,
                        e.getClass().getSimpleName());
            }
        }
        return accumulated;
    }
}
