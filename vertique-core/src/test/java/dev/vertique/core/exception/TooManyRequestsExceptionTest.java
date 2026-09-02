// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * T020 slice 1 / T021 W1: {@link TooManyRequestsException} is a core semantic root — the 429
 * analogue of {@link UnavailableException}'s 503 role — extending {@link VertiqueException}
 * <em>directly</em> and carrying an optional {@code retryAfter} hint.
 *
 * <p>T021 re-parented this class directly under {@link VertiqueException} (it previously extended
 * {@link BusinessRuleException}/{@link ValidationException}): a third external deep review found
 * that the old chain let an application's own {@code ExceptionMapper<ValidationException>} or
 * {@code ExceptionMapper<BusinessRuleException>} out-rank the framework's Throwable-level 429
 * default, rendering every rate-limit denial as 400 with no {@code Retry-After}.
 */
class TooManyRequestsExceptionTest {

    @Test
    void extendsVertiqueExceptionDirectlyNotTheValidationBusinessRuleChain() {
        assertTrue(VertiqueException.class.isAssignableFrom(TooManyRequestsException.class));
        assertFalse(
                ValidationException.class.isAssignableFrom(TooManyRequestsException.class),
                "must not be a ValidationException — an app's own ExceptionMapper<ValidationException> "
                        + "would otherwise out-rank the framework's 429 default and render 400 instead");
        assertFalse(
                BusinessRuleException.class.isAssignableFrom(TooManyRequestsException.class),
                "must not be a BusinessRuleException — the same out-ranking risk applies at this narrower "
                        + "supertype too");
    }

    @Test
    void messageOnlyConstructorLeavesRetryAfterAbsent() {
        TooManyRequestsException ex = new TooManyRequestsException("too many requests");

        assertEquals("too many requests", ex.getMessage());
        assertFalse(ex.retryAfter().isPresent());
    }

    @Test
    void messageAndRetryAfterConstructorExposesRetryAfter() {
        Duration retryAfter = Duration.ofSeconds(42);

        TooManyRequestsException ex = new TooManyRequestsException("slow down", retryAfter);

        assertEquals("slow down", ex.getMessage());
        assertTrue(ex.retryAfter().isPresent());
        assertEquals(retryAfter, ex.retryAfter().orElseThrow());
    }
}
