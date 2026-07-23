// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Facade that exposes all 10 named callback registries as named accessors and provides a static
 * Levenshtein-distance utility for generating "did you mean?" suggestions in validation error
 * messages.
 *
 * <p>Slice D's validator uses this class to check whether ids referenced in a definition document
 * are registered before the document is compiled.
 *
 * <p>This class is intentionally dumb — it only exposes the 10 registries as named accessors and
 * delegates all logic (lookup, contains, ids) to each registry. No additional routing or
 * caching is performed.
 */
@Singleton
public final class RegisteredIdentifierLookup {

    // --- Registries ---

    private final PayloadMapperRegistry payloadMappers;
    private final StartStateMapperRegistry startStateMappers;
    private final StateReducerRegistry stateReducers;
    private final StateMutatorRegistry stateMutators;
    private final TimerResolverRegistry timerResolvers;
    private final FailMessageFactoryRegistry failMessageFactories;
    private final SubjectResolverRegistry subjectResolvers;
    private final TaskAssignmentResolverRegistry taskAssignmentResolvers;
    private final BranchResultReducerRegistry branchResultReducers;
    private final NamedConditionRegistry namedConditions;

    // --- Construction ---

    /**
     * Constructs the lookup facade with all 10 registries.
     *
     * @param payloadMappers the {@link PayloadMapperRegistry}; non-null
     * @param startStateMappers the {@link StartStateMapperRegistry}; non-null
     * @param stateReducers the {@link StateReducerRegistry}; non-null
     * @param stateMutators the {@link StateMutatorRegistry}; non-null
     * @param timerResolvers the {@link TimerResolverRegistry}; non-null
     * @param failMessageFactories the {@link FailMessageFactoryRegistry}; non-null
     * @param subjectResolvers the {@link SubjectResolverRegistry}; non-null
     * @param taskAssignmentResolvers the {@link TaskAssignmentResolverRegistry}; non-null
     * @param branchResultReducers the {@link BranchResultReducerRegistry}; non-null
     * @param namedConditions the {@link NamedConditionRegistry}; non-null
     */
    @Inject
    public RegisteredIdentifierLookup(
            PayloadMapperRegistry payloadMappers,
            StartStateMapperRegistry startStateMappers,
            StateReducerRegistry stateReducers,
            StateMutatorRegistry stateMutators,
            TimerResolverRegistry timerResolvers,
            FailMessageFactoryRegistry failMessageFactories,
            SubjectResolverRegistry subjectResolvers,
            TaskAssignmentResolverRegistry taskAssignmentResolvers,
            BranchResultReducerRegistry branchResultReducers,
            NamedConditionRegistry namedConditions) {
        this.payloadMappers = Objects.requireNonNull(payloadMappers, "payloadMappers");
        this.startStateMappers = Objects.requireNonNull(startStateMappers, "startStateMappers");
        this.stateReducers = Objects.requireNonNull(stateReducers, "stateReducers");
        this.stateMutators = Objects.requireNonNull(stateMutators, "stateMutators");
        this.timerResolvers = Objects.requireNonNull(timerResolvers, "timerResolvers");
        this.failMessageFactories = Objects.requireNonNull(failMessageFactories, "failMessageFactories");
        this.subjectResolvers = Objects.requireNonNull(subjectResolvers, "subjectResolvers");
        this.taskAssignmentResolvers = Objects.requireNonNull(taskAssignmentResolvers, "taskAssignmentResolvers");
        this.branchResultReducers = Objects.requireNonNull(branchResultReducers, "branchResultReducers");
        this.namedConditions = Objects.requireNonNull(namedConditions, "namedConditions");
    }

    // --- Registry accessors ---

    /**
     * Returns the {@link PayloadMapperRegistry} (state → service payload).
     *
     * @return the payload mapper registry; never null
     */
    public PayloadMapperRegistry payloadMappers() {
        return payloadMappers;
    }

    /**
     * Returns the {@link StartStateMapperRegistry} (start payload → initial state).
     *
     * @return the start-state mapper registry; never null
     */
    public StartStateMapperRegistry startStateMappers() {
        return startStateMappers;
    }

    /**
     * Returns the {@link StateReducerRegistry} (state, event → new state).
     *
     * @return the state reducer registry; never null
     */
    public StateReducerRegistry stateReducers() {
        return stateReducers;
    }

