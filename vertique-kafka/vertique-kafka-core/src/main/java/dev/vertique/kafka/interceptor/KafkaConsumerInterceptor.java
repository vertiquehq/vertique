// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.interceptor;

import dev.vertique.core.extension.OrderedExtension;
import io.vertx.core.Future;

/**
 * SPI for cross-cutting logic applied to the Kafka consumer dispatch pipeline.
 *
 * <p>The interface provides callbacks split into two categories, following the same pattern as
 * {@code RestClientInterceptor}:
 *
 * <h3>Sync observers (fire-and-forget, cannot affect outcome)</h3>
 * <ul>
 *   <li>{@link #onRecord} — called after deserialization, before dispatch</li>
 *   <li>{@link #onSuccess} — called after successful dispatch</li>
 *   <li>{@link #onError} — called on any dispatch failure</li>
 *   <li>{@link #onRecordCompleted} — called exactly once for every record the consumer received,
 *       when its disposition is final</li>
 * </ul>
 *
 * <p>An {@link Exception}, {@link LinkageError} or {@link AssertionError} thrown by a sync observer
 * is caught, logged and swallowed. It stops neither the remaining interceptors nor the record.
 *
 * <h3>Which observer for which need</h3>
 * <ul>
 *   <li>{@link #onRecord} sees the <em>deserialized value</em> of records that reached dispatch. It
 *       never sees a record that was filtered before deserialization, matched no route or failed
 *       to deserialize, and it runs before the outcome is known.</li>
 *   <li>{@link #onRecordCompleted} sees <em>every</em> record, its final
 *       {@link KafkaTerminalOutcome} and its raw form (key, headers and value bytes as received).
 *       Use it for audit evidence, per-outcome metrics and anything that must account for each
 *       record exactly once.</li>
 * </ul>
 *
 * <h3>Async handlers (can affect outcome)</h3>
 * <ul>
 *   <li>{@link #beforeDispatch} — returns a (possibly modified) context; can filter by setting
 *       {@code filtered=true}</li>
 *   <li>{@link #afterDispatch} — post-processing after successful dispatch; a failed {@link Future}
 *       is logged but does not affect the outcome</li>
 *   <li>{@link #recoverError} — called on failure; a succeeded {@link Future} means the error is
 *       handled (record committed); a failed {@link Future} proceeds with the normal error strategy</li>
 * </ul>
 *
 * <p>All methods have default no-op implementations. Interceptors are ordered using the
 * {@link OrderedExtension} contract: phase first (coarse), then ascending {@link #priority()}
 * (lower runs first within a phase), then {@link #orderKey()} as a stable tie-break.
 *
 * <p>Common use cases:
 * <ul>
 *   <li>Content-based filtering — {@code beforeDispatch} with {@code ctx.withFiltered(true)}</li>
 *   <li>Structured logging / metrics — {@code onRecord} + {@code onSuccess} + {@code onError}</li>
 *   <li>Outcome metrics / evidence capture for every record — {@code onRecordCompleted}</li>
 *   <li>Distributed tracing — {@code beforeDispatch} (add trace attributes) + {@code afterDispatch}</li>
 *   <li>Ignore specific errors — {@code recoverError} (return succeeded for handled errors)</li>
 *   <li>Non-blocking retry topics — {@code recoverError} (publish to retry topic, return succeeded)</li>
 * </ul>
 *
 * <p>Register interceptors via Dagger multibinding ({@code @IntoSet}).
 */
public interface KafkaConsumerInterceptor extends OrderedExtension {

    // --- Sync observers ---

    /**
     * Synchronous observer called after deserialization and before {@link #beforeDispatch}
     * async handlers run. Suitable for structured logging or metrics.
     *
     * <p>An {@link Exception}, {@link LinkageError} or {@link AssertionError} thrown by this callback
     * is caught, logged, and swallowed; it does not affect the enclosing operation. Use
     * {@link #beforeDispatch} to modify the context or filter the record.
     *
     * @param ctx the dispatch context (read-only; use {@link #beforeDispatch} to modify)
     */
    default void onRecord(KafkaDispatchContext<?> ctx) {}

    /**
     * Synchronous observer called after successful dispatch. Suitable for success metrics or
     * structured logging.
     *
     * <p>An {@link Exception}, {@link LinkageError} or {@link AssertionError} thrown by this callback
     * is caught, logged, and swallowed; it does not affect the enclosing operation.
     *
     * @param ctx the dispatch context
     */
    default void onSuccess(KafkaDispatchContext<?> ctx) {}

    /**
     * Synchronous observer called on any dispatch failure. Suitable for error metrics or
     * alerting. Cannot affect the error handling outcome — use {@link #recoverError} for that.
     *
     * <p>An {@link Exception}, {@link LinkageError} or {@link AssertionError} thrown by this callback
     * is caught, logged, and swallowed; it does not affect the enclosing operation.
     *
     * @param ctx the dispatch context
     * @param error the dispatch failure
     */
    default void onError(KafkaDispatchContext<?> ctx, Throwable error) {}

