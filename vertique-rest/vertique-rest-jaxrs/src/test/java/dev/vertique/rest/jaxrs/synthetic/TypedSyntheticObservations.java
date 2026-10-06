// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.synthetic;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What the typed synthetic fixture observed: how often each operation's terminal handler ran and
 * which actions the counting authorizer was asked about. One instance per component, so a fresh
 * deployment starts from zero.
 */
public final class TypedSyntheticObservations {

    private final Map<String, AtomicInteger> terminalRuns = new ConcurrentHashMap<>();
    private final List<String> authorizedActions = new CopyOnWriteArrayList<>();

    TypedSyntheticObservations() {}

    void recordTerminal(String operationId) {
        terminalRuns
                .computeIfAbsent(operationId, ignored -> new AtomicInteger())
                .incrementAndGet();
    }

    void recordAuthorization(String action) {
        authorizedActions.add(action);
    }

    /**
     * Returns how often the terminal handler of {@code operationId} ran.
     *
     * @param operationId the synthetic operation id
     * @return the run count, zero when the terminal never ran
     */
    public int terminalRuns(String operationId) {
        AtomicInteger runs = terminalRuns.get(operationId);
        return runs == null ? 0 : runs.get();
    }

    /**
     * Returns how often the counting authorizer was asked to evaluate an action.
     *
     * @return the evaluation count across every action
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
}
