// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.eventbus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.vertx.core.eventbus.ReplyException;
import io.vertx.core.eventbus.ReplyFailure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link EventBusExceptionMapper}.
 *
 * <p>Verifies that all four {@link ReplyFailure} types are mapped to the correct exception types with
 * address and cause preserved, and that non-{@link ReplyException} throwables are returned as-is.
 */
class EventBusExceptionMapperTest {

    private EventBusExceptionMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new EventBusExceptionMapper();
    }

    // --- ReplyFailure type mappings ---

    @Nested
    @DisplayName("ReplyFailure.TIMEOUT")
    class TimeoutMapping {

        @Test
        @DisplayName("Should map TIMEOUT to EventBusTimeoutException with address preserved")
        void mapsToTimeoutException() {
            ReplyException re = new ReplyException(ReplyFailure.TIMEOUT, "timed out");

            Throwable result = mapper.translate(re, "services/foo/bar");

            assertInstanceOf(EventBusTimeoutException.class, result);
            assertEquals("services/foo/bar", ((EventBusTimeoutException) result).address());
        }

        @Test
        @DisplayName("TIMEOUT: cause should be the original ReplyException")
        void causeIsReplyException() {
            ReplyException re = new ReplyException(ReplyFailure.TIMEOUT, "timed out");

            Throwable result = mapper.translate(re, "addr");

            assertSame(re, result.getCause());
        }
    }

    @Nested
    @DisplayName("ReplyFailure.NO_HANDLERS")
    class NoHandlersMapping {

        @Test
        @DisplayName("Should map NO_HANDLERS to EventBusAddressUnavailableException with address preserved")
        void mapsToAddressUnavailableException() {
            ReplyException re = new ReplyException(ReplyFailure.NO_HANDLERS, "no handlers");

            Throwable result = mapper.translate(re, "services/my/op");

            assertInstanceOf(EventBusAddressUnavailableException.class, result);
            assertEquals("services/my/op", ((EventBusAddressUnavailableException) result).address());
        }

        @Test
        @DisplayName("NO_HANDLERS: cause should be the original ReplyException")
        void causeIsReplyException() {
            ReplyException re = new ReplyException(ReplyFailure.NO_HANDLERS, "no handlers");

            Throwable result = mapper.translate(re, "addr");

            assertSame(re, result.getCause());
        }
    }

    @Nested
    @DisplayName("ReplyFailure.RECIPIENT_FAILURE")
    class RecipientFailureMapping {

        @Test
        @DisplayName("Should map RECIPIENT_FAILURE to EventBusDispatchException with address preserved")
        void mapsToDispatchException() {
            ReplyException re = new ReplyException(ReplyFailure.RECIPIENT_FAILURE, "handler rejected");

            Throwable result = mapper.translate(re, "services/svc/op");

            assertInstanceOf(EventBusDispatchException.class, result);
            assertEquals("services/svc/op", ((EventBusDispatchException) result).address());
        }

        @Test
        @DisplayName("RECIPIENT_FAILURE: cause should be the original ReplyException")
        void causeIsReplyException() {
            ReplyException re = new ReplyException(ReplyFailure.RECIPIENT_FAILURE, "rejected");

            Throwable result = mapper.translate(re, "addr");

            assertSame(re, result.getCause());
        }
    }

    @Nested
    @DisplayName("ReplyFailure.ERROR")
    class ErrorMapping {

        @Test
        @DisplayName("Should map ERROR to EventBusDispatchException with address preserved")
        void mapsToDispatchException() {
            ReplyException re = new ReplyException(ReplyFailure.ERROR, "fatal error");

            Throwable result = mapper.translate(re, "services/svc/op");

            assertInstanceOf(EventBusDispatchException.class, result);
            assertEquals("services/svc/op", ((EventBusDispatchException) result).address());
        }

        @Test
        @DisplayName("ERROR: cause should be the original ReplyException")
        void causeIsReplyException() {
            ReplyException re = new ReplyException(ReplyFailure.ERROR, "fatal");

            Throwable result = mapper.translate(re, "addr");

            assertSame(re, result.getCause());
        }
    }

    // --- Non-ReplyException passthrough ---

    @Nested
    @DisplayName("Non-ReplyException passthrough")
    class Passthrough {

        @Test
        @DisplayName("Non-ReplyException should be returned as-is")
        void nonReplyExceptionReturnedAsIs() {
            RuntimeException cause = new RuntimeException("unrelated error");

            Throwable result = mapper.translate(cause, "addr");

            assertSame(cause, result);
        }

        @Test
        @DisplayName("RuntimeException with address should be returned unchanged")
        void runtimeExceptionReturnedUnchanged() {
            IllegalStateException cause = new IllegalStateException("unexpected");

            Throwable result = mapper.translate(cause, "services/foo/bar");

            assertSame(cause, result);
        }
    }

    // --- Null address handling ---

    @Nested
    @DisplayName("Null address")
    class NullAddress {

        @Test
        @DisplayName("Null address should not cause NPE for TIMEOUT")
        void nullAddressWorksForTimeout() {
            ReplyException re = new ReplyException(ReplyFailure.TIMEOUT, "timed out");

            Throwable result = mapper.translate(re, null);

            assertInstanceOf(EventBusTimeoutException.class, result);
            assertNull(((EventBusTimeoutException) result).address());
        }

        @Test
        @DisplayName("Null address should not cause NPE for NO_HANDLERS")
        void nullAddressWorksForNoHandlers() {
            ReplyException re = new ReplyException(ReplyFailure.NO_HANDLERS, "no handlers");

            Throwable result = mapper.translate(re, null);

            assertInstanceOf(EventBusAddressUnavailableException.class, result);
            assertNull(((EventBusAddressUnavailableException) result).address());
        }
    }
}
