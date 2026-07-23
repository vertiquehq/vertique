// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen;

import java.util.IllegalFormatException;
import java.util.List;
import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;

/**
 * Compiler diagnostic helper that combines instance methods bound to a {@link Messager} with
 * static factory methods for consistent diagnostic message wording.
 *
 * <p>The static formatters ({@link #mustReturnFuture}, {@link #duplicateOperation},
 * {@link #unsupportedAnnotation}, {@link #expectedRecord}) form an externally-stable contract:
 * downstream processors (CG-002+) MUST use these methods so that error messages stay consistent
 * across the codegen series. The exact string values are tested in {@code DiagnosticsTest}.
 *
 * <p>Obtain an instance via {@link CodegenContext#diagnostics()}.
 */
public final class Diagnostics {

    private final Messager messager;

    /**
     * Constructs a {@code Diagnostics} helper bound to the given {@link Messager}.
     *
     * @param messager the messager from the processing environment; must not be {@code null}
     */
    public Diagnostics(Messager messager) {
        this.messager = messager;
    }

    // --- Instance methods ---

    /**
     * Emits a compiler error diagnostic attributed to the given source element.
     *
     * @param source  the element that caused the error; may be {@code null} (error reported
     *                without source location)
     * @param message the format string (as accepted by {@link String#format}); must not be
     *                {@code null}
     * @param args    optional format arguments
     */
    public void error(Element source, String message, Object... args) {
        emit(Diagnostic.Kind.ERROR, source, message, args);
    }

    /**
     * Emits a compiler warning diagnostic attributed to the given source element.
     *
     * @param source  the element that caused the warning; may be {@code null}
     * @param message the format string; must not be {@code null}
     * @param args    optional format arguments
     */
    public void warning(Element source, String message, Object... args) {
        emit(Diagnostic.Kind.WARNING, source, message, args);
    }

    /**
     * Emits a compiler note (informational) diagnostic attributed to the given source element.
     *
     * @param source  the element to attribute the note to; may be {@code null}
     * @param message the format string; must not be {@code null}
     * @param args    optional format arguments
     */
    public void note(Element source, String message, Object... args) {
        emit(Diagnostic.Kind.NOTE, source, message, args);
    }

    // --- Static message formatters ---

    /**
     * Returns the standard error message for a method or element that must return a
     * {@code Future<T>} or {@code Future<Void>} but does not.
     *
     * @param context a human-readable description of the method or element (e.g., the method name
     *                or fully-qualified class name)
     * @return the formatted error message
     */
    public static String mustReturnFuture(String context) {
        return "%s must return Future<T> or Future<Void>".formatted(context);
    }

    /**
     * Returns the standard error message for a duplicate operation name in a service contract.
     *
     * @param opName the operation name that is duplicated
     * @return the formatted error message
     */
    public static String duplicateOperation(String opName) {
        return "Duplicate operation name '%s' in contract".formatted(opName);
    }

    /**
     * Returns the standard error message for an annotation that is used in an unsupported
     * position.
     *
     * @param fqn the fully-qualified annotation type name (e.g., {@code "jakarta.inject.Inject"})
     * @return the formatted error message
     */
    public static String unsupportedAnnotation(String fqn) {
        return "@%s is not supported in this position".formatted(fqn);
    }

    /**
     * Returns the standard error message for a type that must be a record but is not.
     *
     * @param typeFqn the fully-qualified name of the type that was expected to be a record
     * @return the formatted error message
     */
    public static String expectedRecord(String typeFqn) {
        return "%s must be a record".formatted(typeFqn);
    }

    /**
     * Returns the standard error message for a type that has more than one constructor annotated
     * with {@code @Inject}.
     *
     * <p>This formatter is part of the externally-stable contract established by CG-001 and first
     * used by CG-002's {@code AutoWireProcessor}. Downstream processors MUST use this method for
     * duplicate-inject-constructor errors so that message wording stays consistent across the
     * codegen series.
     *
     * @param typeFqn the fully-qualified name of the type with duplicate {@code @Inject}
     *                constructors
     * @return the formatted error message
     */
    public static String duplicateInjectConstructor(String typeFqn) {
        return "%s has multiple @Inject constructors; auto-wiring requires exactly one".formatted(typeFqn);
    }

    /**
     * Returns the standard error message for a {@code @Path} placeholder that has no matching
     * {@code @PathParam} on the method.
     *
     * <p>This formatter is part of the externally-stable contract established by CG-001 and first
     * used by CG-003's {@code PathPlaceholderValidator}. Downstream processors MUST use this method
     * so that error messages stay consistent across the codegen series.
     *
     * @param placeholder the placeholder name (without braces), e.g. {@code "id"}
     * @param context a human-readable description of the method or element (e.g. the method name)
     * @return the formatted error message
     */
    public static String pathPlaceholderMissingParam(String placeholder, String context) {
        return "@Path placeholder '{%s}' on %s has no matching @PathParam".formatted(placeholder, context);
    }

    /**
     * Returns the standard error message for a {@code @PathParam} annotation that has no matching
     * placeholder in the method's {@code @Path}.
     *
     * <p>This formatter is part of the externally-stable contract established by CG-001 and first
     * used by CG-003's {@code PathPlaceholderValidator}. Downstream processors MUST use this method
     * so that error messages stay consistent across the codegen series.
     *
     * @param paramName the {@code @PathParam} name (e.g. {@code "id"})
     * @param context a human-readable description of the method or element (e.g. the method name)
     * @return the formatted error message
     */
    public static String pathParamMissingPlaceholder(String paramName, String context) {
        return "@PathParam(\"%s\") on %s has no matching placeholder in @Path".formatted(paramName, context);
    }

    // --- CG-009 JAX-RS validation formatters ---

    /**
     * Returns the standard error message for conflicting security annotations at the same
     * declaration level (class or method). Mirrors the base wording of the runtime channel in
     * {@code ResourceScanner} (security-annotation conflict path).
     *
     * <p>This formatter is part of the externally-stable contract introduced by CG-009.
     *
     * @param level       a human-readable description of the declaration level
     *                    (e.g. {@code "class-level"} or {@code "method-level"})
     * @param combination a human-readable description of the conflicting combination
     *                    (e.g. {@code "@DenyAll + @PermitAll"})
     * @return the formatted error message
     */
    public static String securityAnnotationConflict(String level, String combination) {
        return String.format(
                "Conflicting security annotations at %s: %s; pick one of @DenyAll, @PermitAll, or @RolesAllowed/@Authorized",
                level, combination);
    }

