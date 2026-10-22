// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.exception.QueryTimeoutException;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.workflow.exception.WorkflowPersistenceException;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowDefinitionDocument;
import io.vertx.core.Future;
import io.vertx.core.VertxException;
import io.vertx.sqlclient.Pool;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies PostgreSQL workflow-definition transaction failure mapping at the public store boundary. */
class PgWorkflowDefinitionRegistryStoreFailureMappingTest {

    private static final DefinitionKey KEY = new DefinitionKey(null, "mapping-failure", 1);

    @Test
    @DisplayName("Vert.x timeout maps to a retryable workflow persistence failure")
    void mapsVertxTimeoutToRetryablePersistenceFailure() {
        VertxException timeout = new VertxException("Timeout");
        PgWorkflowDefinitionRegistryStore store = storeFailingWith(timeout);

        Future<Optional<WorkflowDefinitionDocument>> result = store.findDocument(KEY);

        assertThat(result.failed()).isTrue();
        assertThat(result.cause()).isInstanceOf(WorkflowPersistenceException.class);
        WorkflowPersistenceException persistenceFailure = (WorkflowPersistenceException) result.cause();
        assertThat(persistenceFailure.retryable()).isTrue();
        assertThat(persistenceFailure.getCause()).isInstanceOf(QueryTimeoutException.class);
        assertThat(persistenceFailure.getCause().getCause()).isSameAs(timeout);
    }

    @Test
    @DisplayName("unknown transaction failures pass through unchanged")
    void preservesUnknownTransactionFailure() {
        IllegalStateException failure = new IllegalStateException("unexpected transaction failure");
        PgWorkflowDefinitionRegistryStore store = storeFailingWith(failure);

        Future<Optional<WorkflowDefinitionDocument>> result = store.findDocument(KEY);

        assertThat(result.failed()).isTrue();
        assertThat(result.cause()).isSameAs(failure);
    }

    private static PgWorkflowDefinitionRegistryStore storeFailingWith(Throwable failure) {
        Pool pool = (Pool) Proxy.newProxyInstance(
                Pool.class.getClassLoader(), new Class<?>[] {Pool.class}, new FailedTransactionPool(failure));
        return new PgWorkflowDefinitionRegistryStore(pool, new PgDbExceptionMapper(), Optional.empty());
    }

    private static final class FailedTransactionPool implements InvocationHandler {

        private final Throwable failure;

        private FailedTransactionPool(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            if (method.getName().equals("withTransaction") && arguments != null && arguments.length == 1) {
                return Future.failedFuture(failure);
            }
            throw new AssertionError("Unexpected Pool call: " + method);
        }
    }
}
