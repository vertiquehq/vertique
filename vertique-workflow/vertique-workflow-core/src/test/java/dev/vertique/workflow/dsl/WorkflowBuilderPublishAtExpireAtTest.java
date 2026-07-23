// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.plan.TimerNode;
import dev.vertique.workflow.plan.TimerSpec;
import dev.vertique.workflow.plan.WorkflowPlan;
import dev.vertique.workflow.registry.WorkflowCallbackRegistry;
import java.time.Instant;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the cycle-5 DSL sugar on {@link WorkflowBuilder}:
 * {@code .publishAt(...)} and {@code .expireAt(...)} both lower to a {@link TimerNode} with the
 * appropriate {@link TimerSpec} variant, and are indistinguishable from equivalent hand-rolled
 * {@code .timer(...)} / {@code .timerAt(...)} calls at the plan level.
 *
 * <p>Coverage targets:
 * <ul>
 *   <li>{@code publishAt(stepId, Instant)} → {@link TimerNode} + {@link TimerSpec.At}.</li>
 *   <li>{@code publishAt(stepId, resolver)} → {@link TimerNode} + {@link TimerSpec.FromState}
 *       with registered callback.</li>
 *   <li>{@code expireAt(stepId, Instant)} → {@link TimerNode} + {@link TimerSpec.At}.</li>
 *   <li>{@code expireAt(stepId, resolver)} → {@link TimerNode} + {@link TimerSpec.FromState}
 *       with registered callback.</li>
 *   <li>Plan-hash byte equivalence for the literal-Instant form: {@code publishAt(stepId, instant)}
 *       and {@code timer(stepId, instant)} produce identical {@code planHash()} values.</li>
 *   <li>Plan-hash byte equivalence for the literal-Instant form: {@code expireAt(stepId, instant)}
 *       and {@code timer(stepId, instant)} produce identical {@code planHash()} values.</li>
 *   <li>Resolver form: both {@code publishAt} and {@code expireAt} produce
 *       {@link TimerSpec.FromState} with a retrievable, callable callback.</li>
 *   <li>Missing next-step reference is rejected at {@code build()}.</li>
 *   <li>Duplicate step id rejected at the second call.</li>
 * </ul>
 */
class WorkflowBuilderPublishAtExpireAtTest {

    // --- State / command types ---

    record ArticleState(Instant publishAt, Instant expireAt) {}

    record StartCmd(Instant publishAt, Instant expireAt) {}

    // --- Helper ---

    private static WorkflowBuilder<ArticleState> baseBuilder() {
        WorkflowBuilder<ArticleState> wf = new WorkflowBuilder<>();
        wf.init(StartCmd.class, cmd -> new ArticleState(cmd.publishAt(), cmd.expireAt()))
                .initialStep("start");
        return wf;
    }

    // -------------------------------------------------------------------------
    // (a) publishAt — literal Instant
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("(a) publishAt — literal Instant")
    class PublishAtLiteralInstant {

        @Test
        @DisplayName("publishAt(stepId, Instant).toStep(next) emits TimerNode with TimerSpec.At")
        void emitsTimerNodeWithAt() {
            Instant fireAt = Instant.parse("2026-12-31T23:59:59Z");
            WorkflowBuilder<ArticleState> wf = baseBuilder();
            wf.dispatch("start", "svc", s -> new Object(), "publish-wait")
                    .publishAt("publish-wait", fireAt)
                    .toStep("published")
                    .complete("published");

            WorkflowPlan plan = wf.build("article-wf", 1L);

            TimerNode node = timerNode(plan, "publish-wait");
            assertThat(node.nextStepId()).isEqualTo("published");
            assertThat(node.spec()).isInstanceOf(TimerSpec.At.class);
            assertThat(((TimerSpec.At) node.spec()).fireAt()).isEqualTo(fireAt);
        }

