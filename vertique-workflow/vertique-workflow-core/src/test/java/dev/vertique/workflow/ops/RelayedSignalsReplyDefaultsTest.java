// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import io.vertx.core.Future;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RelayedSignalsReplyDefaultsTest {
    @Test
    void replyMethodsFailTheFutureWithUnsupportedOperation() {
        RelayedSignals<Object> signals = new RelayedSignals<>() {
            @Override
            public Future<Void> signal(
                    WorkflowInstanceId id,
                    String name,
                    Object payload,
                    String key,
                    String fork,
                    String branch,
                    Object tx) {
                return Future.succeededFuture();
            }

            @Override
            public Future<Void> signal(
                    WorkflowInstanceId id,
                    String name,
                    Object payload,
                    String key,
                    String fork,
                    String branch,
                    DurableMetadata metadata,
                    Object tx) {
                return Future.succeededFuture();
            }
        };
        var id = new WorkflowInstanceId(UUID.randomUUID());
        var waitId = UUID.randomUUID();
        var provenance = new SignalProvenance("outbox", "reply", java.util.Map.of());
        var signal = assertDoesNotThrow(() -> signals.signalWait(id, waitId, "value", provenance, new Object()));
        var open = assertDoesNotThrow(() -> signals.waitOpen(id, waitId, new Object()));
        assertThat(signal.failed()).isTrue();
        assertThat(signal.cause()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(open.failed()).isTrue();
        assertThat(open.cause()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void replyCorrelationRequiresAWaitId() {
        assertThatThrownBy(() -> new WorkflowSideEffectIntent.Correlation.Reply(null, false))
                .isInstanceOf(NullPointerException.class);
    }
}
