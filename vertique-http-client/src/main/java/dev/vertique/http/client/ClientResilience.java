// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import dev.vertique.http.client.exception.HttpBodyLimitException;
import dev.vertique.http.client.exception.HttpClientConfigurationException;
import dev.vertique.http.client.exception.HttpCredentialException;
import dev.vertique.http.client.exception.HttpEgressDeniedException;
import dev.vertique.http.client.exception.HttpStatusRejectedException;
import dev.vertique.resilience.Resilience;
import dev.vertique.resilience.ResilienceDefaults;
import dev.vertique.resilience.ResiliencePipeline;
import dev.vertique.resilience.ResiliencePolicyOverrides;
import dev.vertique.resilience.ResiliencePolicyRegistry;
import dev.vertique.resilience.ResolvedResiliencePolicy;
import dev.vertique.resilience.RetryOverride;
import dev.vertique.resilience.RetryPolicy;
import dev.vertique.resilience.TimeoutOverride;
import dev.vertique.resilience.adapter.AdapterOperationIdentity;
import dev.vertique.resilience.adapter.ResilienceAdapterContext;
import dev.vertique.resilience.annotation.ResilienceAnnotations;
import dev.vertique.resilience.exception.ResilienceTimeoutException;
import io.vertx.core.Future;
import io.vertx.core.http.HttpClosedException;
import io.vertx.core.http.StreamResetException;
import jakarta.annotation.Nullable;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLHandshakeException;

/**
 * The resilience side of one client: the pipelines of its named policies, built once per policy, the
 * default retry classification and the breaker accounting.
 *
 * <p>A policy's pipeline omits the policy's timeout concern: the client's own attempt timer is the only
 * timer, and the policy's timeout value is the attempt timeout it uses.
 */
final class ClientResilience {

    /** The pipeline a call runs in and what the client needs to know about it. */
    record Selection(
            @Nullable ResiliencePipeline pipeline,
            boolean fromPolicy,
            @Nullable Long policyTimeoutMs,
            boolean guarded) {

        static final Selection NONE = new Selection(null, false, null, false);
    }

    private record Policy(
            ResiliencePipeline pipeline, @Nullable Long timeoutMs, boolean guarded) {}

