// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.exception.MalformedDurableMetadataException;
import dev.vertique.inboxoutbox.InboxResult;
import dev.vertique.inboxoutbox.InboxService;
import dev.vertique.services.ServiceContractRegistry.ContractEntry;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import dev.vertique.workflow.ops.TransactionalWorkflowOperations;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import jakarta.inject.Provider;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link WorkflowSignalContributor}.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>{@link WorkflowSignalContributor#contribute(JsonObject)} returns a single contract entry
 *       for the {@code workflow.signals.post} operation with the expected type, name, operation id,
 *       and payload type.</li>
 *   <li>{@link WorkflowSignalContributor#handleSignal(WorkflowSignalRequest)} composes
 *       {@code pool.withTransaction}, {@code inboxService.processOnce}, and
 *       {@code txOps.signal} correctly within the same transaction.</li>
 *   <li>When {@code processOnce} returns {@link InboxResult.Duplicate}, the handler returns
 *       success without calling {@code txOps.signal} again.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class WorkflowSignalContributorTest {

    @Mock
    private TransactionalWorkflowOperations<SqlClient> txOps;

    @Mock
    private InboxService inboxService;

    @Mock
    private Pool pool;

    @Mock
    private SqlConnection tx;

    private WorkflowSignalContributor contributor;

    @BeforeEach
    void setUp() {
        // Wrap the mock in a Provider to match the updated constructor signature.
        // The Provider is resolved at dispatch time, not at construction — wrapping the mock
        // in a simple lambda ensures the test mock is returned on every .get() call.
        Provider<TransactionalWorkflowOperations<SqlClient>> txOpsProvider = () -> txOps;
        contributor = new WorkflowSignalContributor(txOpsProvider, inboxService, pool);
    }

    // --- contribute() ---

    @Nested
    @DisplayName("contribute()")
    class Contribute {

        @Test
        @DisplayName("returns one ContractEntry for workflow.signals.post")
        void contribute_returnsOneEntry() {
            List<ContractEntry<?>> entries = contributor.contribute(new JsonObject());
            assertThat(entries).hasSize(1);
        }

        @Test
        @DisplayName("contract entry has namespace=workflow, name=signals, operation=post")
        void contribute_entryHasCorrectNamespaceNameOperation() {
            ContractEntry<?> entry = contributor.contribute(new JsonObject()).get(0);

            assertThat(entry.namespace()).isEqualTo("workflow");
            assertThat(entry.name()).isEqualTo("signals");
            assertThat(entry.operations()).containsKey("post");
        }

        @Test
        @DisplayName("the post operation has payload type WorkflowSignalRequest")
        void contribute_postOperationHasCorrectPayloadType() {
            ContractEntry<?> entry = contributor.contribute(new JsonObject()).get(0);

            ServiceMethodMeta meta = entry.operations().get("post");
            assertThat(meta).isNotNull();
            assertThat(meta.payloadType()).isEqualTo(WorkflowSignalRequest.class);
        }

        @Test
        @DisplayName("the post operation has return type Void")
        void contribute_postOperationHasVoidReturnType() {
            ContractEntry<?> entry = contributor.contribute(new JsonObject()).get(0);

            ServiceMethodMeta meta = entry.operations().get("post");
            assertThat(meta.returnType()).isEqualTo(Void.class);
        }

        @Test
        @DisplayName("contract key class is WorkflowSignalEndpoint")
        void contribute_contractClassIsWorkflowSignalEndpoint() {
            ContractEntry<?> entry = contributor.contribute(new JsonObject()).get(0);

            assertThat(entry.contract()).isEqualTo(WorkflowSignalEndpoint.class);
        }
    }

    // --- handleSignal() ---

    @Nested
    @DisplayName("handleSignal()")
    class HandleSignal {

        @Test
        @DisplayName("new message: calls pool.withTransaction, processOnce, and txOps.signal")
        @SuppressWarnings("unchecked")
        void handleSignal_newMessage_callsSignal() {
            UUID instanceId = UUID.randomUUID();
            WorkflowInstanceId workflowId = new WorkflowInstanceId(instanceId);
            WorkflowSignalRequest req =
                    WorkflowSignalRequest.instance(workflowId, "payment-confirmed", "payload-value", "dedup-key-1");

            // pool.withTransaction calls the function with tx (SqlConnection)
            doAnswer(invocation -> {
                        Function<SqlConnection, Future<Void>> fn = invocation.getArgument(0);
                        return fn.apply(tx);
                    })
                    .when(pool)
                    .withTransaction(any());

            // inboxService.processOnce calls the supplier and returns Processed
            when(inboxService.processOnce(any(), any(), any(), any())).thenAnswer(invocation -> {
                java.util.function.Supplier<Future<Void>> work = invocation.getArgument(3);
                Future<Void> workResult = work.get();
                return workResult.map(v -> new InboxResult.Processed<>(v));
            });

            when(txOps.signal(
                            eq(workflowId),
                            eq("payment-confirmed"),
                            eq("payload-value"),
                            eq("dedup-key-1"),
                            eq((String) null),
                            eq((String) null),
                            eq(tx)))
                    .thenReturn(Future.succeededFuture());

            Future<Void> result = contributor.handleSignal(req);

            assertThat(result.succeeded()).isTrue();
            verify(txOps).signal(workflowId, "payment-confirmed", "payload-value", "dedup-key-1", null, null, tx);
        }

        @Test
        @DisplayName("processOnce uses instance-scoped messageId (workflowId:dedupKey) and 'workflow-signals' source")
        @SuppressWarnings("unchecked")
        void handleSignal_processOnceUsesCorrectArgs() {
            UUID instanceUuid = UUID.randomUUID();
            WorkflowInstanceId workflowId = new WorkflowInstanceId(instanceUuid);
            WorkflowSignalRequest req =
                    WorkflowSignalRequest.instance(workflowId, "order-shipped", null, "my-dedup-key");

            doAnswer(invocation -> {
                        Function<SqlConnection, Future<Void>> fn = invocation.getArgument(0);
                        return fn.apply(tx);
                    })
                    .when(pool)
                    .withTransaction(any());

            when(inboxService.processOnce(any(), any(), any(), any())).thenAnswer(invocation -> {
                java.util.function.Supplier<Future<Void>> work = invocation.getArgument(3);
                Future<Void> workResult = work.get();
                return workResult.map(v -> new InboxResult.Processed<>(v));
            });

            when(txOps.signal(any(), any(), any(), any(), any(), any(), any())).thenReturn(Future.succeededFuture());

            contributor.handleSignal(req);

            ArgumentCaptor<String> messageIdCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> sourceCaptor = ArgumentCaptor.forClass(String.class);
            verify(inboxService).processOnce(messageIdCaptor.capture(), sourceCaptor.capture(), eq(tx), any());

            // messageId is instance-scoped: workflowId.value() + ":" + dedupKey
            assertThat(messageIdCaptor.getValue()).isEqualTo(instanceUuid + ":my-dedup-key");
            assertThat(sourceCaptor.getValue()).isEqualTo("workflow-signals");
        }

        @Test
        @DisplayName("duplicate message: processOnce returns Duplicate, txOps.signal not called")
        @SuppressWarnings("unchecked")
        void handleSignal_duplicate_returnsSuccessWithoutCallingSignal() {
            WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
            WorkflowSignalRequest req = WorkflowSignalRequest.instance(workflowId, "order-shipped", null, "dup-key");

            doAnswer(invocation -> {
                        Function<SqlConnection, Future<Void>> fn = invocation.getArgument(0);
                        return fn.apply(tx);
                    })
                    .when(pool)
                    .withTransaction(any());

            // processOnce returns Duplicate without invoking work
            when(inboxService.processOnce(any(), any(), any(), any()))
                    .thenReturn(Future.succeededFuture(new InboxResult.Duplicate<>()));

            Future<Void> result = contributor.handleSignal(req);

            assertThat(result.succeeded()).isTrue();
            verify(txOps, never()).signal(any(), any(), any(), any(), any());
        }
    }

    // --- handleSignal(): carrier decode at the async boundary (review round-1 FIX 1b/1c) ---

    @Nested
    @DisplayName("handleSignal(): explicit carrier decode boundary")
    class HandleSignalCarrierDecode {

        @Test
        @DisplayName("malformed carrier: decode failure inside processOnce propagates as a failed Future")
        @SuppressWarnings("unchecked")
        void handleSignal_malformedCarrier_propagatesAsFailedFuture() {
            WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
            // context section present but not a JSON object -> DurableMetadata.fromCarrier throws
            JsonObject malformedCarrier = new JsonObject().put(DurableMetadata.CONTEXT_KEY, "not-an-object");
            WorkflowSignalRequest req =
                    WorkflowSignalRequest.instance(workflowId, "order-shipped", null, "poison-key", malformedCarrier);

            doAnswer(invocation -> {
                        Function<SqlConnection, Future<Void>> fn = invocation.getArgument(0);
                        return fn.apply(tx);
                    })
                    .when(pool)
                    .withTransaction(any());

            // Mirrors DefaultInboxService.processOnce's real shape: work.get() is invoked from
            // inside a Future.compose() handler, so Vert.x's own compose contract (Transformation
            // .complete wraps the mapper invocation in a try/catch) converts a synchronous throw
            // from the decode into a failed Future — no manual try/catch needed here.
            when(inboxService.processOnce(any(), any(), any(), any())).thenAnswer(invocation -> {
                java.util.function.Supplier<Future<Void>> work = invocation.getArgument(3);
                return Future.<Void>succeededFuture().compose(v -> work.get()).map(v -> new InboxResult.Processed<>(v));
            });

            Future<Void> result = contributor.handleSignal(req);

            assertThat(result.failed()).isTrue();
            assertThat(result.cause()).isInstanceOf(MalformedDurableMetadataException.class);
            verify(txOps, never()).signal(any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("malformed carrier redelivery: once processOnce reports Duplicate, the carrier is never re-decoded"
                + " (poison-loop regression proof)")
        @SuppressWarnings("unchecked")
        void handleSignal_malformedCarrierRedelivery_idempotentlySkipped() {
            WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
            JsonObject malformedCarrier = new JsonObject().put(DurableMetadata.CONTEXT_KEY, "not-an-object");
            WorkflowSignalRequest req =
                    WorkflowSignalRequest.instance(workflowId, "order-shipped", null, "poison-key", malformedCarrier);

            doAnswer(invocation -> {
                        Function<SqlConnection, Future<Void>> fn = invocation.getArgument(0);
                        return fn.apply(tx);
                    })
                    .when(pool)
                    .withTransaction(any());

            // Simulates a redelivery of the same message after the first delivery's inbox row
            // committed: processOnce reports Duplicate WITHOUT invoking the work supplier, so the
            // carrier is never touched a second time.
            when(inboxService.processOnce(any(), any(), any(), any()))
                    .thenReturn(Future.succeededFuture(new InboxResult.Duplicate<>()));

            Future<Void> result = contributor.handleSignal(req);

            assertThat(result.succeeded()).isTrue();
            verify(txOps, never()).signal(any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("well-formed carrier: decodes and delivers via the 8-arg metadata-aware signal overload"
                + " (over-strictness guard)")
        @SuppressWarnings("unchecked")
        void handleSignal_wellFormedCarrier_decodesAndSignals() {
            WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
            DurableMetadata metadata = DurableMetadata.of("correlation", new JsonObject().put("requestId", "r1"));
            WorkflowSignalRequest req = WorkflowSignalRequest.instance(
                    workflowId, "order-shipped", "payload-value", "dedup-key-1", metadata.toCarrier());

            doAnswer(invocation -> {
                        Function<SqlConnection, Future<Void>> fn = invocation.getArgument(0);
                        return fn.apply(tx);
                    })
                    .when(pool)
                    .withTransaction(any());

            when(inboxService.processOnce(any(), any(), any(), any())).thenAnswer(invocation -> {
                java.util.function.Supplier<Future<Void>> work = invocation.getArgument(3);
                Future<Void> workResult = work.get();
                return workResult.map(v -> new InboxResult.Processed<>(v));
            });

            when(txOps.signal(
                            eq(workflowId),
                            eq("order-shipped"),
                            eq("payload-value"),
                            eq("dedup-key-1"),
                            eq((String) null),
                            eq((String) null),
                            eq(metadata),
                            eq(tx)))
                    .thenReturn(Future.succeededFuture());

            Future<Void> result = contributor.handleSignal(req);

            assertThat(result.succeeded()).isTrue();
            verify(txOps).signal(workflowId, "order-shipped", "payload-value", "dedup-key-1", null, null, metadata, tx);
        }
    }
}