        @Test
        @DisplayName("publishAt(stepId, Instant) planHash equals timer(stepId, Instant) planHash")
        void planHashEquivalenceWithTimer() {
            Instant fireAt = Instant.parse("2026-12-31T23:59:59Z");

            WorkflowBuilder<ArticleState> wf1 = baseBuilder();
            wf1.dispatch("start", "svc", s -> new Object(), "publish-wait")
                    .publishAt("publish-wait", fireAt)
                    .toStep("published")
                    .complete("published");
            String hashPublishAt = wf1.build("article-wf", 1L).planHash();

            WorkflowBuilder<ArticleState> wf2 = baseBuilder();
            wf2.dispatch("start", "svc", s -> new Object(), "publish-wait")
                    .timer("publish-wait", fireAt)
                    .toStep("published")
                    .complete("published");
            String hashTimer = wf2.build("article-wf", 1L).planHash();

            assertThat(hashPublishAt).isEqualTo(hashTimer);
        }

        @Test
        @DisplayName("publishAt missing next-step reference is rejected at build()")
        void missingNextStepRejected() {
            Instant fireAt = Instant.parse("2026-12-31T23:59:59Z");
            WorkflowBuilder<ArticleState> wf = baseBuilder();
            wf.dispatch("start", "svc", s -> new Object(), "publish-wait")
                    .publishAt("publish-wait", fireAt)
                    .toStep("nonexistent");
            assertThatThrownBy(() -> wf.build("article-wf", 1L)).isInstanceOf(WorkflowDefinitionException.class);
        }

        @Test
        @DisplayName("duplicate stepId for publishAt is rejected")
        void duplicateStepIdRejected() {
            Instant fireAt = Instant.parse("2026-12-31T23:59:59Z");
            WorkflowBuilder<ArticleState> wf = baseBuilder();
            wf.dispatch("start", "svc", s -> new Object(), "publish-wait");
            assertThatThrownBy(() -> wf.publishAt("start", fireAt)).isInstanceOf(WorkflowDefinitionException.class);
        }
    }

    // -------------------------------------------------------------------------
    // (b) publishAt — state resolver
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("(b) publishAt — state resolver")
    class PublishAtResolver {

        @Test
        @DisplayName(
                "publishAt(stepId, resolver).toStep(next) emits TimerNode with TimerSpec.FromState and callable callback")
        void emitsTimerNodeWithFromState() {
            Instant expected = Instant.parse("2026-06-15T10:00:00Z");
            Function<ArticleState, Instant> resolver = ArticleState::publishAt;

            WorkflowBuilder<ArticleState> wf = baseBuilder();
            wf.dispatch("start", "svc", s -> new Object(), "publish-wait")
                    .publishAt("publish-wait", resolver)
                    .toStep("published")
                    .complete("published");

            WorkflowPlan plan = wf.build("article-wf", 1L);

            TimerNode node = timerNode(plan, "publish-wait");
            assertThat(node.nextStepId()).isEqualTo("published");
            assertThat(node.spec()).isInstanceOf(TimerSpec.FromState.class);

            TimerSpec.FromState spec = (TimerSpec.FromState) node.spec();
            assertThat(spec.resolverCallbackId()).isNotNull();

            WorkflowCallbackRegistry registry = wf.callbackRegistry();
            Function<ArticleState, Instant> registered = registry.timerResolver(spec.resolverCallbackId());
            assertThat(registered).isNotNull();
            assertThat(registered.apply(new ArticleState(expected, null))).isEqualTo(expected);
        }

        @Test
        @DisplayName("publishAt resolver produces same TimerSpec variant as timerAt resolver")
        void specVariantMatchesTiaerAt() {
            Function<ArticleState, Instant> resolver = ArticleState::publishAt;

            WorkflowBuilder<ArticleState> wf1 = baseBuilder();
            wf1.dispatch("start", "svc", s -> new Object(), "publish-wait")
                    .publishAt("publish-wait", resolver)
                    .toStep("published")
                    .complete("published");
            TimerNode node1 = timerNode(wf1.build("article-wf", 1L), "publish-wait");

            WorkflowBuilder<ArticleState> wf2 = baseBuilder();
            wf2.dispatch("start", "svc", s -> new Object(), "publish-wait")
                    .timerAt("publish-wait", resolver)
                    .toStep("published")
                    .complete("published");
            TimerNode node2 = timerNode(wf2.build("article-wf", 1L), "publish-wait");

            assertThat(node1.spec()).isInstanceOf(TimerSpec.FromState.class);
            assertThat(node2.spec()).isInstanceOf(TimerSpec.FromState.class);
        }
    }

