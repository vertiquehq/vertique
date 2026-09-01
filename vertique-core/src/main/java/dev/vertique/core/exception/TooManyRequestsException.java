// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.exception;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Thrown when a caller has exceeded a rate or quota limit. Maps to HTTP 429 by default (mirrors
 * {@link UnavailableException}'s role for 503).
 *
 * <p>Represents a transient condition — the caller may retry, optionally after the duration
 * carried in {@link #retryAfter()}.
 *
 * <p>Extends {@link VertiqueException} directly — <em>not</em> {@link BusinessRuleException}/{@link
 * ValidationException} (T021 W1; originally {@code BusinessRuleException} at T020). A third external
 * deep review found that chain let an application's own {@code ExceptionMapper<ValidationException>}
 * or {@code ExceptionMapper<BusinessRuleException>} out-rank the framework's Throwable-level 429
 * default in {@code RestModule}'s hierarchy-aware {@code ExceptionMapperRegistry}: since a 429 denial
 * is not a validation or business-rule failure, folding it into that chain was never semantically
 * correct, and it created exactly this dispatch hazard for any application already handling
 * validation errors.
 */
public class TooManyRequestsException extends VertiqueException {

    private final Duration retryAfter;

    /**
     * Constructs a new exception with the given message and no {@code retryAfter} hint.
     *
     * @param message the detail message
     */
    public TooManyRequestsException(String message) {
        this(message, null);
    }

    /**
     * Constructs a new exception with the given message and a {@code retryAfter} hint.
     *
     * @param message    the detail message
     * @param retryAfter how long the caller should wait before retrying, or {@code null} when unknown
     */
    public TooManyRequestsException(String message, Duration retryAfter) {
        super(Objects.requireNonNull(message, "message"));
        this.retryAfter = retryAfter;
    }

    /**
     * Returns how long the caller should wait before retrying, when known.
     *
     * @return the retry-after duration, or empty when not supplied
     */
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
