// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.kafka.processor.scan;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.kafka.processor.KafkaCodegenAnnotations;
import dev.vertique.codegen.support.MethodOverrides;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

/**
 * APT-side scanner that reads a {@link dev.vertique.kafka.KafkaListener @KafkaListener} type into
 * a {@link ListenerModel}.
 *
 * <p>Discrimination between Model 3 (router) and Model 4 (direct handler) is performed by
 * inspecting the type element:
 *
 * <ul>
 *   <li><b>Model 3 router</b> — the type is an interface; its {@code @KafkaHandler}-annotated
 *       methods are collected into {@link RouteModel} instances using
 *       {@link javax.lang.model.util.Elements#getAllMembers(TypeElement)} so that inherited
 *       {@code @KafkaHandler} methods from super-interfaces are included. Each route's payload type
 *       is resolved via {@link KafkaParamClassifier}. A missing {@code @DispatchTo} on a route is
 *       allowed (the record is consumed but not dispatched); validation of constraints such as
 *       "at least one route must have {@code @DispatchTo}" is deferred to a later validator
 *       slice.</li>
 *   <li><b>Model 4 direct handler</b> — the type is a class (or enum) implementing
 *       {@code KafkaRecordHandler<V>}; the value type {@code V} is resolved via
 *       {@link dev.vertique.codegen.TypeResolver#resolveTypeArgument}. A {@code null} result means
 *       {@code V} could not be resolved (raw type or forwarding variable); validators report
 *       that as an error before emission.</li>
 * </ul>
 */
public final class KafkaListenerScanner {

    // --- @KafkaHandler attribute names ---

    private static final String HANDLER_ATTR_MATCH_HEADER = "matchHeader";
    private static final String HANDLER_ATTR_MATCH_PROPERTY = "matchProperty";
    private static final String HANDLER_ATTR_MATCH_VALUE = "matchValue";
    private static final String HANDLER_ATTR_DEFAULT_HANDLER = "defaultHandler";

    // --- @DispatchTo attribute names ---

    private static final String DISPATCH_ATTR_SERVICE = "service";
    private static final String DISPATCH_ATTR_OPERATION = "operation";

    private final CodegenContext ctx;
    private final KafkaParamClassifier classifier;

    /**
     * Constructs a scanner bound to the given codegen context.
     *
     * @param ctx        the shared codegen context; must not be {@code null}
     * @param classifier the param classifier used to resolve route payload types; must not be
     *                   {@code null}
     */
    public KafkaListenerScanner(CodegenContext ctx, KafkaParamClassifier classifier) {
        this.ctx = ctx;
        this.classifier = classifier;
    }

    /**
     * Scans a {@code @KafkaListener} type element into a {@link ListenerModel}.
     *
     * @param type the type element carrying {@code @KafkaListener}; must not be {@code null}
     * @return the scanned model; never {@code null}
     * @throws IllegalStateException if the element does not carry {@code @KafkaListener}
     */
    public ListenerModel scan(TypeElement type) {
        AnnotationMirror mirror = AnnotationMirrors.findByFqn(type, KafkaCodegenAnnotations.KAFKA_LISTENER)
                .orElseThrow(() -> new IllegalStateException(
                        "scan() called on a type without @KafkaListener: " + type.getQualifiedName()));

        String name = ctx.annotations()
                .attribute(mirror, KafkaAttrs.ATTR_NAME, String.class)
                .orElse("");
        String topic = ctx.annotations()
                .attribute(mirror, KafkaAttrs.ATTR_TOPIC, String.class)
                .orElse("");
        String groupId = ctx.annotations()
                .attribute(mirror, KafkaAttrs.ATTR_GROUP_ID, String.class)
                .orElse("");
        String deadLetterTopic = ctx.annotations()
                .attribute(mirror, KafkaAttrs.ATTR_DEAD_LETTER_TOPIC, String.class)
                .orElse("");

        // Read the type-level @JsonProfile annotation (by FQN string match, no module dep) as the
        // sole per-binding profile selector. A method-level @JsonProfile on a listener type is
        // rejected here (FR-JSON-066).
        rejectMethodLevelJsonProfile(type);
        String jsonProfile = resolveEffectiveProfile(type);

        // Enum constants arrive as VariableElement in the APT mirror
        String errorStrategy = KafkaAttrs.readEnumConstant(ctx, mirror, KafkaAttrs.ATTR_ERROR_STRATEGY, "SKIP");
        String commitStrategy = KafkaAttrs.readEnumConstant(ctx, mirror, KafkaAttrs.ATTR_COMMIT_STRATEGY, "AUTO");

        if (type.getKind() == ElementKind.INTERFACE) {
            return scanRouter(type, name, topic, groupId, errorStrategy, commitStrategy, deadLetterTopic, jsonProfile);
        }
        return scanHandler(type, name, topic, groupId, errorStrategy, commitStrategy, deadLetterTopic, jsonProfile);
    }