    /**
     * Returns the standard error message for {@code @RequiresAction} combined with a blanket
     * {@code @PermitAll} or {@code @DenyAll} at the given declaration level. {@code @RequiresAction}
     * AND-composes only with {@code @RolesAllowed}/{@code @Authorized}, so pairing it with a blanket
     * allow/deny is a contradiction. Mirrors the runtime startup check in {@code JaxRsRouteRegistrar}.
     *
     * @param level    a human-readable description of the declaration level
     *                 (e.g. {@code "class-level"} or {@code "method-level"})
     * @param conflicting the conflicting blanket annotation (e.g. {@code "@PermitAll"})
     * @return the formatted error message
     */
    public static String requiresActionConflict(String level, String conflicting) {
        return String.format(
                "@RequiresAction at %s conflicts with %s; @RequiresAction composes only with @RolesAllowed/@Authorized",
                level, conflicting);
    }

    /**
     * Returns the standard error message for a {@code @RolesAllowed} annotation with an empty
     * value array. Mirrors the base wording of the runtime channel in {@code ResourceScanner}
     * (empty-roles-allowed path).
     *
     * <p>This formatter is part of the externally-stable contract introduced by CG-009.
     *
     * @param level a human-readable description of the declaration level
     *              (e.g. {@code "class-level"} or {@code "method-level"})
     * @return the formatted error message
     */
    public static String emptyRolesAllowed(String level) {
        return String.format(
                "@RolesAllowed at %s has empty value array — use @DenyAll to deny access or specify at least one role",
                level);
    }

    /**
     * Returns the standard error message when a method declares more than one body parameter.
     * Mirrors the exact wording of the runtime channel in {@code RouteValidator}
     * (multiple-body-params path): {@code "Method ClassName.methodName() has N body parameters; …"}.
     *
     * <p>This formatter is part of the externally-stable contract introduced by CG-009.
     *
     * @param resourceClassName the simple name of the resource class (e.g. {@code "HelloResource"})
     * @param methodName        the simple name of the resource method (e.g. {@code "create"})
     * @param count             the number of body parameters found (always &gt; 1)
     * @return the formatted error message
     */
    public static String multipleBodyParams(String resourceClassName, String methodName, int count) {
        return String.format(
                "Method %s.%s() has %d body parameters; at most one is allowed", resourceClassName, methodName, count);
    }

    /**
     * Returns the standard error message when a method mixes {@code @FormParam}/file-upload
     * parameters with a body parameter. Mirrors the exact wording of the runtime channel in
     * {@code RouteValidator} (form-and-body-conflict path):
     * {@code "Method ClassName.methodName() mixes @FormParam/…"}.
     *
     * <p>This formatter is part of the externally-stable contract introduced by CG-009.
     *
     * @param resourceClassName the simple name of the resource class (e.g. {@code "HelloResource"})
     * @param methodName        the simple name of the resource method (e.g. {@code "upload"})
     * @return the formatted error message
     */
    public static String formAndBodyConflict(String resourceClassName, String methodName) {
        return String.format(
                "Method %s.%s() mixes @FormParam/file upload parameters with a body parameter; use one or the other",
                resourceClassName, methodName);
    }

    /**
     * Returns the standard error message when a method declares more than one HTTP verb
     * annotation (Tier-B build-time-only guardrail introduced by CG-009).
     *
     * <p>This formatter is part of the externally-stable contract introduced by CG-009.
     *
     * @param methodName the simple name of the resource method (e.g. {@code "handle"})
     * @param verbs      the HTTP verb annotation simple names present on the method
     *                   (e.g. {@code ["@GET", "@POST"]})
     * @return the formatted error message
     */
    public static String multipleHttpVerbs(String methodName, List<String> verbs) {
        return String.format(
                "Method %s() declares multiple HTTP verb annotations (%s); pick one",
                methodName, String.join(", ", verbs));
    }

    // --- CG-010 contract-conflict formatters ---

    /**
     * Returns the standard compile-time error message when two implemented interfaces carry
     * conflicting values for the same JAX-RS annotation kind on a method.
     *
     * <p>Emitted as a compile-time error by {@code EffectiveJaxRsContractResolver} during
     * method-level contract resolution.
     *
     * @param methodName     the simple name of the conflicting method
     * @param kind           human-readable description of the conflicting annotation kind
     *                       (e.g. {@code "@Path"} or {@code "@RolesAllowed"})
     * @param interfaceA     binary name of the first interface
     * @param valueA         annotation value from the first interface
     * @param interfaceB     binary name of the second interface
     * @param valueB         annotation value from the second interface
     * @return the formatted error message
     */
    public static String interfaceContractConflict(
            String methodName, String kind, String interfaceA, String valueA, String interfaceB, String valueB) {
        return String.format(
                "Method %s() has conflicting %s declarations on interfaces %s (%s) and %s (%s); "
                        + "override the annotation directly on the implementing class to resolve",
                methodName, kind, interfaceA, valueA, interfaceB, valueB);
    }

    /**
     * Returns the standard compile-time error message when a concrete class implements two
     * interfaces with conflicting class-level JAX-RS annotation values.
     *
     * <p>Emitted as a compile-time error by {@code EffectiveJaxRsContractResolver} during
     * class-level contract resolution.
     *
     * @param className  simple name of the concrete class
     * @param kind       human-readable description of the conflicting annotation kind
     *                   (e.g. {@code "@Path"} or {@code "@RolesAllowed"})
     * @param interfaceA binary name of the first interface
     * @param valueA     annotation value from the first interface
     * @param interfaceB binary name of the second interface
     * @param valueB     annotation value from the second interface
     * @return the formatted error message
     */
    public static String classContractConflict(
            String className, String kind, String interfaceA, String valueA, String interfaceB, String valueB) {
        return String.format(
                "Class %s implements two interfaces with conflicting %s declarations: %s (%s) vs %s (%s); "
                        + "override the annotation directly on the implementing class to resolve",
                className, kind, interfaceA, valueA, interfaceB, valueB);
    }

    /**
     * Returns the standard compile-time warning message when a concrete class (or method) carries
     * a direct annotation that disagrees with an annotation declared on an implemented interface.
     * Direct wins per the precedence rule, but the developer should be notified.
     *
     * <p>Emitted as a compiler warning by {@code EffectiveJaxRsContractResolver}.
     *
     * @param context        simple name of the class or method
     * @param kind           human-readable description of the annotation kind (e.g. {@code "@Path"})
     * @param directValue    the value declared directly on the concrete class or method
     * @param interfaceName  binary name of the interface whose value was overridden
     * @param interfaceValue the annotation value on the interface
     * @return the formatted warning message
     */
    public static String directOverridesInterfaceWarning(
            String context, String kind, String directValue, String interfaceName, String interfaceValue) {
        return String.format(
                "%s has a direct %s (%s) that overrides the interface declaration on %s (%s); "
                        + "the direct annotation wins — this may be intentional",
                context, kind, directValue, interfaceName, interfaceValue);
    }

    // --- CG-012 Track B delayed-job formatters ---

