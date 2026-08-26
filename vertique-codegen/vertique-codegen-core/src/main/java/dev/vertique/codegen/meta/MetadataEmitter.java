// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.meta;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.WildcardTypeName;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

/**
 * Reusable JavaPoet emitter that, given a method element, generates a {@code MethodMetadata}
 * implementation whose accessors return compile-time constants and never reflect at call time.
 *
 * <p>The generated class implements {@code dev.vertique.core.codegen.MethodMetadata} and carries a
 * nested {@code ParameterMetadataImpl implements dev.vertique.core.codegen.ParameterMetadata} for
 * its parameters. The reflection-free core accessors are baked as constants:
 * <ul>
 *   <li>{@code name()} → a {@link String} literal;
 *   <li>{@code declaringType()} / {@code returnType()} → {@code X.class} literals;
 *   <li>{@code parameterTypes()} → an array of {@code X.class} literals;
 *   <li>{@code parameters()} → {@code List.of(new ParameterMetadataImpl(...))} with the index, name,
 *       and erased type baked as constants.
 * </ul>
 *
 * <p>The neutral SPI types ({@code MethodMetadata}, {@code ParameterMetadata}) live in
 * {@code vertique-core}, which is <strong>not</strong> a compile dependency of this codegen module;
 * they are therefore referenced by raw {@link ClassName} rather than imported, mirroring the way
 * {@link dev.vertique.codegen.dagger.DaggerModuleWriter} references Dagger annotations.
 *
 * <p><strong>Scope note:</strong> the method-level <em>and</em> parameter-level
 * {@code findAnnotation}/{@code hasAnnotation} surfaces are reflection-free and literal-backed (Phase 2
 * slice 2.1). For each runtime-retained method annotation supplied by the caller, the impl bakes a
 * {@code static final <Ann>} literal constant and resolves the method-level {@code findAnnotation} by a
 * {@code type == <Ann>.class} match, with {@code hasAnnotation} delegating to it — never
 * {@link Method#getAnnotation}. Likewise, each parameter's runtime-retained annotations are baked as
 * {@code PARAM_<p>_ANNOTATION_<i>} literal constants and passed to the nested
 * {@code ParameterMetadataImpl}, whose parameter-level {@code findAnnotation} matches the looked-up
 * {@code type} against each literal's {@code annotationType()} — never {@code Parameter.getAnnotation}.
 * The opt-in reflective-accessor group ({@code asMethod}, {@code genericType}) remains stubbed;
 * {@code genericReturnType()} is emitted as a reflection-free {@link Type} graph so generated
 * consumers can inspect declared payload types without a method lookup. The constant-only core
 * accessors are fully real.
 */
public final class MetadataEmitter {

    // --- Neutral SPI ClassName constants (vertique-core; referenced, not imported) ---

    /** {@code dev.vertique.core.codegen.MethodMetadata} */
    private static final ClassName METHOD_METADATA = ClassName.get("dev.vertique.core.codegen", "MethodMetadata");

    /** {@code dev.vertique.core.codegen.ParameterMetadata} */
    private static final ClassName PARAMETER_METADATA = ClassName.get("dev.vertique.core.codegen", "ParameterMetadata");

    /** Simple name of the nested parameter-metadata implementation class. */
    private static final String PARAM_IMPL_SIMPLE_NAME = "ParameterMetadataImpl";

    private MetadataEmitter() {}

    /**
     * A materialized annotation literal for one of the method's runtime-retained annotations, used to
     * back the reflection-free {@code findAnnotation}/{@code hasAnnotation} lookup. The metadata impl
     * bakes a {@code static final <Ann>} constant initialised to
     * {@code new <Ann>$<Namespace>Literal(<args>)} and
     * matches it against the looked-up {@code Class<A>} via {@code type == <Ann>.class}.
     *
     * <p>The caller ({@code AopProxyEmitter}) is responsible for emitting (and per-compilation
     * deduplicating) the {@code <Ann>$<Namespace>Literal} <em>class</em> via
     * {@link AnnotationLiteralEmitter} and
     * for rejecting unsupported attribute kinds before constructing one of these — by the time a
     * {@code AnnotationLiteralRef} reaches this emitter, its literal class is renderable.
     *
     * @param annotationType the annotation interface (e.g. {@code com.example.Marker}); the constant's
     *                       declared type and the {@code type == <Ann>.class} match target
     * @param literalClass   the generated {@code <Ann>$<Namespace>Literal} class instantiated for the constant
     * @param constructorArgs the comma-joined constructor arguments for
     *                        {@code new <Ann>$<Namespace>Literal(...)},
     *                       in member-declaration order (from
     *                       {@link AnnotationLiteralEmitter#constructorArgs})
     */
    public record AnnotationLiteralRef(ClassName annotationType, ClassName literalClass, CodeBlock constructorArgs) {}

    /**
     * Emits a {@code MethodMetadata} implementation for the given method, with no materialized
     * annotation literals (so {@code findAnnotation}/{@code hasAnnotation} resolve nothing).
     *
     * @param method        the method element to capture; must not be {@code null}
     * @param generatedName the fully-qualified name for the generated implementation class; must not
     *                      be {@code null}
     * @param types         the processing-environment {@link Types} utility, used to erase parameter
     *                      and return types; must not be {@code null}
     * @return a {@link JavaFile} containing the generated {@code MethodMetadata} implementation
     */
    public static JavaFile emitMethodMetadata(ExecutableElement method, ClassName generatedName, Types types) {
        List<List<AnnotationLiteralRef>> noParamAnnotations = method.getParameters().stream()
                .map(p -> List.<AnnotationLiteralRef>of())
                .toList();
        TypeSpec.Builder typeBuilder = methodMetadataType(method, generatedName, types, List.of(), noParamAnnotations)
                .addModifiers(Modifier.FINAL);
        return JavaFile.builder(generatedName.packageName(), typeBuilder.build())
                .build();
    }

