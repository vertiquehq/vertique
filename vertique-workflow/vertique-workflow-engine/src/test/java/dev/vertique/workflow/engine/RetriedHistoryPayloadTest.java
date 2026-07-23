// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link RetriedHistoryPayload}'s {@code commandCorrelationId} stitching field (PRD
 * FR-WF-CTX-050/051, AC-5, Contract Appendix C5).
 */
class RetriedHistoryPayloadTest {

    @Test
    @DisplayName("valid construction succeeds and commandCorrelationId is accessible")
    void validConstruction() {
        RetriedHistoryPayload payload = new RetriedHistoryPayload("FAILED", "corr-E");

        assertThat(payload.previousStatus()).isEqualTo("FAILED");
        assertThat(payload.commandCorrelationId()).isEqualTo("corr-E");
    }

    @Test
    @DisplayName("commandCorrelationId is nullable")
    void nullCommandCorrelationIdAllowed() {
        RetriedHistoryPayload payload = new RetriedHistoryPayload("FAILED", null);

        assertThat(payload.commandCorrelationId()).isNull();
    }
}
