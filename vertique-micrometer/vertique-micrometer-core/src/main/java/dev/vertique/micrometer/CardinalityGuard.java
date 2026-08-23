// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for {@link MeterFilter} instances that guard against high-cardinality label explosions
 * on {@code vertique.*} meters.
 *
 * <p>Two types of guards are produced:
 * <ol>
 *   <li><b>Per-key tag-value cap</b> — for each key in {@link #GUARDED_TAG_KEYS}, one
 *       {@link MeterFilter#maximumAllowableTags} filter is created that limits how many distinct
 *       values that key may carry on any {@code vertique.*} meter. When the cap is reached, the
 *       overflow filter logs exactly one {@code WARN} per guard instance (naming the tag key and
 *       the cap, but never the overflowing value to avoid log-injection) and denies the meter.</li>
 *   <li><b>Global meter cap</b> (optional) — when {@link MetricsConfig.CardinalityConfig#maxMeters()}
 *       is greater than {@code 0}, one additional {@link MeterFilter#maximumAllowableMetrics}
 *       filter is appended to the list that limits the total number of distinct meters across the
 *       composite.</li>
 * </ol>
 *
 * <p>Non-{@code vertique.*} meters are unaffected by the per-key filters (the prefix match in
 * {@link MeterFilter#maximumAllowableTags} handles this) and are also exempt from the global cap
 * because the per-key filters are applied to the composite before backends are added — the
 * {@code maximumAllowableMetrics} filter guards the composite's internal meter set, which includes
 * all meters from all backends.
 *
 * <p><b>Adding a new tag key to any {@code vertique.*} meter requires extending
 * {@link #GUARDED_TAG_KEYS}.</b> This is a review-enforced constraint: the list is frozen
 * to cover all current and planned vertique adapter phases (§7.1 of the telemetry PRD).
 *
 * @see MetricsConfig.CardinalityConfig
 * @see MicrometerAssembly
 */
final class CardinalityGuard {

    private static final Logger log = LoggerFactory.getLogger(CardinalityGuard.class);

    /**
     * Frozen union of all {@code vertique.*} tag keys from PRD §7.1 — future adapter phases
     * are pre-covered. Adding a NEW tag key to any {@code vertique.*} meter requires extending
     * this list (review-enforced).
     */
    static final List<String> GUARDED_TAG_KEYS = List.of(
            "method",
            "route",
            "operation",
            "status",
            "outcome",
            "error.type",
            "client",
            "target",
            "target.host",
            "target.port",
            "oneway",
            "type",
            "queue",
            "state",
            "topic",
            "consumer",
            "workflow",
            "transition",
            "destination.type",
            "result.kind",
            "stage",
            "sink",
            "decision",
            // MCP adapter dimensions (T021/T022, repair R06, issue #430): "tool", "result.type", and
            // "transport.outcome" are the three keys McpServerMetricsObserver emits that this list
            // never covered — see McpMetricsCardinalityGuardContractTest for the derived-key proof.
            "tool",
            "result.type",
            "transport.outcome");

    /** Prevent instantiation — this class is a static factory only. */
    private CardinalityGuard() {}

    // --- Factory ---

    /**
     * Builds the list of {@link MeterFilter} instances that enforce the cardinality bounds
     * described by the given configuration.
     *
     * <p>The returned list always contains one filter per entry in {@link #GUARDED_TAG_KEYS}
     * (using {@code "vertique."} as the meter-name prefix and
     * {@link MetricsConfig.CardinalityConfig#maxTagValuesPerKey()} as the cap). When
     * {@link MetricsConfig.CardinalityConfig#maxMeters()} is greater than {@code 0}, one
     * additional {@link MeterFilter#maximumAllowableMetrics} filter is appended at the end.
     *
     * <p>Filters must be registered on the target registry/composite <em>before</em> any meter
     * is created in that registry (i.e. before backends and binders are added).
     *
     * @param config the cardinality configuration; must not be {@code null}
     * @return an unmodifiable list of meter filters; never {@code null}
     */
    static List<MeterFilter> filters(MetricsConfig.CardinalityConfig config) {
        int cap = config.maxTagValuesPerKey();
        List<MeterFilter> result = new ArrayList<>(GUARDED_TAG_KEYS.size() + 1);

        for (String tagKey : GUARDED_TAG_KEYS) {
            result.add(MeterFilter.maximumAllowableTags("vertique.", tagKey, cap, denyAndWarnOnce(tagKey, cap)));
        }

        if (config.maxMeters() > 0) {
            result.add(MeterFilter.maximumAllowableMetrics(config.maxMeters()));
        }

        return List.copyOf(result);
    }

    // --- Private helpers ---

    /**
     * Returns a {@link MeterFilter} that denies the meter and logs exactly one {@code WARN} for
     * the lifetime of the returned filter instance. The warning names the tag key and the cap but
     * never the overflowing tag value (values could be hostile or cause log-injection).
     *
     * <p>The filter is used as the {@code onMaxReached} argument to
     * {@link MeterFilter#maximumAllowableTags}; Micrometer delegates both {@code accept()} and
     * {@code configure()} to {@code onMaxReached} once the cap is hit, so only {@code accept()}
     * needs to be overridden here.
     *
     * @param tagKey the tag key whose cap was breached, included in the log message
     * @param cap    the configured maximum number of tag values, included in the log message
     * @return a deny filter that logs at most one WARN per instance
     */
    private static MeterFilter denyAndWarnOnce(String tagKey, int cap) {
        AtomicBoolean warned = new AtomicBoolean(false);
        return new MeterFilter() {
            @Override
            public MeterFilterReply accept(Meter.Id id) {
                if (warned.compareAndSet(false, true)) {
                    log.warn(
                            "Cardinality guard: tag key '{}' on vertique.* meters has exceeded the cap of {} "
                                    + "distinct values. Further meters with new values for this key will be denied. "
                                    + "Increase cardinality.maxTagValuesPerKey to raise the limit.",
                            tagKey,
                            cap);
                }
                return MeterFilterReply.DENY;
            }
        };
    }
}