    // --- @JsonProfile resolution (FR-JSON-066) ---

    /**
     * Resolves the effective JSON mapper profile for a {@code @KafkaListener} type from the
     * type-level {@code @JsonProfile} annotation. {@code @JsonProfile} is the sole per-binding
     * profile selector: a non-blank value selects that profile, and a blank or absent annotation
     * resolves to {@code ""} (the framework default).
     *
     * @param type the {@code @KafkaListener} type element; must not be {@code null}
     * @return the effective profile id, or {@code ""} for the framework default
     */
    private String resolveEffectiveProfile(TypeElement type) {
        return AnnotationMirrors.findByFqn(type, KafkaAttrs.ATTR_JSON_PROFILE_FQN)
                .flatMap(annMirror ->
                        ctx.annotations().attribute(annMirror, KafkaAttrs.ATTR_JSON_PROFILE_VALUE, String.class))
                .filter(profile -> !profile.isBlank())
                .orElse("");
    }

    /**
     * Rejects a method-level {@code @JsonProfile} on a {@code @KafkaListener} type (FR-JSON-066).
     * {@code @JsonProfile} selects a profile for the whole listener and must be placed at TYPE
     * level; a method-level placement on either a router interface or a direct-handler class is a
     * compile error naming the offending method.
     *
     * <p>Scans the inherited-inclusive member set via
     * {@link javax.lang.model.util.Elements#getAllMembers(TypeElement)} — the same source
     * {@link #scanRouter} uses to read handler methods — so a method-level {@code @JsonProfile} on an
     * <em>inherited</em> {@code @KafkaHandler} method (declared on a super-type) is also rejected, not
     * silently bypassed. Methods declared on {@code java.lang.Object} are skipped.
     *
     * @param type the {@code @KafkaListener} type element; must not be {@code null}
     */
    private void rejectMethodLevelJsonProfile(TypeElement type) {
        for (Element member : ctx.elements().getAllMembers(type)) {
            if (!(member instanceof ExecutableElement method)) {
                continue;
            }
            Element enclosingType = method.getEnclosingElement();
            if (enclosingType instanceof TypeElement te
                    && OBJECT_FQN.equals(te.getQualifiedName().toString())) {
                continue;
            }
            if (AnnotationMirrors.isPresent(method, KafkaAttrs.ATTR_JSON_PROFILE_FQN)) {
                ctx.diagnostics()
                        .error(
                                method,
                                "@JsonProfile on method " + method.getSimpleName() + " in "
                                        + type.getSimpleName()
                                        + " is not allowed; place @JsonProfile at TYPE level on the @KafkaListener type");
            }
        }
    }

    // --- Model 3: router ---

    /** Fully-qualified name of {@code java.lang.Object}, used to skip inherited Object methods. */
    private static final String OBJECT_FQN = "java.lang.Object";

