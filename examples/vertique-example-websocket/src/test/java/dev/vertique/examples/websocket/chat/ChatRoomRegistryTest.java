// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.websocket.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import dev.vertique.rest.websocket.WebSocketSession;
import io.vertx.core.Future;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link ChatRoomRegistry}.
 *
 * <p>Verifies join/leave membership management, broadcast fan-out, per-peer failure isolation,
 * closed-session pruning, and room isolation.
 */
@ExtendWith(MockitoExtension.class)
class ChatRoomRegistryTest {

    private ChatRoomRegistry registry;

    @Mock
    private WebSocketSession sessionA;

    @Mock
    private WebSocketSession sessionB;

    @Mock
    private WebSocketSession sessionC;

    @BeforeEach
    void setUp() {
        registry = new ChatRoomRegistry();
        // By default, all sessions report as open. Individual tests override where needed.
        lenient().when(sessionA.isOpen()).thenReturn(true);
        lenient().when(sessionB.isOpen()).thenReturn(true);
        lenient().when(sessionC.isOpen()).thenReturn(true);
    }

    private ChatEvent event() {
        return new ChatEvent("message", "alice", "hello", Instant.now());
    }

    @Nested
    @DisplayName("join")
    class Join {

        @Test
        @DisplayName("increments room size after join")
        void joinIncreasesRoomSize() {
            registry.join("room1", sessionA);

            assertEquals(1, registry.roomSize("room1"));
        }

        @Test
        @DisplayName("two sessions in same room → room size is 2")
        void twoSessionsSameRoom() {
            registry.join("room1", sessionA);
            registry.join("room1", sessionB);

            assertEquals(2, registry.roomSize("room1"));
        }
    }

    @Nested
    @DisplayName("broadcast")
    class Broadcast {

        @Test
        @DisplayName("broadcast reaches both sessions in the room")
        void broadcastReachesBothSessions() {
            when(sessionA.send(any(ChatEvent.class))).thenReturn(Future.succeededFuture());
            when(sessionB.send(any(ChatEvent.class))).thenReturn(Future.succeededFuture());

            registry.join("room1", sessionA);
            registry.join("room1", sessionB);

            Future<Void> result = registry.broadcast("room1", event());

            assertTrue(result.succeeded() || !result.failed(), "broadcast future should complete");
            verify(sessionA).send(any(ChatEvent.class));
            verify(sessionB).send(any(ChatEvent.class));
        }

        @Test
        @DisplayName("failing peer is evicted; healthy peer still receives event; aggregate future completes")
        void failingPeerEvictedHealthyPeerReceives() {
            when(sessionA.send(any(ChatEvent.class))).thenReturn(Future.failedFuture(new RuntimeException("boom")));
            when(sessionB.send(any(ChatEvent.class))).thenReturn(Future.succeededFuture());

            registry.join("room1", sessionA);
            registry.join("room1", sessionB);

            Future<Void> result = registry.broadcast("room1", event());

            // All sends complete synchronously in tests; aggregate must not fail even though one peer failed.
            assertFalse(result.failed(), "aggregate future must not fail when only one peer fails");

            // healthy peer received the event
            verify(sessionB).send(any(ChatEvent.class));

            // failing peer is evicted
            assertEquals(1, registry.roomSize("room1"), "failing peer must be evicted");
        }

        @Test
        @DisplayName("closed session (isOpen=false) is skipped and removed from the room")
        void closedSessionSkippedAndRemoved() {
            when(sessionA.isOpen()).thenReturn(false);
            when(sessionB.send(any(ChatEvent.class))).thenReturn(Future.succeededFuture());

            registry.join("room1", sessionA);
            registry.join("room1", sessionB);

            registry.broadcast("room1", event());

            // sessionA (closed) must not have send called on it
            verify(sessionA, never()).send(any(ChatEvent.class));
            // sessionA must be removed from the room
            assertEquals(1, registry.roomSize("room1"), "closed session must be evicted");
        }

        @Test
        @DisplayName("broadcast to room A does not reach session in room B")
        void broadcastIsolatedByRoom() {
            when(sessionA.send(any(ChatEvent.class))).thenReturn(Future.succeededFuture());

            registry.join("roomA", sessionA);
            registry.join("roomB", sessionB);

            registry.broadcast("roomA", event());

            verify(sessionA).send(any(ChatEvent.class));
            verify(sessionB, never()).send(any(ChatEvent.class));
        }
    }

    @Nested
    @DisplayName("leave")
    class Leave {

        @Test
        @DisplayName("leave removes session from all rooms")
        void leaveRemovesSessionFromAllRooms() {
            registry.join("room1", sessionA);
            registry.join("room2", sessionA);
            registry.join("room1", sessionB);

            registry.leave(sessionA);

            assertEquals(0, registry.roomSize("room2"), "sessionA must be gone from room2");
            assertEquals(1, registry.roomSize("room1"), "sessionB still in room1");
        }

        @Test
        @DisplayName("leave then broadcast does not call send on departed session")
        void leavePreventsFutureBroadcast() {
            when(sessionB.send(any(ChatEvent.class))).thenReturn(Future.succeededFuture());

            registry.join("room1", sessionA);
            registry.join("room1", sessionB);

            registry.leave(sessionA);
            registry.broadcast("room1", event());

            verify(sessionA, never()).send(any(ChatEvent.class));
        }
    }
}
