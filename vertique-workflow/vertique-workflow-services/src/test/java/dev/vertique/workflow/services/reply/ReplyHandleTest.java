// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.workflow.services.reply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReplyHandleTest {
    @Test
    void parsesCanonicalHandleAndRefusesOversizedInputWithoutItsValue() {
        ReplyHandle handle = new ReplyHandle(
                UUID.fromString("01234567-89ab-cdef-0123-456789abcdef"),
                UUID.fromString("fedcba98-7654-3210-fedc-ba9876543210"));
        String encoded = handle.toString();
        assertThat(encoded).hasSize(73);
        assertThat(ReplyHandle.parse(encoded)).isEqualTo(handle);

        String malformed = "sensitive-marker:" + ":".repeat(100_000);
        assertThatThrownBy(() -> ReplyHandle.parse(malformed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid reply handle")
                .hasMessageNotContaining("sensitive-marker")
                .hasNoCause();
    }
}
