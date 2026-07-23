// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.websocket.chat;

import dev.vertique.rest.websocket.WebSocketSession;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Thread-safe registry that maps room identifiers to their current set of WebSocket sessions.
 *
 * <p>Room membership is protected by a {@link ConcurrentHashMap} with {@link CopyOnWriteArraySet}
 * values — membership writes are rare (connect / disconnect) while membership snapshots for
 * broadcast are frequent. The map guards membership; Vert.x itself serialises writes to an
 * individual socket.
 *
 * <p>Closed or failed sessions are automatically pruned from their room during broadcast so that
 * the registry self-heals without requiring explicit eviction on every disconnect path.
 */
@Singleton
public class ChatRoomRegistry {

    private final ConcurrentHashMap<String, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();

    /** Creates a new, empty registry. */
    @Inject
    public ChatRoomRegistry() {}

    /**
     * Adds the session to the given room. Creates the room entry if it does not yet exist.
     *
     * @param roomId  room identifier
     * @param session the session to register
     */
    public void join(String roomId, WebSocketSession session) {
        rooms.computeIfAbsent(roomId, k -> new CopyOnWriteArraySet<>()).add(session);
    }

    /**
     * Removes the session from every room it is a member of. Empty rooms are cleaned up.
     *
     * @param session the session to remove
     */
    public void leave(WebSocketSession session) {
        rooms.forEach((roomId, sessions) -> rooms.computeIfPresent(roomId, (k, v) -> {
            v.remove(session);
            return v.isEmpty() ? null : v;
        }));
    }

    /**
     * Broadcasts {@code event} to every open session in the given room.
     *
     * <p>Sessions that are closed ({@link WebSocketSession#isOpen()} returns {@code false}) or
     * whose {@code send} future fails are removed from the room automatically. The returned future
     * always completes (never fails) once all per-peer futures have resolved, regardless of
     * individual peer failures.
     *
     * @param roomId room identifier
     * @param event  event to broadcast
     * @return a future that completes when all per-peer send attempts have resolved
     */
    public Future<Void> broadcast(String roomId, ChatEvent event) {
        Set<WebSocketSession> sessions = rooms.get(roomId);
        if (sessions == null || sessions.isEmpty()) {
            return Future.succeededFuture();
        }

        // Each per-peer future resolves to Optional.of(session) if the peer is dead, otherwise empty.
        // Collecting dead peers this way keeps all writes on the composition thread (not per-session
        // event loops), avoiding a race on a shared collector. WebSocketSession instances may be
        // scoped to different event-loop contexts — see WebSocketSession javadoc.
        List<Future<Optional<WebSocketSession>>> perPeerFutures = new ArrayList<>();
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                perPeerFutures.add(Future.succeededFuture(Optional.of(session)));
                continue;
            }
            perPeerFutures.add(session.send(event)
                    .<Optional<WebSocketSession>>map(v -> Optional.empty())
                    .otherwise(err -> Optional.of(session)));
        }

        return Future.all(perPeerFutures).compose(cf -> {
            List<WebSocketSession> dead = new ArrayList<>();
            for (int i = 0; i < cf.size(); i++) {
                Optional<WebSocketSession> result = cf.resultAt(i);
                result.ifPresent(dead::add);
            }
            if (!dead.isEmpty()) {
                rooms.computeIfPresent(roomId, (k, v) -> {
                    v.removeAll(dead);
                    return v.isEmpty() ? null : v;
                });
            }
            return Future.<Void>succeededFuture();
        });
    }

    /**
     * Returns the number of sessions currently registered in the given room.
     *
     * <p><strong>Visible for testing only.</strong> Do not call this from production code.
     *
     * @param roomId room identifier
     * @return current session count, or {@code 0} if the room does not exist
     */
    int roomSize(String roomId) {
        Set<WebSocketSession> sessions = rooms.get(roomId);
        return sessions == null ? 0 : sessions.size();
    }
}
