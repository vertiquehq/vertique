// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.workflow.definition.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.definition.schema.ServiceStep;
import dev.vertique.workflow.definition.schema.WorkflowDefinitionDocument;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentTypeReferencesTest {
    @Test
    void replyPayloadTypeIsCollected() {
        var reply = new ServiceStep.ReplyBlock(
                "catalog.Reply", "reducer", "done", new ServiceStep.FailureBlock(null, "done"), null, false);
        var doc = new WorkflowDefinitionDocument(
                "wf",
                1,
                "state",
                "contract",
                "start",
                "init",
                null,
                "svc",
                List.of(new ServiceStep("svc", "target", "mapper", null, null, reply)));
        assertThat(DocumentTypeReferences.of(doc))
                .contains(new DocumentTypeReferences.Reference(false, "steps[].reply.payloadType", "catalog.Reply"));
    }
}
