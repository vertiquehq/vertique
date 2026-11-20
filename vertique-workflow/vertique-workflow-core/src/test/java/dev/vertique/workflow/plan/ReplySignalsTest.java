// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies generated reply names and the reserved namespace. */
class ReplySignalsTest {
    @Test
    @DisplayName("reply names come from the step and reserve the namespace")
    void namesComeFromTheStepIdAndThePrefixIsReserved() {
        assertThat(ReplySignals.replyName("reserve")).isEqualTo("vertique.reply.ok.reserve");
        assertThat(ReplySignals.failureName("reserve")).isEqualTo("vertique.reply.failed.reserve");
        assertThat(ReplySignals.waitStepId("reserve")).isEqualTo("reserve.reply");
        assertThat(ReplySignals.failureNameFor("vertique.reply.ok.reserve")).isEqualTo("vertique.reply.failed.reserve");
        assertThat(ReplySignals.replyNameFor("vertique.reply.failed.reserve")).isEqualTo("vertique.reply.ok.reserve");
        Map.of("vertique.reply.ok.reserve", true, "vertique.reply.other", true, "vertique.replyish", false)
                .forEach((name, reserved) ->
                        assertThat(ReplySignals.isReserved(name)).isEqualTo(reserved));
    }
}