    /**
     * Returns the standard compile-time error message when a {@code @DelayedJobContract} annotation is
     * placed on a type that is not an interface. The generated proxy {@code implements} the contract, so
     * the target must be an interface (the runtime {@code DelayedJobClientFactory.create} also requires
     * one via an explicit {@code isInterface()} check).
     *
     * <p>This formatter is part of the externally-stable contract introduced by CG-012 Track B and used
     * by {@code DelayedJobContractProcessor}. The check runs before the {@code isAssignable} check so
     * that an abstract class annotated with {@code @DelayedJobContract} yields a clear diagnostic
     * instead of a generated-source compile error from {@code implements SomeClass}.
     *
     * @param contractFqn the fully-qualified name of the offending type
     * @return the formatted error message
     */
    public static String delayedJobMustBeInterface(String contractFqn) {
        return "@DelayedJobContract %s must be an interface".formatted(contractFqn);
    }

    /**
     * Returns the standard compile-time error message when a {@code @DelayedJobContract} interface does
     * not extend {@code DelayedJobClient<P>} directly with a concrete payload type.
     *
     * <p>This formatter is part of the externally-stable contract introduced by CG-012 Track B and used
     * by {@code DelayedJobContractProcessor}.
     *
     * @param contractFqn the fully-qualified name of the offending contract interface
     * @return the formatted error message
     */
    public static String delayedJobMustExtendClient(String contractFqn) {
        return "%s must extend DelayedJobClient<P> directly with a concrete payload type".formatted(contractFqn);
    }

    /**
     * Returns the standard compile-time error message when the payload type {@code P} of a
     * {@code @DelayedJobContract} interface cannot be resolved (e.g. raw {@code DelayedJobClient} or a
     * forwarding type variable).
     *
     * <p>This formatter is part of the externally-stable contract introduced by CG-012 Track B.
     *
     * @param contractFqn the fully-qualified name of the contract interface
     * @return the formatted error message
     */
    public static String delayedJobUnresolvablePayload(String contractFqn) {
        return "Cannot resolve payload type P for %s; @DelayedJobContract interface must extend DelayedJobClient<ConcreteType>"
                .formatted(contractFqn);
    }

    /**
     * Returns the standard compile-time error message when two {@code @DelayedJobContract} interfaces in
     * the same compilation unit declare the same {@code name()}.
     *
     * <p>This formatter is part of the externally-stable contract introduced by CG-012 Track B.
     *
     * @param name      the duplicated contract name
     * @param contractA the fully-qualified name of the first contract
     * @param contractB the fully-qualified name of the second contract
     * @return the formatted error message
     */
    public static String duplicateDelayedJobName(String name, String contractA, String contractB) {
        return "Duplicate @DelayedJobContract name '%s' in this compilation unit: %s and %s"
                .formatted(name, contractA, contractB);
    }

    /**
     * Returns the standard compile-time message when no {@code DelayedJobExecutor} for a
     * {@code @DelayedJobContract} is present in the same compilation unit. Emitted as a warning by default;
     * promoted to an error under the {@code vertique.codegen.delayedjob.requireExecutor} option (the executor
     * may legitimately live in another module).
     *
     * <p>This formatter is part of the externally-stable contract introduced by CG-012 Track B.
     *
     * @param contractFqn the fully-qualified name of the contract interface
     * @return the formatted message
     */
    public static String delayedJobNoExecutorInUnit(String contractFqn) {
        return "No DelayedJobExecutor for @DelayedJobContract %s in this compilation unit".formatted(contractFqn);
    }

    /**
     * Returns the standard compile-time error message when a same-unit {@code DelayedJobExecutor}'s payload
     * type does not match the payload type declared by its contract.
     *
     * <p>This formatter is part of the externally-stable contract introduced by CG-012 Track B.
     *
     * @param executorFqn the fully-qualified name of the executor
     * @param executorP   the executor's resolved payload type
     * @param contractFqn the fully-qualified name of the contract
     * @param contractP   the contract's declared payload type
     * @return the formatted error message
     */
    public static String delayedJobExecutorPayloadMismatch(
            String executorFqn, String executorP, String contractFqn, String contractP) {
        return "DelayedJobExecutor %s has payload type %s but its contract %s declares payload %s"
                .formatted(executorFqn, executorP, contractFqn, contractP);
    }

    /**
     * Returns the standard compile-time error message when a {@code @DelayedJobContract} interface
     * declares its own abstract method, which the generated proxy cannot implement.
     *
     * <p>This formatter is part of the externally-stable contract introduced by CG-012 Track B. The check
     * covers methods declared on the contract <em>and</em> inherited through intermediate interfaces,
     * whether {@code abstract} or {@code default}: an extra {@code abstract} method would leave the
     * generated proxy uncompilable, while a {@code default} method would execute in the generated proxy but
     * throw {@code UnsupportedOperationException} in the reflective proxy — both are parity divergences.
     *
     * @param contractFqn the fully-qualified name of the contract interface
     * @param methodName  the offending method name
     * @return the formatted error message
     */
    public static String delayedJobContractDeclaresMethod(String contractFqn, String methodName) {
        return "@DelayedJobContract %s must not declare or inherit methods other than DelayedJobClient's enqueue overloads (found '%s')"
                .formatted(contractFqn, methodName);
    }

    // --- CG-012 Track A Kafka consumer formatters ---

    /**
     * Returns the standard compile-time error message when a {@code @KafkaHandler} method declares
     * zero or more than one payload parameter.
     *
     * <p>This formatter is part of the externally-stable contract introduced by CG-012 Track A.
     *
     * @param className   simple name of the {@code @KafkaListener} interface (e.g. {@code "OrderRouter"})
     * @param methodName  simple name of the handler method (e.g. {@code "onOrder"})
     * @param payloadCount the number of payload parameters found (0 or &gt;1)
     * @return the formatted error message
     */
    public static String kafkaHandlerPayloadArity(String className, String methodName, int payloadCount) {
        return "@KafkaHandler method %s.%s() must declare exactly one payload parameter (found %d)"
                .formatted(className, methodName, payloadCount);
    }

    /**
     * Returns the standard compile-time error message when a {@code @KafkaHandler} method does not
     * return {@code void} or {@code Future<Void>}.
     *
     * <p>This formatter is part of the externally-stable contract introduced by CG-012 Track A.
     *
     * @param className  simple name of the {@code @KafkaListener} interface
     * @param methodName simple name of the handler method
     * @return the formatted error message
     */
    public static String kafkaHandlerReturnType(String className, String methodName) {
        return "@KafkaHandler method %s.%s() must return void or Future<Void>".formatted(className, methodName);
    }

