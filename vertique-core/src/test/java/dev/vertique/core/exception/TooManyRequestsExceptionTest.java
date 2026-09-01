// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * T020 slice 1: {@link TooManyRequestsException} is a new core semantic root — the 429 analogue of
 * {@link UnavailableException}'s 503 role — extending {@link BusinessRuleException} and carrying an
 * optional {@code retryAfter} hint.
 */
class TooManyRequestsExceptionTest {

    @Test
    void extendsBusinessRuleException() {
        assertTrue(BusinessRuleException.class.isAssignableFrom(TooManyRequestsException.class));
        assertTrue(ValidationException.class.isAssignableFrom(TooManyRequestsException.class));
        assertTrue(VertiqueException.class.isAssignableFrom(TooManyRequestsException.class));
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
