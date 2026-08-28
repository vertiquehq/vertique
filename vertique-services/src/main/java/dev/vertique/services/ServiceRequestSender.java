// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.EventBusAddressUnavailableException;
import dev.vertique.core.eventbus.EventBusClient;
import dev.vertique.core.eventbus.EventBusDispatchException;
import dev.vertique.core.eventbus.EventBusTimeoutException;
import dev.vertique.core.eventbus.Result;
import dev.vertique.resilience.annotation.CircuitBreakerDeclaration;
import dev.vertique.resilience.annotation.ResilienceAnnotations;
import dev.vertique.resilience.annotation.RetryDeclaration;
import dev.vertique.resilience.annotation.TimeoutDeclaration;
import dev.vertique.services.config.CircuitBreakerOverride;
import dev.vertique.services.config.RetryOverride;
import dev.vertique.services.config.ServiceConfig;
import dev.vertique.services.config.ServiceOperationConfig;
import dev.vertique.services.config.ServicesConfig;
import dev.vertique.services.config.ServicesConfig.ServiceKey;
import dev.vertique.services.config.TimeoutOverride;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Low-level service request sender that owns the transport concerns for event bus
 * request/reply dispatch and fire-and-forget messaging.
 *
 * <p>Wraps {@link EventBusClient} with service-layer concerns:
 * <ul>
 *   <li><strong>Supervisor check</strong> — fails fast with {@link ServiceUnavailableException}
 *       when the service's restart budget is exhausted</li>
 *   <li><strong>Resilience timeout computation</strong> — derives the event bus send timeout from
 *       resilience annotations and configuration overrides</li>
 *   <li><strong>Error enrichment</strong> — translates transport exceptions
 *       ({@link EventBusTimeoutException}, {@link EventBusAddressUnavailableException},
 *       {@link EventBusDispatchException}) into service-specific exceptions that carry the
 *       contract class</li>
 * </ul>
 *
 * <p>Four transport methods are provided:
 * <ul>
 *   <li>{@link #send(ResolvedServiceTarget, DispatchEnvelope)} — request/reply with computed timeout</li>
 *   <li>{@link #send(ResolvedServiceTarget, DispatchEnvelope, long)} — request/reply with explicit timeout</li>
 *   <li>{@link #send(String, DispatchEnvelope, long)} — request/reply with explicit timeout, no supervisor</li>
 *   <li>{@link #sendOneWay(ResolvedServiceTarget, DispatchEnvelope)} — fire-and-forget with supervisor check</li>
 * </ul>
 *
 * <p>Both {@link ServiceClientFactory} (typed proxy path) and the transactional messaging
 * SERVICE adapter share this sender to ensure consistent codec, timeout, supervisor check,
 * and error enrichment behavior.
 *
 * <p>Instances are provided as a Dagger singleton via {@link DispatchModule}.
 */
@Slf4j
@Singleton
public class ServiceRequestSender {

    /** Default event bus send timeout when no resilience annotations are present. */
    private static final long DEFAULT_SEND_TIMEOUT_MS = 30_000L;

    /** Overhead buffer added to the computed send timeout to account for event bus processing. */
    private static final long SEND_TIMEOUT_BUFFER_MS = 1000L;

    private final EventBusClient eventBusClient;
    private final ServiceSupervisor supervisor;
    private final Long globalSendTimeoutMs;
    private final Map<ServiceKey, ServiceConfig> serviceConfigIndex;

    /**
     * Creates a new service request sender.
     *
     * @param eventBusClient the event bus client for transport
     * @param supervisor     the supervisor for availability checks
     * @param servicesConfig the typed services config supplying the global send timeout
     * @param serviceConfigIndex the {@code (namespace, name) -> ServiceConfig} index for per-service and
     *     per-operation send timeout overrides
     */
    @Inject
    public ServiceRequestSender(
            EventBusClient eventBusClient,
            ServiceSupervisor supervisor,
            ServicesConfig servicesConfig,
            Map<ServiceKey, ServiceConfig> serviceConfigIndex) {
        this.eventBusClient = eventBusClient;
        this.supervisor = supervisor;
        this.globalSendTimeoutMs = servicesConfig.sendTimeoutMs();
        this.serviceConfigIndex = serviceConfigIndex;
    }

    // --- Public API ---

    /**
     * Sends a request-reply message to the given service target and returns the raw
     * {@link Result} from the handler.
     *
     * <p>This method:
     * <ol>
     *   <li>Checks {@link ServiceSupervisor#isAvailable(Class)} — fails fast with
     *       {@link ServiceUnavailableException} if the service is unavailable</li>
     *   <li>Computes the send timeout from resilience annotations and config overrides</li>
     *   <li>Delegates to {@link EventBusClient#request(String, DispatchEnvelope, long)}</li>
     *   <li>Enriches transport exceptions with the contract class</li>
     * </ol>
     *
     * @param target the resolved service target (must not be {@code @OneWay})
     * @param envelope the request envelope wrapping the payload and optional context
     * @return a future containing the service handler's {@link Result}, or a failed future on
     *         transport errors ({@link ServiceUnavailableException}, {@link ServiceTimeoutException},
     *         {@link ServiceDispatchException})
     */
    public Future<Result<?>> send(ResolvedServiceTarget target, DispatchEnvelope<?> envelope) {
        if (!supervisor.isAvailable(target.contract())) {
            return Future.failedFuture(new ServiceUnavailableException(target.contract()));
        }
        long sendTimeout = computeSendTimeout(target.meta());
        return doRequest(target.address(), envelope, sendTimeout, target.contract());
    }

    /**
     * Sends a request-reply message to the given service target with an explicit timeout.
     *
     * <p>Like {@link #send(ResolvedServiceTarget, DispatchEnvelope)} but overrides the computed
     * timeout with the caller-supplied value. The supervisor check and error enrichment still apply.
     *
     * @param target        the resolved service target
     * @param envelope      the request envelope wrapping the payload and optional context
     * @param sendTimeoutMs the maximum milliseconds to wait for a reply
     * @return a future containing the service handler's {@link Result}, or a failed future on
     *         transport errors ({@link ServiceUnavailableException}, {@link ServiceTimeoutException},
     *         {@link ServiceDispatchException})
     */
    public Future<Result<?>> send(ResolvedServiceTarget target, DispatchEnvelope<?> envelope, long sendTimeoutMs) {
        if (!supervisor.isAvailable(target.contract())) {
            return Future.failedFuture(new ServiceUnavailableException(target.contract()));
        }
        return doRequest(target.address(), envelope, sendTimeoutMs, target.contract());
    }

    /**
     * Sends a request-reply message to an explicit event bus address with the given timeout.
     *
     * <p>No supervisor check is performed — the caller is responsible for ensuring the target is
     * available. Errors are not enriched with a contract class; the raw transport exceptions from
     * {@link EventBusClient} propagate as-is.
     *
     * @param address       the event bus address to send the request to
     * @param envelope      the request envelope wrapping the payload and optional context
     * @param sendTimeoutMs the maximum milliseconds to wait for a reply
     * @return a future containing the service handler's {@link Result}, or a failed future on
     *         transport errors ({@link EventBusTimeoutException},
     *         {@link EventBusAddressUnavailableException}, {@link EventBusDispatchException})
     */
    public Future<Result<?>> send(String address, DispatchEnvelope<?> envelope, long sendTimeoutMs) {
        return eventBusClient.request(address, envelope, sendTimeoutMs);
    }

    /**
     * Sends a fire-and-forget message to the given service target.
     *
     * <p>Intended for {@code @OneWay} operations. Checks the supervisor before sending:
     * <ul>
     *   <li>If unavailable: returns a failed future with {@link ServiceUnavailableException}
     *       without touching the event bus</li>
     *   <li>If available: delegates to {@link EventBusClient#send(String, DispatchEnvelope)} and
     *       returns a succeeded future immediately — no reply is awaited</li>
     * </ul>
     *
     * @param target   the resolved service target (must be a {@code @OneWay} operation)
     * @param envelope the request envelope wrapping the payload and optional context
     * @return a succeeded {@link Future} if the supervisor permits the send, or a failed future
     *         with {@link ServiceUnavailableException} if the service is unavailable
     */
    public Future<Void> sendOneWay(ResolvedServiceTarget target, DispatchEnvelope<?> envelope) {
        if (!supervisor.isAvailable(target.contract())) {
            return Future.failedFuture(new ServiceUnavailableException(target.contract()));
        }
        eventBusClient.send(target.address(), envelope);
        return Future.succeededFuture();
    }

    // --- Timeout Computation ---

    /**
     * Computes the event bus send timeout for an operation.
     *
     * <p>Resolution precedence (highest to lowest), resolved against the typed
     * {@link ServiceConfig}/{@link ServiceOperationConfig} records:
     * <ol>
     *   <li>{@code services.contracts.{namespace}.{name}.operations.{operation}.sendTimeoutMs} — per-operation config</li>
     *   <li>{@code services.contracts.{namespace}.{name}.sendTimeoutMs} — per-service config</li>
     *   <li>{@code services.sendTimeoutMs} — global config</li>
     *   <li>Computed from effective resilience config (annotation + config overrides)</li>
     *   <li>{@link #DEFAULT_SEND_TIMEOUT_MS} (30 seconds)</li>
     * </ol>
     *
     * <p>An explicit value at any of the first three levels always wins over the resilience-computed
     * value; a WARN is logged when the explicit value is shorter than the resilience timeout.
     *
     * <p>A service absent from the typed index resolves to {@code null} config and falls through to
     * the resilience computation / default.
     *
     * @param meta the operation metadata
     * @return the send timeout in milliseconds
     */
    long computeSendTimeout(ServiceMethodMeta meta) {
        ServiceConfig serviceConfig = serviceConfigIndex.get(new ServiceKey(meta.namespace(), meta.name()));
        ServiceOperationConfig operationConfig = findOperationConfig(serviceConfig, meta.operation());

        Long explicitTimeout = null;
        if (operationConfig != null) {
            explicitTimeout = operationConfig.sendTimeoutMs();
        }
        if (explicitTimeout == null && serviceConfig != null) {
            explicitTimeout = serviceConfig.sendTimeoutMs();
        }
        if (explicitTimeout == null) {
            explicitTimeout = globalSendTimeoutMs;
        }

        long resilienceTimeout = computeResilienceTimeout(meta, operationConfig);

        if (explicitTimeout != null) {
            if (explicitTimeout < resilienceTimeout) {
                log.warn(
                        "[{}/{}] Explicit sendTimeoutMs ({}ms) is shorter than computed resilience "
                                + "timeout ({}ms) for operation {} — client may receive timeout errors "
                                + "while server retries are in progress",
                        meta.namespace(),
                        meta.name(),
                        explicitTimeout,
                        resilienceTimeout,
                        meta.operation());
            }
            return explicitTimeout;
        }

        return resilienceTimeout;
    }

    /**
     * Looks up the typed per-operation config for an operation within a service.
     *
     * @param serviceConfig the resolved service config, or {@code null} when the service has no config
     * @param operation the operation id
     * @return the matching {@link ServiceOperationConfig}, or {@code null} when absent
     */
    private static ServiceOperationConfig findOperationConfig(ServiceConfig serviceConfig, String operation) {
        if (serviceConfig == null) {
            return null;
        }
        return serviceConfig.operations().stream()
                .filter(op -> op.operation().equals(operation))
                .findFirst()
                .orElse(null);
    }

    // --- Private Helpers ---

    /**
     * Executes a request/reply dispatch and enriches transport exceptions with the contract class.
     *
     * @param address   the event bus address
     * @param envelope  the request envelope
     * @param timeout   the send timeout in milliseconds
     * @param contract  the service contract interface, used to build service-specific exceptions
     * @return a future with the handler's {@link Result}, or a failed future with an enriched
     *         service exception
     */
    private Future<Result<?>> doRequest(String address, DispatchEnvelope<?> envelope, long timeout, Class<?> contract) {
        return eventBusClient
                .request(address, envelope, timeout)
                .recover(cause -> Future.failedFuture(enrichWithContract(cause, contract, address)));
    }

    /**
     * Translates a transport-level event bus exception into a service-specific exception that
     * carries the contract class. Non-event-bus exceptions are returned unchanged.
     *
     * @param cause    the exception to translate
     * @param contract the service contract interface
     * @param address  the event bus address where the error occurred
     * @return a service-specific exception, or {@code cause} if no translation applies
     */
    private static Throwable enrichWithContract(Throwable cause, Class<?> contract, String address) {
        if (cause instanceof EventBusTimeoutException) {
            return new ServiceTimeoutException(contract, address, cause);
        }
        if (cause instanceof EventBusAddressUnavailableException) {
            return new ServiceUnavailableException(contract, "no handlers at " + address, cause);
        }
        if (cause instanceof EventBusDispatchException e) {
            return new ServiceDispatchException(contract, address, e.getMessage(), cause);
        }
        return cause;
    }

    /**
     * Computes the send timeout from effective resilience config (annotations + typed config overrides).
     *
     * <p>Each annotation value may be overridden by the corresponding typed per-operation override
     * ({@link TimeoutOverride}, {@link CircuitBreakerOverride}, {@link RetryOverride}); an override
     * field that is {@code null} ("not overridden") falls back to the annotation value.
     *
     * @param meta            the operation metadata
     * @param operationConfig the typed per-operation config overrides, or {@code null} when absent
     * @return the computed send timeout, or {@link #DEFAULT_SEND_TIMEOUT_MS} if no resilience config
     */
    private static long computeResilienceTimeout(ServiceMethodMeta meta, ServiceOperationConfig operationConfig) {
        ResilienceAnnotations annotations = meta.resilienceAnnotations();
        if (!annotations.hasAny()) {
            return DEFAULT_SEND_TIMEOUT_MS;
        }

        long perAttemptMs = DEFAULT_SEND_TIMEOUT_MS;
        TimeoutDeclaration timeout = annotations.timeout().orElse(null);
        CircuitBreakerDeclaration cb = annotations.circuitBreaker().orElse(null);
        if (timeout != null) {
            TimeoutOverride timeoutOverride = operationConfig != null ? operationConfig.timeout() : null;
            Long valueMs = timeoutOverride != null ? timeoutOverride.valueMs() : null;
            perAttemptMs = valueMs != null ? valueMs : timeout.unit().toMillis(timeout.value());
        } else if (cb != null && cb.timeoutMs() >= 0) {
            CircuitBreakerOverride cbOverride = operationConfig != null ? operationConfig.circuitBreaker() : null;
            Long timeoutMs = cbOverride != null ? cbOverride.timeoutMs() : null;
            perAttemptMs = timeoutMs != null ? timeoutMs : cb.timeoutMs();
        }

        int maxRetries = 0;
        long totalBackoff = 0;
        RetryDeclaration retry = annotations.retry().orElse(null);
        if (retry != null) {
            RetryOverride retryOverride = operationConfig != null ? operationConfig.retry() : null;
            maxRetries = retryOverride != null && retryOverride.maxRetries() != null
                    ? retryOverride.maxRetries()
                    : retry.maxRetries();
            long delayMs = retryOverride != null && retryOverride.delayMs() != null
                    ? retryOverride.delayMs()
                    : retry.delayMs();
            double multiplier = retryOverride != null && retryOverride.backoffMultiplier() != null
                    ? retryOverride.backoffMultiplier()
                    : retry.backoffMultiplier();
            long maxDelayMs = retryOverride != null && retryOverride.maxDelayMs() != null
                    ? retryOverride.maxDelayMs()
                    : retry.maxDelayMs();
            for (int i = 0; i < maxRetries; i++) {
                long delay = Math.min((long) (delayMs * Math.pow(multiplier, i)), maxDelayMs);
                long jitter = Math.min(delay, 1000L);
                totalBackoff += delay + jitter;
            }
        }

        return perAttemptMs * (1L + maxRetries) + totalBackoff + SEND_TIMEOUT_BUFFER_MS;
    }
}