    /**
     * Returns the standard compile-time error message when a {@code @KafkaListener}'s
     * {@code topic()} attribute is blank.
     *
     * <p>This formatter is part of the externally-stable contract introduced by CG-012 Track A.
     *
     * @param className the simple name of the {@code @KafkaListener}-annotated type
     * @return the formatted error message
     */
    public static String kafkaListenerBlankTopic(String className) {
        return "@KafkaListener on %s has a blank topic()".formatted(className);
    }

    /**
     * Returns the standard compile-time error message when a {@code @KafkaHandler} method does not
     * satisfy the mutual-exclusion rule: exactly one of {@code matchHeader}, {@code matchProperty},
     * or {@code defaultHandler = true} must be set.
     *
     * <p>This formatter is part of the externally-stable contract introduced by CG-012 Track A.
     *
     * @param className  simple name of the {@code @KafkaListener} interface
     * @param methodName simple name of the handler method
     * @return the formatted error message
     */
    public static String kafkaHandlerMatchRule(String className, String methodName) {
        return "@KafkaHandler method %s.%s() must specify exactly one of matchHeader, matchProperty, or defaultHandler=true"
                .formatted(className, methodName);
    }

    /**
     * Returns the compile-time error message when two {@code @KafkaHandler} methods within the same
     * router declare the same route selector — the same {@code matchHeader}+{@code matchValue} or the
     * same {@code matchProperty}+{@code matchValue}. {@code KafkaRecordDispatcher.resolveRoute} matches
     * header routes first (in declaration order), then property routes, then the default, so a second
     * route with an identical selector is unreachable within its pass. (Selectors of different kinds — a
     * header vs. a property — sharing the same name and value are <em>not</em> duplicates and are not
     * reported here; they can both be meaningful.)
     *
     * <p>This formatter is part of the externally-stable contract introduced by CG-012 Track A.
     *
     * @param className    simple name of the {@code @KafkaListener} interface
     * @param selectorKind the selector kind, {@code "header"} or {@code "property"}
     * @param selectorName the header or property name
     * @param matchValue   the value the duplicated selector matches on
     * @return the formatted error message
     */
    public static String kafkaDuplicateRouteSelector(
            String className, String selectorKind, String selectorName, String matchValue) {
        return ("@KafkaListener %s has multiple @KafkaHandler methods with the same %s selector '%s'='%s';"
                        + " route selectors must be unique")
                .formatted(className, selectorKind, selectorName, matchValue);
    }

    /**
     * Returns the standard compile-time error message when {@link dev.vertique.kafka.KafkaSource
     * &#64;KafkaSource} is found on an interface method.
     *
     * <p>The annotation must appear on the concrete service-implementation method, not on the
     * contract interface — placing it on the interface would mean the annotation is part of the
     * public API contract rather than an infrastructure wiring decision.
     *
     * <p>This formatter is part of the externally-stable contract introduced by CG-012 Track A
     * (Slice 5).
     *
     * @param className  simple name of the interface that carries the misplaced annotation
     * @param methodName simple name of the annotated method
     * @return the formatted error message
     */
    public static String kafkaSourceOnInterface(String className, String methodName) {
        return "@KafkaSource on %s.%s() must be on the service implementation method, not the contract interface"
                .formatted(className, methodName);
    }

    /**
     * Returns the standard compile-time error message when a {@code @KafkaListener} class
     * implements {@code KafkaRecordHandler} without a resolvable value type argument {@code V}
     * (i.e., the raw type is used or {@code V} is a forwarding type variable).
     *
     * <p>A raw {@code KafkaRecordHandler} prevents the emitter from producing a valid
     * {@code .class} literal for the value type. The reflective path also errors in this case.
     * The fix is to parameterize the implementation: {@code implements KafkaRecordHandler<MyEvent>}.
     *
     * <p>This formatter is part of the externally-stable contract introduced by CG-012 Track A.
     *
     * @param className the simple name of the offending {@code @KafkaListener} class
     * @return the formatted error message
     */
    public static String kafkaHandlerUnresolvedValueType(String className) {
        return ("@KafkaListener class %s implements KafkaRecordHandler with an unresolvable value type V"
                        + " — use a concrete type argument, e.g. implements KafkaRecordHandler<MyEvent>")
                .formatted(className);
    }

    /**
     * Returns the standard compile-time error message when a {@code @KafkaListener} type is neither
     * a router (no {@link dev.vertique.kafka.KafkaHandler @KafkaHandler} methods) nor a direct
     * handler (does not implement {@code KafkaRecordHandler<V>}).
     *
     * <p>A {@code @KafkaListener} type must satisfy exactly one of:
     * <ul>
     *   <li>Model 3 router — interface with at least one {@code @KafkaHandler}-annotated method</li>
     *   <li>Model 4 direct handler — class implementing {@code KafkaRecordHandler<V>}</li>
     * </ul>
     *
     * <p>This formatter is part of the externally-stable contract introduced by CG-012 Track A
     * (Slice 6).
     *
     * @param className the simple name of the offending {@code @KafkaListener} type
     * @return the formatted error message
     */
    public static String kafkaListenerNotRouterOrRecordHandler(String className) {
        return ("@KafkaListener on %s must either declare @KafkaHandler methods (router)"
                        + " or implement KafkaRecordHandler<V> (direct handler)")
                .formatted(className);
    }

    /**
     * Returns the standard compile-time error message when a {@code @KafkaHandler} method declares
     * a context parameter (assignable to {@code KafkaRecordContext} or {@code SecurityContext}, or
     * annotated with {@code @DispatchContextValue} on its type) before the payload parameter.
     *
     * <p>The payload parameter must always be the first parameter in a {@code @KafkaHandler} method
     * signature; context parameters must follow the payload. This invariant ensures codegen-vs-
     * reflective parity: the reflective {@code KafkaConsumerScanner.resolveRouteValueType} reads
     * {@code params[0]} as the payload type, so {@code params[0]} must be the payload.
     *
     * <p>This formatter is part of the externally-stable contract introduced by CG-012 Track A.
     *
     * @param className  simple name of the {@code @KafkaListener} interface
     * @param methodName simple name of the handler method
     * @return the formatted error message
     */
    public static String kafkaHandlerPayloadNotFirst(String className, String methodName) {
        return ("@KafkaHandler method %s.%s() must declare its payload as the first parameter;"
                        + " context parameters must come after")
                .formatted(className, methodName);
    }