    /**
     * Scans a {@code @KafkaListener} interface (Model 3 router) and produces a {@link ListenerModel}
     * with kind {@link ListenerModel.Kind#ROUTER}.
     *
     * <p>Uses {@link javax.lang.model.util.Elements#getAllMembers(TypeElement)} rather than
     * {@link TypeElement#getEnclosedElements()} so that methods inherited from super-interfaces are
     * included. Methods declared on {@code java.lang.Object} are skipped.
     *
     * <p><strong>Dedup-then-filter ordering (reflective parity):</strong> all non-Object instance
     * methods are first deduplicated by erased signature (retaining the most-specific override), and
     * <em>then</em> filtered to those that carry {@code @KafkaHandler} on the retained declaration.
     * This mirrors {@code Class#getMethods()} + {@code method.getAnnotation(KafkaHandler.class)}
     * in the reflective path: {@code getMethods()} returns the most-specific override (B's declaration
     * when {@code B extends A} overrides {@code void onX()}), and if that declaration does not
     * re-declare {@code @KafkaHandler} the annotation lookup returns {@code null}, yielding no route.
     *
     * <p>The pre-filter-then-dedup approach used previously diverged from the reflective path for
     * the override-drops-annotation case: super-interface {@code A} declares
     * {@code @KafkaHandler void onX()}, sub-interface {@code B extends A} overrides {@code void onX()}
     * without re-declaring {@code @KafkaHandler}. With pre-filter, A's annotated declaration survived
     * into dedup and a route was emitted; with dedup-first, B's unannotated declaration wins and is
     * then rejected by the {@code @KafkaHandler} filter, correctly yielding no route.
     *
     * @param type             the interface element
     * @param name             binding name
     * @param topic            Kafka topic
     * @param groupId          consumer group id
     * @param errorStrategy    error strategy constant name
     * @param commitStrategy   commit strategy constant name
     * @param deadLetterTopic  dead-letter topic
     * @param jsonProfile      JSON mapper profile id, or {@code ""} for the framework default
     * @return the scanned router model
     */
    private ListenerModel scanRouter(
            TypeElement type,
            String name,
            String topic,
            String groupId,
            String errorStrategy,
            String commitStrategy,
            String deadLetterTopic,
            String jsonProfile) {

        // Step 1: collect ALL non-Object instance methods (own + inherited) without pre-filtering
        // by @KafkaHandler. Filtering before dedup would prevent the most-specific override
        // (which may lack @KafkaHandler) from winning the dedup step.
        List<ExecutableElement> allMethods = new ArrayList<>();
        for (Element member : ctx.elements().getAllMembers(type)) {
            if (!(member instanceof ExecutableElement method)) {
                continue;
            }
            // Skip methods from java.lang.Object (e.g. toString, equals, hashCode)
            Element enclosingType = method.getEnclosingElement();
            if (enclosingType instanceof TypeElement te
                    && OBJECT_FQN.equals(te.getQualifiedName().toString())) {
                continue;
            }
            allMethods.add(method);
        }

        // Step 2: deduplicate by erased signature, keeping the most-specific (override). An
        // override that drops @KafkaHandler wins the dedup over the annotated super-declaration,
        // then correctly fails the @KafkaHandler filter in step 3.
        List<ExecutableElement> deduplicated = MethodOverrides.deduplicateByErasedSignature(allMethods, ctx.types());

        // Step 3: filter to @KafkaHandler-annotated methods on the retained most-specific
        // declaration, then build routes. Checking the annotation AFTER dedup mirrors
        // Class#getMethods() + method.getAnnotation(KafkaHandler.class) in the reflective path.
        List<RouteModel> routes = new ArrayList<>();
        for (ExecutableElement method : deduplicated) {
            Optional<AnnotationMirror> handlerMirror =
                    AnnotationMirrors.findByFqn(method, KafkaCodegenAnnotations.KAFKA_HANDLER);
            handlerMirror.ifPresent(mirror -> routes.add(scanRoute(method, mirror)));
        }

        return new ListenerModel(
                type,
                name,
                topic,
                groupId,
                errorStrategy,
                commitStrategy,
                deadLetterTopic,
                jsonProfile,
                ListenerModel.Kind.ROUTER,
                null,
                routes);
    }

