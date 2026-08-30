// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.EventBusAddressUnavailableException;
import dev.vertique.core.eventbus.EventBusClient;
import dev.vertique.core.eventbus.EventBusDispatchException;
import dev.vertique.core.eventbus.EventBusTimeoutException;
import dev.vertique.core.eventbus.Result;
import dev.vertique.resilience.DurationBound;
import dev.vertique.resilience.ResolvedResiliencePolicy;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import dev.vertique.services.exception.ServiceDispatchException;
import dev.vertique.services.exception.ServiceTimeoutException;
import dev.vertique.services.exception.ServiceUnavailableException;
import dev.vertique.services.resilience.ServiceResilienceConfigAdapter;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
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

    private final EventBusClient eventBusClient;
    private final ServiceSupervisor supervisor;
    private final ServiceResilienceConfigAdapter resilienceConfigAdapter;

    /**
     * Creates a new service request sender.
     *
     * @param eventBusClient the event bus client for transport
     * @param supervisor     the supervisor for availability checks
     * @param resilienceConfigAdapter the shared Services-to-common resilience adapter
     */
    @Inject
    public ServiceRequestSender(
            EventBusClient eventBusClient,
            ServiceSupervisor supervisor,
            ServiceResilienceConfigAdapter resilienceConfigAdapter) {
        this.eventBusClient = eventBusClient;
        this.supervisor = supervisor;
        this.resilienceConfigAdapter = resilienceConfigAdapter;
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
     *   <li>the framework default (30 seconds)</li>
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
        Long explicitTimeout = resilienceConfigAdapter.explicitSendTimeoutMs(meta);
        ResolvedResiliencePolicy policy = resilienceConfigAdapter.resolve(meta);

        if (explicitTimeout != null) {
            DurationBound active = policy.executionBudget().activeExecution();
            if (active instanceof DurationBound.Known known
                    && !known.saturated()
                    && explicitTimeout < resilienceConfigAdapter.derivedSendTimeoutMs(meta, policy)) {
                log.warn(
                        "Explicit sendTimeoutMs ({}ms) is shorter than the resolved resilience budget "
                                + "for operation key {} — client may receive timeout errors while server retries "
                                + "are in progress",
                        explicitTimeout,
                        policy.executionBudget());
            }
            return explicitTimeout;
        }

        return resilienceConfigAdapter.derivedSendTimeoutMs(meta, policy);
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
}