    /**
     * Returns the standard compile-time error message when a {@code @KafkaListener} direct-handler
     * class declares a {@code valueType()} attribute that is not assignable from the resolved
     * {@code KafkaRecordHandler<V>} value type.
     *
     * <p>The reflective {@code KafkaConsumerScanner.processHandlerInstance} rejects an instance when
     * {@code annotatedValueType != Void.class && !annotatedValueType.isAssignableFrom(resolvedV)}.
     * This formatter produces the compile-time parity diagnostic when the codegen processor detects
     * the same mismatch via the APT type system.
     *
     * <p>This formatter is part of the externally-stable contract introduced by CG-012 Track A.
     *
     * @param className      the simple name of the offending {@code @KafkaListener} class
     * @param annotatedType  the human-readable name of {@code @KafkaListener.valueType()} as declared
     * @param resolvedType   the human-readable name of the resolved {@code V} from
     *                       {@code KafkaRecordHandler<V>}
     * @return the formatted error message
     */
    public static String kafkaListenerValueTypeMismatch(String className, String annotatedType, String resolvedType) {
        return ("@KafkaListener on %s has valueType=%s but the handler implements KafkaRecordHandler<%s>;"
                        + " annotated valueType must be assignable from the handler's value type V")
                .formatted(className, annotatedType, resolvedType);
    }

    // --- CG-012 Track C workflow contract formatters ---
    //
    // These mirror the exact wording of WorkflowProxyContractException (the runtime channel in
    // WorkflowProxyValidator) so that a contract-shape mistake reads identically whether caught at
    // compile time (this processor) or at WorkflowClientFactory.create(...) time. Only the structural
    // subset is reproduced here — registry/plan checks (definition registered, signal name in plan,
    // payload type matches plan) stay runtime-only because APT cannot see the WorkflowRegistry.

    /**
     * Returns the compile-time error message when a {@code @WorkflowContract} annotation is placed on a
     * type that is not an interface. The generated proxy {@code implements} the contract, so the target
     * must be an interface (the reflective path likewise builds a JDK dynamic proxy, which requires one).
     *
     * <p>This formatter is part of the externally-stable contract introduced by CG-012 Track C.
     *
     * @param typeFqn the fully-qualified name of the offending type
     * @return the formatted error message
     */
    public static String workflowContractMustBeInterface(String typeFqn) {
        return "@WorkflowContract %s must be an interface".formatted(typeFqn);
    }

    /**
     * Returns the error message when a workflow contract declares a {@code default} method. Mirrors
     * {@code WorkflowProxyValidator}'s default-method rejection.
     *
     * @param contractFqn the fully-qualified contract interface name
     * @param methodName  the offending method name
     * @return the formatted error message
     */
    public static String workflowDefaultMethod(String contractFqn, String methodName) {
        return ("Method %s in %s is a default method; default methods are not supported on workflow"
                        + " contract interfaces in cycle 1.")
                .formatted(methodName, contractFqn);
    }

    /**
     * Returns the error message when a method carries more than one of {@code @WorkflowStart},
     * {@code @WorkflowSignal}, {@code @WorkflowQuery}. Mirrors the runtime conflicting-roles wording.
     *
     * @param contractFqn the fully-qualified contract interface name
     * @param methodName  the offending method name
     * @return the formatted error message
     */
    public static String workflowConflictingRoles(String contractFqn, String methodName) {
        return ("Method %s on %s has conflicting workflow annotations; a method must have at most one of"
                        + " @WorkflowStart, @WorkflowSignal, @WorkflowQuery")
                .formatted(methodName, contractFqn);
    }

    /**
     * Returns the error message when a non-default, non-{@code Object} method has no workflow role
     * annotation. Mirrors the runtime no-role wording.
     *
     * @param contractFqn the fully-qualified contract interface name
     * @param methodName  the offending method name
     * @return the formatted error message
     */
    public static String workflowNoRole(String contractFqn, String methodName) {
        return ("Method %s in %s has no workflow role annotation; declare exactly one of @WorkflowStart,"
                        + " @WorkflowSignal, or @WorkflowQuery.")
                .formatted(methodName, contractFqn);
    }

    /**
     * Returns the error message when a {@code @WorkflowStart} method does not return
     * {@code Future<WorkflowInstanceId>}. Mirrors the runtime start-return wording.
     *
     * @param contractFqn the fully-qualified contract interface name
     * @param methodName  the offending method name
     * @param got         the actual generic return type as text
     * @return the formatted error message
     */
    public static String workflowStartReturnType(String contractFqn, String methodName, String got) {
        return "Method %s in %s is @WorkflowStart but return type is not Future<WorkflowInstanceId>; got %s"
                .formatted(methodName, contractFqn, got);
    }

    /**
     * Returns the error message when a {@code @WorkflowStart} method declares a
     * {@code WorkflowInstanceId} parameter. Mirrors the runtime wording.
     *
     * @param contractFqn the fully-qualified contract interface name
     * @param methodName  the offending method name
     * @return the formatted error message
     */
    public static String workflowStartForbidsInstanceId(String contractFqn, String methodName) {
        return ("Method %s in %s: @WorkflowStart must not have a WorkflowInstanceId parameter; start always"
                        + " creates a new instance")
                .formatted(methodName, contractFqn);
    }

    /**
     * Returns the error message when a key/role parameter annotation is placed on a parameter of the
     * wrong type. Mirrors the runtime per-annotation wording (e.g. {@code @IdempotencyKey} must be on a
     * {@code String} parameter, {@code @SubjectRef} on a {@code WorkflowSubjectRef} parameter).
     *
     * @param contractFqn  the fully-qualified contract interface name
     * @param methodName   the offending method name
     * @param annotation   the simple annotation name without the {@code @} (e.g. {@code "IdempotencyKey"})
     * @param requiredType the simple required parameter type (e.g. {@code "String"})
     * @return the formatted error message
     */
    public static String workflowParamAnnotationType(
            String contractFqn, String methodName, String annotation, String requiredType) {
        return "Method %s in %s: @%s must be on a %s parameter"
                .formatted(methodName, contractFqn, annotation, requiredType);
    }

    /**
     * Returns the error message when a key/role parameter annotation appears on more than one parameter.
     * Mirrors the runtime duplicate-parameter-annotation wording.
     *
     * @param contractFqn the fully-qualified contract interface name
     * @param methodName  the offending method name
     * @param annotation  the simple annotation name without the {@code @} (e.g. {@code "IdempotencyKey"})
     * @return the formatted error message
     */
    public static String workflowDuplicateParamAnnotation(String contractFqn, String methodName, String annotation) {
        return "Method %s in %s: duplicate @%s parameters are not allowed"
                .formatted(methodName, contractFqn, annotation);
    }

    /**
     * Returns the error message when a {@code @WorkflowStart} method declares more than one payload
     * (non-annotated) parameter. Mirrors the runtime wording.
     *
     * @param contractFqn the fully-qualified contract interface name
     * @param methodName  the offending method name
     * @return the formatted error message
     */
    public static String workflowStartPayloadCardinality(String contractFqn, String methodName) {
        return ("Method %s in %s: @WorkflowStart must have at most one payload (non-annotated) parameter;"
                        + " found more than one")
                .formatted(methodName, contractFqn);
    }