    /**
     * Scans a single {@code @KafkaHandler}-annotated method into a {@link RouteModel}.
     *
     * @param method        the handler method
     * @param handlerMirror the {@code @KafkaHandler} annotation mirror
     * @return the route model
     */
    private RouteModel scanRoute(ExecutableElement method, AnnotationMirror handlerMirror) {
        String matchHeader = ctx.annotations()
                .attribute(handlerMirror, HANDLER_ATTR_MATCH_HEADER, String.class)
                .orElse("");
        String matchProperty = ctx.annotations()
                .attribute(handlerMirror, HANDLER_ATTR_MATCH_PROPERTY, String.class)
                .orElse("");
        String matchValue = ctx.annotations()
                .attribute(handlerMirror, HANDLER_ATTR_MATCH_VALUE, String.class)
                .orElse("");
        boolean defaultHandler = ctx.annotations()
                .attribute(handlerMirror, HANDLER_ATTR_DEFAULT_HANDLER, Boolean.class)
                .orElse(false);

        TypeMirror routeValueType = classifier.resolvePayloadType(method);

        // Read optional @DispatchTo
        TypeMirror targetService = null;
        String targetOperation = null;
        Optional<AnnotationMirror> dispatchMirror =
                AnnotationMirrors.findByFqn(method, KafkaCodegenAnnotations.DISPATCH_TO);
        if (dispatchMirror.isPresent()) {
            targetService = ctx.annotations()
                    .attributeClass(dispatchMirror.get(), DISPATCH_ATTR_SERVICE)
                    .orElse(null);
            targetOperation = ctx.annotations()
                    .attribute(dispatchMirror.get(), DISPATCH_ATTR_OPERATION, String.class)
                    .orElse(null);
        }

        return new RouteModel(
                method,
                matchHeader,
                matchProperty,
                matchValue,
                defaultHandler,
                routeValueType,
                targetService,
                targetOperation);
    }

    // --- Model 4: direct handler ---

    /**
     * Scans a {@code @KafkaListener} class (Model 4 direct handler) and produces a
     * {@link ListenerModel} with kind {@link ListenerModel.Kind#HANDLER}.
     *
     * <p>The value type {@code V} is resolved via
     * {@link dev.vertique.codegen.TypeResolver#resolveTypeArgument} against {@code KafkaRecordHandler}.
     * A {@code null} result (raw type / forwarding variable) is preserved in the model; validators
     * in Slice 4 will report that as a compile error.
     *
     * @param type             the class element
     * @param name             binding name
     * @param topic            Kafka topic
     * @param groupId          consumer group id
     * @param errorStrategy    error strategy constant name
     * @param commitStrategy   commit strategy constant name
     * @param deadLetterTopic  dead-letter topic
     * @param jsonProfile      JSON mapper profile id, or {@code ""} for the framework default
     * @return the scanned handler model
     */
    private ListenerModel scanHandler(
            TypeElement type,
            String name,
            String topic,
            String groupId,
            String errorStrategy,
            String commitStrategy,
            String deadLetterTopic,
            String jsonProfile) {
        TypeMirror handlerValueType = resolveHandlerValueType(type);

        return new ListenerModel(
                type,
                name,
                topic,
                groupId,
                errorStrategy,
                commitStrategy,
                deadLetterTopic,
                jsonProfile,
                ListenerModel.Kind.HANDLER,
                handlerValueType,
                List.of());
    }

    /**
     * Resolves the value type {@code V} from a {@code KafkaRecordHandler<V>} implementation.
     *
     * @param type the implementing class element
     * @return the resolved type mirror, or {@code null} when not resolvable
     */
    private TypeMirror resolveHandlerValueType(TypeElement type) {
        TypeElement handlerElement = ctx.elements().getTypeElement(KafkaCodegenAnnotations.KAFKA_RECORD_HANDLER);
        if (handlerElement == null) {
            return null;
        }
        return ctx.typeResolver()
                .resolveTypeArgument(type.asType(), handlerElement, 0)
                .orElse(null);
    }
}