    /**
     * Synchronous observer called exactly once per record when its disposition is final, after all
     * asynchronous work (dead-letter publish, seek, recovery, {@link #afterDispatch}) has settled.
     *
     * <p>It is called for every record the consumer received, on every path: filtered before
     * deserialization, matching no route, failing to deserialize, filtered or failed by
     * {@link #beforeDispatch}, dispatched successfully, recovered by {@link #recoverError}, or handed
     * to the error strategy. A record that is redelivered for a retry completes once per delivery,
     * with a higher {@link KafkaConsumerRecordIdentity#retryCount()} each time.
     *
     * <p><strong>Observer only.</strong> The decision is already made when this runs. An
     * implementation cannot change filtering, dispatch, commit, retry or dead-lettering, and must
     * not try to by acting on the consumer.
     *
     * <p><strong>Threading.</strong> This callback may run on any thread, and concurrently for
     * different records: it runs on whichever thread settles the record's last future, which can be
     * the consumer's context, a handler's or interceptor's thread, or a producer's context on a
     * dead-letter path. Implementations must be thread-safe.
     *
     * <p><strong>The record.</strong> {@code record} is built by the consumer before any filter,
     * deserializer or interceptor runs, so its identity, key and headers are the record's own
     * whatever a {@link #beforeDispatch} implementation returned. {@code event.identity()} and
     * {@code record.identity()} are the same instance. {@link KafkaConsumerRecordView#value()} is
     * the array the broker delivered, uncopied; it is not a snapshot, and an in-place edit by a
     * deserializer or handler is visible through it.
     *
     * <p>An {@link Exception}, {@link LinkageError} or {@link AssertionError} thrown by this callback
     * is caught, logged, and swallowed; it does not affect the record, later interceptors or later
     * records.
     *
     * @param event  the completion facts: the record's identity and final outcome; carries no
     *               payload, so it can be logged as is
     * @param record the framework-owned view of the record as received: identity, key, headers
     *               and raw value
     */
    default void onRecordCompleted(KafkaConsumerCompletedEvent event, KafkaConsumerRecordView record) {}

    // --- Async handlers ---

    /**
     * Async handler called before dispatch. Implementations may return a modified copy of the
     * context (using {@code withFiltered}, {@code withAttribute}) to filter or enrich.
     * A context with {@code filtered=true} skips dispatch entirely.
     *
     * <p>Each interceptor in the chain receives the context returned by the previous interceptor.
     * A failed {@link Future} short-circuits dispatch and routes to error handling.
     *
     * @param ctx the current dispatch context (immutable, copy-on-write)
     * @return a {@link Future} containing the (possibly updated) context; a failed future
     *     short-circuits dispatch
     */
    default Future<KafkaDispatchContext<?>> beforeDispatch(KafkaDispatchContext<?> ctx) {
        return Future.succeededFuture(ctx);
    }

    /**
     * Async handler called after successful dispatch. Suitable for async post-processing
     * like flushing metrics buffers or completing distributed trace spans.
     *
     * <p>Failures are logged but do not affect the dispatch outcome or commit strategy.
     *
     * @param ctx the dispatch context
     * @return a {@link Future} that completes when post-processing is done; failures are logged
     */
    default Future<Void> afterDispatch(KafkaDispatchContext<?> ctx) {
        return Future.succeededFuture();
    }

    /**
     * Async recovery handler called when dispatch fails, before the normal error strategy
     * ({@link dev.vertique.kafka.ErrorStrategy}) is applied.
     *
     * <p>Return a succeeded {@link Future} to indicate the error is handled — the record will
     * be committed and processing moves to the next record. Return a failed {@link Future}
     * (the default) to decline recovery and proceed with the configured error strategy
     * (SKIP, DEAD_LETTER, or RETRY).
     *
     * <p>Interceptors are tried in {@link dev.vertique.core.extension.OrderedExtension} order
     * (phase → priority → orderKey); the first interceptor that returns a succeeded future wins.
     * Later interceptors are not invoked once recovery is accepted.
     *
     * <p>The {@link KafkaDispatchContext#retryCount()} field indicates how many times this record
     * has been retried, enabling retry-topic routing decisions.
     *
     * <p>Example — ignore validation errors:
     * <pre>{@code
     * public Future<Void> recoverError(KafkaDispatchContext<?> ctx, Throwable error) {
     *     if (error instanceof BeanValidationException) {
     *         return Future.succeededFuture(); // handled
     *     }
     *     return Future.failedFuture(error); // not handled
     * }
     * }</pre>
     *
     * @param ctx the dispatch context at the time of the failure
     * @param error the original dispatch failure
     * @return a succeeded {@link Future} to mark the error as handled, or a failed
     *     {@link Future} to decline recovery
     */
    default Future<Void> recoverError(KafkaDispatchContext<?> ctx, Throwable error) {
        return Future.failedFuture(error);
    }
}
