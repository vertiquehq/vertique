// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.workflow.services.reply;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.Multibinds;
import dev.vertique.context.InboundExecutionContextScope;
import dev.vertique.inboxoutbox.OutboxOutcomeConsumer;
import dev.vertique.workflow.ops.RelayedSignals;
import dev.vertique.workflow.ops.ReplyOutcomeObserver;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import java.util.Set;

/** Binds the in-process outbox reply consumer. */
@Module
public abstract class WorkflowReplyModule {
    @Multibinds
    abstract Set<ReplyOutcomeObserver> replyObservers();

    @Provides
    @Singleton
    static OutboxOutcomeConsumer consumer(
            Provider<RelayedSignals<SqlClient>> signals,
            Pool pool,
            Vertx vertx,
            InboundExecutionContextScope scope,
            Set<ReplyOutcomeObserver> observers) {
        return new WorkflowReplyConsumer(signals, pool, vertx, scope, observers);
    }
}
