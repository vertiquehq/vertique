// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.plan.WorkflowPlan;
import dev.vertique.workflow.registry.CallbackId;
import dev.vertique.workflow.registry.WorkflowCallbackRegistry;
import dev.vertique.workflow.subject.WorkflowSubjectRef;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@code .subject(resolver)} DSL surface on {@link WorkflowBuilder}.
 *
 * <p>Tests cover only the registration plumbing — no engine behavior is exercised:
 * <ul>
 *   <li>Calling {@code .subject(resolver)} stores a {@code CallbackId} on the plan
 *       ({@link WorkflowPlan#subjectResolverCallbackId()} is non-null).</li>
 *   <li>The stored {@code CallbackId} is stable across independent builds of the same DSL.</li>
 *   <li>The callback registry returned by {@link WorkflowBuilder#callbackRegistry()} resolves
 *       the lambda via {@link WorkflowCallbackRegistry#subjectResolver(CallbackId)} and
 *       invoking it returns the expected {@link WorkflowSubjectRef}.</li>
 *   <li>No subject resolver registered → {@code subjectResolverCallbackId()} is null on the plan,
 *       and calling {@code subjectResolver(id)} on the registry throws
 *       {@link UnsupportedOperationException}.</li>
 *   <li>Passing {@code null} to {@code .subject(...)} throws {@link NullPointerException}.</li>
 * </ul>
 */
class WorkflowBuilderSubjectResolverTest {

    // --- State / command types ---

    record ArticleState(String articleId, String version) {}

    record StartCmd(String articleId, String version) {}

    // --- Helper to build a minimal plan with a subject resolver ---

    private static WorkflowBuilder<ArticleState> builderWithSubjectResolver(
            Function<ArticleState, WorkflowSubjectRef> resolver) {
        WorkflowBuilder<ArticleState> wf = new WorkflowBuilder<>();
        wf.subject(resolver)
                .init(StartCmd.class, cmd -> new ArticleState(cmd.articleId(), cmd.version()))
                .initialStep("done")
                .complete("done");
        return wf;
    }

    // --- Helper to build a minimal plan WITHOUT a subject resolver ---

    private static WorkflowBuilder<ArticleState> builderWithoutSubjectResolver() {
        WorkflowBuilder<ArticleState> wf = new WorkflowBuilder<>();
        wf.init(StartCmd.class, cmd -> new ArticleState(cmd.articleId(), cmd.version()))
                .initialStep("done")
                .complete("done");
        return wf;
    }

    // --- (a) subjectResolverCallbackId on plan ---

    @Nested
    @DisplayName("(a) plan carries subjectResolverCallbackId when resolver registered")
    class PlanCallbackId {

        @Test
        @DisplayName("plan.subjectResolverCallbackId() is non-null after .subject(resolver)")
        void callbackIdNonNullWhenRegistered() {
            Function<ArticleState, WorkflowSubjectRef> resolver =
                    s -> new WorkflowSubjectRef("Article", s.articleId(), s.version());
            WorkflowPlan plan =
                    builderWithSubjectResolver(resolver).build("article-wf", 1L, ArticleState.class.getName());
            assertThat(plan.subjectResolverCallbackId()).isNotNull();
        }

        @Test
        @DisplayName("plan.subjectResolverCallbackId() is null when no resolver registered")
        void callbackIdNullWhenNotRegistered() {
            WorkflowPlan plan = builderWithoutSubjectResolver().build("article-wf", 1L, ArticleState.class.getName());
            assertThat(plan.subjectResolverCallbackId()).isNull();
        }

        @Test
        @DisplayName("CallbackId value is stable across two independent builds of the same DSL")
        void callbackIdIsStableAcrossBuilds() {
            Function<ArticleState, WorkflowSubjectRef> resolver =
                    s -> new WorkflowSubjectRef("Article", s.articleId(), s.version());

            WorkflowBuilder<ArticleState> wf1 = new WorkflowBuilder<>();
            wf1.subject(resolver)
                    .init(StartCmd.class, cmd -> new ArticleState(cmd.articleId(), cmd.version()))
                    .initialStep("done")
                    .complete("done");
            CallbackId cbId1 =
                    wf1.build("article-wf", 1L, ArticleState.class.getName()).subjectResolverCallbackId();

            WorkflowBuilder<ArticleState> wf2 = new WorkflowBuilder<>();
            wf2.subject(resolver)
                    .init(StartCmd.class, cmd -> new ArticleState(cmd.articleId(), cmd.version()))
                    .initialStep("done")
                    .complete("done");
            CallbackId cbId2 =
                    wf2.build("article-wf", 1L, ArticleState.class.getName()).subjectResolverCallbackId();

            assertThat(cbId1).isNotNull();
            assertThat(cbId1.value()).isEqualTo(cbId2.value());
        }
    }

    // --- (b) Registry round-trip ---

    @Nested
    @DisplayName("(b) registry round-trip — resolver lambda is retrievable and callable")
    class RegistryRoundTrip {

        @Test
        @DisplayName("subjectResolver(callbackId).apply(state) returns the expected WorkflowSubjectRef")
        void resolverRoundTrip() {
            Function<ArticleState, WorkflowSubjectRef> resolver =
                    s -> new WorkflowSubjectRef("Article", s.articleId(), s.version());

            WorkflowBuilder<ArticleState> wf = builderWithSubjectResolver(resolver);
            WorkflowPlan plan = wf.build("article-wf", 1L, ArticleState.class.getName());
            WorkflowCallbackRegistry registry = wf.callbackRegistry();

            CallbackId cbId = plan.subjectResolverCallbackId();
            assertThat(cbId).isNotNull();

            Function<ArticleState, WorkflowSubjectRef> retrieved = registry.subjectResolver(cbId);
            assertThat(retrieved).isNotNull();

            ArticleState state = new ArticleState("article-123", "v7");
            WorkflowSubjectRef ref = retrieved.apply(state);
            assertThat(ref.type()).isEqualTo("Article");
            assertThat(ref.id()).isEqualTo("article-123");
            assertThat(ref.version()).isEqualTo("v7");
        }

        @Test
        @DisplayName("subjectResolver on registry with no resolver throws UnsupportedOperationException")
        void noResolverThrows() {
            WorkflowBuilder<ArticleState> wf = builderWithoutSubjectResolver();
            wf.build("article-wf", 1L, ArticleState.class.getName());
            WorkflowCallbackRegistry registry = wf.callbackRegistry();

            assertThatThrownBy(() -> registry.subjectResolver(new CallbackId("$subject.resolver")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // --- (c) Null guard ---

    @Nested
    @DisplayName("(c) null resolver is rejected")
    class NullGuard {

        @Test
        @DisplayName(".subject(null) throws NullPointerException")
        void nullResolverThrows() {
            WorkflowBuilder<ArticleState> wf = new WorkflowBuilder<>();
            assertThatNullPointerException().isThrownBy(() -> wf.subject(null)).withMessage("resolver");
        }
    }

    @Nested
    @DisplayName("(d) duplicate registration is rejected")
    class DuplicateGuard {

        @Test
        @DisplayName("a second .subject(...) on the same builder throws IllegalStateException")
        void duplicateSubjectRegistrationThrows() {
            WorkflowBuilder<ArticleState> wf = new WorkflowBuilder<>();
            wf.subject(state -> new WorkflowSubjectRef("Article", state.articleId(), state.version()));
            assertThatIllegalStateException()
                    .isThrownBy(() -> wf.subject(state -> new WorkflowSubjectRef("Article", "other", null)))
                    .withMessageContaining("already been registered");
        }
    }
}
