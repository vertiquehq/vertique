// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.subject.WorkflowSubjectRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Differential plan-hash tests for the cycle-5 subject-resolver field.
 *
 * <p>These tests prove that {@link WorkflowPlan#subjectResolverCallbackId()} participates in
 * the plan hash when set, and is absent (zero bytes written) when null. The golden-hash continuity
 * test in {@link PlanHashContinuityTest} separately pins the exact hash bytes for the cycle-3
 * reference plan — this class only proves differential behaviour.
 *
 * <p>Tests:
 * <ol>
 *   <li>A plan built with {@code .subject(resolver)} and one built without it produce <em>different</em>
 *       plan hashes — confirming the field participates when set.</li>
 *   <li>Two independent builds of the same plan (both with a resolver) produce <em>identical</em>
 *       hashes — confirming the hash is deterministic.</li>
 *   <li>Two independent builds of the same plan (both without a resolver) produce <em>identical</em>
 *       hashes — confirming null is handled consistently.</li>
 * </ol>
 */
class PlanHashSubjectResolverDifferentialTest {

    // --- Minimal state / command types ---

    record SimpleState(String id) {}

    record StartCmd(String id) {}

    // --- Helper: build a minimal start→complete plan, optionally with subject resolver ---

    private static WorkflowPlan buildMinimalPlan(boolean withSubjectResolver) {
        WorkflowBuilder<SimpleState> wf = new WorkflowBuilder<>();
        if (withSubjectResolver) {
            wf.subject(s -> new WorkflowSubjectRef("Foo", s.id(), null));
        }
        wf.init(StartCmd.class, cmd -> new SimpleState(cmd.id()))
                .initialStep("done")
                .complete("done");
        return wf.build("subject-diff-test", 1L, SimpleState.class.getName());
    }

    // --- (a) Subject resolver changes the hash ---

    @Nested
    @DisplayName("(a) subject resolver participates in hash when set")
    class SubjectResolverParticipates {

        @Test
        @DisplayName("plan with resolver hashes differently from plan without resolver")
        void withVsWithoutResolverHashesDiffer() {
            String hashWithResolver = buildMinimalPlan(true).planHash();
            String hashWithoutResolver = buildMinimalPlan(false).planHash();

            assertThat(hashWithResolver)
                    .as("plan with .subject(resolver) must hash differently from plan without it")
                    .isNotEqualTo(hashWithoutResolver);
        }
    }

    // --- (b) Determinism with resolver ---

    @Nested
    @DisplayName("(b) determinism — two builds with resolver produce equal hash")
    class DeterminismWithResolver {

        @Test
        @DisplayName("two independent builds with subject resolver produce identical plan hashes")
        void twoBuildsWithResolverAreEqual() {
            String hash1 = buildMinimalPlan(true).planHash();
            String hash2 = buildMinimalPlan(true).planHash();

            assertThat(hash1)
                    .as("two builds with same .subject(resolver) DSL must produce equal hashes")
                    .isEqualTo(hash2);
        }
    }

    // --- (c) Determinism without resolver ---

    @Nested
    @DisplayName("(c) determinism — two builds without resolver produce equal hash")
    class DeterminismWithoutResolver {

        @Test
        @DisplayName("two independent builds without subject resolver produce identical plan hashes")
        void twoBuildsWithoutResolverAreEqual() {
            String hash1 = buildMinimalPlan(false).planHash();
            String hash2 = buildMinimalPlan(false).planHash();

            assertThat(hash1)
                    .as("two builds without .subject(resolver) must produce equal hashes")
                    .isEqualTo(hash2);
        }
    }
}
