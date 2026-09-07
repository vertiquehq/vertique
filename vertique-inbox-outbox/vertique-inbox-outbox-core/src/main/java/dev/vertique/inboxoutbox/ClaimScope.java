// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Declares which outbox rows of a given {@link DestinationType} this relay node is eligible to
 * claim and deliver.
 *
 * <p>A handler returns a {@code ClaimScope} from {@link OutboxDestinationHandler#claimScope()} to
 * control which rows of its destination type this node may pick up during a claim cycle.
 *
 * <p>Two variants are defined:
 * <ul>
 *   <li>{@link All} — every row of this destination type is claimable by this node; use for
 *       globally deliverable destinations such as Kafka topics or cluster-wide event bus
 *       addresses.</li>
 *   <li>{@link Destinations} — only rows whose {@code destination} column value is present in the
 *       supplier's set are claimable; use for resolver-scoped destinations where only a subset of
 *       targets are reachable from this node (e.g. locally registered service operations).</li>
 * </ul>
 *
 * <p>The relay evaluates the supplier in {@link Destinations} at claim time, so the set reflects
 * the registrations available to this node at that moment. The supplier must be fast and
 * non-blocking. A {@code null} return from the supplier, or blank / oversized elements in the
 * returned set, will fail the claim cycle. An empty set claims nothing.
 *
 * <p>Use the static factories {@link #all()} and {@link #destinations(Supplier)} rather than
 * constructing the nested records directly.
 */
public sealed interface ClaimScope permits ClaimScope.All, ClaimScope.Destinations {

    // --- Shared constant ---

    /** Shared singleton instance of {@link All}; returned by {@link #all()}. */
    All ALL_INSTANCE = new All();

    // --- Static factories ---

    /**
     * Returns a {@link ClaimScope} that admits every row of the associated destination type.
     *
     * <p>Suitable for globally deliverable destination types such as Kafka topics or
     * cluster-wide event bus addresses where every relay node can handle every row.
     *
     * @return the shared {@link All} instance
     */
    static ClaimScope all() {
        return ALL_INSTANCE;
    }

    /**
     * Returns a {@link ClaimScope} that admits only rows whose {@code destination} column value is
     * present in the set returned by {@code claimableTargets} at claim time.
     *
     * <p>The supplier is evaluated lazily at each claim cycle so it reflects the current set of
     * registrations available to this node. The supplier must be fast, non-blocking, and must not
     * return {@code null}.
     *
     * @param claimableTargets supplier of the set of destination values this node can deliver to;
     *                         must not be {@code null} and must not return {@code null}
     * @return a {@link Destinations} scope wrapping the given supplier
     */
    static ClaimScope destinations(Supplier<Set<String>> claimableTargets) {
        return new Destinations(claimableTargets);
    }

    // --- Permitted variants ---

    /**
     * A {@link ClaimScope} that admits every row of the associated destination type for claiming.
     *
     * <p>Use when the destination type is globally deliverable and every relay node can handle
     * every row of that type.
     */
    record All() implements ClaimScope {}

    /**
     * A {@link ClaimScope} that admits only outbox rows whose {@code destination} column value is
     * contained in the set supplied by {@link #claimableTargets()} at claim time.
     *
     * <p>Use when only a subset of targets for this destination type are reachable from this relay
     * node — for example, when the node hosts only certain locally registered service operations.
     *
     * <p>The supplier is evaluated at each claim cycle; it must be fast, non-blocking, and must
     * not return {@code null}. An empty set means this node claims nothing for the type. The store adapter
     * validates supplier results before use: {@code null} returns, blank elements, and oversized
     * elements all fail the claim cycle.
     *
     * @param claimableTargets supplier of destination values this node can deliver to; must not be
     *                         {@code null}
     */
    record Destinations(Supplier<Set<String>> claimableTargets) implements ClaimScope {

        /**
         * Compact constructor that validates the supplier reference is non-null.
         *
         * @param claimableTargets supplier of claimable destination values; must not be
         *                         {@code null}
         */
        public Destinations {
            Objects.requireNonNull(claimableTargets, "claimableTargets");
        }
    }
}
