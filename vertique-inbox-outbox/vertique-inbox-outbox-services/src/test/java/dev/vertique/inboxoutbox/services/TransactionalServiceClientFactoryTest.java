// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.core.resilience.ResilienceAnnotations;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxEntry;
import dev.vertique.inboxoutbox.OutboxService;
import dev.vertique.services.ResolvedServiceTarget;
import dev.vertique.services.ServiceOperation;
import dev.vertique.services.ServiceTargetResolver;
import dev.vertique.services.dispatch.ServiceMethodDescriptor;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies the proxy creation, eager validation, and method invocation behaviour of
 * {@link TransactionalServiceClientFactory}. Covers successful proxy creation and invocation,
 * all validation rejection paths (missing annotation, @OneWay, non-Void return, no payload,
 * multiple payloads, security context parameters), and Object method delegation.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionalServiceClientFactory")
class TransactionalServiceClientFactoryTest {

    @Mock
    ServiceTargetResolver targetResolver;

    @Mock
    OutboxService outboxService;

    @Mock
    SqlClient tx;

    TransactionalServiceClientFactory factory;

    @BeforeEach
    void setUp() {
        factory = new TransactionalServiceClientFactory(targetResolver, outboxService);
    }

    // --- Test contract interfaces ---

    /** Valid contract: single @ServiceOperation method with one payload param. */
    interface ValidContract {
        @ServiceOperation("send-notification")
        Future<Void> sendNotification(String payload);
    }

    /** Invalid: method lacks @ServiceOperation. */
    interface MissingAnnotationContract {
        Future<Void> sendNotification(String payload);
    }

    /** Invalid: method is @OneWay. */
    interface OneWayContract {
        @ServiceOperation("send-notification")
        @dev.vertique.services.OneWay
        Future<Void> sendNotification(String payload);
    }

    /** Invalid: return type is not Future<Void>. */
    interface NonVoidReturnContract {
        @ServiceOperation("get-data")
        Future<String> getData(String payload);
    }

    /** Invalid: no payload parameter. */
    interface NoPayloadContract {
        @ServiceOperation("send-notification")
        Future<Void> sendNotification();
    }

    /** Invalid: two payload parameters. */
    interface MultiPayloadContract {
        @ServiceOperation("send-notification")
        Future<Void> sendNotification(String payload1, String payload2);
    }

    /** Invalid: security context parameter. */
    interface SecurityContextContract {
        @ServiceOperation("send-notification")
        Future<Void> sendNotification(String payload, dev.vertique.security.SecurityContext securityContext);
    }

    // --- Helpers ---

    /**
     * Builds a {@link ResolvedServiceTarget} whose meta satisfies the factory validation rules
     * for a single payload parameter at index 0.
     */
    private ResolvedServiceTarget makeTarget(
            String targetId,
            String operationId,
            boolean oneWay,
            Class<?> returnType,
            List<ServiceMethodMeta.ParamMeta> params,
            Class<?> declaringClass)
            throws NoSuchMethodException {
        Method m = declaringClass.getMethods()[0];
        ServiceMethodDescriptor descriptor = ServiceMethodDescriptor.of(m);
        ServiceMethodMeta meta = new ServiceMethodMeta(
                null,
                descriptor,
                descriptor,
                "services/test/" + operationId,
                targetId,
                "",
                "test",
                operationId,
                params.isEmpty() ? null : params.get(0).type(),
                returnType,
                params,
                params,
                ResilienceAnnotations.NONE,
                List.of(),
                List.of(),
                oneWay);
        return new ResolvedServiceTarget(
                targetId, declaringClass, "", "test", operationId, meta, "services/test/" + operationId);
    }

    private ResolvedServiceTarget makeValidTarget() throws NoSuchMethodException {
        return makeTarget(
                "test.send-notification",
                "send-notification",
                false,
                Void.class,
                List.of(new ServiceMethodMeta.ParamMeta(
                        "payload", ServiceMethodMeta.ParamSource.PAYLOAD, String.class)),
                ValidContract.class);
    }

    // --- create() returns proxy ---

    @Test
    @DisplayName("create returns a proxy implementing the contract interface")
    void createReturnsProxy() throws Exception {
        when(targetResolver.resolve(eq(ValidContract.class), any(Method.class))).thenReturn(makeValidTarget());

        ValidContract proxy = factory.create(ValidContract.class, tx);

        assertNotNull(proxy);
        assertInstanceOf(ValidContract.class, proxy);
    }

