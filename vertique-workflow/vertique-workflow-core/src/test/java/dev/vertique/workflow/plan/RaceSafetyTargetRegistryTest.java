// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the PRD-WF-002 {@link RaceSafetyTargetRegistry} SPI.
 *
 * <p>Verifies:
 * <ul>
 *   <li>unregistered target ids resolve to {@link RaceSafety#NORMAL};</li>
 *   <li>registered targets resolve to their declared safety;</li>
 *   <li>same-safety re-registration is a no-op;</li>
 *   <li>conflicting registrations throw at registry construction.</li>
 * </ul>
 */
class RaceSafetyTargetRegistryTest {

    @Nested
    @DisplayName("Lookup")
    class LookupTests {

        @Test
        @DisplayName("unregistered target resolves to NORMAL")
        void unregisteredIsNormal() {
            RaceSafetyTargetRegistry r = build();
            assertThat(r.lookup("anything")).isEqualTo(RaceSafety.NORMAL);
        }

        @Test
        @DisplayName("registered target resolves to declared safety")
        void registeredResolvesToDeclared() {
            RaceSafetyTargetRegistry r = build(b -> b.register("svc.foo", RaceSafety.CANCEL_SAFE)
                    .register("svc.bar", RaceSafety.IGNORE_LATE_RESULT_SAFE));
            assertThat(r.lookup("svc.foo")).isEqualTo(RaceSafety.CANCEL_SAFE);
            assertThat(r.lookup("svc.bar")).isEqualTo(RaceSafety.IGNORE_LATE_RESULT_SAFE);
            assertThat(r.lookup("svc.unknown")).isEqualTo(RaceSafety.NORMAL);
        }
    }

    @Nested
    @DisplayName("Re-registration")
    class ReregistrationTests {

        @Test
        @DisplayName("same-safety re-registration is a no-op")
        void sameSafetyAllowed() {
            RaceSafetyTargetRegistry r = build(
                    b -> b.register("svc.foo", RaceSafety.CANCEL_SAFE).register("svc.foo", RaceSafety.CANCEL_SAFE));
            assertThat(r.lookup("svc.foo")).isEqualTo(RaceSafety.CANCEL_SAFE);
        }

        @Test
        @DisplayName("conflicting safety throws at construction")
        void conflictingSafetyThrows() {
            assertThatThrownBy(() -> build(b -> b.register("svc.foo", RaceSafety.CANCEL_SAFE)
                            .register("svc.foo", RaceSafety.IGNORE_LATE_RESULT_SAFE)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("svc.foo")
                    .hasMessageContaining("CANCEL_SAFE")
                    .hasMessageContaining("IGNORE_LATE_RESULT_SAFE");
        }

        @Test
        @DisplayName("conflicting safety across two contributors throws")
        void conflictAcrossContributors() {
            RaceSafetyTargetContributor a = b -> b.register("svc.foo", RaceSafety.CANCEL_SAFE);
            RaceSafetyTargetContributor b2 = b -> b.register("svc.foo", RaceSafety.NORMAL);
            assertThatThrownBy(() -> new DefaultRaceSafetyTargetRegistry(Set.of(a, b2)))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("Validation")
    class ValidationTests {

        @Test
        @DisplayName("rejects null targetId")
        void rejectsNullTargetId() {
            assertThatThrownBy(() -> build(b -> b.register(null, RaceSafety.CANCEL_SAFE)))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects empty targetId")
        void rejectsEmptyTargetId() {
            assertThatThrownBy(() -> build(b -> b.register("", RaceSafety.CANCEL_SAFE)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects null safety")
        void rejectsNullSafety() {
            assertThatThrownBy(() -> build(b -> b.register("svc.foo", null))).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects null lookup arg")
        void rejectsNullLookup() {
            RaceSafetyTargetRegistry r = build();
            assertThatThrownBy(() -> r.lookup(null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Empty registry")
    class EmptyTests {

        @Test
        @DisplayName("empty contributor set yields a registry that always returns NORMAL")
        void emptySetYieldsNormalEverywhere() {
            RaceSafetyTargetRegistry r = new DefaultRaceSafetyTargetRegistry(Set.of());
            assertThat(r.lookup("anything")).isEqualTo(RaceSafety.NORMAL);
        }
    }

    // --- helpers ---

    private static RaceSafetyTargetRegistry build() {
        return new DefaultRaceSafetyTargetRegistry(Set.of());
    }

    private static RaceSafetyTargetRegistry build(RaceSafetyTargetContributor contributor) {
        return new DefaultRaceSafetyTargetRegistry(Set.of(contributor));
    }
}
