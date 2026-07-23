// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link WorkflowIdempotencyConflictException}: constructor parameters populate
 * accessors, and {@link Exception#getMessage()} includes all four fields.
 */
class WorkflowIdempotencyConflictExceptionTest {

    private static final String KIND = "task-complete";
    private static final String KEY = "idem-key-001";
    private static final String EXISTING = "fingerprint-aaa";
    private static final String INCOMING = "fingerprint-bbb";

    // --- Constructor and accessors ---

    @Nested
    @DisplayName("constructor and accessors")
    class ConstructorAndAccessors {

        @Test
        @DisplayName("kind() returns the kind supplied to the constructor")
        void kindAccessor() {
            WorkflowIdempotencyConflictException ex =
                    new WorkflowIdempotencyConflictException(KIND, KEY, EXISTING, INCOMING);
            assertThat(ex.kind()).isEqualTo(KIND);
        }

        @Test
        @DisplayName("idempotencyKey() returns the key supplied to the constructor")
        void idempotencyKeyAccessor() {
            WorkflowIdempotencyConflictException ex =
                    new WorkflowIdempotencyConflictException(KIND, KEY, EXISTING, INCOMING);
            assertThat(ex.idempotencyKey()).isEqualTo(KEY);
        }

        @Test
        @DisplayName("existingFingerprint() returns the existing fingerprint")
        void existingFingerprintAccessor() {
            WorkflowIdempotencyConflictException ex =
                    new WorkflowIdempotencyConflictException(KIND, KEY, EXISTING, INCOMING);
            assertThat(ex.existingFingerprint()).isEqualTo(EXISTING);
        }

        @Test
        @DisplayName("incomingFingerprint() returns the incoming fingerprint")
        void incomingFingerprintAccessor() {
            WorkflowIdempotencyConflictException ex =
                    new WorkflowIdempotencyConflictException(KIND, KEY, EXISTING, INCOMING);
            assertThat(ex.incomingFingerprint()).isEqualTo(INCOMING);
        }
    }

    // --- getMessage / toString includes all fields ---

    @Nested
    @DisplayName("getMessage includes all four fields")
    class MessageContent {

        @Test
        @DisplayName("getMessage() contains kind")
        void messageContainsKind() {
            WorkflowIdempotencyConflictException ex =
                    new WorkflowIdempotencyConflictException(KIND, KEY, EXISTING, INCOMING);
            assertThat(ex.getMessage()).contains(KIND);
        }

        @Test
        @DisplayName("getMessage() contains idempotencyKey")
        void messageContainsKey() {
            WorkflowIdempotencyConflictException ex =
                    new WorkflowIdempotencyConflictException(KIND, KEY, EXISTING, INCOMING);
            assertThat(ex.getMessage()).contains(KEY);
        }

        @Test
        @DisplayName("getMessage() contains existingFingerprint")
        void messageContainsExistingFingerprint() {
            WorkflowIdempotencyConflictException ex =
                    new WorkflowIdempotencyConflictException(KIND, KEY, EXISTING, INCOMING);
            assertThat(ex.getMessage()).contains(EXISTING);
        }

        @Test
        @DisplayName("getMessage() contains incomingFingerprint")
        void messageContainsIncomingFingerprint() {
            WorkflowIdempotencyConflictException ex =
                    new WorkflowIdempotencyConflictException(KIND, KEY, EXISTING, INCOMING);
            assertThat(ex.getMessage()).contains(INCOMING);
        }

        @Test
        @DisplayName("toString() includes all four values")
        void toStringContainsAllFields() {
            WorkflowIdempotencyConflictException ex =
                    new WorkflowIdempotencyConflictException(KIND, KEY, EXISTING, INCOMING);
            String str = ex.toString();
            assertThat(str).contains(KIND).contains(KEY).contains(EXISTING).contains(INCOMING);
        }
    }

    // --- Subtype check ---

    @Test
    @DisplayName("is a subtype of WorkflowConflictException")
    void isSubtypeOfWorkflowConflictException() {
        WorkflowIdempotencyConflictException ex =
                new WorkflowIdempotencyConflictException(KIND, KEY, EXISTING, INCOMING);
        assertThat(ex).isInstanceOf(WorkflowConflictException.class);
    }
}
