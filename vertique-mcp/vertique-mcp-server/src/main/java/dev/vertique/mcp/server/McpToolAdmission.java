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
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable, composition-built admission seam for MCP tool calls. */
final class McpToolAdmission {

    private static final long DEFAULT_COST = 1L;

    private final Optional<RateLimitAdapterSupport> adapterSupport;
    private final Optional<AdmissionPolicy> defaultPolicy;

    private McpToolAdmission(Optional<RateLimiters> rateLimiters, Optional<AdmissionPolicy> defaultPolicy) {
        this.adapterSupport =
                Objects.requireNonNull(rateLimiters, "rateLimiters").map(RateLimiters::adapterSupport);
        this.defaultPolicy = Objects.requireNonNull(defaultPolicy, "defaultPolicy");
    }

    static McpToolAdmission create(
            McpServerConfig config, McpToolRegistry registry, Optional<RateLimiters> rateLimiters) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(rateLimiters, "rateLimiters");
        McpRateLimitConfig rateLimit = config.rateLimit();
        String defaultPolicyName = rateLimit.defaultPolicy();
        if (defaultPolicyName == null) {
            return noPolicy();
        }
        RateLimiters runtime = rateLimiters.orElseThrow(() ->
                new ConfigurationException("mcp.rateLimit.defaultPolicy requires RateLimitCoreModule to be installed"));
        RateLimiter limiter;
        try {
            limiter = runtime.adapterSupport().limiter(defaultPolicyName);
        } catch (IllegalArgumentException unknownPolicy) {
            throw new ConfigurationException(
                    "mcp.rateLimit.defaultPolicy references unknown rate-limit policy '" + defaultPolicyName + "'",
                    unknownPolicy);
        }
        AdmissionPolicy policy = new AdmissionPolicy(limiter, rateLimit.subject(), rateLimit.anonymous(), DEFAULT_COST);
        return new McpToolAdmission(rateLimiters, Optional.of(policy));
    }

    static McpToolAdmission noPolicy() {
        return new McpToolAdmission(Optional.empty(), Optional.empty());
    }

    /**
     * Acquires the configured default policy before a tool call selects its response transport.
     *
     * @param toolName the resolved MCP tool name
     * @return the bounded admission result; never a failed future
     */
    Future<Admission> admit(String toolName) {
        Objects.requireNonNull(toolName, "toolName");
        if (defaultPolicy.isEmpty()) {
            return Future.succeededFuture(Admission.continueRequest());
        }
        AdmissionPolicy policy = defaultPolicy.orElseThrow();
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

    private static Admission admissionFor(RateLimitDecision decision) {
        if (decision == null) {
            return Admission.failed();
        }
        return switch (decision.outcome()) {
            case PERMITTED -> Admission.continueRequest();
            case QUOTA_EXCEEDED -> Admission.quotaExceeded(decision.retryAfter());
            case DISABLED, BACKEND_FAILURE_OPEN, BACKEND_FAILURE_CLOSED -> Admission.failed();
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

        static Admission failed() {
            return FAILED_RESULT;
        }
    }

    /** Closed outcomes the dispatcher maps through its existing terminal writer. */
    enum Outcome {
        CONTINUE,
        QUOTA_EXCEEDED,
        FAILED
    }

    private record AdmissionPolicy(
            RateLimiter limiter, RateLimitSubject subject, AnonymousRateLimitPolicy anonymous, long cost) {}
}
