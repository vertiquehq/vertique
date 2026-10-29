// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.exception.ConnectionException;
import dev.vertique.db.exception.TransactionDeadlineExceededException;
import dev.vertique.workflow.exception.WorkflowBusinessKeyConflictException;
import dev.vertique.workflow.exception.WorkflowConflictException;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowPersistenceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies how a failure, as the transaction runner returns it, is classified. */
class DispositionClassificationTest {

    @Test
    @DisplayName("a retryable persistence failure and an optimistic conflict are transient")
    void retryablePersistenceFailureAndOptimisticConflictAreTransient() {
        assertThat(Dispositions.isTransient(
                        new WorkflowPersistenceException("lock", new ConnectionException("reset"), true)))
                .isTrue();
        assertThat(Dispositions.isTransient(new WorkflowConflictException("version moved")))
                .isTrue();
        assertThat(Dispositions.isTransient(new ConnectionException("reset"))).isTrue();
    }

    @Test
    @DisplayName("a non-retryable persistence failure, a business conflict and a definition failure are not transient")
    void nonRetryableFailuresAreNotTransient() {
        assertThat(Dispositions.isTransient(new WorkflowPersistenceException("x", new IllegalStateException(), false)))
                .isFalse();
        assertThat(Dispositions.isTransient(
                        new WorkflowBusinessKeyConflictException("d", "k", new IllegalStateException())))
                .isFalse();
        assertThat(Dispositions.isTransient(new WorkflowDefinitionException("bad", new IllegalStateException())))
                .isFalse();
        assertThat(Dispositions.isTransient(new IllegalStateException("boom"))).isFalse();
    }

    @Test
    @DisplayName("a deadline overrun is recognised as the failure and as a cause")
    void deadlineOverrunIsRecognisedAsFailureAndAsCause() {
        TransactionDeadlineExceededException deadline = new TransactionDeadlineExceededException("late");
        assertThat(Dispositions.isDeadline(deadline)).isTrue();
        assertThat(Dispositions.isDeadline(new IllegalStateException("wrapped", deadline)))
                .isTrue();
        assertThat(Dispositions.isDeadline(new IllegalStateException("other"))).isFalse();
    }

    @Test
    @DisplayName("a step-limit overrun is found as the failure and as a cause")
    void stepLimitOverrunIsFoundAsFailureAndAsCause() {
        StepLimitExceededException limit = new StepLimitExceededException(7);
        assertThat(Dispositions.stepLimit(limit)).isSameAs(limit);
        assertThat(Dispositions.stepLimit(new IllegalStateException("wrapped", limit)))
                .isSameAs(limit);
        assertThat(Dispositions.stepLimit(new IllegalStateException("other"))).isNull();
    }
}