    /**
     * Builds the {@code MethodMetadata}-implementing {@link TypeSpec.Builder} for the given method,
     * <strong>without</strong> top-level modifiers, so the caller can either emit it as a top-level
     * class (adding {@code final}) or nest it inside another generated type (adding
     * {@code static final}).
     *
     * <p>The {@code int.class} / {@code int[].class} / {@code void.class} type literals in the
     * generated accessors land in whichever file ultimately contains this type — nesting it inside a
     * proxy is what makes those literals appear in the proxy's own source.
     *
     * <p>The {@code methodAnnotations} list backs the reflection-free {@code findAnnotation} /
     * {@code hasAnnotation} surface: one {@code static final <Ann>} literal constant is baked per
     * entry, and {@code findAnnotation(type)} matches {@code type} against each constant's annotation
     * type ({@code type == <Ann>.class}) with no reflection. An empty list yields a metadata impl whose
     * {@code findAnnotation} resolves nothing (returns {@link Optional#empty()}).
     *
     * <p>The {@code parameterAnnotations} list — one inner list per method parameter, in parameter
     * order — backs the nested {@code ParameterMetadataImpl}'s reflection-free parameter-level
     * {@code findAnnotation}/{@code hasAnnotation}: one {@code static final <Ann>} literal constant is
     * baked per (parameter, annotation) entry (named {@code PARAM_<p>_ANNOTATION_<i>}), and each
     * parameter's {@code ParameterMetadataImpl} is constructed with its literals so the lookup matches
     * {@code type} against each literal's {@code annotationType()} with no reflection — exactly
     * mirroring the method-level path.
     *
     * @param method              the method element to capture; must not be {@code null}
     * @param generatedName       the class name (its simple name names the type; its nested
     *                            {@code ParameterMetadataImpl} is derived from it); must not be {@code null}
     * @param types               the {@link Types} utility used to erase parameter and return types; must
     *                            not be {@code null}
     * @param methodAnnotations   the method's runtime-retained annotations, materialized into literals by
     *                            the caller; must not be {@code null} (may be empty)
     * @param parameterAnnotations one materialized-literal list per parameter (parallel to
     *                            {@code method.getParameters()}); must not be {@code null} and must have
     *                            one entry per parameter (each inner list may be empty)
     * @return a {@link TypeSpec.Builder} carrying the constant-only metadata accessors, the
     *         reflection-free annotation-literal lookup, and the nested {@code ParameterMetadataImpl}
     */
    public static TypeSpec.Builder methodMetadataType(
            ExecutableElement method,
            ClassName generatedName,
            Types types,
            List<AnnotationLiteralRef> methodAnnotations,
            List<List<AnnotationLiteralRef>> parameterAnnotations) {
        ClassName paramImpl = generatedName.nestedClass(PARAM_IMPL_SIMPLE_NAME);
        TypeSpec.Builder builder = TypeSpec.classBuilder(generatedName.simpleName())
                .addSuperinterface(METHOD_METADATA)
                .addMethod(nameAccessor(method))
                .addMethod(declaringTypeAccessor(method))
                .addMethod(returnTypeAccessor(method, types));

        // One static final <Ann> literal constant per (parameter, annotation), named
        // PARAM_<p>_ANNOTATION_<i> in declaration order, backing the nested ParameterMetadataImpl's
        // reflection-free parameter-level findAnnotation lookup. The constants live on this outer
        // metadata impl; the private-static nested ParameterMetadataImpl references them through its
        // constructor (see parametersAccessor), one Annotation[] per parameter.
        List<List<String>> paramLiteralFieldNames = new ArrayList<>();
        for (int p = 0; p < parameterAnnotations.size(); p++) {
            List<AnnotationLiteralRef> refs = parameterAnnotations.get(p);
            List<String> fieldNames = new ArrayList<>();
            for (int i = 0; i < refs.size(); i++) {
                AnnotationLiteralRef ref = refs.get(i);
                String fieldName = "PARAM_" + p + "_ANNOTATION_" + i;
                builder.addField(FieldSpec.builder(
                                ref.annotationType(), fieldName, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("new $T($L)", ref.literalClass(), ref.constructorArgs())
                        .build());
                fieldNames.add(fieldName);
            }
            paramLiteralFieldNames.add(fieldNames);
        }

        builder.addMethod(parameterTypesAccessor(method, types))
                .addMethod(parametersAccessor(method, types, paramImpl, paramLiteralFieldNames));

        // One static final <Ann> literal constant per runtime-retained method annotation, named
        // ANNOTATION_<i> in declaration order, backing the reflection-free findAnnotation lookup.
        List<String> literalFieldNames = new ArrayList<>();
        for (int i = 0; i < methodAnnotations.size(); i++) {
            AnnotationLiteralRef ref = methodAnnotations.get(i);
            String fieldName = "ANNOTATION_" + i;
            builder.addField(FieldSpec.builder(
                            ref.annotationType(), fieldName, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .initializer("new $T($L)", ref.literalClass(), ref.constructorArgs())
                    .build());
            literalFieldNames.add(fieldName);
        }

        return builder.addMethod(findAnnotation(methodAnnotations, literalFieldNames))
                .addMethod(hasAnnotation())
                .addMethod(genericReturnTypeAccessor(method, types))
                .addMethod(asMethodStub())
                .addType(parameterMetadataImpl(paramImpl));
    }

    /**
     * Emits a standalone {@code ParameterMetadata} implementation for a single parameter, independent
     * of any enclosing {@code MethodMetadata} wrapper class. Its reflection-free
     * {@code findAnnotation}/{@code hasAnnotation} lookup reuses {@link #findAnnotation} — the same
     * compile-time {@code if (type == <Ann>.class)} matcher {@link #methodMetadataType} bakes for its
     * method-level annotation lookup — and its {@code annotationsLazy()} override reuses
     * {@link #parameterAnnotationsLazy}, the same override the nested {@code ParameterMetadataImpl}
     * uses, so both lookups are byte-for-byte consistent with their existing method-level/nested-case
     * counterparts rather than introducing a third lookup shape.
     *
     * <p>Unlike the nested case — whose index/name/type/annotation literals are read from a
     * {@link VariableElement} at a given parameter-list position and passed into the nested impl's
     * constructor — this method takes those values as direct constant arguments and bakes them as
     * {@code static final} fields with constant-returning accessors, since there is no enclosing
     * metadata class to hold per-parameter constants on. The annotation literal constants are
     * declared directly on this standalone class, named {@code ANNOTATION_<i>} (no {@code PARAM_<p>_}
     * prefix, since there is no enclosing parameter-list index to disambiguate against).
     *
     * @param generatedName the fully-qualified name for the generated implementation class; must not
     *                       be {@code null}
     * @param index          the compile-time-captured parameter index constant
     * @param name           the compile-time-captured parameter name constant (nullable, preserved as-is)
     * @param type           the parameter's erased type mirror; must not be {@code null}
     * @param types          the processing-environment {@link Types} utility, used to erase the type;
     *                       must not be {@code null}
     * @param annotations    this parameter's materialized annotation literals; must not be {@code null}
     *                       (may be empty)
     * @return a {@link JavaFile} containing the generated top-level {@code ParameterMetadata}
     *         implementation
     */
    public static JavaFile emitParameterMetadata(
            ClassName generatedName,
            int index,
            String name,
            TypeMirror type,
            Types types,
            List<AnnotationLiteralRef> annotations) {
        return emitParameterMetadata(generatedName, index, name, type, types, annotations, null);
    }

    /**
     * Emits a standalone {@code ParameterMetadata} implementation with an optional reflective
     * fallback for annotations that could not be materialized into literals.
     *
     * <p>This is the parity-first variant used by {@code vertique-codegen-jaxrs} (ADR-0146). JAX-RS
     * resource parameters routinely carry annotations this literal emitter cannot render — most
     * commonly Swagger's {@code @Parameter}, whose {@code schema} member defaults to a nested
     * {@code @Schema} instance. Rather than silently omit such annotations (which would make codegen
     * routes behave differently from reflectively-scanned routes) or fail the build (too blunt for a
     * ubiquitous doc annotation), the emitter accepts a {@code reflectiveFallbackSupplier}: a
     * {@code CodeBlock} producing a {@code Supplier<Annotation[]>} that returns the <em>full merged
     * effective annotation set</em> for the parameter (the same set the runtime scanner produces via
     * {@code AnnotationResolver.resolveParameterAnnotations}, including superclass/interface
     * declarations). When present:
     * <ul>
     *   <li>{@code findAnnotation}/{@code hasAnnotation} check the literals first (the fast, reflection-free
     *       path), then fall back to scanning the reflective array — so an unsupported-member
     *       annotation is still resolvable;</li>
     *   <li>{@code annotationsLazy()} returns a defensive copy of the <em>reflective</em> array (the
     *       full merged set), not just the literal subset — byte-for-byte parity with the reflective
     *       route.</li>
     * </ul>
     * When {@code reflectiveFallbackSupplier} is {@code null} (the AOP path and jaxrs parameters whose
     * annotations are <em>all</em> materializable) the generated class is purely literal-backed and
     * never reflects.
     *
     * @param generatedName the fully-qualified name for the generated implementation class; must not
     *                       be {@code null}
     * @param index          the compile-time-captured parameter index constant
     * @param name           the compile-time-captured parameter name constant (nullable, preserved as-is)
     * @param type           the parameter's erased type mirror; must not be {@code null}
     * @param types          the processing-environment {@link Types} utility; must not be {@code null}
     * @param annotations    the materialized annotation literals (the subset that could be rendered);
     *                       must not be {@code null} (may be empty)
     * @param reflectiveFallbackSupplier a {@code CodeBlock} producing a {@code Supplier<Annotation[]>}
     *                       of the full merged effective annotation set, or {@code null} for a purely
     *                       literal-backed (reflection-free) impl
     * @return a {@link JavaFile} containing the generated top-level {@code ParameterMetadata}
     *         implementation
     */
    public static JavaFile emitParameterMetadata(
            ClassName generatedName,
            int index,
            String name,
            TypeMirror type,
            Types types,
            List<AnnotationLiteralRef> annotations,
            CodeBlock reflectiveFallbackSupplier) {
        TypeSpec typeSpec = parameterMetadataType(
                generatedName,
                List.of(Modifier.PUBLIC, Modifier.FINAL),
                CodeBlock.of("$L", index),
                name == null ? CodeBlock.of("null") : CodeBlock.of("$S", name),
                erasedTypeName(type, types),
                annotations,
                "ANNOTATION_",
                reflectiveFallbackSupplier);
        return JavaFile.builder(generatedName.packageName(), typeSpec).build();
    }

    // --- MethodMetadata constant-core accessors ---

    /** Emits {@code public String name() { return "<methodName>"; }}. */
    private static MethodSpec nameAccessor(ExecutableElement method) {
        return MethodSpec.methodBuilder("name")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(String.class)
                .addStatement("return $S", method.getSimpleName().toString())
                .build();
    }

    /** Emits {@code public Class<?> declaringType() { return <DeclaringType>.class; }}. */
    private static MethodSpec declaringTypeAccessor(ExecutableElement method) {
        // ClassName.get(TypeElement) is correct for nested classes (renders the enclosing-class
        // chain), whereas ClassName.bestGuess on the flat qualified name cannot tell a package
        // segment from an outer-class segment.
        ClassName declaring = ClassName.get((TypeElement) method.getEnclosingElement());
        return MethodSpec.methodBuilder("declaringType")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(classOfWildcard())
                .addStatement("return $T.class", declaring)
                .build();
    }

    /** Emits {@code public Class<?> returnType() { return <ErasedReturnType>.class; }}. */
    private static MethodSpec returnTypeAccessor(ExecutableElement method, Types types) {
        TypeName returnType = erasedTypeName(method.getReturnType(), types);
        return MethodSpec.methodBuilder("returnType")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(classOfWildcard())
                .addStatement("return $T.class", returnType)
                .build();
    }

    /** Emits {@code public Class<?>[] parameterTypes() { return new Class<?>[] { A.class, ... }; }}. */
    private static MethodSpec parameterTypesAccessor(ExecutableElement method, Types types) {
        CodeBlock literals = method.getParameters().stream()
                .map(p -> CodeBlock.of("$T.class", erasedTypeName(p.asType(), types)))
                .collect(CodeBlock.joining(", "));
        return MethodSpec.methodBuilder("parameterTypes")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ArrayTypeName.of(classOfWildcard()))
                .addStatement("return new $T[] {$L}", classOfWildcard(), literals)
                .build();
    }

    /**
     * Emits {@code public List<ParameterMetadata> parameters() { return List.of(new ...Impl(...)); }}.
     *
     * <p>Each {@code ParameterMetadataImpl} is constructed with its index, name, erased type, and its
     * own materialized annotation literals ({@code PARAM_<p>_ANNOTATION_<i>} constants on the outer
     * impl), so the nested impl's reflection-free parameter-level {@code findAnnotation} resolves
     * against that parameter's own literals.
     *
     * @param method                 the method element whose parameters to render
     * @param types                  the {@link Types} utility for erasing parameter types
     * @param paramImpl              the nested {@code ParameterMetadataImpl} class name
     * @param paramLiteralFieldNames per-parameter literal-constant field names (parallel to the
     *                               parameter list), passed as trailing {@code Annotation...} arguments
     * @return the {@code parameters()} {@link MethodSpec}
     */
    private static MethodSpec parametersAccessor(
            ExecutableElement method, Types types, ClassName paramImpl, List<List<String>> paramLiteralFieldNames) {
        List<? extends VariableElement> params = method.getParameters();
        List<CodeBlock> constructions = new ArrayList<>();
        for (int p = 0; p < params.size(); p++) {
            VariableElement param = params.get(p);
            CodeBlock literalArgs = paramLiteralFieldNames.get(p).stream()
                    .map(name -> CodeBlock.of("$N", name))
                    .collect(CodeBlock.joining(", "));
            CodeBlock trailing = literalArgs.isEmpty() ? CodeBlock.of("") : CodeBlock.of(", $L", literalArgs);
            constructions.add(CodeBlock.of(
                    "new $T($L, $S, $T.class$L)",
                    paramImpl,
                    p,
                    param.getSimpleName().toString(),
                    erasedTypeName(param.asType(), types),
                    trailing));
        }
        return MethodSpec.methodBuilder("parameters")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(ClassName.get(List.class), PARAMETER_METADATA))
                .addStatement("return $T.of($L)", List.class, CodeBlock.join(constructions, ", "))
                .build();
    }

