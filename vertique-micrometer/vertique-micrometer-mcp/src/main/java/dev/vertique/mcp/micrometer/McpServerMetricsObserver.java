// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.micrometer;

import dev.vertique.mcp.lifecycle.McpErrorType;
import dev.vertique.mcp.lifecycle.McpMethod;
import dev.vertique.mcp.lifecycle.McpRequestCompletedEvent;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpRequestTerminalEvent;
import dev.vertique.micrometer.MetricsConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link McpRequestLifecycleObserver} implementation that records MCP server outcomes as Micrometer
 * meters (T021, contract §4.10).
 *
 * <p>Contributed to the {@link McpRequestLifecycleObserver} multibinding by {@link
 * McpMicrometerModule}. {@link #open} increments the untagged {@value #ACTIVE_GAUGE} gauge; the
 * returned session's {@code onCompleted} decrements it and records the remaining instruments from
 * the post-wire {@link McpRequestCompletedEvent}. No metric is recorded from the logical {@code
 * onTerminal} callback — every instrument reflects the request's actual transport settlement.
 *
 * <p>Registered meters:
 *
 * <ul>
 *   <li>{@value #REQUESTS_TIMER} — one sample per completed request; tags {@code method}, {@code
 *       outcome}, {@code error.type}, {@code result.type}, {@code transport.outcome}
 *   <li>{@value #ACTIVE_GAUGE} — untagged in-flight request count
 *   <li>{@value #VALIDATION_FAILURES_COUNTER} — incremented only when the terminal {@code error.type}
 *       is {@link McpErrorType#INPUT_VALIDATION} or {@link McpErrorType#OUTPUT_VALIDATION}; tags
 *       {@code method}, {@code tool}, {@code error.type}
 *   <li>{@value #TOOL_CALLS_TIMER} — recorded only for {@link McpMethod#TOOLS_CALL} requests; tags
 *       {@code tool}, {@code outcome}, {@code error.type}
 * </ul>
 *
 * <p><b>Bounded dimensions only.</b> Every tag value is drawn from a fixed, low-cardinality domain:
 * {@code method} is an {@link McpMethod} name with {@link McpMethod#OTHER} remapped to {@value
 * #OTHER_METHOD_TAG} (FR-MCP-203); {@code outcome}, {@code error.type}, {@code result.type}, and
 * {@code transport.outcome} are the corresponding enum names; {@code tool} is {@link
 * McpRequestTerminalEvent#toolName()}, which {@code McpRequestTerminalEvent}'s own compact
 * constructor already bounds to either the literal {@link McpRequestTerminalEvent#UNKNOWN_TOOL_NAME}
 * or a generated, registered tool name matching {@code [A-Za-z0-9_.-]{1,128}} — an unresolved
 * client-requested tool name never reaches this observer as a distinct value; every producer
 * upstream of the lifecycle SPI collapses it to {@code UNKNOWN} first. No request ID, correlation ID,
 * trace ID, principal, argument, result, or exception text is ever read by this class, so none can
 * become a tag (FR-MCP-202).
 *
 * <p>Throwing meter registries are isolated: any exception during gauge registration, gauge
 * maintenance, or meter recording is caught, logged at WARN, and swallowed, so a misbehaving registry
 * never affects MCP request processing.
 *
 * @see McpMicrometerModule
 */
@Slf4j
@Singleton
final class McpServerMetricsObserver implements McpRequestLifecycleObserver {

    /** Micrometer meter name for the per-request server timer. */
    static final String REQUESTS_TIMER = "vertique.mcp.server.requests";

    /** Micrometer meter name for the active-requests gauge. */
    static final String ACTIVE_GAUGE = "vertique.mcp.server.active";

    /** Micrometer meter name for the validation-failures counter. */
    static final String VALIDATION_FAILURES_COUNTER = "vertique.mcp.validation.failures";

    /** Micrometer meter name for the per-tool-call timer. */
    static final String TOOL_CALLS_TIMER = "vertique.mcp.tool.calls";

    /** Tag name for the recognized MCP method class. */
    static final String TAG_METHOD = "method";

    /** Tag name for the generated tool name, or {@link McpRequestTerminalEvent#UNKNOWN_TOOL_NAME}. */
    static final String TAG_TOOL = "tool";

    /** Tag name for the logical settlement outcome. */
    static final String TAG_OUTCOME = "outcome";

    /** Tag name for the bounded error classification. */
    static final String TAG_ERROR_TYPE = "error.type";

    /** Tag name for the logical result-type classification. */
    static final String TAG_RESULT_TYPE = "result.type";

    /** Tag name for the transport-level completion outcome. */
    static final String TAG_TRANSPORT_OUTCOME = "transport.outcome";

    /**
     * Bounded fallback tag value for an unrecognized client-provided {@link McpMethod}
     * (FR-MCP-203). {@link McpMethod#OTHER} already exists to represent this case, but its enum
     * name is remapped here to the underscore-prefixed literal so the tag value cannot be confused
     * with a raw, unbounded client-supplied method string.
     */
    static final String OTHER_METHOD_TAG = "_OTHER";

    /** No-op session returned by {@link #open} when metrics recording is disabled. */
    private static final McpRequestObservation DISABLED = new McpRequestObservation() {};

    private final MeterRegistry registry;
    private final boolean enabled;

    /** Backing counter for the active-requests gauge; registered once at construction. */
    private final AtomicInteger active = new AtomicInteger(0);

    /** Guards the one-time WARN log on gauge underflow. {@code false} = warning not yet emitted. */
    private final AtomicBoolean underflowWarned = new AtomicBoolean(false);

    /**
     * Creates the observer with the given meter registry and optional metrics configuration.
     *
     * <p>When enabled, registers the active-requests gauge immediately at construction time. Any
     * exception thrown by the registry during registration is caught and swallowed so that a
     * misbehaving registry cannot break construction.
     *
     * @param registry      the application-wide meter registry; never {@code null}
     * @param metricsConfig an optional {@link MetricsConfig}; when present and {@link
     *                      MetricsConfig#enabled()} is {@code false}, all recording is skipped; when
     *                      empty recording is active (default-enabled)
     */
    @Inject
    McpServerMetricsObserver(MeterRegistry registry, Optional<MetricsConfig> metricsConfig) {
        this.registry = registry;
        this.enabled = metricsConfig.map(MetricsConfig::enabled).orElse(true);
        if (enabled) {
            try {
                registry.gauge(ACTIVE_GAUGE, active);
            } catch (Exception e) {
                log.warn(
                        "McpServerMetricsObserver failed to register active gauge: {}",
                        e.getClass().getName());
            }
        }
    }

    /**
     * Increments the active-requests gauge and opens a session that decrements it and records the
     * remaining instruments on transport completion.
     *
     * @param startedAt when the request started; unused beyond the SPI contract, since every
     *                   duration this observer records is derived from the terminal event's own
     *                   {@code startedAt}/{@code terminalAt} facts
     * @return the observation session; a no-op session when metrics recording is disabled
     */
    @Override
    public McpRequestObservation open(Instant startedAt) {
        if (!enabled) {
            return DISABLED;
        }
        try {
            active.incrementAndGet();
        } catch (Exception e) {
            log.warn(
                    "McpServerMetricsObserver failed to increment active gauge: {}",
                    e.getClass().getName());
        }
        return new Session();
    }

    /** Per-request session that decrements the active gauge and records metrics on completion. */
    private final class Session implements McpRequestObservation {
        @Override
        public void onCompleted(McpRequestCompletedEvent event) {
            decrementActive();
            recordMetrics(event);
        }
    }

    /**
     * Decrements the active-requests counter, clamping at 0 and emitting a one-time WARN when a
     * decrement would result in a negative value.
     */
    private void decrementActive() {
        try {
            int previous = active.getAndUpdate(current -> current > 0 ? current - 1 : 0);
            if (previous == 0 && underflowWarned.compareAndSet(false, true)) {
                log.warn("McpServerMetricsObserver: active-request gauge underflow detected — a completion event"
                        + " was received when the gauge was already 0. Further underflows are silently clamped.");
            }
        } catch (Exception e) {
            log.warn(
                    "McpServerMetricsObserver failed to decrement active gauge: {}",
                    e.getClass().getName());
        }
    }

    /**
     * Records the frozen request timer, the validation-failures counter (when applicable), and the
     * tool-call timer (when applicable) from the given completion event.
     *
     * @param event the post-wire completion event; never {@code null}
     */
    private void recordMetrics(McpRequestCompletedEvent event) {
        try {
            McpRequestTerminalEvent terminal = event.terminal();
            String method = methodTag(terminal.method());
            String tool = terminal.toolName();
            String outcome = terminal.outcome().name();
            String errorType = terminal.errorType().name();
            String resultType = terminal.resultType().name();
            String transportOutcome = event.transportOutcome().name();

            Duration duration = Duration.between(terminal.startedAt(), terminal.terminalAt());
            if (duration.isNegative()) {
                duration = Duration.ZERO;
            }

            Timer.builder(REQUESTS_TIMER)
                    .description("Per-request MCP server timer")
                    .tags(Tags.of(
                            TAG_METHOD, method,
                            TAG_OUTCOME, outcome,
                            TAG_ERROR_TYPE, errorType,
                            TAG_RESULT_TYPE, resultType,
                            TAG_TRANSPORT_OUTCOME, transportOutcome))
                    .register(registry)
                    .record(duration);

            if (isValidationFailure(terminal.errorType())) {
                registry.counter(
                                VALIDATION_FAILURES_COUNTER,
                                Tags.of(TAG_METHOD, method, TAG_TOOL, tool, TAG_ERROR_TYPE, errorType))
                        .increment();
            }

            if (terminal.method() == McpMethod.TOOLS_CALL) {
                Timer.builder(TOOL_CALLS_TIMER)
                        .description("Per-tool-call MCP timer")
                        .tags(Tags.of(TAG_TOOL, tool, TAG_OUTCOME, outcome, TAG_ERROR_TYPE, errorType))
                        .register(registry)
                        .record(duration);
            }
        } catch (Exception e) {
            log.warn(
                    "McpServerMetricsObserver failed to record metrics: {}",
                    e.getClass().getName());
        }
    }

    /**
     * Reports whether {@code errorType} belongs to the validation family the {@value
     * #VALIDATION_FAILURES_COUNTER} counter tracks.
     *
     * @param errorType the terminal event's bounded error classification
     * @return {@code true} for {@link McpErrorType#INPUT_VALIDATION} or {@link
     *     McpErrorType#OUTPUT_VALIDATION}
     */
    private static boolean isValidationFailure(McpErrorType errorType) {
        return errorType == McpErrorType.INPUT_VALIDATION || errorType == McpErrorType.OUTPUT_VALIDATION;
    }

    /**
     * Maps a recognized {@link McpMethod} to its bounded tag value, collapsing {@link
     * McpMethod#OTHER} to {@value #OTHER_METHOD_TAG} (FR-MCP-203).
     *
     * @param method the terminal event's recognized method class; never {@code null}
     * @return the bounded tag value; never {@code null}
     */
    private static String methodTag(McpMethod method) {
        return method == McpMethod.OTHER ? OTHER_METHOD_TAG : method.name();
    }
}