    // -------------------------------------------------------------------------
    // (c) expireAt — literal Instant
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("(c) expireAt — literal Instant")
    class ExpireAtLiteralInstant {

        @Test
        @DisplayName("expireAt(stepId, Instant).toStep(next) emits TimerNode with TimerSpec.At")
        void emitsTimerNodeWithAt() {
            Instant fireAt = Instant.parse("2027-01-01T00:00:00Z");
            WorkflowBuilder<ArticleState> wf = baseBuilder();
            wf.dispatch("start", "svc", s -> new Object(), "expire-wait")
                    .expireAt("expire-wait", fireAt)
                    .toStep("expired")
                    .complete("expired");

            WorkflowPlan plan = wf.build("article-wf", 1L);

            TimerNode node = timerNode(plan, "expire-wait");
            assertThat(node.nextStepId()).isEqualTo("expired");
            assertThat(node.spec()).isInstanceOf(TimerSpec.At.class);
            assertThat(((TimerSpec.At) node.spec()).fireAt()).isEqualTo(fireAt);
        }

        @Test
        @DisplayName("expireAt(stepId, Instant) planHash equals timer(stepId, Instant) planHash")
        void planHashEquivalenceWithTimer() {
            Instant fireAt = Instant.parse("2027-01-01T00:00:00Z");

            WorkflowBuilder<ArticleState> wf1 = baseBuilder();
            wf1.dispatch("start", "svc", s -> new Object(), "expire-wait")
                    .expireAt("expire-wait", fireAt)
                    .toStep("expired")
                    .complete("expired");
            String hashExpireAt = wf1.build("article-wf", 1L).planHash();

            WorkflowBuilder<ArticleState> wf2 = baseBuilder();
            wf2.dispatch("start", "svc", s -> new Object(), "expire-wait")
                    .timer("expire-wait", fireAt)
                    .toStep("expired")
                    .complete("expired");
            String hashTimer = wf2.build("article-wf", 1L).planHash();

            assertThat(hashExpireAt).isEqualTo(hashTimer);
        }

        @Test
        @DisplayName("expireAt missing next-step reference is rejected at build()")
        void missingNextStepRejected() {
            Instant fireAt = Instant.parse("2027-01-01T00:00:00Z");
            WorkflowBuilder<ArticleState> wf = baseBuilder();
            wf.dispatch("start", "svc", s -> new Object(), "expire-wait")
                    .expireAt("expire-wait", fireAt)
                    .toStep("nonexistent");
            assertThatThrownBy(() -> wf.build("article-wf", 1L)).isInstanceOf(WorkflowDefinitionException.class);
        }

        @Test
        @DisplayName("duplicate stepId for expireAt is rejected")
        void duplicateStepIdRejected() {
            Instant fireAt = Instant.parse("2027-01-01T00:00:00Z");
            WorkflowBuilder<ArticleState> wf = baseBuilder();
            wf.dispatch("start", "svc", s -> new Object(), "expire-wait");
            assertThatThrownBy(() -> wf.expireAt("start", fireAt)).isInstanceOf(WorkflowDefinitionException.class);
        }
    }

    // -------------------------------------------------------------------------
    // (d) expireAt — state resolver
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("(d) expireAt — state resolver")
    class ExpireAtResolver {

        @Test
        @DisplayName(
                "expireAt(stepId, resolver).toStep(next) emits TimerNode with TimerSpec.FromState and callable callback")
        void emitsTimerNodeWithFromState() {
            Instant expected = Instant.parse("2027-03-01T00:00:00Z");
            Function<ArticleState, Instant> resolver = ArticleState::expireAt;

            WorkflowBuilder<ArticleState> wf = baseBuilder();
            wf.dispatch("start", "svc", s -> new Object(), "expire-wait")
                    .expireAt("expire-wait", resolver)
                    .toStep("expired")
                    .complete("expired");

            WorkflowPlan plan = wf.build("article-wf", 1L);

            TimerNode node = timerNode(plan, "expire-wait");
            assertThat(node.nextStepId()).isEqualTo("expired");
            assertThat(node.spec()).isInstanceOf(TimerSpec.FromState.class);

            TimerSpec.FromState spec = (TimerSpec.FromState) node.spec();
            assertThat(spec.resolverCallbackId()).isNotNull();

            WorkflowCallbackRegistry registry = wf.callbackRegistry();
            Function<ArticleState, Instant> registered = registry.timerResolver(spec.resolverCallbackId());
            assertThat(registered).isNotNull();
            assertThat(registered.apply(new ArticleState(null, expected))).isEqualTo(expected);
        }