    private final @Nullable Resilience resilience;
    private final ResiliencePolicyRegistry registry;
    private final String clientName;
    private final @Nullable String defaultPolicy;
    private final @Nullable ResilienceAdapterContext context;
    private final Map<String, Policy> policies = new ConcurrentHashMap<>();
    private final Set<Throwable> refusedTimeouts =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    ClientResilience(
            @Nullable Resilience resilience,
            ResiliencePolicyRegistry registry,
            String clientName,
            @Nullable String defaultPolicy) {
        this.resilience = resilience;
        this.registry = registry;
        this.clientName = clientName;
        this.defaultPolicy = defaultPolicy;
        this.context = resilience == null ? null : resilience.adapterSupport().newContext();
        if (defaultPolicy != null) {
            try {
                policy(defaultPolicy);
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }
    }

    /**
     * Chooses the pipeline of a call: the options' prebuilt pipeline, else the options' or the client's
     * policy, else none.
     *
     * @throws RuntimeException when the policy is not defined or the client has no resilience runtime
     */
    Selection select(CallOptions options) {
        if (options.isNoResilience()) {
            return Selection.NONE;
        }
        if (options.pipeline() != null) {
            return new Selection(options.pipeline(), false, null, false);
        }
        String name = options.tier() != null ? options.tier() : defaultPolicy;
        if (name == null) {
            return Selection.NONE;
        }
        Policy policy = policy(name);
        return new Selection(policy.pipeline(), true, policy.timeoutMs(), policy.guarded());
    }

    /** Records a timeout the client raised for an attempt with a non-idempotent exchange as not retryable. */
    void refuseRetry(Throwable timeout) {
        refusedTimeouts.add(timeout);
    }

    /** The number of timeouts currently recorded as not retryable. */
    int refusedTimeoutCount() {
        return refusedTimeouts.size();
    }

    /** Forgets the timeouts of one settled execution. */
    void forget(Collection<Throwable> timeouts) {
        timeouts.forEach(refusedTimeouts::remove);
    }

    /** Closes the pipelines' breakers and fences their running executions. */
    Future<Void> close() {
        return context == null ? Future.succeededFuture() : context.close();
    }

    private Policy policy(String name) {
        return policies.computeIfAbsent(name, this::build);
    }

    private Policy build(String name) {
        if (resilience == null || context == null) {
            throw new HttpClientConfigurationException("A resilience policy needs a resilience runtime");
        }
        ResiliencePolicyOverrides tier = registry.layer(
                new ResilienceAnnotations(
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(name)),
                ResiliencePolicyOverrides.none());
        Long timeoutMs = tier.timeout()
                .filter(timeout -> timeout.enabled().orElse(true))
                .filter(timeout -> timeout.timeoutMs().isPresent())
                .map(timeout -> timeout.timeoutMs().getAsLong())
                .orElse(null);
        ResiliencePolicyOverrides effective = new ResiliencePolicyOverrides(
                Optional.of(new TimeoutOverride(Optional.of(false), OptionalLong.empty())),
                tier.retry().map(this::withDefaultClassification),
                tier.circuitBreaker(),
                tier.bulkhead());
        // A policy whose only concern is a timeout resolves to no concern: the adapter context accepts it.
        ResolvedResiliencePolicy resolved =
                resilience.policyResolver().resolve(ResilienceAnnotations.NONE, effective, ResilienceDefaults.none());
        boolean guarded = resolved.retry()
                .map(retry -> retry.maxRetries() > 0 && retry.retryOn().isEmpty())
                .orElse(false);
        AdapterOperationIdentity identity = new AdapterOperationIdentity("http-client", List.of(clientName, name));
        ResiliencePipeline pipeline = resolved.circuitBreaker().isPresent()
                ? context.pipeline(identity, resolved, ClientResilience::countsAsBreakerFailure)
                : context.pipeline(identity, resolved);
        return new Policy(pipeline, timeoutMs, guarded);
    }

    private RetryOverride withDefaultClassification(RetryOverride retry) {
        if (retry.enabled().filter(enabled -> !enabled).isPresent()) {
            return retry;
        }
        RetryPolicy fallback = this::shouldRetry;
        return new RetryOverride(
                retry.enabled(),
                retry.maxRetries(),
                retry.backoff(),
                retry.retryOn(),
                retry.abortOn(),
                Optional.of(fallback),
                retry.retryAfterMaxMs());
    }

    /** The default retry classification: the fallback policy of every pipeline of this client. */
    private boolean shouldRetry(Throwable failure, int retryCount) {
        if (failure instanceof NonRetryableFailure || refusedTimeouts.contains(failure)) {
            return false;
        }
        return isConnectionFailure(failure) || isOverloaded(failure) || isIdempotentOnly(failure);
    }

    private static boolean isConnectionFailure(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current instanceof ConnectException
                    || current instanceof UnknownHostException
                    || current instanceof NoRouteToHostException
                    || current instanceof SSLHandshakeException) {
                return true;
            }
            Throwable cause = current.getCause();
            current = cause == current ? null : cause;
        }
        return false;
    }

    private static boolean isOverloaded(Throwable failure) {
        return failure instanceof HttpStatusRejectedException rejected
                && (rejected.statusCode() == 429 || rejected.statusCode() == 503);
    }

    /** Failures only an attempt whose every exchange is idempotent may repeat. */
    static boolean isIdempotentOnly(Throwable failure) {
        if (failure instanceof HttpStatusRejectedException rejected) {
            return rejected.statusCode() == 502 || rejected.statusCode() == 504;
        }
        return failure instanceof ResilienceTimeoutException
                || failure instanceof HttpClosedException
                || failure instanceof StreamResetException;
    }

    /**
     * Whether a failure that reached the breaker counts against it: every failure does, except a 4xx
     * rejection other than 429, a body limit and a refusal on a redirect hop, which show that the
     * dependency answered.
     */
    private static boolean countsAsBreakerFailure(Throwable failure) {
        Throwable original = NonRetryableFailure.unwrap(failure);
        if (original instanceof HttpStatusRejectedException rejected) {
            int status = rejected.statusCode();
            return status < 400 || status >= 500 || status == 429;
        }
        return !(original instanceof HttpBodyLimitException
                || original instanceof HttpEgressDeniedException
                || original instanceof HttpCredentialException);
    }
}
