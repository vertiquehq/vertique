// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.kafka.processor.emit;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.kafka.processor.scan.KafkaSourceMethodModel;
import dev.vertique.codegen.kafka.processor.scan.KafkaSourceModel;
import dev.vertique.codegen.kafka.processor.scan.ListenerModel;
import dev.vertique.codegen.kafka.processor.scan.RouteModel;
import dev.vertique.codegen.support.Identifiers;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import javax.annotation.processing.Generated;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

/**
 * Emitter that generates {@code {Consumer}_BindingMeta} companion classes for
 * {@link dev.vertique.kafka.KafkaListener @KafkaListener} types and
 * {@link dev.vertique.kafka.KafkaSource @KafkaSource} impl classes.
 *
 * <p>Each generated companion is {@code public final}, cannot be instantiated, and exposes a
 * single field:
 *
 * <pre>{@code
 * public static final List<KafkaBindingMeta> METAS = List.of(new KafkaBindingMeta(...));
 * }</pre>
 *
 * <p>For Model 3 routers ({@link ListenerModel.Kind#ROUTER}): the meta carries {@code null}
 * valueType and targetOperation, and a non-empty {@code KafkaBindingMeta.RouteMeta} list with one
 * entry per {@code @KafkaHandler} method.
 *
 * <p>For Model 4 direct handlers ({@link ListenerModel.Kind#HANDLER}): the meta carries the
 * resolved {@code V} from {@code KafkaRecordHandler<V>} as valueType, {@code null}
 * targetOperation, and an empty routes list.
 *
 * <p>For dual-annotated types (a type is both a {@code @KafkaListener} direct handler and carries
 * {@code @KafkaSource} methods), a single companion is emitted whose {@code METAS} list is the
 * union of the listener metas and the source metas. Use
 * {@link #emit(ListenerModel, KafkaSourceModel)} for this case.
 *
 * <p>The generated class lands in {@code ctx.packageNameOf(origin)} (never the
 * {@code -Avertique.codegen.package} override) so downstream loaders can resolve it by name.
 */
public final class BindingMetaEmitter {

    private static final String PROCESSOR_FQN = "dev.vertique.codegen.kafka.processor.KafkaConsumerProcessor";
    private static final String SUFFIX = "_BindingMeta";

    // --- ClassName constants for Kafka runtime types (referenced by JavaPoet, not imported) ---

    private static final String KAFKA_PKG = "dev.vertique.kafka";

    private static final ClassName KAFKA_BINDING_META = ClassName.get(KAFKA_PKG, "KafkaBindingMeta");
    private static final ClassName ROUTE_META = ClassName.get(KAFKA_PKG, "KafkaBindingMeta", "RouteMeta");
    private static final ClassName KIND = ClassName.get(KAFKA_PKG, "KafkaBindingMeta", "Kind");
    private static final ClassName ERROR_STRATEGY = ClassName.get(KAFKA_PKG, "ErrorStrategy");
    private static final ClassName COMMIT_STRATEGY = ClassName.get(KAFKA_PKG, "CommitStrategy");

    private static final ParameterizedTypeName LIST_OF_META =
            ParameterizedTypeName.get(ClassName.get(List.class), KAFKA_BINDING_META);

    private final CodegenContext ctx;

    /**
     * Constructs an emitter bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public BindingMetaEmitter(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Emits the {@code {ImplClass}_BindingMeta} companion for a service-implementation class that
     * carries one or more {@link dev.vertique.kafka.KafkaSource @KafkaSource} methods.
     *
     * <p>The generated companion's {@code METAS} list has one {@link dev.vertique.kafka.KafkaBindingMeta
     * KafkaBindingMeta} entry per method model in {@code model}. Each entry is a
     * {@link dev.vertique.kafka.KafkaBindingMeta.Kind#SOURCE SOURCE} meta with
     * {@code valueType = null} — the runtime loader resolves the payload type from the
     * {@code ServiceMethodMeta} at boot using {@link KafkaSourceMethodModel#targetOperation()}.
     *
     * @param model the {@code @KafkaSource} model to emit; must have at least one method entry
     */
    public void emit(KafkaSourceModel model) {
        writeCompanion(model.implType(), buildSourceMetasField(model));
    }

    /**
     * Emits the {@code {Consumer}_BindingMeta} companion for the given scanned listener model.
     *
     * @param model the listener model to emit; must not be {@code null}
     */
    public void emit(ListenerModel model) {
        writeCompanion(model.originType(), buildMetasField(model));
    }

