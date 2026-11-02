// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.workflow.services.reply;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.Multibinds;
import dev.vertique.context.InboundExecutionContextScope;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.inboxoutbox.OutboxOutcomeConsumer;
import dev.vertique.inboxoutbox.OutboxReplies;
import dev.vertique.workflow.config.WorkflowRecordingConfig;
import dev.vertique.workflow.ops.RelayedSignals;
import dev.vertique.workflow.ops.ReplyOutcomeObserver;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import java.util.Optional;
import java.util.Set;

/** Binds the in-process outbox reply consumer. */
@Module
public abstract class WorkflowReplyModule {
    @Multibinds
    abstract Set<ReplyOutcomeObserver> replyObservers();

    @Provides
    @Singleton
    static WorkflowReplyConsumer consumer(
            Provider<RelayedSignals<SqlClient>> signals,
            Pool pool,
            Vertx vertx,
            InboundExecutionContextScope scope,
            Set<ReplyOutcomeObserver> observers,
            ContextHolder contextHolder,
            Optional<WorkflowRecordingConfig> recording) {
        return new WorkflowReplyConsumer(
                signals,
                pool,
                vertx,
                scope,
                observers,
                contextHolder,
                recording.orElseGet(WorkflowRecordingConfig::defaults));
    }

    @Provides
    static OutboxOutcomeConsumer outcomeConsumer(WorkflowReplyConsumer consumer) {
        return consumer;
    }

    @Provides
    static OutboxReplies outboxReplies(WorkflowReplyConsumer consumer) {
        return consumer;
    }
}
