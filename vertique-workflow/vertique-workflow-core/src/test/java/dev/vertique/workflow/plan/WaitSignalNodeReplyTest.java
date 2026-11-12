// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.registry.CallbackId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies the names accepted by plain and reply waits. */
class WaitSignalNodeReplyTest {
    @Test
    @DisplayName("a reply wait accepts its signal followed by its failure signal")
    void acceptedNamesAreTheSignalThenTheFailureName() {
        WaitSignalNode reply = replyWait();
        assertThat(reply.acceptedSignalNames()).containsExactly("ok", "failed");
        for (WaitSignalNode plain : new WaitSignalNode[] {plainSix(), plainSeven()}) {
            assertThat(plain.acceptedSignalNames()).containsExactly("ok");
            assertThat(plain.reply()).isNull();
        }
    }

    private static WaitSignalNode replyWait() {
        return new WaitSignalNode(
                "wait",
                "ok",
                "java.lang.String",
                new CallbackId("update"),
                "done",
                null,
                null,
                new WaitSignalNode.ReplyWait("call", "failed", null, "done", false));
    }

    private static WaitSignalNode plainSix() {
        return new WaitSignalNode("wait", "ok", "java.lang.String", new CallbackId("update"), "done", null);
    }

    private static WaitSignalNode plainSeven() {
        return new WaitSignalNode("wait", "ok", "java.lang.String", new CallbackId("update"), "done", null, null);
    }
}