    /**
     * Returns the error message when a {@code @WorkflowStart} method declares no payload parameter.
     * Mirrors the runtime wording.
     *
     * @param contractFqn the fully-qualified contract interface name
     * @param methodName  the offending method name
     * @return the formatted error message
     */
    public static String workflowStartNoPayload(String contractFqn, String methodName) {
        return ("Method %s in %s is @WorkflowStart but has no payload parameter; exactly one payload"
                        + " parameter is required")
                .formatted(methodName, contractFqn);
    }

    /**
     * Returns the error message when a {@code @WorkflowStart} method has no idempotency key source
     * (no {@code @IdempotencyKey} parameter and the payload does not implement {@code IdempotencyKeyed}).
     * Mirrors the runtime wording.
     *
     * @param contractFqn the fully-qualified contract interface name
     * @param methodName  the offending method name
     * @return the formatted error message
     */
    public static String workflowMissingIdempotencySource(String contractFqn, String methodName) {
        return ("Method %s in %s is @WorkflowStart but has no idempotency key source; either add"
                        + " @IdempotencyKey String parameter or have the payload implement IdempotencyKeyed")
                .formatted(methodName, contractFqn);
    }

    /**
     * Returns the error message when a {@code @WorkflowSignal} method does not return {@code Future<Void>}.
     * Mirrors the runtime signal-return wording.
     *
     * @param contractFqn the fully-qualified contract interface name
     * @param methodName  the offending method name
     * @param got         the actual generic return type as text
     * @return the formatted error message
     */
    public static String workflowSignalReturnType(String contractFqn, String methodName, String got) {
        return "Method %s in %s is @WorkflowSignal but return type is not Future<Void>; got %s"
                .formatted(methodName, contractFqn, got);
    }

    /**
     * Returns the error message when a {@code @WorkflowSignal} method does not declare exactly one
     * {@code WorkflowInstanceId} parameter. Mirrors the runtime wording ({@code "found none"} for zero,
     * the count otherwise).
     *
     * @param contractFqn the fully-qualified contract interface name
     * @param methodName  the offending method name
     * @param count       the number of {@code WorkflowInstanceId} parameters found (not 1)
     * @return the formatted error message
     */
    public static String workflowSignalInstanceIdCardinality(String contractFqn, String methodName, int count) {
        String found = count == 0 ? "found none" : "found " + count;
        return "Method %s in %s: @WorkflowSignal requires exactly one WorkflowInstanceId parameter; %s"
                .formatted(methodName, contractFqn, found);
    }

    /**
     * Returns the error message when a {@code @WorkflowSignal} method's {@code WorkflowInstanceId} is not
     * the first parameter. Mirrors the runtime wording.
     *
     * @param contractFqn the fully-qualified contract interface name
     * @param methodName  the offending method name
     * @param index       the zero-based index where {@code WorkflowInstanceId} was found
     * @return the formatted error message
     */
    public static String workflowSignalInstanceIdFirst(String contractFqn, String methodName, int index) {
        return ("Method %s in %s: @WorkflowSignal requires WorkflowInstanceId to be the first parameter"
                        + " (found at index %d)")
                .formatted(methodName, contractFqn, index);
    }

    /**
     * Returns the error message when a {@code @WorkflowSignal} method declares more than one payload
     * (non-annotated) parameter. Mirrors the runtime wording.
     *
     * @param contractFqn the fully-qualified contract interface name
     * @param methodName  the offending method name
     * @return the formatted error message
     */
    public static String workflowSignalPayloadCardinality(String contractFqn, String methodName) {
        return ("Method %s in %s: @WorkflowSignal must have at most one payload (non-annotated) parameter;"
                        + " found more than one")
                .formatted(methodName, contractFqn);
    }

    /**
     * Returns the error message when a {@code @WorkflowSignal} method declares no payload parameter.
     * Mirrors the runtime wording.
     *
     * @param contractFqn the fully-qualified contract interface name
     * @param methodName  the offending method name
     * @return the formatted error message
     */
    public static String workflowSignalNoPayload(String contractFqn, String methodName) {
        return ("Method %s in %s is @WorkflowSignal but has no payload parameter; exactly one payload"
                        + " parameter is required")
                .formatted(methodName, contractFqn);
    }

    /**
     * Returns the error message when a {@code @WorkflowSignal} method has no dedup key source (no
     * {@code @SignalDedupKey} parameter and the payload does not implement {@code SignalDedupKeyed}).
     * Mirrors the runtime wording.
     *
     * @param contractFqn the fully-qualified contract interface name
     * @param methodName  the offending method name
     * @return the formatted error message
     */
    public static String workflowMissingDedupSource(String contractFqn, String methodName) {
        return ("Method %s in %s is @WorkflowSignal but has no dedup key source; either add @SignalDedupKey"
                        + " String parameter or have the payload implement SignalDedupKeyed")
                .formatted(methodName, contractFqn);
    }

    /**
     * Returns the error message when two {@code @WorkflowSignal} methods on a contract share the same
     * signal name. Mirrors the runtime duplicate-signal-name wording.
     *
     * @param contractFqn the fully-qualified contract interface name
     * @param signalName  the duplicated signal name
     * @return the formatted error message
     */
    public static String workflowDuplicateSignalName(String contractFqn, String signalName) {
        return "Duplicate @WorkflowSignal(\"%s\") methods on %s; signal names must be unique within a contract"
                .formatted(signalName, contractFqn);
    }

    /**
     * Returns the error message when a {@code @WorkflowQuery} method does not return exactly
     * {@code Future<WorkflowView>}. Mirrors the runtime query-return wording. V1 constrains query methods to
     * the single untyped {@code Future<WorkflowView>} surface (no supertype/wildcard), so runtime and codegen
     * enforce the same exact rule.
     *
     * @param contractFqn the fully-qualified contract interface name
     * @param methodName  the offending method name
     * @param got         the actual return type as text
     * @return the formatted error message
     */
    public static String workflowQueryReturnType(String contractFqn, String methodName, String got) {
        return "Method %s in %s is @WorkflowQuery but return type is not Future<WorkflowView>; got %s"
                .formatted(methodName, contractFqn, got);
    }

    /**
     * Returns the error message when a {@code @WorkflowQuery} method does not declare exactly one
     * {@code WorkflowInstanceId} parameter. Mirrors the runtime wording.
     *
     * @param contractFqn the fully-qualified contract interface name
     * @param methodName  the offending method name
     * @param count       the number of parameters found (not the single required one)
     * @return the formatted error message
     */
    public static String workflowQueryParam(String contractFqn, String methodName, int count) {
        return ("Method %s in %s is @WorkflowQuery but must have exactly one parameter of type"
                        + " WorkflowInstanceId; got %d params")
                .formatted(methodName, contractFqn, count);
    }

