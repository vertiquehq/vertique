// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.plan.*;
import dev.vertique.workflow.registry.CallbackId;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies stored signal names across branch routes. */
class DeclaredSignalNamesTest {
    @Test
    @DisplayName("both reply names are stored and the branch failure route is followed")
    void bothNamesAreStoredAndTheFailureRouteIsFollowedInABranch() {
        var names = DeclaredSignalNames.of(workflow());
        assertThat(names.getJsonArray("instance").getList()).containsExactly("main-failed", "main-ok");
        assertThat(names.getJsonObject("branches").getJsonArray("fork/branch").getList())
                .containsExactly("branch-failed", "branch-ok", "later");
    }

    private static RuntimeWorkflow workflow() {
        var main = wait("main", "main-ok", new WaitSignalNode.ReplyWait("call", "main-failed", null, "done", false));
        var branch = wait(
                "branch-wait",
                "branch-ok",
                new WaitSignalNode.ReplyWait("branch-call", "branch-failed", null, "later-wait", false));
        var later = wait("later-wait", "later", null);
        var fork = new ForkNode(
                "fork", List.of(BranchStart.of("branch", "branch-wait")), "join", BranchRetryPolicy.none());
        var plan = new WorkflowPlan(
                "test",
                1,
                "",
                "java.lang.String",
                "main",
                List.of(main, fork, branch, later, new CompleteNode("done")),
                null);
        return RuntimeWorkflow.of(plan, "contract", String.class, String.class, x -> x, Map.of(), null);
    }

    private static WaitSignalNode wait(String id, String name, WaitSignalNode.ReplyWait reply) {
        return new WaitSignalNode(id, name, "java.lang.String", new CallbackId("update"), "done", null, null, reply);
    }
}
