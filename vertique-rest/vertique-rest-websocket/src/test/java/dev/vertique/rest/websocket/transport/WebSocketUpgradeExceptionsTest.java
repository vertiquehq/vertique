// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket.transport;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.core.exception.TechnicalException;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.UpgradeRejectedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WebSocketUpgradeExceptions} translation.
 *
 * <p>Covers the three documented input shapes — structured Vert.x exception, netty
 * handshake exception with status in message, netty handshake exception without status —
 * plus the idempotence, HTTP-status-range guards, and exception hierarchy proof.
 */
class WebSocketUpgradeExceptionsTest {

    // --- Hierarchy proof ---

    @Test
    @DisplayName("WebSocketUpgradeException is a TechnicalException")
    void webSocketUpgradeExceptionIsTechnicalException() {
        assertTrue(TechnicalException.class.isAssignableFrom(WebSocketUpgradeException.class));
        assertInstanceOf(TechnicalException.class, new WebSocketUpgradeTransportFailure("io", null));
    }

    @Nested
    @DisplayName("translate(UpgradeRejectedException)")
    class StructuredVertxRejection {

        @Test
        @DisplayName("preserves status from UpgradeRejectedException.getStatus()")
        void preservesStructuredStatus() {
            UpgradeRejectedException src =
                    new UpgradeRejectedException("rejected", 401, MultiMap.caseInsensitiveMultiMap(), Buffer.buffer());

            WebSocketUpgradeException result = WebSocketUpgradeExceptions.translate(src);

            assertInstanceOf(WebSocketUpgradeRejected.class, result);
            assertEquals(401, ((WebSocketUpgradeRejected) result).status());
            assertSame(src, result.getCause());
        }

        @Test
        @DisplayName("preserves non-401 statuses unchanged")
        void preservesOtherStatuses() {
            UpgradeRejectedException src =
                    new UpgradeRejectedException("forbidden", 403, MultiMap.caseInsensitiveMultiMap(), Buffer.buffer());

            WebSocketUpgradeException result = WebSocketUpgradeExceptions.translate(src);

            assertEquals(403, ((WebSocketUpgradeRejected) result).status());
        }
    }

    @Nested
    @DisplayName("translate(WebSocketHandshakeException) with status in message")
    class NettyHandshakeWithStatus {

        @Test
        @DisplayName("parses status from 'Invalid handshake response getStatus: 401 Unauthorized'")
        void parsesStatusFromTypicalNettyMessage() {
            Throwable src = new WebSocketHandshakeException("Invalid handshake response getStatus: 401 Unauthorized");

            WebSocketUpgradeException result = WebSocketUpgradeExceptions.translate(src);

            assertInstanceOf(WebSocketUpgradeRejected.class, result);
            assertEquals(401, ((WebSocketUpgradeRejected) result).status());
        }

        @Test
        @DisplayName("parses 503 from 'getStatus: 503 Service Unavailable'")
        void parsesNon401Status() {
            Throwable src = new WebSocketHandshakeException("getStatus: 503 Service Unavailable");

            WebSocketUpgradeException result = WebSocketUpgradeExceptions.translate(src);

            assertEquals(503, ((WebSocketUpgradeRejected) result).status());
        }

        @Test
        @DisplayName("does not match 4-digit numbers like port 8080 in 'Connection refused: localhost:8080'")
        void ignoresNonStatusNumbers() {
            Throwable src = new RuntimeException("Connection refused: localhost:8080");

            WebSocketUpgradeException result = WebSocketUpgradeExceptions.translate(src);

            assertInstanceOf(WebSocketUpgradeTransportFailure.class, result);
        }

        @Test
        @DisplayName("does not match numbers outside HTTP status range")
        void ignoresOutOfRangeNumbers() {
            Throwable src = new RuntimeException("Buffer overflow at 999999 bytes (limit 600)");
            // 600 is just outside HTTP range (1xx-5xx) so it must NOT be parsed as status.

            WebSocketUpgradeException result = WebSocketUpgradeExceptions.translate(src);

            assertInstanceOf(WebSocketUpgradeTransportFailure.class, result);
        }
    }

    @Nested
    @DisplayName("translate(WebSocketHandshakeException) without status")
    class NettyHandshakeWithoutStatus {

        @Test
        @DisplayName("'Connection closed while handshake in process' maps to TransportFailure")
        void connectionClosedMapsToTransportFailure() {
            Throwable src = new WebSocketHandshakeException("Connection closed while handshake in process");

            WebSocketUpgradeException result = WebSocketUpgradeExceptions.translate(src);

            assertInstanceOf(WebSocketUpgradeTransportFailure.class, result);
            assertSame(src, result.getCause());
        }

        @Test
        @DisplayName("null message maps to TransportFailure")
        void nullMessageMapsToTransportFailure() {
            Throwable src = new RuntimeException((String) null);

            WebSocketUpgradeException result = WebSocketUpgradeExceptions.translate(src);

            assertInstanceOf(WebSocketUpgradeTransportFailure.class, result);
        }
    }

    @Nested
    @DisplayName("translate(WebSocketUpgradeException) idempotence")
    class Idempotence {

        @Test
        @DisplayName("already-typed Rejected is returned as-is")
        void alreadyTypedRejectedReturnedAsIs() {
            WebSocketUpgradeRejected src = new WebSocketUpgradeRejected(404, "not found", new RuntimeException());

            WebSocketUpgradeException result = WebSocketUpgradeExceptions.translate(src);

            assertSame(src, result);
        }

        @Test
        @DisplayName("already-typed TransportFailure is returned as-is")
        void alreadyTypedTransportFailureReturnedAsIs() {
            WebSocketUpgradeTransportFailure src = new WebSocketUpgradeTransportFailure("io", new RuntimeException());

            WebSocketUpgradeException result = WebSocketUpgradeExceptions.translate(src);

            assertSame(src, result);
        }
    }
}
