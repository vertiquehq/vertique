// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class McpRawEvidenceSnapshotTest {

    @Test
    @DisplayName("response evidence is unaffected by later writes to the array it was built from")
    void shouldSnapshotResponseBodyOnConstruction() {
        byte[] source = {1, 2, 3};

        McpResponseEvidence evidence = new McpResponseEvidence(source, Map.of());
        source[0] = 99;

        assertThat(evidence.body()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("response evidence hands every reader its own copy")
    void shouldReturnFreshResponseBodyOnEveryRead() {
        McpResponseEvidence evidence = new McpResponseEvidence(new byte[] {1, 2, 3}, Map.of());

        evidence.body()[0] = 99;

        assertThat(evidence.body()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("admission evidence is unaffected by later writes to the array it was built from")
    void shouldSnapshotAdmissionBodyOnConstruction() {
        byte[] source = {1, 2, 3};

        McpRequestAdmissionEvidence evidence = new McpRequestAdmissionEvidence(source, Map.of(), null, null, null);
        source[0] = 99;

        assertThat(evidence.body()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("admission evidence hands every reader its own copy")
    void shouldReturnFreshAdmissionBodyOnEveryRead() {
        McpRequestAdmissionEvidence evidence =
                new McpRequestAdmissionEvidence(new byte[] {1, 2, 3}, Map.of(), null, null, null);

        evidence.body()[0] = 99;

        assertThat(evidence.body()).containsExactly(1, 2, 3);
    }
}
