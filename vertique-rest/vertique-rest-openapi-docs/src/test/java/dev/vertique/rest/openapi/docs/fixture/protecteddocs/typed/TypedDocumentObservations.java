// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.protecteddocs.typed;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What the typed document fixture observed: which actions its counting authorizer evaluated and how
 * many requests reached its late contributor. One instance per component, so a fresh deployment starts
 * from zero.
 */
public final class TypedDocumentObservations {

    private final List<String> authorizedActions = new CopyOnWriteArrayList<>();
    private final AtomicInteger lateContributorRuns = new AtomicInteger();

    /** Creates empty observations. */
    public TypedDocumentObservations() {}

    void recordAuthorization(String action) {
        authorizedActions.add(action);
    }

    void recordLateContributorRun() {
        lateContributorRuns.incrementAndGet();
    }

    /**
     * Returns how often the counting authorizer was asked to evaluate an action.
     *
     * @return the evaluation count
     */
    public int authorizations() {
        return authorizedActions.size();
    }

    /**
     * Returns the actions the counting authorizer evaluated, in order.
     *
     * @return the evaluated actions
     */
    public List<String> authorizedActions() {
        return List.copyOf(authorizedActions);
    }

    /**
     * Returns how many requests reached the late contributor, which runs after authorization.
     *
     * @return the run count
     */
    public int lateContributorRuns() {
        return lateContributorRuns.get();
    }
}