    /**
     * Returns the error message when a contract does not declare exactly one {@code @WorkflowStart}
     * method. Mirrors the runtime wording.
     *
     * @param contractFqn the fully-qualified contract interface name
     * @param count       the number of {@code @WorkflowStart} methods found (not 1)
     * @return the formatted error message
     */
    public static String workflowStartMethodCardinality(String contractFqn, int count) {
        return "Contract %s must have exactly one @WorkflowStart method; found %d".formatted(contractFqn, count);
    }

    /**
     * Returns the error message when a contract inherits the same method signature from two unrelated
     * super-interfaces (a sibling-duplicate erased signature that is not an override chain). The
     * generated proxy is a concrete class and cannot override the same signature twice, and the
     * reflective runtime sees both declarations via {@code Class#getMethods()} — both paths reject
     * the ambiguous shape with this wording for parity.
     *
     * @param contractFqn the fully-qualified contract interface name
     * @param signature   the ambiguous erased method signature (e.g. {@code start(com.example.Cmd)})
     * @return the formatted error message
     */
    public static String workflowDuplicateOperationSignature(String contractFqn, String signature) {
        return ("Contract %s inherits an ambiguous operation '%s' from multiple unrelated super-interfaces;"
                        + " a workflow contract may not inherit the same method signature from sibling interfaces")
                .formatted(contractFqn, signature);
    }

    // --- CG-013 Events observer-signature formatters ---

    /**
     * Returns the standard compile-time error message when an {@code @Observes}-annotated parameter
     * appears on a method that does not return {@code void}.
     *
     * <p>The event-dispatch engine invokes observers through a synchronous {@code Consumer<Object>}
     * that cannot consume a return value; returning anything other than {@code void} is therefore a
     * contract violation that is rejected at compile time.
     *
     * <p>This formatter is part of the externally-stable contract introduced by CG-013 Events and
     * used by {@code EventsProcessor}. The message contains the word {@code void} so that the test
     * harness's {@code assertErrorMessage("void")} assertion can locate it by substring.
     *
     * @param methodFqn the fully-qualified {@code Type.method} name of the offending observer method
     * @return the formatted error message
     */
    public static String observerMustReturnVoid(String methodFqn) {
        return "Observer method %s must return void; non-void observers cannot be dispatched".formatted(methodFqn);
    }

    /**
     * Returns the standard compile-time error message when an {@code @Observes}-annotated method
     * declares more than one parameter.
     *
     * <p>The event-dispatch engine invokes observers through a single-argument lambda
     * {@code e -> bean.method((EventType) e)}. A method that declares more than one parameter
     * cannot be expressed by that lambda and must be rejected so that the generated source remains
     * compilable. The {@code "single"} substring is used by {@code assertErrorMessage("single")}
     * in the test harness to locate this diagnostic.
     *
     * <p>This formatter is part of the externally-stable contract introduced by CG-013 Events and
     * used by {@code EventsProcessor} (P3-W2).
     *
     * @param methodFqn the fully-qualified {@code Type.method} name of the offending observer method
     * @return the formatted error message
     */
    public static String observerMustHaveSingleParameter(String methodFqn) {
        return "Observer method %s must declare a single parameter (the @Observes one); multi-parameter observers cannot be dispatched"
                .formatted(methodFqn);
    }

    /**
     * Returns the standard compile-time error message when an {@code @Observes} parameter type is
     * exactly {@code java.lang.Object}.
     *
     * <p>The {@link dev.vertique.events.ObserverRegistry}'s superclass walk excludes
     * {@code Object.class} from dispatch, so an {@code @Observes Object} observer silently never
     * fires. Allowing it would mislead the author into thinking they have a catch-all observer that
     * works. The {@code "Object"} substring is used by {@code assertErrorMessage("Object")} in the
     * test harness to locate this diagnostic.
     *
     * <p>This formatter is part of the externally-stable contract introduced by CG-013 Events and
     * used by {@code EventsProcessor} (P3-W5).
     *
     * @param methodFqn the fully-qualified {@code Type.method} name of the offending observer method
     * @return the formatted error message
     */
    public static String observerMustNotObserveObject(String methodFqn) {
        return "Observer method %s must not observe java.lang.Object; Object is excluded from the registry's superclass walk and would silently never fire"
                .formatted(methodFqn);
    }

    /**
     * Returns the standard compile-time error message when an {@code @Observes} observer method is
     * not accessible from the generated {@code GeneratedEventsModule}.
     *
     * <p>The generated module emits a static {@code @Provides @IntoSet ObserverRegistration} method
     * whose body contains a lambda {@code e -> bean.method((EventType) e)}. That lambda lives in
     * the module's package. If the observer method (or its declaring class) is {@code private} or
     * {@code protected}, the lambda cannot call it and the generated source fails to compile.
     * Package-private visibility is also rejected because {@code GeneratedEventsModule} is not
     * guaranteed to be in the same package as the declaring bean.
     *
     * <p>The v1 rule: the observer method must be {@code public}. The declaring class must also be
     * accessible (not {@code private} or non-{@code public} nested). This formatter contains the
     * word {@code "accessible"} so that tests can locate it via
     * {@code assertErrorMessage("accessible")}.
     *
     * <p>This formatter is part of the externally-stable contract introduced by CG-013 Events and
     * used by {@code EventsProcessor} (W2).
     *
     * @param methodFqn the fully-qualified {@code Type.method} name of the offending observer method
     * @return the formatted error message
     */
    public static String observerMustBeAccessible(String methodFqn) {
        return "Observer method %s must be public (accessible from the generated module); private, protected, and package-private observers cannot be called from GeneratedEventsModule"
                .formatted(methodFqn);
    }

    /**
     * Returns the standard compile-time error message when the payload type of an {@code @Observes}
     * parameter or an {@code Event<T>} constructor injection is a parameterized (generic) type such
     * as {@code Box<String>}.
     *
     * <p>The v1 event inventory stores event types as erased {@code TypeElement} references.
     * A parameterized payload such as {@code Event<Box<String>>} would be recorded as
     * {@code Box} (erased), generating a {@code @Binds Event<Box>} binding that does not satisfy
     * Dagger's requested {@code Event<Box<String>>} key and causes a {@code MissingBinding} error.
     * Rather than silently producing a wrong binding, the processor rejects parameterized payloads
     * with this diagnostic.
     *
     * <p>This formatter contains the word {@code "parameterized"} so that tests can locate it via
     * {@code assertErrorMessage("parameterized")}.
     *
     * <p>This formatter is part of the externally-stable contract introduced by CG-013 Events and
     * used by {@code EventsProcessor} (W3).
     *
     * @param typeName a human-readable description of the parameterized type (e.g.
     *                 {@code "Box<String>"} or the FQN of the {@code @Observes} parameter type)
     * @return the formatted error message
     */
    public static String eventPayloadMustNotBeParameterized(String typeName) {
        return "Event payload type %s is parameterized (generic); parameterized event payloads are not supported in v1 — use a non-generic type"
                .formatted(typeName);
    }

