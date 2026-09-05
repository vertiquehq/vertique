// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.ratelimit.RateLimitDecision;
import dev.vertique.ratelimit.RateLimitKey;
import dev.vertique.ratelimit.RateLimiter;
import dev.vertique.ratelimit.RateLimiters;
import dev.vertique.ratelimit.spi.AnonymousRateLimitPolicy;
import dev.vertique.ratelimit.spi.RateLimitAdapterSupport;
import dev.vertique.ratelimit.spi.RateLimitSubject;
import io.vertx.core.Future;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable, composition-built admission seam for MCP tool calls. */
final class McpToolAdmission {

    private static final long DEFAULT_COST = 1L;

    private final Optional<RateLimitAdapterSupport> adapterSupport;
    private final Map<String, AdmissionPolicy> policiesByTool;

    private McpToolAdmission(Optional<RateLimiters> rateLimiters, Map<String, AdmissionPolicy> policiesByTool) {
        this.adapterSupport =
                Objects.requireNonNull(rateLimiters, "rateLimiters").map(RateLimiters::adapterSupport);
        this.policiesByTool = Map.copyOf(Objects.requireNonNull(policiesByTool, "policiesByTool"));
    }

    static McpToolAdmission create(
            McpServerConfig config, McpToolRegistry registry, Optional<RateLimiters> rateLimiters) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(rateLimiters, "rateLimiters");
        McpRateLimitConfig rateLimit = config.rateLimit();
        validateConfiguredTools(rateLimit, registry);
        if (rateLimit.defaultPolicy() == null && rateLimit.tools().isEmpty()) {
            return noPolicy();
        }
        RateLimiters runtime = rateLimiters.orElseThrow(
                () -> new ConfigurationException("mcp.rateLimit requires RateLimitCoreModule to be installed"));
        RateLimitAdapterSupport adapterSupport = runtime.adapterSupport();
        Map<String, RateLimiter> limitersByPolicy = new LinkedHashMap<>();
        Map<String, AdmissionPolicy> policiesByTool = new LinkedHashMap<>();
        if (rateLimit.defaultPolicy() != null) {
            resolveLimiter(adapterSupport, limitersByPolicy, rateLimit.defaultPolicy());
        }
        for (String toolName : registry.invokersByName().keySet()) {
            Optional<String> policyName = resolvePolicy(rateLimit, toolName);
            if (policyName.isEmpty()) {
                continue;
            }
            McpToolRateLimitConfig toolConfig = toolConfig(rateLimit, toolName);
            long cost = toolConfig != null ? toolConfig.cost() : DEFAULT_COST;
            RateLimiter limiter = resolveLimiter(adapterSupport, limitersByPolicy, policyName.orElseThrow());
            validateCost(toolName, cost, policyName.orElseThrow(), limiter.capacity());
            RateLimitSubject subject =
                    toolConfig != null && toolConfig.subject() != null ? toolConfig.subject() : rateLimit.subject();
            AnonymousRateLimitPolicy anonymous = toolConfig != null && toolConfig.anonymous() != null
                    ? toolConfig.anonymous()
                    : rateLimit.anonymous();
            policiesByTool.put(toolName, new AdmissionPolicy(limiter, subject, anonymous, cost));
        }
        return new McpToolAdmission(rateLimiters, policiesByTool);
    }

    static McpToolAdmission noPolicy() {
        return new McpToolAdmission(Optional.empty(), Map.of());
    }

    /** Resolves a generated MCP tool's configured policy without inspecting annotations. */
    static Optional<String> resolvePolicy(McpRateLimitConfig rateLimit, String toolName) {
        Objects.requireNonNull(rateLimit, "rateLimit");
        Objects.requireNonNull(toolName, "toolName");
        McpToolRateLimitConfig toolConfig = toolConfig(rateLimit, toolName);
        return Optional.ofNullable(toolConfig != null ? toolConfig.policy() : rateLimit.defaultPolicy());
    }

    /**
     * Acquires the configured tool policy before a tool call selects its response transport.
     *
     * @param toolName the resolved MCP tool name
     * @return the bounded admission result; never a failed future
     */
    Future<Admission> admit(String toolName) {
        Objects.requireNonNull(toolName, "toolName");
        AdmissionPolicy policy = policiesByTool.get(toolName);
        if (policy == null) {
            return Future.succeededFuture(Admission.continueRequest());
        }
        RateLimitKey key;
        try {
            key = keyFor(policy);
        } catch (RuntimeException keyFailure) {
            return Future.succeededFuture(Admission.failed());
        }
        if (key == null) {
            return Future.succeededFuture(Admission.continueRequest());
        }
        try {
            return policy.limiter()
                    .acquire(key, policy.cost())
                    .map(McpToolAdmission::admissionFor)
                    .recover(failure -> Future.succeededFuture(Admission.failed()));
        } catch (RuntimeException acquireFailure) {
            return Future.succeededFuture(Admission.failed());
        }
    }

    private RateLimitKey keyFor(AdmissionPolicy policy) {
        return adapterSupport
                .orElseThrow(() -> new IllegalStateException("rate-limit adapter support is unavailable"))
                .subjectKey(policy.subject(), policy.anonymous(), List.of())
                .orElse(null);
    }

    private static void validateConfiguredTools(McpRateLimitConfig rateLimit, McpToolRegistry registry) {
        for (McpToolRateLimitConfig toolConfig : rateLimit.tools()) {
            if (!registry.invokersByName().containsKey(toolConfig.tool())) {
                throw new ConfigurationException(
                        "mcp.rateLimit.tools[" + toolConfig.tool() + "] references an unknown generated MCP tool");
            }
        }
    }

    private static McpToolRateLimitConfig toolConfig(McpRateLimitConfig rateLimit, String toolName) {
        return rateLimit.tools().stream()
                .filter(candidate -> candidate.tool().equals(toolName))
                .findFirst()
                .orElse(null);
    }

    private static RateLimiter resolveLimiter(
            RateLimitAdapterSupport adapterSupport, Map<String, RateLimiter> limitersByPolicy, String policyName) {
        try {
            return limitersByPolicy.computeIfAbsent(policyName, adapterSupport::limiter);
        } catch (IllegalArgumentException unknownPolicy) {
            throw new ConfigurationException(
                    "mcp.rateLimit references unknown rate-limit policy '" + policyName + "'", unknownPolicy);
        }
    }

    private static void validateCost(String toolName, long cost, String policyName, long capacity) {
        if (cost > capacity) {
            throw new ConfigurationException("mcp.rateLimit.tools[" + toolName + "].cost declares cost " + cost
                    + " exceeding policy '" + policyName + "' capacity " + capacity);
        }
    }

    private static Admission admissionFor(RateLimitDecision decision) {
        if (decision == null) {
            return Admission.failed();
        }
        return switch (decision.outcome()) {
            case PERMITTED -> Admission.continueRequest();
            case QUOTA_EXCEEDED -> Admission.quotaExceeded(decision.retryAfter());
            case DISABLED, BACKEND_FAILURE_OPEN -> Admission.continueRequest();
            case BACKEND_FAILURE_CLOSED -> Admission.rejected();
        };
    }

    /** Bounded result of the config-bound admission operation. */
    record Admission(Outcome outcome, Optional<Duration> retryAfter) {
        private static final Admission CONTINUE_RESULT = new Admission(Outcome.CONTINUE, Optional.empty());
        private static final Admission FAILED_RESULT = new Admission(Outcome.FAILED, Optional.empty());

        Admission {
            Objects.requireNonNull(outcome, "outcome");
            retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
        }

        static Admission continueRequest() {
            return CONTINUE_RESULT;
        }

        static Admission quotaExceeded(Optional<Duration> retryAfter) {
            return new Admission(Outcome.QUOTA_EXCEEDED, retryAfter);
        }

        static Admission rejected() {
            return new Admission(Outcome.REJECTED, Optional.empty());
        }

        static Admission failed() {
            return FAILED_RESULT;
        }
    }

    /** Closed outcomes the dispatcher maps through its existing terminal writer. */
    enum Outcome {
        CONTINUE,
        QUOTA_EXCEEDED,
        REJECTED,
        FAILED
    }

    private record AdmissionPolicy(
            RateLimiter limiter, RateLimitSubject subject, AnonymousRateLimitPolicy anonymous, long cost) {}
}
