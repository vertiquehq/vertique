// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link Diagnostics}.
 *
 * <p>The static formatter methods are externally-stable contracts for CG-002+ — their exact
 * string outputs are tested here and must not change without a deliberate API decision.
 */
@ExtendWith(MockitoExtension.class)
class DiagnosticsTest {

    @Mock
    private Messager messager;

    private Diagnostics diagnostics;

    @BeforeEach
    void setUp() {
        diagnostics = new Diagnostics(messager);
    }

    // --- Instance method tests ---

    @Test
    void error_invokesMessagerWithErrorKind() {
        Element element = mock(Element.class);
        diagnostics.error(element, "Something went wrong: %s", "details");
        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR), eq("Something went wrong: details"), eq(element));
    }

    @Test
    void warning_invokesMessagerWithWarningKind() {
        Element element = mock(Element.class);
        diagnostics.warning(element, "Watch out: %s", "warning-details");
        verify(messager).printMessage(eq(Diagnostic.Kind.WARNING), eq("Watch out: warning-details"), eq(element));
    }

    @Test
    void note_invokesMessagerWithNoteKind() {
        Element element = mock(Element.class);
        diagnostics.note(element, "Info: %s", "note-details");
        verify(messager).printMessage(eq(Diagnostic.Kind.NOTE), eq("Info: note-details"), eq(element));
    }

    @Test
    void error_withNoArgs_doesNotFormat() {
        Element element = mock(Element.class);
        diagnostics.error(element, "Plain message");
        verify(messager).printMessage(eq(Diagnostic.Kind.ERROR), eq("Plain message"), eq(element));
    }

    // --- Static formatter tests (stable contract) ---

    @Test
    void mustReturnFuture_exactString() {
        assertEquals("MyMethod must return Future<T> or Future<Void>", Diagnostics.mustReturnFuture("MyMethod"));
    }

    @Test
    void duplicateOperation_exactString() {
        assertEquals(
                "Duplicate operation name 'doSomething' in contract", Diagnostics.duplicateOperation("doSomething"));
    }

    @Test
    void unsupportedAnnotation_exactString() {
        assertEquals(
                "@jakarta.inject.Inject is not supported in this position",
                Diagnostics.unsupportedAnnotation("jakarta.inject.Inject"));
    }

    @Test
    void expectedRecord_exactString() {
        assertEquals("com.example.MyType must be a record", Diagnostics.expectedRecord("com.example.MyType"));
    }

    @Test
    void duplicateInjectConstructor_exactString() {
        assertEquals(
                "com.example.MyType has multiple @Inject constructors; auto-wiring requires exactly one",
                Diagnostics.duplicateInjectConstructor("com.example.MyType"));
    }

    @Test
    void pathPlaceholderMissingParam_exactString() {
        assertEquals(
                "@Path placeholder '{id}' on myMethod has no matching @PathParam",
                Diagnostics.pathPlaceholderMissingParam("id", "myMethod"));
    }

    @Test
    void pathParamMissingPlaceholder_exactString() {
        assertEquals(
                "@PathParam(\"id\") on myMethod has no matching placeholder in @Path",
                Diagnostics.pathParamMissingPlaceholder("id", "myMethod"));
    }

    // --- T003 shared selector and proxyability formatters (stable contract) ---

    @Test
    @DisplayName("selectorPathBlank formats the family-prefixed diagnostic")
    void selectorPathBlank_exactString() {
        assertEquals("cache key selector path must not be blank", Diagnostics.selectorPathBlank("cache key"));
    }

    @Test
    @DisplayName("selectorPathTooLong formats the family-prefixed diagnostic")
    void selectorPathTooLong_exactString() {
        assertEquals(
                "rate-limit key selector path must not exceed 256 characters",
                Diagnostics.selectorPathTooLong("rate-limit key"));
    }

    @Test
    @DisplayName("propertyPathsTooDeep formats the family-prefixed diagnostic")
    void propertyPathsTooDeep_exactString() {
        assertEquals(
                "cache key property paths are limited to eight segments including the root parameter",
                Diagnostics.propertyPathsTooDeep("cache key"));
    }

    @Test
    @DisplayName("propertyPathInvalidIdentifier formats the family-prefixed diagnostic")
    void propertyPathInvalidIdentifier_exactString() {
        assertEquals(
                "rate-limit key property path contains an invalid identifier: bad-name",
                Diagnostics.propertyPathInvalidIdentifier("rate-limit key", "bad-name"));
    }

    @Test
    @DisplayName("selectorParameterNotFound formats the family-prefixed diagnostic")
    void selectorParameterNotFound_exactString() {
        assertEquals(
                "cache key selector does not resolve to a method parameter: missing",
                Diagnostics.selectorParameterNotFound("cache key", "missing"));
    }

    @Test
    @DisplayName("propertyAccessorNotFound formats the family-prefixed diagnostic")
    void propertyAccessorNotFound_exactString() {
        assertEquals(
                "rate-limit key property is not an accessible record or bean accessor: region",
                Diagnostics.propertyAccessorNotFound("rate-limit key", "region"));
    }

    @Test
    @DisplayName("selectorNotScalar formats the family-prefixed diagnostic")
    void selectorNotScalar_exactString() {
        assertEquals(
                "cache key selector must end in a supported scalar type", Diagnostics.selectorNotScalar("cache key"));
    }

    @Test
    @DisplayName("methodsNotOnPublicClass formats the family-prefixed diagnostic")
    void methodsNotOnPublicClass_exactString() {
        assertEquals(
                "cacheable methods must be declared on a public Dagger-managed class",
                Diagnostics.methodsNotOnPublicClass("cacheable"));
    }

    @Test
    @DisplayName("methodsOnFinalClass formats the family-prefixed diagnostic")
    void methodsOnFinalClass_exactString() {
        assertEquals(
                "rate-limited methods cannot be declared on a final class",
                Diagnostics.methodsOnFinalClass("rate-limited"));
    }

    @Test
    @DisplayName("methodsRequireInjectConstructor formats the family-prefixed diagnostic")
    void methodsRequireInjectConstructor_exactString() {
        assertEquals(
                "cacheable methods require exactly one @Inject constructor",
                Diagnostics.methodsRequireInjectConstructor("cacheable"));
    }

    @Test
    @DisplayName("methodsNotOverridable formats the family-prefixed diagnostic")
    void methodsNotOverridable_exactString() {
        assertEquals(
                "rate-limited methods must be instance methods that can be overridden",
                Diagnostics.methodsNotOverridable("rate-limited"));
    }

    // --- CG-009 JAX-RS formatters (stable contract) ---

    @Test
    void securityAnnotationConflict_exactString() {
        assertEquals(
                "Conflicting security annotations at method-level: @DenyAll + @PermitAll;"
                        + " pick one of @DenyAll, @PermitAll, or @RolesAllowed/@Authorized",
                Diagnostics.securityAnnotationConflict("method-level", "@DenyAll + @PermitAll"));
    }

    @Test
    void emptyRolesAllowed_exactString() {
        assertEquals(
                "@RolesAllowed at class-level has empty value array"
                        + " — use @DenyAll to deny access or specify at least one role",
                Diagnostics.emptyRolesAllowed("class-level"));
    }

    @Test
    void multipleBodyParams_exactString() {
        assertEquals(
                "Method HelloResource.create() has 2 body parameters; at most one is allowed",
                Diagnostics.multipleBodyParams("HelloResource", "create", 2));
    }

    @Test
    void formAndBodyConflict_exactString() {
        assertEquals(
                "Method HelloResource.upload() mixes @FormParam/file upload parameters with a body parameter; use one or the other",
                Diagnostics.formAndBodyConflict("HelloResource", "upload"));
    }

    @Test
    void multipleHttpVerbs_exactString() {
        assertEquals(
                "Method handle() declares multiple HTTP verb annotations (@GET, @POST); pick one",
                Diagnostics.multipleHttpVerbs("handle", java.util.List.of("@GET", "@POST")));
    }

    // --- CG-012 Track B delayed-job formatters (stable contract) ---

    @Test
    void delayedJobMustBeInterface_exactString() {
        assertEquals(
                "@DelayedJobContract com.example.MyJob must be an interface",
                Diagnostics.delayedJobMustBeInterface("com.example.MyJob"));
    }

    @Test
    void delayedJobMustExtendClient_exactString() {
        assertEquals(
                "com.example.MyJob must extend DelayedJobClient<P> directly with a concrete payload type",
                Diagnostics.delayedJobMustExtendClient("com.example.MyJob"));
    }

    @Test
    void delayedJobUnresolvablePayload_exactString() {
        assertEquals(
                "Cannot resolve payload type P for com.example.MyJob; @DelayedJobContract interface must extend DelayedJobClient<ConcreteType>",
                Diagnostics.delayedJobUnresolvablePayload("com.example.MyJob"));
    }

    @Test
    void duplicateDelayedJobName_exactString() {
        assertEquals(
                "Duplicate @DelayedJobContract name 'deliver' in this compilation unit: com.example.A and com.example.B",
                Diagnostics.duplicateDelayedJobName("deliver", "com.example.A", "com.example.B"));
    }

    @Test
    void delayedJobNoExecutorInUnit_exactString() {
        assertEquals(
                "No DelayedJobExecutor for @DelayedJobContract com.example.MyJob in this compilation unit",
                Diagnostics.delayedJobNoExecutorInUnit("com.example.MyJob"));
    }

    @Test
    void delayedJobExecutorPayloadMismatch_exactString() {
        assertEquals(
                "DelayedJobExecutor com.example.MyExec has payload type com.example.A but its contract com.example.MyJob declares payload com.example.B",
                Diagnostics.delayedJobExecutorPayloadMismatch(
                        "com.example.MyExec", "com.example.A", "com.example.MyJob", "com.example.B"));
    }

    @Test
    void delayedJobContractDeclaresMethod_exactString() {
        assertEquals(
                "@DelayedJobContract com.example.MyJob must not declare or inherit methods other than DelayedJobClient's enqueue overloads (found 'doExtra')",
                Diagnostics.delayedJobContractDeclaresMethod("com.example.MyJob", "doExtra"));
    }

    // --- CG-012 Track A Kafka consumer formatters (stable contract) ---

    @Test
    void kafkaHandlerPayloadArity_exactString() {
        assertEquals(
                "@KafkaHandler method OrderRouter.onOrder() must declare exactly one payload parameter (found 0)",
                Diagnostics.kafkaHandlerPayloadArity("OrderRouter", "onOrder", 0));
    }

    @Test
    void kafkaHandlerReturnType_exactString() {
        assertEquals(
                "@KafkaHandler method OrderRouter.onOrder() must return void or Future<Void>",
                Diagnostics.kafkaHandlerReturnType("OrderRouter", "onOrder"));
    }

    @Test
    void kafkaDuplicateRouteSelector_exactString() {
        assertEquals(
                "@KafkaListener OrderRouter has multiple @KafkaHandler methods with the same header selector"
                        + " 'event-type'='created'; route selectors must be unique",
                Diagnostics.kafkaDuplicateRouteSelector("OrderRouter", "header", "event-type", "created"));
    }

    @Test
    void kafkaListenerBlankTopic_exactString() {
        assertEquals(
                "@KafkaListener on OrderRouter has a blank topic()",
                Diagnostics.kafkaListenerBlankTopic("OrderRouter"));
    }

    @Test
    void kafkaHandlerMatchRule_exactString() {
        assertEquals(
                "@KafkaHandler method OrderRouter.onOrder() must specify exactly one of matchHeader, matchProperty, or defaultHandler=true",
                Diagnostics.kafkaHandlerMatchRule("OrderRouter", "onOrder"));
    }

    @Test
    void kafkaSourceOnInterface_exactString() {
        assertEquals(
                "@KafkaSource on OrderService.processOrder() must be on the service implementation method, not the contract interface",
                Diagnostics.kafkaSourceOnInterface("OrderService", "processOrder"));
    }

    @Test
    void kafkaListenerNotRouterOrRecordHandler_exactString() {
        assertEquals(
                "@KafkaListener on OrderHandler must either declare @KafkaHandler methods (router)"
                        + " or implement KafkaRecordHandler<V> (direct handler)",
                Diagnostics.kafkaListenerNotRouterOrRecordHandler("OrderHandler"));
    }

    @Test
    void kafkaHandlerUnresolvedValueType_exactString() {
        assertEquals(
                "@KafkaListener class OrderHandler implements KafkaRecordHandler with an unresolvable value type V"
                        + " — use a concrete type argument, e.g. implements KafkaRecordHandler<MyEvent>",
                Diagnostics.kafkaHandlerUnresolvedValueType("OrderHandler"));
    }

    @Test
    void kafkaListenerValueTypeMismatch_exactString() {
        assertEquals(
                "@KafkaListener on OrderHandler has valueType=Order but the handler implements KafkaRecordHandler<Payment>;"
                        + " annotated valueType must be assignable from the handler's value type V",
                Diagnostics.kafkaListenerValueTypeMismatch("OrderHandler", "Order", "Payment"));
    }

    @Test
    void kafkaHandlerPayloadNotFirst_exactString() {
        assertEquals(
                "@KafkaHandler method OrderRouter.onOrder() must declare its payload as the first parameter;"
                        + " context parameters must come after",
                Diagnostics.kafkaHandlerPayloadNotFirst("OrderRouter", "onOrder"));
    }

    // --- CG-012 Track C workflow contract formatters (stable contract; mirror WorkflowProxyValidator) ---

    @Test
    void workflowContractMustBeInterface_exactString() {
        assertEquals(
                "@WorkflowContract com.example.OrderWorkflow must be an interface",
                Diagnostics.workflowContractMustBeInterface("com.example.OrderWorkflow"));
    }

    @Test
    void workflowDefaultMethod_exactString() {
        assertEquals(
                "Method helper in com.example.OrderWorkflow is a default method; default methods are not supported"
                        + " on workflow contract interfaces in cycle 1.",
                Diagnostics.workflowDefaultMethod("com.example.OrderWorkflow", "helper"));
    }

    @Test
    void workflowConflictingRoles_exactString() {
        assertEquals(
                "Method start on com.example.OrderWorkflow has conflicting workflow annotations; a method must have"
                        + " at most one of @WorkflowStart, @WorkflowSignal, @WorkflowQuery",
                Diagnostics.workflowConflictingRoles("com.example.OrderWorkflow", "start"));
    }

    @Test
    void workflowNoRole_exactString() {
        assertEquals(
                "Method orphan in com.example.OrderWorkflow has no workflow role annotation; declare exactly one of"
                        + " @WorkflowStart, @WorkflowSignal, or @WorkflowQuery.",
                Diagnostics.workflowNoRole("com.example.OrderWorkflow", "orphan"));
    }

    @Test
    void workflowStartReturnType_exactString() {
        assertEquals(
                "Method start in com.example.OrderWorkflow is @WorkflowStart but return type is not"
                        + " Future<WorkflowInstanceId>; got java.lang.String",
                Diagnostics.workflowStartReturnType("com.example.OrderWorkflow", "start", "java.lang.String"));
    }

    @Test
    void workflowStartForbidsInstanceId_exactString() {
        assertEquals(
                "Method start in com.example.OrderWorkflow: @WorkflowStart must not have a WorkflowInstanceId"
                        + " parameter; start always creates a new instance",
                Diagnostics.workflowStartForbidsInstanceId("com.example.OrderWorkflow", "start"));
    }

    @Test
    void workflowParamAnnotationType_exactString() {
        assertEquals(
                "Method start in com.example.OrderWorkflow: @IdempotencyKey must be on a String parameter",
                Diagnostics.workflowParamAnnotationType(
                        "com.example.OrderWorkflow", "start", "IdempotencyKey", "String"));
        assertEquals(
                "Method start in com.example.OrderWorkflow: @SubjectRef must be on a WorkflowSubjectRef parameter",
                Diagnostics.workflowParamAnnotationType(
                        "com.example.OrderWorkflow", "start", "SubjectRef", "WorkflowSubjectRef"));
    }

    @Test
    void workflowDuplicateParamAnnotation_exactString() {
        assertEquals(
                "Method start in com.example.OrderWorkflow: duplicate @IdempotencyKey parameters are not allowed",
                Diagnostics.workflowDuplicateParamAnnotation("com.example.OrderWorkflow", "start", "IdempotencyKey"));
    }

    @Test
    void workflowStartPayloadCardinality_exactString() {
        assertEquals(
                "Method start in com.example.OrderWorkflow: @WorkflowStart must have at most one payload"
                        + " (non-annotated) parameter; found more than one",
                Diagnostics.workflowStartPayloadCardinality("com.example.OrderWorkflow", "start"));
    }

    @Test
    void workflowStartNoPayload_exactString() {
        assertEquals(
                "Method start in com.example.OrderWorkflow is @WorkflowStart but has no payload parameter; exactly"
                        + " one payload parameter is required",
                Diagnostics.workflowStartNoPayload("com.example.OrderWorkflow", "start"));
    }

    @Test
    void workflowMissingIdempotencySource_exactString() {
        assertEquals(
                "Method start in com.example.OrderWorkflow is @WorkflowStart but has no idempotency key source;"
                        + " either add @IdempotencyKey String parameter or have the payload implement IdempotencyKeyed",
                Diagnostics.workflowMissingIdempotencySource("com.example.OrderWorkflow", "start"));
    }

    @Test
    void workflowSignalReturnType_exactString() {
        assertEquals(
                "Method cancel in com.example.OrderWorkflow is @WorkflowSignal but return type is not Future<Void>;"
                        + " got io.vertx.core.Future<java.lang.String>",
                Diagnostics.workflowSignalReturnType(
                        "com.example.OrderWorkflow", "cancel", "io.vertx.core.Future<java.lang.String>"));
    }

    @Test
    void workflowSignalInstanceIdCardinality_exactString() {
        assertEquals(
                "Method cancel in com.example.OrderWorkflow: @WorkflowSignal requires exactly one WorkflowInstanceId"
                        + " parameter; found none",
                Diagnostics.workflowSignalInstanceIdCardinality("com.example.OrderWorkflow", "cancel", 0));
        assertEquals(
                "Method cancel in com.example.OrderWorkflow: @WorkflowSignal requires exactly one WorkflowInstanceId"
                        + " parameter; found 2",
                Diagnostics.workflowSignalInstanceIdCardinality("com.example.OrderWorkflow", "cancel", 2));
    }

    @Test
    void workflowSignalInstanceIdFirst_exactString() {
        assertEquals(
                "Method cancel in com.example.OrderWorkflow: @WorkflowSignal requires WorkflowInstanceId to be the"
                        + " first parameter (found at index 1)",
                Diagnostics.workflowSignalInstanceIdFirst("com.example.OrderWorkflow", "cancel", 1));
    }

    @Test
    void workflowSignalPayloadCardinality_exactString() {
        assertEquals(
                "Method cancel in com.example.OrderWorkflow: @WorkflowSignal must have at most one payload"
                        + " (non-annotated) parameter; found more than one",
                Diagnostics.workflowSignalPayloadCardinality("com.example.OrderWorkflow", "cancel"));
    }

    @Test
    void workflowSignalNoPayload_exactString() {
        assertEquals(
                "Method cancel in com.example.OrderWorkflow is @WorkflowSignal but has no payload parameter; exactly"
                        + " one payload parameter is required",
                Diagnostics.workflowSignalNoPayload("com.example.OrderWorkflow", "cancel"));
    }

    @Test
    void workflowMissingDedupSource_exactString() {
        assertEquals(
                "Method cancel in com.example.OrderWorkflow is @WorkflowSignal but has no dedup key source; either"
                        + " add @SignalDedupKey String parameter or have the payload implement SignalDedupKeyed",
                Diagnostics.workflowMissingDedupSource("com.example.OrderWorkflow", "cancel"));
    }

    @Test
    void workflowDuplicateSignalName_exactString() {
        assertEquals(
                "Duplicate @WorkflowSignal(\"cancel\") methods on com.example.OrderWorkflow; signal names must be"
                        + " unique within a contract",
                Diagnostics.workflowDuplicateSignalName("com.example.OrderWorkflow", "cancel"));
    }

    @Test
    void workflowQueryReturnType_exactString() {
        assertEquals(
                "Method status in com.example.OrderWorkflow is @WorkflowQuery but return type is not"
                        + " Future<WorkflowView>; got java.lang.String",
                Diagnostics.workflowQueryReturnType("com.example.OrderWorkflow", "status", "java.lang.String"));
    }

    @Test
    void workflowQueryParam_exactString() {
        assertEquals(
                "Method status in com.example.OrderWorkflow is @WorkflowQuery but must have exactly one parameter of"
                        + " type WorkflowInstanceId; got 2 params",
                Diagnostics.workflowQueryParam("com.example.OrderWorkflow", "status", 2));
    }

    @Test
    void workflowStartMethodCardinality_exactString() {
        assertEquals(
                "Contract com.example.OrderWorkflow must have exactly one @WorkflowStart method; found 0",
                Diagnostics.workflowStartMethodCardinality("com.example.OrderWorkflow", 0));
    }

    @Test
    void workflowDuplicateOperationSignature_exactString() {
        assertEquals(
                "Contract com.example.OrderWorkflow inherits an ambiguous operation"
                        + " 'start(com.example.StartOrder)' from multiple unrelated super-interfaces; a workflow"
                        + " contract may not inherit the same method signature from sibling interfaces",
                Diagnostics.workflowDuplicateOperationSignature(
                        "com.example.OrderWorkflow", "start(com.example.StartOrder)"));
    }

    // --- CG-013 Events W2/W3 formatters (stable contract) ---

    @Test
    void observerMustBeAccessible_exactString() {
        assertEquals(
                "Observer method com.example.MyObserver.onEvent must be public (accessible from the generated module);"
                        + " private, protected, and package-private observers cannot be called from GeneratedEventsModule",
                Diagnostics.observerMustBeAccessible("com.example.MyObserver.onEvent"));
    }

    @Test
    void eventPayloadMustNotBeParameterized_exactString() {
        assertEquals(
                "Event payload type Box<String> is parameterized (generic);"
                        + " parameterized event payloads are not supported in v1 — use a non-generic type",
                Diagnostics.eventPayloadMustNotBeParameterized("Box<String>"));
    }

    // --- CG-013 Events observer-signature formatter (stable contract) ---

    @Test
    void observerMustReturnVoid_exactString() {
        assertEquals(
                "Observer method com.example.MyObserver.onOrder must return void;"
                        + " non-void observers cannot be dispatched",
                Diagnostics.observerMustReturnVoid("com.example.MyObserver.onOrder"));
    }

    @Test
    void observerMustHaveSingleParameter_exactString() {
        assertEquals(
                "Observer method com.example.MyObserver.on must declare a single parameter"
                        + " (the @Observes one); multi-parameter observers cannot be dispatched",
                Diagnostics.observerMustHaveSingleParameter("com.example.MyObserver.on"));
    }

    @Test
    void observerMustNotObserveObject_exactString() {
        assertEquals(
                "Observer method com.example.MyObserver.onAny must not observe java.lang.Object;"
                        + " Object is excluded from the registry's superclass walk and would silently never fire",
                Diagnostics.observerMustNotObserveObject("com.example.MyObserver.onAny"));
    }

    // --- CG-013 AOP intercepted-return formatters (stable contract) ---

    @Test
    void aopBeanMustBePublic_exactString() {
        assertEquals(
                "com.example.PackagePrivateGreeter must be public;"
                        + " the generated @Binds in GeneratedAopModule references the bean type from a"
                        + " possibly-different-package module — a non-public bean is not accessible from that package",
                Diagnostics.aopBeanMustBePublic("com.example.PackagePrivateGreeter"));
    }

    @Test
    void aopUnsupportedFutureReturn_raw_exactString() {
        assertEquals(
                "Aspect-intercepted method com.example.MyBean.doWork returns a raw (unparameterized) Future,"
                        + " which cannot be proxied; use a concrete Future<ConcreteType>",
                Diagnostics.aopUnsupportedFutureReturn("com.example.MyBean.doWork", false));
    }

    @Test
    void aopUnsupportedFutureReturn_wildcard_exactString() {
        assertEquals(
                "Aspect-intercepted method com.example.MyBean.doWork returns a wildcard Future<? ...>,"
                        + " which cannot be proxied; use a concrete Future<ConcreteType>",
                Diagnostics.aopUnsupportedFutureReturn("com.example.MyBean.doWork", true));
    }

    @Test
    void aopNotProxyable_exactString() {
        assertEquals(
                "com.example.FinalGreeter cannot be proxied for method AOP: the class is final and cannot be subclassed",
                Diagnostics.aopNotProxyable("com.example.FinalGreeter", "the class is final and cannot be subclassed"));
        assertEquals(
                "com.example.Greeter.greet cannot be proxied for method AOP: it is a final method and cannot be overridden",
                Diagnostics.aopNotProxyable(
                        "com.example.Greeter.greet", "it is a final method and cannot be overridden"));
        assertEquals(
                "com.example.Greeter.greet cannot be proxied for method AOP: it is private and cannot be overridden",
                Diagnostics.aopNotProxyable("com.example.Greeter.greet", "it is private and cannot be overridden"));
        assertEquals(
                "com.example.Greeter.greet cannot be proxied for method AOP: it is a static method and cannot be overridden",
                Diagnostics.aopNotProxyable(
                        "com.example.Greeter.greet", "it is a static method and cannot be overridden"));
        assertEquals(
                "com.example.Greeter.boom cannot be proxied for method AOP: it declares a type variable in its throws"
                        + " clause, which the sync guard cannot reify (instanceof on a type variable)",
                Diagnostics.aopNotProxyable(
                        "com.example.Greeter.boom",
                        "it declares a type variable in its throws clause, which the sync guard cannot reify"
                                + " (instanceof on a type variable)"));
    }

    @Test
    void unsupportedAnnotationAttributeKind_exactString() {
        assertEquals(
                "@com.example.Weighted member 'weight' has unsupported attribute kind 'float' for reflection-free"
                        + " metadata; char/float/double (and arrays of them) cannot be materialized into an"
                        + " annotation literal",
                Diagnostics.unsupportedAnnotationAttributeKind("com.example.Weighted", "weight", "float"));
    }
}