    // --- CG-013 AOP intercepted-return formatters ---

    /**
     * Returns the standard compile-time error message when the aspect-carrying bean class is not
     * {@code public}.
     *
     * <p>The generated {@code GeneratedAopModule} lives in the resolved output package (the option
     * override, or the longest common prefix of the aspect beans' packages). Its {@code @Binds}
     * method returns the original bean type ({@code binding.boundType}). If the bean class is
     * non-public and the module is relocated to a different package, the {@code @Binds} references
     * a type that is inaccessible from the module's package — producing uncompilable generated
     * source. Requiring {@code public} on the aspect-carrying bean is consistent with the
     * {@code @Observes} observer accessibility rule.
     *
     * <p>This formatter is part of the externally-stable contract introduced by CG-013 and used by
     * {@code AopProcessor}. The message contains the word {@code "public"} so that tests can locate
     * it via {@code assertErrorMessage("must be public")}.
     *
     * @param beanFqn the fully-qualified name of the non-public aspect-carrying bean
     * @return the formatted error message
     */
    public static String aopBeanMustBePublic(String beanFqn) {
        return ("%s must be public; the generated @Binds in GeneratedAopModule references the bean"
                        + " type from a possibly-different-package module — a non-public bean is not accessible"
                        + " from that package")
                .formatted(beanFqn);
    }

    /**
     * Returns the standard compile-time error message when an aspect-intercepted method returns a
     * raw (unparameterized) or wildcard-parameterized {@code io.vertx.core.Future}. Neither shape can
     * be safely proxied: a raw {@code Future} would be misclassified as a synchronous return (so the
     * around-chain meters the wrong outcome), and a wildcard {@code Future<? ...>} would force the
     * emitter to render an invalid back-cast to the wildcard element type in the generated override.
     * Both are rejected with this diagnostic so the author supplies a concrete {@code Future<X>}.
     *
     * <p>This formatter is part of the externally-stable contract introduced by CG-013 and used by
     * {@code AopProcessor}. The {@code wildcard} flag selects the wording: the raw form names the
     * return as <em>raw (unparameterized)</em>, the wildcard form contains the word {@code wildcard}.
     *
     * @param methodFqn the fully-qualified {@code Type.method} name of the offending intercepted method
     * @param wildcard  {@code true} for a wildcard {@code Future<? ...>} return, {@code false} for a
     *                  raw (unparameterized) {@code Future} return
     * @return the formatted error message
     */
    public static String aopUnsupportedFutureReturn(String methodFqn, boolean wildcard) {
        String shape = wildcard ? "a wildcard Future<? ...>" : "a raw (unparameterized) Future";
        return ("Aspect-intercepted method %s returns %s, which cannot be proxied;"
                        + " use a concrete Future<ConcreteType>")
                .formatted(methodFqn, shape);
    }

    /**
     * Returns the standard compile-time error message when an aspect bean (or one of its intercepted
     * methods) has a shape the subclass-proxy strategy cannot proxy. The {@code reason} text carries
     * the specific non-proxyable cause and is appended to a stable prefix that names the offending
     * element.
     *
     * <p>The subclass-proxy strategy emits {@code {Bean}$AopProxy extends Bean} with an
     * {@code @Override} per intercepted method, so it cannot proxy:
     * <ul>
     *   <li>a {@code final} class (cannot be subclassed) — reason contains {@code "is final"};
     *   <li>a {@code final} intercepted method (cannot be overridden) — reason contains
     *       {@code "final method"};
     *   <li>a {@code private} intercepted method (not visible to a subclass) — reason contains
     *       {@code "is private"};
     *   <li>a {@code static} intercepted method (not an instance method) — reason contains
     *       {@code "static method"};
     *   <li>an intercepted method whose {@code throws} clause names a method type variable, which the
     *       generated sync guard would render as a non-reifiable {@code instanceof E} — reason
     *       contains {@code "type variable"}.
     * </ul>
     *
     * <p>Each refusal is a hard compile error emitted <em>before</em> proxy emission, so the build
     * fails with this clear diagnostic instead of a confusing generated-source {@code javac} error.
     * This formatter is part of the externally-stable contract introduced by CG-013 and used by
     * {@code AopProcessor}.
     *
     * @param elementFqn the fully-qualified name of the offending class, or {@code Type.method} of the
     *                   offending intercepted method
     * @param reason     the specific non-proxyable cause (carrying the substring above)
     * @return the formatted error message
     */
    public static String aopNotProxyable(String elementFqn, String reason) {
        return "%s cannot be proxied for method AOP: %s".formatted(elementFqn, reason);
    }

    /**
     * Returns the standard compile-time error message when a method's runtime-retained annotation
     * must be materialized into a reflection-free {@code <Ann>$Literal} (for the metadata
     * {@code findAnnotation}/{@code hasAnnotation} surface) but carries a member of an unsupported
     * attribute kind ({@code char} / {@code float} / {@code double}, or an array of one of those).
     *
     * <p>An unsupported attribute kind is a hard compile error rather than a silent reflective
     * {@code asMethod()} fallback (which would break the reflection-free guarantee, FR-013-13 /
     * FR-013-09c). The message names the annotation, the offending member, and its kind so the
     * author can locate it.
     *
     * <p>This formatter is part of the externally-stable contract introduced by CG-013 and used by
     * {@code MetadataEmitter} via {@code AopProxyEmitter}.
     *
     * @param annotationFqn the fully-qualified name of the annotation type being materialized
     * @param member        the name of the offending annotation member (e.g. {@code "weight"})
     * @param kind          the unsupported attribute kind as text (e.g. {@code "float"} or
     *                      {@code "float[]"})
     * @return the formatted error message
     */
    public static String unsupportedAnnotationAttributeKind(String annotationFqn, String member, String kind) {
        return ("@%s member '%s' has unsupported attribute kind '%s' for reflection-free metadata;"
                        + " char/float/double (and arrays of them) cannot be materialized into an annotation literal")
                .formatted(annotationFqn, member, kind);
    }

    /**
     * Returns the {@link Messager} this instance is bound to.
     *
     * @return the messager; never {@code null}
     */
    public Messager messager() {
        return messager;
    }

    // --- Internal helpers ---

    /**
     * Formats a message and emits it via the messager at the specified diagnostic kind.
     *
     * @param kind    the diagnostic severity
     * @param source  the element to attribute the diagnostic to; may be {@code null}
     * @param message the format string
     * @param args    optional format arguments
     */
    private void emit(Diagnostic.Kind kind, Element source, String message, Object... args) {
        String formatted;
        if (args.length == 0) {
            formatted = message;
        } else {
            try {
                formatted = message.formatted(args);
            } catch (IllegalFormatException ex) {
                formatted = message;
            }
        }
        messager.printMessage(kind, formatted, source);
    }
}