    /**
     * Emits a single {@code {Origin}_BindingMeta} companion whose {@code METAS} list is the union
     * of the listener metas (ROUTER or HANDLER entries) and the {@code @KafkaSource} SOURCE metas.
     *
     * <p>This overload is used when the same origin type carries both a {@code @KafkaListener}
     * annotation (making it a Model 4 direct handler, since interfaces cannot have
     * {@code @KafkaSource} methods) and one or more {@code @KafkaSource} methods. Emitting a single
     * companion ensures that the runtime {@code scanKafkaSources} path finds the SOURCE metas
     * alongside the HANDLER meta, rather than having them silently dropped by the per-origin
     * duplicate-write guard.
     *
     * <p>Either argument may be {@code null}, but at least one must be non-{@code null}. When only
     * one is non-{@code null} this method behaves identically to the single-argument overloads.
     *
     * @param listenerModel the listener model to include; may be {@code null} if none
     * @param sourceModel   the {@code @KafkaSource} model to include; may be {@code null} if none
     * @throws IllegalArgumentException if both arguments are {@code null}
     */
    public void emit(ListenerModel listenerModel, KafkaSourceModel sourceModel) {
        if (listenerModel == null && sourceModel == null) {
            throw new IllegalArgumentException("At least one of listenerModel or sourceModel must be non-null");
        }
        if (listenerModel == null) {
            emit(sourceModel);
            return;
        }
        if (sourceModel == null) {
            emit(listenerModel);
            return;
        }
        // Both present — aggregate into a single companion with a union METAS list.
        FieldSpec combinedField = buildCombinedMetasField(listenerModel, sourceModel);
        writeCompanion(listenerModel.originType(), combinedField);
    }

    // --- Shared companion-writing scaffold ---

