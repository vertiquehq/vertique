// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link CancelledHistoryPayload}'s {@code commandCorrelationId} stitching field (PRD
 * FR-WF-CTX-050/051, AC-5, Contract Appendix C5).
 */
class CancelledHistoryPayloadTest {

    @Test
    @DisplayName("valid construction succeeds and commandCorrelationId is accessible")
    void validConstruction() {
        CancelledHistoryPayload payload = new CancelledHistoryPayload("because", "corr-E");

        assertThat(payload.reason()).isEqualTo("because");
        assertThat(payload.commandCorrelationId()).isEqualTo("corr-E");
    }

    @Test
    @DisplayName("commandCorrelationId is nullable")
    void nullCommandCorrelationIdAllowed() {
        CancelledHistoryPayload payload = new CancelledHistoryPayload("because", null);

        assertThat(payload.commandCorrelationId()).isNull();
    }
}
