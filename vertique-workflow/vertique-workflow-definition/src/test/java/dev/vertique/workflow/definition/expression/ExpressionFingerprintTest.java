// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.expression;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link ExpressionFingerprint}: SHA-256 hex output, stability across invocations, and
 * distinctness for semantically different canonical forms.
 */
class ExpressionFingerprintTest {

    @Test
    @DisplayName("fingerprint returns a 64-character lowercase hex string (SHA-256)")
    void fingerprintReturnsSha256HexString() {
        CompiledExpression expr = new CompiledExpression("state.x > 5", ExpressionType.BOOLEAN, new Object());

        String fp = ExpressionFingerprint.of(expr);

        assertThat(fp).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("same canonical form produces the same fingerprint every time (determinism)")
    void sameCanonicialFormProducesSameFingerprint() {
        Object handle = new Object();
        CompiledExpression a = new CompiledExpression("state.x > 5", ExpressionType.BOOLEAN, handle);
        CompiledExpression b = new CompiledExpression("state.x > 5", ExpressionType.BOOLEAN, handle);

        assertThat(ExpressionFingerprint.of(a)).isEqualTo(ExpressionFingerprint.of(b));
    }

    @Test
    @DisplayName("different canonical forms produce different fingerprints")
    void differentCanonicialFormProducesDifferentFingerprint() {
        CompiledExpression a = new CompiledExpression("state.x > 5", ExpressionType.BOOLEAN, new Object());
        CompiledExpression b = new CompiledExpression("state.x > 10", ExpressionType.BOOLEAN, new Object());

        assertThat(ExpressionFingerprint.of(a)).isNotEqualTo(ExpressionFingerprint.of(b));
    }

    @Test
    @DisplayName("fingerprint delegates to canonicalForm, not to handle object")
    void fingerprintUsesCanonicalFormNotHandle() {
        String canonicalForm = "state.status == 'approved'";
        // Two different handle objects with the same canonicalForm must yield the same fingerprint.
        CompiledExpression a = new CompiledExpression(canonicalForm, ExpressionType.STRING, new Object());
        CompiledExpression b = new CompiledExpression(canonicalForm, ExpressionType.BOOLEAN, new Object());

        assertThat(ExpressionFingerprint.of(a)).isEqualTo(ExpressionFingerprint.of(b));
    }
}