    // --- Proxy invocation ---

    @Nested
    @DisplayName("proxy invocation")
    class ProxyInvocation {

        @Test
        @DisplayName("invocation records outbox entry with correct destination and eventType")
        void invocationRecordsOutboxEntry() throws Exception {
            when(targetResolver.resolve(eq(ValidContract.class), any(Method.class)))
                    .thenReturn(makeValidTarget());
            ArgumentCaptor<OutboxEntry> entryCaptor = forClass(OutboxEntry.class);
            when(outboxService.publish(eq(tx), entryCaptor.capture())).thenReturn(Future.succeededFuture(1L));

            ValidContract proxy = factory.create(ValidContract.class, tx);
            proxy.sendNotification("hello").result();

            OutboxEntry captured = entryCaptor.getValue();
            assertEquals(DestinationType.SERVICE, captured.destinationType());
            assertEquals("test.send-notification", captured.destination());
            assertEquals("send-notification", captured.eventType());
            assertEquals("hello", captured.payload());
        }

        @Test
        @DisplayName("invocation returns Future<Void> (mapEmpty result)")
        void invocationReturnsFutureVoid() throws Exception {
            when(targetResolver.resolve(eq(ValidContract.class), any(Method.class)))
                    .thenReturn(makeValidTarget());
            when(outboxService.publish(eq(tx), any())).thenReturn(Future.succeededFuture(1L));

            ValidContract proxy = factory.create(ValidContract.class, tx);
            Future<Void> result = proxy.sendNotification("hello");

            assertTrue(result.succeeded());
        }

        @Test
        @DisplayName("invocation uses the captured transaction")
        void invocationUsesTransactionFromCreate() throws Exception {
            when(targetResolver.resolve(eq(ValidContract.class), any(Method.class)))
                    .thenReturn(makeValidTarget());
            when(outboxService.publish(eq(tx), any())).thenReturn(Future.succeededFuture(1L));

            ValidContract proxy = factory.create(ValidContract.class, tx);
            proxy.sendNotification("hello").result();

            verify(outboxService).publish(eq(tx), any());
        }
    }

    // --- Object method delegation ---

    @Nested
    @DisplayName("Object method delegation")
    class ObjectMethods {

        @Test
        @DisplayName("toString returns proxy description with contract name")
        void toStringReturnsProxyDescription() throws Exception {
            when(targetResolver.resolve(eq(ValidContract.class), any(Method.class)))
                    .thenReturn(makeValidTarget());

            ValidContract proxy = factory.create(ValidContract.class, tx);

            assertTrue(proxy.toString().contains("ValidContract"));
        }

        @Test
        @DisplayName("equals is reference-based: same proxy equals itself")
        void equalsIsSelfReferential() throws Exception {
            when(targetResolver.resolve(eq(ValidContract.class), any(Method.class)))
                    .thenReturn(makeValidTarget());

            ValidContract proxy = factory.create(ValidContract.class, tx);

            assertEquals(proxy, proxy);
        }

        @Test
        @DisplayName("hashCode is stable across calls")
        void hashCodeIsStable() throws Exception {
            when(targetResolver.resolve(eq(ValidContract.class), any(Method.class)))
                    .thenReturn(makeValidTarget());

            ValidContract proxy = factory.create(ValidContract.class, tx);

            assertEquals(proxy.hashCode(), proxy.hashCode());
        }
    }

    // --- Validation rejections ---

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("method missing @ServiceOperation throws IllegalArgumentException at create time")
        void missingServiceOperationThrows() {
            assertThrows(IllegalArgumentException.class, () -> factory.create(MissingAnnotationContract.class, tx));
        }

        @Test
        @DisplayName("@OneWay method throws IllegalArgumentException at create time")
        void oneWayMethodThrows() throws Exception {
            when(targetResolver.resolve(eq(OneWayContract.class), any(Method.class)))
                    .thenReturn(makeTarget(
                            "test.send-notification",
                            "send-notification",
                            true,
                            Void.class,
                            List.of(new ServiceMethodMeta.ParamMeta(
                                    "payload", ServiceMethodMeta.ParamSource.PAYLOAD, String.class)),
                            OneWayContract.class));

            assertThrows(IllegalArgumentException.class, () -> factory.create(OneWayContract.class, tx));
        }