    /**
     * Writes the {@code {Origin}_BindingMeta} companion class to the filer. Both
     * {@link #emit(KafkaSourceModel)} and {@link #emit(ListenerModel)} delegate here after building
     * their respective {@code METAS} field spec.
     *
     * @param origin     the annotated type element that owns the companion
     * @param metasField the {@code public static final List<KafkaBindingMeta> METAS} field spec
     */
    private void writeCompanion(TypeElement origin, FieldSpec metasField) {
        String packageName = ctx.packageNameOf(origin);
        String generatedSimpleName = Identifiers.generatedClassName(origin, SUFFIX);

        TypeSpec typeSpec = TypeSpec.classBuilder(generatedSimpleName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addAnnotation(AnnotationSpec.builder(Generated.class)
                        .addMember("value", "$S", PROCESSOR_FQN)
                        .build())
                .addJavadoc(
                        "Generated binding metadata companion for {@link $T}.\n\n"
                                + "<p>Provides precomputed {@link $T} instances that allow\n"
                                + "{@code KafkaConsumerRegistrar} to skip boot-time reflective scanning.\n",
                        ClassName.get(origin),
                        KAFKA_BINDING_META)
                .addMethod(MethodSpec.constructorBuilder()
                        .addModifiers(Modifier.PRIVATE)
                        .addJavadoc("Utility class — not instantiable.\n")
                        .build())
                .addField(metasField)
                .build();

        JavaFile javaFile = JavaFile.builder(packageName, typeSpec).build();
        try {
            javaFile.writeTo(ctx.filer());
        } catch (IOException e) {
            ctx.diagnostics()
                    .error(
                            origin,
                            "Failed to write generated source file '%s.%s': %s",
                            packageName,
                            generatedSimpleName,
                            e.getMessage());
        }
    }

    // --- Field builders ---

    /**
     * Builds the {@code public static final List<KafkaBindingMeta> METAS} field for a
     * {@code @KafkaSource} model. Each method in {@code model} produces one SOURCE meta entry.
     *
     * @param model the {@code @KafkaSource} model
     * @return the field spec
     */
    private FieldSpec buildSourceMetasField(KafkaSourceModel model) {
        CodeBlock initializer = listOf(model.methods(), this::buildKafkaSourceMetaEntry);
        return FieldSpec.builder(LIST_OF_META, "METAS")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .addJavadoc("Precomputed binding metadata for this consumer.\n")
                .initializer(initializer)
                .build();
    }

    /**
     * Builds the {@code public static final List<KafkaBindingMeta> METAS} field.
     *
     * @param model the listener model
     * @return the field spec
     */
    private FieldSpec buildMetasField(ListenerModel model) {
        CodeBlock initializer = model.kind() == ListenerModel.Kind.ROUTER
                ? buildRouterMetaInitializer(model)
                : buildBindingMetaInitializer(model);

        return FieldSpec.builder(LIST_OF_META, "METAS")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .addJavadoc("Precomputed binding metadata for this consumer.\n")
                .initializer(initializer)
                .build();
    }

    // --- Combined (dual-annotation) field builder ---

    /**
     * Builds the {@code public static final List<KafkaBindingMeta> METAS} field containing both
     * the listener metas (ROUTER or HANDLER) and the SOURCE metas from {@code sourceModel}.
     *
     * <p>The combined initializer is a single {@code List.of(meta1, meta2, ...)} call whose
     * arguments are: first the listener meta entries (one for ROUTER, one for HANDLER), then the
     * SOURCE meta entries (one per {@code @KafkaSource} method).
     *
     * @param listenerModel the listener model; must not be {@code null}
     * @param sourceModel   the source model; must not be {@code null}
     * @return the combined field spec
     */
    private FieldSpec buildCombinedMetasField(ListenerModel listenerModel, KafkaSourceModel sourceModel) {
        // Build individual code blocks for each entry in the union.
        List<CodeBlock> entries = new ArrayList<>();

        // Add the listener meta entry (ROUTER or HANDLER)
        if (listenerModel.kind() == ListenerModel.Kind.ROUTER) {
            entries.add(buildRouterMetaEntry(listenerModel));
        } else {
            entries.add(buildHandlerMetaEntry(listenerModel));
        }

        // Add one SOURCE entry per @KafkaSource method
        for (KafkaSourceMethodModel m : sourceModel.methods()) {
            entries.add(buildKafkaSourceMetaEntry(m));
        }

        CodeBlock initializer = listOf(entries, entry -> entry);
        return FieldSpec.builder(LIST_OF_META, "METAS")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .addJavadoc("Precomputed binding metadata for this consumer.\n")
                .initializer(initializer)
                .build();
    }

    // --- List.of(...) builder helper ---

    /**
     * Builds a {@code List.of(\n item1,\n item2\n)} {@link CodeBlock} from a list of items,
     * delegating per-element rendering to the provided function.
     *
     * <p>The resulting block always uses the multi-line form even for a single element, which keeps
     * generated source consistent regardless of list size.
     *
     * @param <T>    the item type
     * @param items  the list of items to render; must not be {@code null}
     * @param render function that converts one item to a {@link CodeBlock}; must not be {@code null}
     * @return the complete {@code List.of(...)} initializer block
     */
    private <T> CodeBlock listOf(List<T> items, Function<T, CodeBlock> render) {
        CodeBlock.Builder builder = CodeBlock.builder().add("$T.of(\n", List.class);
        for (int i = 0; i < items.size(); i++) {
            builder.add("    $L", render.apply(items.get(i)));
            if (i < items.size() - 1) {
                builder.add(",\n");
            } else {
                builder.add("\n");
            }
        }
        builder.add(")");
        return builder.build();
    }

    // --- @KafkaSource meta entry ---

    /**
     * Builds a single {@code new KafkaBindingMeta(...)} expression for one {@code @KafkaSource}
     * method.
     *
     * <p>{@code valueType} is emitted as {@code null} — resolved at runtime from
     * {@code ServiceMethodMeta}. {@code targetOperation} is the impl method name so the runtime
     * loader can locate the {@code ServiceMethodMeta} by name.
     *
     * @param m the method model
     * @return the code block for one {@code KafkaBindingMeta} constructor call
     */
    private CodeBlock buildKafkaSourceMetaEntry(KafkaSourceMethodModel m) {
        return CodeBlock.builder()
                .add("new $T(\n", KAFKA_BINDING_META)
                .add("    $S,\n", m.name())
                .add("    $S,\n", m.topic())
                .add("    $S,\n", m.groupId())
                .add("    $T.SOURCE,\n", KIND)
                .add("    null,\n") // valueType — resolved at runtime from ServiceMethodMeta
                .add("    $T.$L,\n", ERROR_STRATEGY, m.errorStrategy())
                .add("    $T.$L,\n", COMMIT_STRATEGY, m.commitStrategy())
                .add("    $S,\n", m.deadLetterTopic())
                .add("    null,\n") // jsonProfile — @KafkaSource has no profile (reflective parity)
                .add("    $S,\n", m.targetOperation()) // impl method name for ServiceMethodMeta lookup
                .add("    $T.of()\n", List.class) // SOURCE has no routes
                .add(")")
                .build();
    }

    // --- ROUTER initializer ---

    /**
     * Builds the {@code new KafkaBindingMeta(...)} entry for a Model 3 router, without wrapping
     * in {@code List.of(...)}. Used by the combined-metas builder.
     *
     * @param model the router listener model
     * @return the {@code new KafkaBindingMeta(...)} code block
     */
    private CodeBlock buildRouterMetaEntry(ListenerModel model) {
        CodeBlock routesBlock = buildRoutesBlock(model.routes());
        return CodeBlock.builder()
                .add("new $T(\n", KAFKA_BINDING_META)
                .add("    $S,\n", model.name())
                .add("    $S,\n", model.topic())
                .add("    $S,\n", model.groupId())
                .add("    $T.ROUTER,\n", KIND)
                .add("    null,\n") // valueType = null for ROUTER
                .add("    $T.$L,\n", ERROR_STRATEGY, model.errorStrategy())
                .add("    $T.$L,\n", COMMIT_STRATEGY, model.commitStrategy())
                .add("    $S,\n", model.deadLetterTopic())
                .add("    $L,\n", buildJsonProfileBlock(model.jsonProfile()))
                .add("    null,\n") // targetOperation = null for ROUTER
                .add("    $L\n", routesBlock)
                .add(")")
                .build();
    }

    /**
     * Builds the {@code List.of(new KafkaBindingMeta(...))} initializer for a Model 3 router.
     *
     * @param model the router listener model
     * @return the code block
     */
    private CodeBlock buildRouterMetaInitializer(ListenerModel model) {
        CodeBlock entry = buildRouterMetaEntry(model);
        return CodeBlock.builder().add("$T.of(\n    $L\n)", List.class, entry).build();
    }

    /**
     * Builds the {@code List.of(new KafkaBindingMeta.RouteMeta(...))} block for the routes list.
     *
     * @param routes the route models
     * @return the code block representing the routes list
     */
    private CodeBlock buildRoutesBlock(List<RouteModel> routes) {
        if (routes.isEmpty()) {
            return CodeBlock.of("$T.of()", List.class);
        }
        return listOf(routes, this::buildRouteMetaBlock);
    }

    /**
     * Builds a single {@code new KafkaBindingMeta.RouteMeta(...)} expression.
     *
     * @param route the route model
     * @return the code block for one route meta constructor call
     */
    private CodeBlock buildRouteMetaBlock(RouteModel route) {
        TypeName routeValueTypeName = resolveRouteValueTypeName(route.routeValueType());
        CodeBlock targetServiceBlock = buildTargetServiceBlock(route.targetService());
        CodeBlock targetOperationBlock = buildTargetOperationBlock(route.targetOperation());

        return CodeBlock.builder()
                .add("new $T(\n", ROUTE_META)
                .add("        $S,\n", route.matchHeader())
                .add("        $S,\n", route.matchProperty())
                .add("        $S,\n", route.matchValue())
                .add("        $L,\n", route.defaultHandler())
                .add("        $L,\n", routeValueTypeName + ".class")
                .add("        $L,\n", targetServiceBlock)
                .add("        $L\n", targetOperationBlock)
                .add("    )")
                .build();
    }

    // --- HANDLER initializer ---

    /**
     * Builds the {@code new KafkaBindingMeta(...)} entry for a Model 4 handler, without wrapping
     * in {@code List.of(...)}. Used by the combined-metas builder.
     *
     * <p>Emits {@link dev.vertique.kafka.KafkaBindingMeta.Kind#HANDLER} so the runtime loader can
     * distinguish a direct-handler entry from a {@link dev.vertique.kafka.KafkaBindingMeta.Kind#SOURCE}
     * entry when both kinds appear in the same companion.
     *
     * @param model the handler listener model
     * @return the {@code new KafkaBindingMeta(...)} code block
     */
    private CodeBlock buildHandlerMetaEntry(ListenerModel model) {
        TypeName valueTypeName = resolveHandlerValueTypeName(model.handlerValueType());
        return CodeBlock.builder()
                .add("new $T(\n", KAFKA_BINDING_META)
                .add("    $S,\n", model.name())
                .add("    $S,\n", model.topic())
                .add("    $S,\n", model.groupId())
                .add("    $T.HANDLER,\n", KIND)
                .add("    $L,\n", valueTypeName + ".class")
                .add("    $T.$L,\n", ERROR_STRATEGY, model.errorStrategy())
                .add("    $T.$L,\n", COMMIT_STRATEGY, model.commitStrategy())
                .add("    $S,\n", model.deadLetterTopic())
                .add("    $L,\n", buildJsonProfileBlock(model.jsonProfile()))
                .add("    null,\n") // targetOperation = null (custom handler, no @DispatchTo)
                .add("    $T.of()\n", List.class)
                .add(")")
                .build();
    }

    /**
     * Builds the {@code List.of(new KafkaBindingMeta(...))} initializer for a Model 4 handler.
     *
     * @param model the handler listener model
     * @return the code block
     */
    private CodeBlock buildBindingMetaInitializer(ListenerModel model) {
        CodeBlock entry = buildHandlerMetaEntry(model);
        return CodeBlock.builder().add("$T.of(\n    $L\n)", List.class, entry).build();
    }

    // --- Type name helpers ---

    /**
     * Resolves an <em>erased</em> {@link TypeName} for a route's payload type, suitable for use in
     * a {@code .class} literal. Returns {@code Void} when {@code typeMirror} is {@code null} or
     * resolves to {@code Void}.
     *
     * <p>Erasure is mandatory here: a parameterized type such as {@code Envelope<OrderEvent>} would
     * otherwise produce {@code Envelope<OrderEvent>.class}, which does not compile. The runtime
     * {@code KafkaBindingMeta} holds an erased {@code Class<?>}, so emitting the erased type is both
     * correct and necessary.
     *
     * @param typeMirror the route value type mirror, or {@code null}
     * @return the non-null erased type name to use in the generated class literal
     */
    private TypeName resolveRouteValueTypeName(TypeMirror typeMirror) {
        if (typeMirror == null) {
            return ClassName.get(Void.class);
        }
        TypeMirror erased = ctx.types().erasure(typeMirror);
        TypeName name = TypeName.get(erased);
        if (name.equals(ClassName.get(Void.class)) || name.equals(TypeName.VOID)) {
            return ClassName.get(Void.class);
        }
        return name;
    }

    /**
     * Resolves an <em>erased</em> {@link TypeName} for a Model 4 handler's value type, suitable
     * for use in a {@code .class} literal. Returns {@code Void} when {@code typeMirror} is
     * {@code null}.
     *
     * <p>Erasure is mandatory: a parameterized type such as {@code Envelope<OrderEvent>} would
     * produce {@code Envelope<OrderEvent>.class}, which does not compile. The runtime
     * {@code KafkaBindingMeta} holds an erased {@code Class<?>}, so the erased form is correct.
     *
     * @param typeMirror the handler value type mirror, or {@code null}
     * @return the non-null erased type name
     */
    private TypeName resolveHandlerValueTypeName(TypeMirror typeMirror) {
        if (typeMirror == null) {
            return ClassName.get(Void.class);
        }
        return TypeName.get(ctx.types().erasure(typeMirror));
    }

    /**
     * Returns a {@link CodeBlock} for the targetService class literal, or {@code null} when no
     * {@code @DispatchTo} is present.
     *
     * <p>The erased form is used to produce a valid {@code .class} literal — a parameterized service
     * type such as {@code MyService<T>} must not appear in a class literal.
     *
     * @param targetServiceType the targetService type mirror, or {@code null}
     * @return {@code null} literal or {@code SomeClass.class} code block
     */
    private CodeBlock buildTargetServiceBlock(TypeMirror targetServiceType) {
        if (targetServiceType == null) {
            return CodeBlock.of("null");
        }
        TypeName name = TypeName.get(ctx.types().erasure(targetServiceType));
        return CodeBlock.of("$L.class", name);
    }

    /**
     * Returns a {@link CodeBlock} for the targetOperation string literal, or {@code null} when
     * absent.
     *
     * @param targetOperation the operation string, or {@code null}
     * @return {@code null} or {@code "op"} code block
     */
    private CodeBlock buildTargetOperationBlock(String targetOperation) {
        if (targetOperation == null) {
            return CodeBlock.of("null");
        }
        return CodeBlock.of("$S", targetOperation);
    }

    /**
     * Returns a {@link CodeBlock} for the {@code jsonProfile} argument: {@code null} when the
     * {@code @JsonProfile} value is {@code null} or blank (the framework
     * default), or the string literal otherwise.
     *
     * <p>Emitting {@code null} for a blank profile keeps the generated {@code KafkaBindingMeta} in
     * parity with the reflective consumer path, where {@code ResolvedKafkaConsumerConfig.resolve}
     * treats {@code null} and blank identically and falls back to the {@code vertx} default.
     *
     * @param jsonProfile the profile id from {@code @JsonProfile}, or
     *     {@code ""}/{@code null} for the default
     * @return {@code null} or {@code "profile"} code block
     */
    private CodeBlock buildJsonProfileBlock(String jsonProfile) {
        if (jsonProfile == null || jsonProfile.isBlank()) {
            return CodeBlock.of("null");
        }
        return CodeBlock.of("$S", jsonProfile);
    }
}
