// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.eventbus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.eventbus.ReplyException;
import io.vertx.core.eventbus.ReplyFailure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link EventBusClient}.
 *
 * <p>Verifies successful request/reply, exception translation for TIMEOUT and NO_HANDLERS failures,
 * and fire-and-forget send semantics.
 */
@ExtendWith(MockitoExtension.class)
class EventBusClientTest {

    @Mock
    private Vertx vertx;

    @Mock
    private EventBus eventBus;

    @Mock
    @SuppressWarnings("unchecked")
    private Message<Object> replyMessage;

    private EventBusExceptionMapper exceptionMapper;
    private EventBusClient client;

    @BeforeEach
    void setUp() {
        exceptionMapper = new EventBusExceptionMapper();
        client = new EventBusClient(vertx, exceptionMapper);
        when(vertx.eventBus()).thenReturn(eventBus);
    }

    // --- request() ---

    @Nested
    @DisplayName("request()")
    class RequestMethod {

        @Test
        @DisplayName("Successful request should return the Result from the reply message body")
        void successfulRequestReturnsResult() {
            Result<String> expectedResult = Result.success("hello");
            when(replyMessage.body()).thenReturn(expectedResult);
            when(eventBus.request(eq("test.address"), any(DispatchEnvelope.class), any()))
                    .thenReturn(Future.succeededFuture(replyMessage));

            DispatchEnvelope<Void> envelope = DispatchEnvelope.empty();
            Future<Result<?>> future = client.request("test.address", envelope, 5000L);

            assertTrue(future.succeeded(), "future should succeed");
            assertNotNull(future.result());
            assertEquals(expectedResult, future.result());
        }

        @Test
        @DisplayName("TIMEOUT ReplyException should be translated to EventBusTimeoutException")
        void timeoutReplyExceptionTranslatedToTimeoutException() {
            ReplyException re = new ReplyException(ReplyFailure.TIMEOUT, "timed out");
            when(eventBus.request(eq("services/svc/op"), any(DispatchEnvelope.class), any()))
                    .thenReturn(Future.failedFuture(re));

            Future<Result<?>> future = client.request("services/svc/op", DispatchEnvelope.empty(), 5000L);

            assertTrue(future.failed(), "future should fail");
            assertInstanceOf(EventBusTimeoutException.class, future.cause());
            assertEquals("services/svc/op", ((EventBusTimeoutException) future.cause()).address());
        }

        @Test
        @DisplayName("NO_HANDLERS ReplyException should be translated to EventBusAddressUnavailableException")
        void noHandlersReplyExceptionTranslatedToUnavailableException() {
            ReplyException re = new ReplyException(ReplyFailure.NO_HANDLERS, "no handlers");
            when(eventBus.request(eq("services/svc/op"), any(DispatchEnvelope.class), any()))
                    .thenReturn(Future.failedFuture(re));

            Future<Result<?>> future = client.request("services/svc/op", DispatchEnvelope.empty(), 5000L);

            assertTrue(future.failed(), "future should fail");
            assertInstanceOf(EventBusAddressUnavailableException.class, future.cause());
            assertEquals("services/svc/op", ((EventBusAddressUnavailableException) future.cause()).address());
        }

        @Test
        @DisplayName("RECIPIENT_FAILURE ReplyException should be translated to EventBusDispatchException")
        void recipientFailureTranslatedToDispatchException() {
            ReplyException re = new ReplyException(ReplyFailure.RECIPIENT_FAILURE, "rejected");
            when(eventBus.request(eq("services/svc/op"), any(DispatchEnvelope.class), any()))
                    .thenReturn(Future.failedFuture(re));

            Future<Result<?>> future = client.request("services/svc/op", DispatchEnvelope.empty(), 5000L);

            assertTrue(future.failed(), "future should fail");
            assertInstanceOf(EventBusDispatchException.class, future.cause());
            assertEquals("services/svc/op", ((EventBusDispatchException) future.cause()).address());
        }
    }

    // --- send() ---

    @Nested
    @DisplayName("send()")
    class SendMethod {

        @Test
        @DisplayName("send() should call eventBus.send() with envelope and dispatch.envelope codec")
        void sendCallsEventBusSend() {
            DispatchEnvelope<Void> envelope = DispatchEnvelope.empty();

            client.send("fire.and.forget.address", envelope);

            verify(eventBus).send(eq("fire.and.forget.address"), eq(envelope), any());
        }
    }
}