        @Test
        @DisplayName("expireAt resolver produces same TimerSpec variant as timerAt resolver")
        void specVariantMatchesTiaerAt() {
            Function<ArticleState, Instant> resolver = ArticleState::expireAt;

            WorkflowBuilder<ArticleState> wf1 = baseBuilder();
            wf1.dispatch("start", "svc", s -> new Object(), "expire-wait")
                    .expireAt("expire-wait", resolver)
                    .toStep("expired")
                    .complete("expired");
            TimerNode node1 = timerNode(wf1.build("article-wf", 1L), "expire-wait");

            WorkflowBuilder<ArticleState> wf2 = baseBuilder();
            wf2.dispatch("start", "svc", s -> new Object(), "expire-wait")
                    .timerAt("expire-wait", resolver)
                    .toStep("expired")
                    .complete("expired");
            TimerNode node2 = timerNode(wf2.build("article-wf", 1L), "expire-wait");

            assertThat(node1.spec()).isInstanceOf(TimerSpec.FromState.class);
            assertThat(node2.spec()).isInstanceOf(TimerSpec.FromState.class);
        }
    }

    // -------------------------------------------------------------------------
    // (e) Plan-hash continuity — PlanHashContinuityTest reference hash is unaffected
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("(e) Plan-hash continuity — sugar methods do not change existing plan hashes")
    class PlanHashContinuity {

        @Test
        @DisplayName("publishAt and timer produce identical planHash for the same literal Instant step graph")
        void publishAtAndTimerHashIdentical() {
            Instant fireAt = Instant.parse("2026-12-31T23:59:59Z");

            String h1 = buildHashWith(wf -> wf.dispatch("start", "svc", s -> new Object(), "w")
                    .publishAt("w", fireAt)
                    .toStep("done")
                    .complete("done"));

            String h2 = buildHashWith(wf -> wf.dispatch("start", "svc", s -> new Object(), "w")
                    .timer("w", fireAt)
                    .toStep("done")
                    .complete("done"));

            assertThat(h1).isEqualTo(h2);
        }

        @Test
        @DisplayName("expireAt and timer produce identical planHash for the same literal Instant step graph")
        void expireAtAndTimerHashIdentical() {
            Instant fireAt = Instant.parse("2027-01-01T00:00:00Z");

            String h1 = buildHashWith(wf -> wf.dispatch("start", "svc", s -> new Object(), "w")
                    .expireAt("w", fireAt)
                    .toStep("done")
                    .complete("done"));

            String h2 = buildHashWith(wf -> wf.dispatch("start", "svc", s -> new Object(), "w")
                    .timer("w", fireAt)
                    .toStep("done")
                    .complete("done"));

            assertThat(h1).isEqualTo(h2);
        }
    }

    // -------------------------------------------------------------------------
    // Helper methods
    // -------------------------------------------------------------------------

    /** Extracts the {@link TimerNode} with the given step id from the plan; fails if absent. */
    private static TimerNode timerNode(WorkflowPlan plan, String stepId) {
        return plan.nodes().stream()
                .filter(n -> n.stepId().equals(stepId))
                .map(n -> {
                    assertThat(n).as("node '%s' must be a TimerNode", stepId).isInstanceOf(TimerNode.class);
                    return (TimerNode) n;
                })
                .findFirst()
                .orElseThrow(() -> new AssertionError("No node with stepId '" + stepId + "' in plan: " + plan.nodes()));
    }

    /**
     * Builds a plan from a consumer that populates the builder body beyond the pre-configured
     * {@code start} dispatch step, and returns the resulting {@code planHash}.
     */
    private static String buildHashWith(java.util.function.Consumer<WorkflowBuilder<ArticleState>> body) {
        WorkflowBuilder<ArticleState> wf = baseBuilder();
        body.accept(wf);
        return wf.build("article-wf", 1L).planHash();
    }
}
