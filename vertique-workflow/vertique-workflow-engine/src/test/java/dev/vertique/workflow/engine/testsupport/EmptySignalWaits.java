// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.testsupport;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import dev.vertique.workflow.engine.spi.WorkflowSignalWaitRepository;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import java.util.List;

/** A wait record repository for tests that never read a record: a delete finds none, an instance holds none and every write succeeds. */
public final class EmptySignalWaits {

    private EmptySignalWaits() {}

    /**
     * A mock repository whose writes and deletes succeed and whose delete reports that no record was held.
     *
     * @return the repository
     */
    @SuppressWarnings("unchecked")
    public static WorkflowSignalWaitRepository<SqlClient> create() {
        WorkflowSignalWaitRepository<SqlClient> waits = mock(WorkflowSignalWaitRepository.class);
        lenient().when(waits.write(any(), any())).thenReturn(Future.succeededFuture());
        lenient().when(waits.delete(any(), any(), any(), any(), any())).thenReturn(Future.succeededFuture(false));
        lenient().when(waits.deleteAll(any(), any())).thenReturn(Future.succeededFuture());
        lenient().when(waits.findByInstance(any(), any())).thenReturn(Future.succeededFuture(List.of()));
        return waits;
    }
}