    // --- MethodMetadata annotation lookup (reflection-free, literal-backed) + reflective-group stubs ---

    /**
     * Emits the reflection-free {@code findAnnotation(Class<A>)} for the method-level metadata impl.
     *
     * <p>The body matches the looked-up {@code type} against each materialized literal's annotation
     * type via {@code type == <Ann>.class} — never {@link java.lang.reflect.Method#getAnnotation} — and
     * returns {@code Optional.of((A) <CONSTANT>)} on the first match, else {@link Optional#empty()}. The
     * unchecked cast is safe because the {@code type == <Ann>.class} guard establishes that {@code A}
     * is exactly the constant's annotation type. With no materialized literals the body collapses to a
     * bare {@code return Optional.empty()}.
     *
     * @param methodAnnotations  the materialized annotation literals, parallel to {@code literalFields}
     * @param literalFields      the {@code static final <Ann>} constant field names, in the same order
     * @return the {@code findAnnotation} {@link MethodSpec}
     */
    private static MethodSpec findAnnotation(List<AnnotationLiteralRef> methodAnnotations, List<String> literalFields) {
        var a = com.palantir.javapoet.TypeVariableName.get("A", Annotation.class);
        MethodSpec.Builder method = MethodSpec.methodBuilder("findAnnotation")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .addTypeVariable(a)
                .returns(ParameterizedTypeName.get(ClassName.get(Optional.class), a))
                .addParameter(ParameterizedTypeName.get(ClassName.get(Class.class), a), "type");
        if (!methodAnnotations.isEmpty()) {
            method.addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
                    .addMember("value", "$S", "unchecked")
                    .build());
        }
        for (int i = 0; i < methodAnnotations.size(); i++) {
            ClassName annType = methodAnnotations.get(i).annotationType();
            method.beginControlFlow("if (type == $T.class)", annType)
                    .addStatement("return $T.of((A) $N)", Optional.class, literalFields.get(i))
                    .endControlFlow();
        }
        // Type-witnessed empty fallback (Optional.<A>empty()): semantically an empty Optional, written
        // so the literal-backed lookup is never mistaken for the Optional.empty() stub.
        return method.addStatement("return $T.<A>empty()", Optional.class).build();
    }

    /**
     * Emits the reflection-free {@code hasAnnotation(Class<? extends Annotation>)} for the method-level
     * metadata impl as {@code return findAnnotation(type).isPresent()} — delegating to the
     * literal-backed lookup so the two stay consistent and neither reflects.
     *
     * @return the {@code hasAnnotation} {@link MethodSpec}
     */
    private static MethodSpec hasAnnotation() {
        ParameterizedTypeName classOfAnnotation = ParameterizedTypeName.get(
                ClassName.get(Class.class), WildcardTypeName.subtypeOf(ClassName.get(Annotation.class)));
        return MethodSpec.methodBuilder("hasAnnotation")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(boolean.class)
                .addParameter(classOfAnnotation, "type")
                .addStatement("return findAnnotation(type).isPresent()")
                .build();
    }

    /**
     * Emits a {@code genericReturnType} accessor as a reflection-free {@link Type} graph.
     *
     * <p>Declared type arguments are represented by generated {@link ParameterizedType} instances,
     * while reifiable types use class literals. This keeps generated cache proxies independent of
     * reflective method lookup while still exposing the declared payload type to serializers.
     */
    private static MethodSpec genericReturnTypeAccessor(ExecutableElement method, Types types) {
        return MethodSpec.methodBuilder("genericReturnType")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(Type.class)
                .addStatement("return $L", genericTypeExpression(method.getReturnType(), types))
                .build();
    }

    /**
     * Emits an {@code asMethod} accessor from the reflective-accessor group.
     *
     * <p>Not part of the reflection-free guarantee; throws {@link UnsupportedOperationException}
     * until the reflective-accessor group is implemented (Phase 2).
     */
    private static MethodSpec asMethodStub() {
        return MethodSpec.methodBuilder("asMethod")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(Method.class)
                .addStatement(
                        "throw new $T($S)",
                        UnsupportedOperationException.class,
                        "asMethod is part of the reflective-accessor group (Phase 2)")
                .build();
    }

    // --- Nested ParameterMetadataImpl ---

    /**
     * Emits the nested {@code ParameterMetadataImpl} carrying constant index, name, erased type, and
     * its materialized annotation literals, with the reflective {@code genericType()} accessor stubbed.
     *
     * <p>The constructor accepts a trailing {@code Annotation... annotations} array (the per-parameter
     * {@code <Ann>$<Namespace>Literal} constants emitted on the outer metadata impl). The reflection-free
     * parameter-level {@code findAnnotation} matches the looked-up {@code type} against each literal's
     * {@link Annotation#annotationType()} — never {@code Parameter.getAnnotation} — returning the first
     * match; {@code hasAnnotation} delegates to it so the two stay consistent and neither reflects.
     *
     * @param paramImpl the nested {@code ParameterMetadataImpl} class name
     * @return the nested {@code ParameterMetadataImpl} {@link TypeSpec}
     */
    private static TypeSpec parameterMetadataImpl(ClassName paramImpl) {
        ArrayTypeName annotationArray = ArrayTypeName.of(ClassName.get(Annotation.class));
        return TypeSpec.classBuilder(paramImpl.simpleName())
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .addSuperinterface(PARAMETER_METADATA)
                .addField(int.class, "index", Modifier.PRIVATE, Modifier.FINAL)
                .addField(String.class, "name", Modifier.PRIVATE, Modifier.FINAL)
                .addField(classOfWildcard(), "type", Modifier.PRIVATE, Modifier.FINAL)
                .addField(annotationArray, "annotations", Modifier.PRIVATE, Modifier.FINAL)
                .addMethod(MethodSpec.constructorBuilder()
                        .varargs(true)
                        .addParameter(int.class, "index")
                        .addParameter(String.class, "name")
                        .addParameter(classOfWildcard(), "type")
                        .addParameter(annotationArray, "annotations")
                        .addStatement("this.index = index")
                        .addStatement("this.name = name")
                        .addStatement("this.type = type")
                        .addStatement("this.annotations = annotations")
                        .build())
                .addMethod(MethodSpec.methodBuilder("index")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(int.class)
                        .addStatement("return index")
                        .build())
                .addMethod(MethodSpec.methodBuilder("name")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(String.class)
                        .addStatement("return name")
                        .build())
                .addMethod(MethodSpec.methodBuilder("type")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(classOfWildcard())
                        .addStatement("return type")
                        .build())
                .addMethod(parameterFindAnnotation())
                .addMethod(hasAnnotation())
                .addMethod(parameterAnnotationsLazy(annotationArray))
                .addMethod(MethodSpec.methodBuilder("genericType")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(Type.class)
                        .addStatement(
                                "throw new $T($S)",
                                UnsupportedOperationException.class,
                                "genericType is part of the reflective-accessor group (Phase 2)")
                        .build())
                .build();
    }

    /**
     * Emits a {@code ParameterMetadata}-implementing {@link TypeSpec} with constant
     * {@code index()}/{@code name()}/{@code type()} accessors and a reflection-free, literal-backed
     * {@code findAnnotation}/{@code hasAnnotation}/{@code annotationsLazy()} surface. Currently the sole
     * caller is {@link #emitParameterMetadata} (top-level, standalone); it is factored out as its own
     * method so a future caller needing a differently-modified/named {@code ParameterMetadata} impl
     * (e.g. a nested variant) can reuse the same field/method-emission logic.
     *
     * <p>Unlike {@link #parameterMetadataImpl(ClassName)} (which stores its index/name/type in
     * constructor-assigned fields so a single nested class can be instantiated once per parameter with
     * different values), this variant bakes {@code index}/{@code name}/{@code type} directly as
     * constant-returning accessors — there is exactly one parameter per generated class, so no
     * constructor is needed. The annotation literal constants ({@code <fieldPrefix><i>}) are declared
     * directly on this class and collected into a {@code static final Annotation[] annotations} field
     * via an initializer (rather than a constructor assignment); {@code findAnnotation} reuses
     * {@link #findAnnotation(List, List)} — the same compile-time {@code type == <Ann>.class} matcher
     * used for method-level annotations — and {@code annotationsLazy()} reuses
     * {@link #parameterAnnotationsLazy(ArrayTypeName)}, the same override the nested
     * {@code ParameterMetadataImpl} uses, both reading the same {@code annotations} field name the
     * nested case's instance field shares.
     *
     * @param generatedName    the class name for the generated type; must not be {@code null}
     * @param modifiers        the top-level modifiers for the generated class (e.g.
     *                         {@code PUBLIC, FINAL} for a standalone class)
     * @param indexLiteral     the code block returning the constant {@code index()} value
     * @param nameLiteral      the code block returning the constant {@code name()} value (may render
     *                         {@code null})
     * @param typeLiteral      the erased type name rendered as {@code <TypeLiteral>.class} by
     *                         {@code type()}
     * @param annotations      the materialized annotation literals for this parameter; must not be
     *                         {@code null} (may be empty)
     * @param literalFieldPrefix the prefix used for the per-annotation literal constant field names
     *                         (e.g. {@code "ANNOTATION_"} for the standalone case)
     * @return the {@code ParameterMetadata}-implementing {@link TypeSpec}
     */
    private static TypeSpec parameterMetadataType(
            ClassName generatedName,
            List<Modifier> modifiers,
            CodeBlock indexLiteral,
            CodeBlock nameLiteral,
            TypeName typeLiteral,
            List<AnnotationLiteralRef> annotations,
            String literalFieldPrefix,
            CodeBlock reflectiveFallbackSupplier) {
        ArrayTypeName annotationArray = ArrayTypeName.of(ClassName.get(Annotation.class));

        List<String> literalFieldNames = new ArrayList<>();
        TypeSpec.Builder builder = TypeSpec.classBuilder(generatedName.simpleName())
                .addModifiers(modifiers.toArray(Modifier[]::new))
                .addSuperinterface(PARAMETER_METADATA);
        for (int i = 0; i < annotations.size(); i++) {
            AnnotationLiteralRef ref = annotations.get(i);
            String fieldName = literalFieldPrefix + i;
            builder.addField(FieldSpec.builder(
                            ref.annotationType(), fieldName, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .initializer("new $T($L)", ref.literalClass(), ref.constructorArgs())
                    .build());
            literalFieldNames.add(fieldName);
        }
        CodeBlock annotationsInitializer = literalFieldNames.stream()
                .map(name -> CodeBlock.of("$N", name))
                .collect(CodeBlock.joining(", ", "{", "}"));

        builder.addField(
                FieldSpec.builder(annotationArray, "annotations", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("new $T $L", annotationArray, annotationsInitializer)
                        .build());

        // Optional reflective fallback for annotations that could not be materialized into literals
        // (ADR-0146 parity-first policy). When present, findAnnotation checks literals first then the
        // reflective array, and annotationsLazy returns the full merged reflective set (defensively
        // copied). When null, the impl is purely literal-backed and never reflects.
        boolean hasReflectiveFallback = reflectiveFallbackSupplier != null;
        if (hasReflectiveFallback) {
            builder.addField(FieldSpec.builder(
                            ParameterizedTypeName.get(ClassName.get(Supplier.class), annotationArray),
                            "REFLECTIVE_FALLBACK",
                            Modifier.PRIVATE,
                            Modifier.STATIC,
                            Modifier.FINAL)
                    .initializer(reflectiveFallbackSupplier)
                    .build());
        }

        return builder.addMethod(MethodSpec.methodBuilder("index")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(int.class)
                        .addStatement("return $L", indexLiteral)
                        .build())
                .addMethod(MethodSpec.methodBuilder("name")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(String.class)
                        .addStatement("return $L", nameLiteral)
                        .build())
                .addMethod(MethodSpec.methodBuilder("type")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(classOfWildcard())
                        .addStatement("return $T.class", typeLiteral)
                        .build())
                .addMethod(standaloneFindAnnotation(annotations, literalFieldNames, hasReflectiveFallback))
                .addMethod(hasAnnotation())
                .addMethod(standaloneAnnotationsLazy(annotationArray, hasReflectiveFallback))
                .addMethod(MethodSpec.methodBuilder("genericType")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(Type.class)
                        .addStatement(
                                "throw new $T($S)",
                                UnsupportedOperationException.class,
                                "genericType is part of the reflective-accessor group (Phase 2)")
                        .build())
                .build();
    }

    /**
     * Emits the {@code findAnnotation(Class<A>)} for a standalone {@code ParameterMetadata} impl:
     * matches each materialized literal by {@code type == <Ann>.class} first, then — when a reflective
     * fallback is present — scans {@code REFLECTIVE_FALLBACK.get()} for an annotation whose
     * {@link Annotation#annotationType()} equals {@code type}, so an annotation that could not be
     * materialized into a literal is still resolvable (ADR-0146 parity-first policy). With no literals
     * and no fallback the body collapses to {@code Optional.<A>empty()}.
     *
     * @param annotations          the materialized annotation literals, parallel to {@code literalFields}
     * @param literalFields        the {@code static final <Ann>} constant field names, in the same order
     * @param hasReflectiveFallback whether a {@code REFLECTIVE_FALLBACK} supplier field is present
     * @return the {@code findAnnotation} {@link MethodSpec}
     */
    private static MethodSpec standaloneFindAnnotation(
            List<AnnotationLiteralRef> annotations, List<String> literalFields, boolean hasReflectiveFallback) {
        var a = com.palantir.javapoet.TypeVariableName.get("A", Annotation.class);
        MethodSpec.Builder method = MethodSpec.methodBuilder("findAnnotation")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .addTypeVariable(a)
                .returns(ParameterizedTypeName.get(ClassName.get(Optional.class), a))
                .addParameter(ParameterizedTypeName.get(ClassName.get(Class.class), a), "type");
        if (!annotations.isEmpty() || hasReflectiveFallback) {
            method.addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
                    .addMember("value", "$S", "unchecked")
                    .build());
        }
        for (int i = 0; i < annotations.size(); i++) {
            ClassName annType = annotations.get(i).annotationType();
            method.beginControlFlow("if (type == $T.class)", annType)
                    .addStatement("return $T.of((A) $N)", Optional.class, literalFields.get(i))
                    .endControlFlow();
        }
        if (hasReflectiveFallback) {
            method.beginControlFlow("for ($T annotation : REFLECTIVE_FALLBACK.get())", Annotation.class)
                    .beginControlFlow("if (annotation.annotationType() == type)")
                    .addStatement("return $T.of((A) annotation)", Optional.class)
                    .endControlFlow()
                    .endControlFlow();
        }
        return method.addStatement("return $T.<A>empty()", Optional.class).build();
    }

    /**
     * Emits the {@code annotationsLazy()} override for a standalone {@code ParameterMetadata} impl.
     * Returns a defensive copy on every invocation ({@code annotations.clone()}, or
     * {@code REFLECTIVE_FALLBACK.get().clone()} when a reflective fallback is present — the fallback
     * carries the full merged effective set, so it supersedes the literal subset). The backing array
     * is a per-route singleton passed directly to external {@code ParamConverterProvider}s by
     * {@code ParamConversionResolver}; cloning prevents an external mutation from corrupting it for
     * every subsequent request (ADR-0146).
     *
     * @param annotationArray        the {@code Annotation[]} array type name
     * @param hasReflectiveFallback  whether a {@code REFLECTIVE_FALLBACK} supplier field is present
     * @return the {@code annotationsLazy()} {@link MethodSpec}
     */
    private static MethodSpec standaloneAnnotationsLazy(ArrayTypeName annotationArray, boolean hasReflectiveFallback) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("annotationsLazy")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(ClassName.get(Supplier.class), annotationArray));
        if (hasReflectiveFallback) {
            return method.addStatement("return () -> REFLECTIVE_FALLBACK.get().clone()")
                    .build();
        }
        return method.addStatement("return () -> annotations.clone()").build();
    }

    /**
     * Emits the reflection-free {@code findAnnotation(Class<A>)} for the nested
     * {@code ParameterMetadataImpl}, mirroring the method-level lookup but iterating the parameter's
     * own materialized literal array.
     *
     * <p>The body walks {@code this.annotations} and returns {@code Optional.of((A) a)} for the first
     * literal whose {@link Annotation#annotationType()} equals {@code type} — never
     * {@code Parameter.getAnnotation} — else {@link Optional#empty()}. The unchecked cast is safe
     * because the {@code annotationType() == type} guard establishes that {@code A} is exactly the
     * literal's annotation type.
     *
     * @return the parameter-level {@code findAnnotation} {@link MethodSpec}
     */
    private static MethodSpec parameterFindAnnotation() {
        var a = com.palantir.javapoet.TypeVariableName.get("A", Annotation.class);
        return MethodSpec.methodBuilder("findAnnotation")
                .addAnnotation(Override.class)
                .addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
                        .addMember("value", "$S", "unchecked")
                        .build())
                .addModifiers(Modifier.PUBLIC)
                .addTypeVariable(a)
                .returns(ParameterizedTypeName.get(ClassName.get(Optional.class), a))
                .addParameter(ParameterizedTypeName.get(ClassName.get(Class.class), a), "type")
                .beginControlFlow("for ($T annotation : annotations)", Annotation.class)
                .beginControlFlow("if (annotation.annotationType() == type)")
                .addStatement("return $T.of((A) annotation)", Optional.class)
                .endControlFlow()
                .endControlFlow()
                .addStatement("return $T.<A>empty()", Optional.class)
                .build();
    }

    /**
     * Emits the {@code annotationsLazy()} override for the nested {@code ParameterMetadataImpl},
     * returning a {@link Supplier} over the same {@code annotations} array that backs
     * {@link #parameterFindAnnotation()}.
     *
     * <p>Without this override the nested impl would inherit the SPI default
     * ({@code () -> new Annotation[0]}), which would silently disagree with
     * {@code findAnnotation}/{@code hasAnnotation} — both of which read the real, stored
     * {@code annotations} array. Overriding it keeps the two views consistent for consumers of the
     * opt-in reflective-accessor group (e.g. the JAX-RS parameter-conversion bridge and AOP aspects).
     *
     * <p>Returns a defensive copy on every invocation ({@code annotations.clone()}) rather than the
     * shared array reference. The backing {@code annotations} field is {@code static final} — one
     * instance shared across every request routed through this generated class (the AOP nested
     * {@code ParameterMetadataImpl} is similarly a per-method singleton reused per invocation) — and
     * {@code ParamConversionResolver.resolveJaxRs} passes this array directly into every registered
     * {@code jakarta.ws.rs.ext.ParamConverterProvider}. An external provider that mutates the array
     * it receives (a technically legal {@code Annotation[]} caller, since nothing about the JAX-RS
     * contract forbids it) would otherwise corrupt the shared array for every subsequent request on
     * that route/method. Cloning trades one array allocation per {@code annotationsLazy()} call
     * (already gated to only fire when a JAX-RS provider is registered, per ADR-0142) for eliminating
     * a cross-request data-corruption hazard.
     *
     * @param annotationArray the {@code Annotation[]} array type name, reused from the enclosing
     *                        {@code parameterMetadataImpl} builder
     * @return the {@code annotationsLazy()} {@link MethodSpec}
     */
    private static MethodSpec parameterAnnotationsLazy(ArrayTypeName annotationArray) {
        return MethodSpec.methodBuilder("annotationsLazy")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(ClassName.get(Supplier.class), annotationArray))
                .addStatement("return () -> annotations.clone()")
                .build();
    }

    // --- Type-resolution helpers ---

    /**
     * Resolves the erased {@link TypeName} for a type mirror, rendering a valid {@code X.class}
     * literal for every kind a method signature can carry: declared types, primitives, arrays, and
     * {@code void}.
     *
     * <p>Erases the mirror via {@link Types#erasure(TypeMirror)} first so that a generic type
     * ({@code Future<String>}, {@code List<T>}) collapses to its raw form ({@code Future},
     * {@code List}) — the constant must be a {@code Class} literal, and a parameterized
     * {@code Future<String>.class} is not legal Java. The erased mirror is then rendered as:
     * <ul>
     *   <li>a {@link ClassName} for a declared type, resolved via {@link ClassName#get(TypeElement)}
     *       from the type's element → {@code com.example.Foo.class} (correct for nested classes);
     *   <li>a primitive {@link TypeName} → {@code int.class}, {@code boolean.class}, …;
     *   <li>an {@link com.palantir.javapoet.ArrayTypeName} → {@code int[].class},
     *       {@code java.lang.String[].class};
     *   <li>the {@code void} {@link TypeName} → {@code void.class}.
     * </ul>
     *
     * <p>Primitives, arrays, and {@code void} are delegated to JavaPoet's
     * {@link TypeName#get(TypeMirror)} visitor. This replaces the earlier {@code DeclaredType}-only
     * cast, which threw a {@link ClassCastException} on primitive/array/void return and parameter
     * types (the slice-0.2 watch-item). Erasure preserves array depth, so {@code int[]} erases to
     * {@code int[]} and renders as {@code int[].class}.
     *
     * @param mirror the type mirror to erase and render; must not be {@code null}
     * @param types  the {@link Types} utility used for erasure; must not be {@code null}
     * @return the erased {@link TypeName} suitable for a {@code $T.class} literal
     */
    private static TypeName erasedTypeName(TypeMirror mirror, Types types) {
        TypeMirror erased = types.erasure(mirror);
        // For a declared (class/interface) type, resolve the ClassName directly from its element via
        // ClassName.get(TypeElement) — correct for nested classes (it renders the enclosing-class
        // chain). This arm exists for compatibility with the Mockito-based MetadataEmitterTest,
        // whose thin DeclaredType mocks cannot drive JavaPoet's full TypeName.get(TypeMirror)
        // visitor; collapsing the whole method to that single call NPEs on those mocks.
        // Primitives, arrays, and void are delegated to JavaPoet's visitor, which renders them as the
        // correct {@code int.class} / {@code int[].class} / {@code void.class} literals (the slice-0.2
        // watch-item).
        if (erased instanceof DeclaredType declared && declared.asElement() instanceof TypeElement element) {
            return ClassName.get(element);
        }
        return TypeName.get(erased);
    }

    /** Emits a runtime {@link Type} value corresponding to a compile-time type mirror. */
    private static CodeBlock genericTypeExpression(TypeMirror mirror, Types types) {
        return switch (mirror.getKind()) {
            case ARRAY -> genericArrayOrClassExpression((ArrayType) mirror, types);
            case DECLARED -> declaredTypeExpression((DeclaredType) mirror, types);
            case WILDCARD -> wildcardTypeExpression((javax.lang.model.type.WildcardType) mirror, types);
            case TYPEVAR -> erasedTypeClassExpression(mirror, types);
            case INTERSECTION ->
                genericTypeExpression(
                        ((javax.lang.model.type.IntersectionType) mirror)
                                .getBounds()
                                .get(0),
                        types);
            default -> erasedTypeClassExpression(mirror, types);
        };
    }

    private static CodeBlock declaredTypeExpression(DeclaredType declared, Types types) {
        if (declared.getTypeArguments().isEmpty()) {
            return CodeBlock.of("$T.class", ClassName.get((TypeElement) declared.asElement()));
        }
        CodeBlock arguments = declared.getTypeArguments().stream()
                .map(argument -> genericTypeExpression(argument, types))
                .collect(CodeBlock.joining(", "));
        CodeBlock owner = declared.getEnclosingType().getKind() == TypeKind.NONE
                ? CodeBlock.of("null")
                : genericTypeExpression(declared.getEnclosingType(), types);
        return CodeBlock.builder()
                .add("new $T() {", ParameterizedType.class)
                .add(
                        "\n@Override public $T[] getActualTypeArguments() { return new $T[] {$L}; }",
                        Type.class,
                        Type.class,
                        arguments)
                .add("\n@Override public $T getRawType() { return $T.class; }", Type.class, ClassName.get((TypeElement)
                        declared.asElement()))
                .add("\n@Override public $T getOwnerType() { return $L; }", Type.class, owner)
                .add("\n}")
                .build();
    }

    private static CodeBlock genericArrayOrClassExpression(ArrayType array, Types types) {
        if (isReifiable(array.getComponentType())) {
            return erasedTypeClassExpression(array, types);
        }
        return CodeBlock.builder()
                .add("new $T() {", java.lang.reflect.GenericArrayType.class)
                .add(
                        "\n@Override public $T getGenericComponentType() { return $L; }",
                        Type.class,
                        genericTypeExpression(array.getComponentType(), types))
                .add("\n}")
                .build();
    }

    private static CodeBlock wildcardTypeExpression(javax.lang.model.type.WildcardType wildcard, Types types) {
        CodeBlock upper = wildcard.getExtendsBound() == null
                ? CodeBlock.of("$T.class", Object.class)
                : genericTypeExpression(wildcard.getExtendsBound(), types);
        CodeBlock lower = wildcard.getSuperBound() == null
                ? CodeBlock.of("")
                : genericTypeExpression(wildcard.getSuperBound(), types);
        CodeBlock lowerArray = wildcard.getSuperBound() == null
                ? CodeBlock.of("new $T[0]", Type.class)
                : CodeBlock.of("new $T[] {$L}", Type.class, lower);
        return CodeBlock.builder()
                .add("new $T() {", WildcardType.class)
                .add(
                        "\n@Override public $T[] getUpperBounds() { return new $T[] {$L}; }",
                        Type.class,
                        Type.class,
                        upper)
                .add("\n@Override public $T[] getLowerBounds() { return $L; }", Type.class, lowerArray)
                .add("\n}")
                .build();
    }

    private static CodeBlock erasedTypeClassExpression(TypeMirror mirror, Types types) {
        return CodeBlock.of("$T.class", erasedTypeName(mirror, types));
    }

    private static boolean isReifiable(TypeMirror mirror) {
        return switch (mirror.getKind()) {
            case ARRAY -> isReifiable(((ArrayType) mirror).getComponentType());
            case DECLARED -> ((DeclaredType) mirror).getTypeArguments().isEmpty();
            default -> true;
        };
    }

    /** Returns the {@code Class<?>} type name used uniformly for type accessors. */
    private static ParameterizedTypeName classOfWildcard() {
        return ParameterizedTypeName.get(ClassName.get(Class.class), WildcardTypeName.subtypeOf(Object.class));
    }
}