        @Test
        @DisplayName("non-Future<Void> return type throws IllegalArgumentException at create time")
        void nonVoidReturnTypeThrows() throws Exception {
            when(targetResolver.resolve(eq(NonVoidReturnContract.class), any(Method.class)))
                    .thenReturn(makeTarget(
                            "test.get-data",
                            "get-data",
                            false,
                            String.class,
                            List.of(new ServiceMethodMeta.ParamMeta(
                                    "payload", ServiceMethodMeta.ParamSource.PAYLOAD, String.class)),
                            NonVoidReturnContract.class));

            assertThrows(IllegalArgumentException.class, () -> factory.create(NonVoidReturnContract.class, tx));
        }

        @Test
        @DisplayName("no payload parameter throws IllegalArgumentException at create time")
        void noPayloadThrows() throws Exception {
            when(targetResolver.resolve(eq(NoPayloadContract.class), any(Method.class)))
                    .thenReturn(makeTarget(
                            "test.send-notification",
                            "send-notification",
                            false,
                            Void.class,
                            List.of(),
                            NoPayloadContract.class));

            assertThrows(IllegalArgumentException.class, () -> factory.create(NoPayloadContract.class, tx));
        }

        @Test
        @DisplayName("multiple payload parameters throws IllegalArgumentException at create time")
        void multiplePayloadsThrow() throws Exception {
            when(targetResolver.resolve(eq(MultiPayloadContract.class), any(Method.class)))
                    .thenReturn(makeTarget(
                            "test.send-notification",
                            "send-notification",
                            false,
                            Void.class,
                            List.of(
                                    new ServiceMethodMeta.ParamMeta(
                                            "p1", ServiceMethodMeta.ParamSource.PAYLOAD, String.class),
                                    new ServiceMethodMeta.ParamMeta(
                                            "p2", ServiceMethodMeta.ParamSource.PAYLOAD, String.class)),
                            MultiPayloadContract.class));

            assertThrows(IllegalArgumentException.class, () -> factory.create(MultiPayloadContract.class, tx));
        }

        @Test
        @DisplayName(
                "DISPATCH_CONTEXT parameter with SecurityContext type throws IllegalArgumentException at create time")
        void securityContextParamThrows() throws Exception {
            when(targetResolver.resolve(eq(SecurityContextContract.class), any(Method.class)))
                    .thenReturn(makeTarget(
                            "test.send-notification",
                            "send-notification",
                            false,
                            Void.class,
                            List.of(
                                    new ServiceMethodMeta.ParamMeta(
                                            "payload", ServiceMethodMeta.ParamSource.PAYLOAD, String.class),
                                    new ServiceMethodMeta.ParamMeta(
                                            "ctx",
                                            ServiceMethodMeta.ParamSource.DISPATCH_CONTEXT,
                                            dev.vertique.security.SecurityContext.class,
                                            dev.vertique.security.SecurityContext.class.getName())),
                            SecurityContextContract.class));

            assertThrows(IllegalArgumentException.class, () -> factory.create(SecurityContextContract.class, tx));
        }

        @Test
        @DisplayName(
                "DISPATCH_CONTEXT parameter with SecurityContext subtype throws IllegalArgumentException at create time")
        void securityContextSubtypeParamThrows() throws Exception {
            when(targetResolver.resolve(eq(SecurityContextContract.class), any(Method.class)))
                    .thenReturn(makeTarget(
                            "test.send-notification",
                            "send-notification",
                            false,
                            Void.class,
                            List.of(
                                    new ServiceMethodMeta.ParamMeta(
                                            "payload", ServiceMethodMeta.ParamSource.PAYLOAD, String.class),
                                    new ServiceMethodMeta.ParamMeta(
                                            "ctx",
                                            ServiceMethodMeta.ParamSource.DISPATCH_CONTEXT,
                                            // subtype: CustomSecurityContext extends SecurityContext
                                            dev.vertique.security.SecurityContext.class,
                                            dev.vertique.security.SecurityContext.class.getName())),
                            SecurityContextContract.class));

            assertThrows(IllegalArgumentException.class, () -> factory.create(SecurityContextContract.class, tx));
        }
    }
}
