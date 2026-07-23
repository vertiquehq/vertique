// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import dev.vertique.inboxoutbox.exception.InboxOutboxConfigurationException;

/**
 * Internal exception raised when a {@link dev.vertique.inboxoutbox.ClaimScope.Destinations}
 * supplier produces or throws something invalid during a claim cycle.
 *
 * <p>This exception is a configuration/usage fault, not a transient database error. It is
 * propagated directly as a failed {@link io.vertx.core.Future} from
 * {@link PgInboxOutboxRepository#claimBatch} — it is intentionally <em>not</em> routed through
 * the {@code PgDbExceptionMapper} so that callers can distinguish a misconfigured handler from
 * a database failure.
 *
 * <p><strong>Message sanitization contract (CRITICAL):</strong> The exception message must name
 * only the destination TYPE and a human-readable reason category. It must <em>never</em> include
 * the offending target value from the supplier (e.g. a service address string or job type name),
 * and the raw supplier exception must <em>not</em> be attached as a cause. The relay logs the
 * full throwable separately, so attaching the cause or embedding a value in the message would
 * leak potentially sensitive target identifiers into upper-layer error handlers or observability
 * pipelines.
 *
 * <p>Examples of compliant messages:
 * <ul>
 *   <li>{@code "claim scope for destination type 'SERVICE' produced a null target set"}</li>
 *   <li>{@code "claim scope for destination type 'DELAYED_JOB' supplier or its result failed"}</li>
 *   <li>{@code "claim scope for destination type 'SERVICE' contains a null or blank element"}</li>
 *   <li>{@code "claim scope for destination type 'SERVICE' element exceeds maximum length of 255"}</li>
 *   <li>{@code "claim scope for destination type 'SERVICE' target set exceeds limit of 10000"}</li>
 * </ul>
 */
final class ClaimScopeException extends InboxOutboxConfigurationException {

    /**
     * Creates a new {@code ClaimScopeException} with a sanitized message.
     *
     * <p>The message must describe only the destination type and reason category — never an
     * offending value. Do not pass the raw supplier exception as a cause.
     *
     * @param message a sanitized description naming the destination type and failure reason
     */
    ClaimScopeException(String message) {
        super(message);
    }
}