    /**
     * Returns the {@link StateMutatorRegistry} (state → mutated state).
     *
     * @return the state mutator registry; never null
     */
    public StateMutatorRegistry stateMutators() {
        return stateMutators;
    }

    /**
     * Returns the {@link TimerResolverRegistry} (state → fire {@link java.time.Instant}).
     *
     * @return the timer resolver registry; never null
     */
    public TimerResolverRegistry timerResolvers() {
        return timerResolvers;
    }

    /**
     * Returns the {@link FailMessageFactoryRegistry} (state → failure message).
     *
     * @return the fail-message factory registry; never null
     */
    public FailMessageFactoryRegistry failMessageFactories() {
        return failMessageFactories;
    }

    /**
     * Returns the {@link SubjectResolverRegistry} (state → {@link dev.vertique.workflow.subject.WorkflowSubjectRef}).
     *
     * @return the subject resolver registry; never null
     */
    public SubjectResolverRegistry subjectResolvers() {
        return subjectResolvers;
    }

    /**
     * Returns the {@link TaskAssignmentResolverRegistry} (state → {@link dev.vertique.workflow.tasks.TaskAssignment}).
     *
     * @return the task-assignment resolver registry; never null
     */
    public TaskAssignmentResolverRegistry taskAssignmentResolvers() {
        return taskAssignmentResolvers;
    }

    /**
     * Returns the {@link BranchResultReducerRegistry}
     * (state, branch-results → merged state).
     *
     * @return the branch-result reducer registry; never null
     */
    public BranchResultReducerRegistry branchResultReducers() {
        return branchResultReducers;
    }

    /**
     * Returns the {@link NamedConditionRegistry} (state → Boolean).
     *
     * @return the named condition registry; never null
     */
    public NamedConditionRegistry namedConditions() {
        return namedConditions;
    }

    // --- Levenshtein nearest-id utility ---

    /**
     * Returns up to {@code limit} ids from {@code registeredIds} that are closest to
     * {@code unknownId} by Levenshtein edit distance, sorted ascending (smallest distance first).
     *
     * <p>This is a pure static utility — it carries no state and is safe to call from any thread.
     * Typical usage is inside a validation error message to provide "did you mean: …?" suggestions.
     *
     * <p>Examples:
     * <pre>{@code
     * nearestIds("ordr.payload", List.of("order.payload", "order.reduce"), 5)
     *   // → ["order.payload", "order.reduce"]  (order.payload is closer)
     * nearestIds("order.payload", List.of("order.payload", "order.reduce"), 1)
     *   // → ["order.payload"]                  (exact match, distance 0)
     * nearestIds("x", List.of(), 5)
     *   // → []                                 (no registered ids)
     * }</pre>
     *
     * @param unknownId the id that was not found; non-null
     * @param registeredIds the collection of all registered ids to rank; non-null
     * @param limit maximum number of ids to return; non-negative (0 returns empty list)
     * @return ordered list of nearest ids, from closest to furthest; never null, may be empty
     */
    public static List<String> nearestIds(String unknownId, Collection<String> registeredIds, int limit) {
        Objects.requireNonNull(unknownId, "unknownId");
        Objects.requireNonNull(registeredIds, "registeredIds");
        if (limit <= 0 || registeredIds.isEmpty()) {
            return List.of();
        }

        List<String> sorted = new ArrayList<>(registeredIds);
        sorted.sort(Comparator.comparingInt(id -> levenshtein(unknownId, id)));
        return List.copyOf(sorted.subList(0, Math.min(limit, sorted.size())));
    }

    /**
     * Computes the Levenshtein edit distance between {@code a} and {@code b}.
     *
     * <p>Uses the standard single-row DP algorithm: O(|a| × |b|) time,
     * O(min(|a|, |b|)) space.
     *
     * @param a first string; non-null
     * @param b second string; non-null
     * @return non-negative edit distance
     */
    private static int levenshtein(String a, String b) {
        int la = a.length();
        int lb = b.length();
        if (la == 0) return lb;
        if (lb == 0) return la;

        // Keep only two rows to minimise allocation.
        int[] prev = new int[lb + 1];
        int[] curr = new int[lb + 1];

        for (int j = 0; j <= lb; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= la; i++) {
            curr[0] = i;
            for (int j = 1; j <= lb; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[lb];
    }
}
