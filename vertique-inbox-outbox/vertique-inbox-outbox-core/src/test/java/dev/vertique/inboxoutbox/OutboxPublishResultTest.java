// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OutboxPublishResult} sealed hierarchy — factory methods, field values,
 * and exhaustive pattern matching over the four permitted variants.
 */
@DisplayName("OutboxPublishResult")
class OutboxPublishResultTest {

    @Nested
    @DisplayName("success()")
    class SuccessFactory {

        @Test
        @DisplayName("returns a Success instance")
        void returnsSuccessInstance() {
            OutboxPublishResult result = OutboxPublishResult.success();
            assertInstanceOf(OutboxPublishResult.Success.class, result);
        }

        @Test
        @DisplayName("isSuccess is true via pattern match")
        void isSuccessViaPatternMatch() {
            OutboxPublishResult result = OutboxPublishResult.success();
            boolean matched = result instanceof OutboxPublishResult.Success;
            assertEquals(true, matched);
        }
    }

    @Nested
    @DisplayName("retryable(msg, cause)")
    class RetryableFactory {

        @Test
        @DisplayName("returns a RetryableFailure instance")
        void returnsRetryableFailureInstance() {
            RuntimeException cause = new RuntimeException("upstream timeout");
            OutboxPublishResult result = OutboxPublishResult.retryable("upstream timeout", cause);
            assertInstanceOf(OutboxPublishResult.RetryableFailure.class, result);
        }

        @Test
        @DisplayName("message is preserved")
        void messageIsPreserved() {
            OutboxPublishResult result = OutboxPublishResult.retryable("upstream timeout", null);
            OutboxPublishResult.RetryableFailure failure = (OutboxPublishResult.RetryableFailure) result;
            assertEquals("upstream timeout", failure.message());
        }

        @Test
        @DisplayName("cause is preserved")
        void causeIsPreserved() {
            RuntimeException cause = new RuntimeException("connection refused");
            OutboxPublishResult result = OutboxPublishResult.retryable("upstream timeout", cause);
            OutboxPublishResult.RetryableFailure failure = (OutboxPublishResult.RetryableFailure) result;
            assertSame(cause, failure.cause());
        }

        @Test
        @DisplayName("cause may be null")
        void causeCanBeNull() {
            OutboxPublishResult result = OutboxPublishResult.retryable("timeout", null);
            OutboxPublishResult.RetryableFailure failure = (OutboxPublishResult.RetryableFailure) result;
            assertNull(failure.cause());
        }
    }

    @Nested
    @DisplayName("permanent(msg, cause)")
    class PermanentFactory {

        @Test
        @DisplayName("returns a PermanentFailure instance")
        void returnsPermanentFailureInstance() {
            RuntimeException cause = new RuntimeException("validation failed");
            OutboxPublishResult result = OutboxPublishResult.permanent("validation failed", cause);
            assertInstanceOf(OutboxPublishResult.PermanentFailure.class, result);
        }

        @Test
        @DisplayName("message is preserved")
        void messageIsPreserved() {
            OutboxPublishResult result = OutboxPublishResult.permanent("validation failed", null);
            OutboxPublishResult.PermanentFailure failure = (OutboxPublishResult.PermanentFailure) result;
            assertEquals("validation failed", failure.message());
        }

        @Test
        @DisplayName("cause is preserved")
        void causeIsPreserved() {
            IllegalArgumentException cause = new IllegalArgumentException("bad payload");
            OutboxPublishResult result = OutboxPublishResult.permanent("validation failed", cause);
            OutboxPublishResult.PermanentFailure failure = (OutboxPublishResult.PermanentFailure) result;
            assertSame(cause, failure.cause());
        }

        @Test
        @DisplayName("cause may be null")
        void causeCanBeNull() {
            OutboxPublishResult result = OutboxPublishResult.permanent("permanent error", null);
            OutboxPublishResult.PermanentFailure failure = (OutboxPublishResult.PermanentFailure) result;
            assertNull(failure.cause());
        }
    }

    @Nested
    @DisplayName("unresolvable(msg)")
    class UnresolvableFactory {

        @Test
        @DisplayName("returns an Unresolvable instance")
        void returnsUnresolvableInstance() {
            OutboxPublishResult result = OutboxPublishResult.unresolvable("no handler for: foo/bar");
            assertInstanceOf(OutboxPublishResult.Unresolvable.class, result);
        }

        @Test
        @DisplayName("message is preserved")
        void messageIsPreserved() {
            OutboxPublishResult result = OutboxPublishResult.unresolvable("no handler for: foo/bar");
            OutboxPublishResult.Unresolvable unresolvable = (OutboxPublishResult.Unresolvable) result;
            assertEquals("no handler for: foo/bar", unresolvable.message());
        }
    }

    @Nested
    @DisplayName("sealed interface permits only 4 variants")
    class SealedVariants {

        @Test
        @DisplayName("switch expression is exhaustive over all permitted subtypes")
        void switchExpressionIsExhaustive() {
            OutboxPublishResult[] results = {
                OutboxPublishResult.success(),
                OutboxPublishResult.retryable("r", null),
                OutboxPublishResult.permanent("p", null),
                OutboxPublishResult.unresolvable("u")
            };

            for (OutboxPublishResult result : results) {
                String label =
                        switch (result) {
                            case OutboxPublishResult.Success ignored -> "success";
                            case OutboxPublishResult.RetryableFailure ignored -> "retryable";
                            case OutboxPublishResult.PermanentFailure ignored -> "permanent";
                            case OutboxPublishResult.Unresolvable ignored -> "unresolvable";
                        };
                // Compiles only if all 4 variants are covered — verifies exhaustiveness
                assertEquals(true, label != null);
            }
        }
    }
}
