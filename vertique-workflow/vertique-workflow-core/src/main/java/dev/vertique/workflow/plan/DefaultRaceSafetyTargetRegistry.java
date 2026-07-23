// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code @Singleton} implementation of {@link RaceSafetyTargetRegistry} populated from the set of
 * {@link RaceSafetyTargetContributor}s contributed via Dagger multibinding.
 *
 * <p>Construction-time semantics:
 * <ol>
 *   <li>Each contributor is invoked exactly once with a fresh internal {@link Builder}.</li>
 *   <li>Contributors register {@code (targetId, safety)} pairs.</li>
 *   <li>After all contributors run the builder is sealed; the resulting map is immutable and
 *       lookups are O(1).</li>
 * </ol>
 *
 * <p>Conflict policy: registering the same {@code targetId} with different safeties throws
 * {@link IllegalStateException} at construction; same-safety re-registration is a no-op. This
 * surfaces ambiguity instead of silently picking a winner.
 */
@Singleton
public final class DefaultRaceSafetyTargetRegistry implements RaceSafetyTargetRegistry {

    private final Map<String, RaceSafety> byTargetId;

    /**
     * Constructs the registry by invoking every contributor and sealing the result.
     *
     * @param contributors the set of all contributed {@link RaceSafetyTargetContributor}s; an
     *     empty set is valid (no race-safe targets registered)
     */
    @Inject
    public DefaultRaceSafetyTargetRegistry(Set<RaceSafetyTargetContributor> contributors) {
        Builder b = new Builder();
        for (RaceSafetyTargetContributor c : contributors) {
            c.contribute(b);
        }
        this.byTargetId = Map.copyOf(b.byTargetId);
    }

    @Override
    public RaceSafety lookup(String targetId) {
        Objects.requireNonNull(targetId, "targetId");
        return byTargetId.getOrDefault(targetId, RaceSafety.NORMAL);
    }

    /**
     * Mutable builder used during construction and exposed to contributors.
     */
    private static final class Builder implements RaceSafetyTargetRegistry.Builder {

        private final Map<String, RaceSafety> byTargetId = new HashMap<>();

        @Override
        public Builder register(String targetId, RaceSafety safety) {
            Objects.requireNonNull(targetId, "targetId");
            Objects.requireNonNull(safety, "safety");
            if (targetId.isEmpty()) {
                throw new IllegalArgumentException("targetId must be non-empty");
            }
            RaceSafety existing = byTargetId.putIfAbsent(targetId, safety);
            if (existing != null && existing != safety) {
                throw new IllegalStateException("conflicting RaceSafety declarations for target '" + targetId
                        + "': already registered as " + existing + ", attempted to re-register as " + safety
                        + "; remove the duplicate contributor or align on a single safety classification");
            }
            return this;
        }
    }
}
