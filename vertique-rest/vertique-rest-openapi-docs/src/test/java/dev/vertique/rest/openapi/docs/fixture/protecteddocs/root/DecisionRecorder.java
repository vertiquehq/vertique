// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.protecteddocs.root;

import dev.vertique.security.events.AuthorizationDecisionEvent;
import dev.vertique.security.events.SecurityEventObserver;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records every {@link AuthorizationDecisionEvent} its component emits, in arrival order. Observers
 * are invoked asynchronously, so a test polls {@link #decisions()} until the event it expects has
 * arrived; it {@link #clear() clears} the recorder before the request whose decisions it inspects.
 */
@Singleton
public final class DecisionRecorder implements SecurityEventObserver {

    private final List<AuthorizationDecisionEvent> decisions = new CopyOnWriteArrayList<>();

    /** Creates an empty recorder. */
    @Inject
    public DecisionRecorder() {}

    @Override
    public Future<Void> onAuthorizationDecided(AuthorizationDecisionEvent event) {
        decisions.add(event);
        return Future.succeededFuture();
    }

    /**
     * Returns a copy of the decisions recorded since the last {@link #clear()}.
     *
     * @return the recorded decisions, in arrival order
     */
    public List<AuthorizationDecisionEvent> decisions() {
        return List.copyOf(decisions);
    }

    /** Forgets every recorded decision. */
    public void clear() {
        decisions.clear();
    }
}
