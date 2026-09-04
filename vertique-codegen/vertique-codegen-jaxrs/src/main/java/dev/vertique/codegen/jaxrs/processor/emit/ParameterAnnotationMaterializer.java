// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs.processor.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.jaxrs.EffectiveParamContract;
import dev.vertique.codegen.meta.AnnotationLiteralEmitter;
import dev.vertique.codegen.meta.MetadataEmitter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;

/**
 * Shared logic for materializing a JAX-RS parameter's runtime-retained annotations into a
 * literal-backed {@code dev.vertique.core.codegen.ParameterMetadata} implementation, used identically
 * by {@link ExecutionPlanEmitter} and {@link JaxRsDescriptorEmitter} (ADR-0146).
 *
 * <p><strong>Parity-first policy.</strong> Codegen is a performance optimization, not a feature gate:
 * adding or removing the annotation processor must not change runtime behavior. Each parameter's
 * annotations are therefore materialized per-parameter as follows:
 * <ul>
 *   <li>every legal Java annotation member shape that {@code AnnotationLiteralEmitter} can render is
 *       baked into a compile-time {@code <Ann>$JaxRsLiteral} constant (the reflection-free fast path);
 *       this includes {@code char}, floating-point, nested-annotation, and array members;</li>
 *   <li>if a future or otherwise non-renderable annotation shape is reported by the retained
 *       compatibility hook, the generated metadata additionally carries a <em>reflective fallback</em>:
 *       a {@code Supplier<Annotation[]>} producing the full merged effective annotation set via
 *       {@code GeneratedJaxRsReflectiveAnnotations.mergedParameterAnnotations(...)}. The generated
 *       {@code findAnnotation} checks the literals first, then the fallback; {@code annotationsLazy()}
 *       returns the full merged set. Neither silently omits nor fails the build.</li>
 * </ul>
 *
 * <p>The annotation set is unioned across
 * {@link EffectiveParamContract#annotationSources()} (concrete parameter + superclass/interface
 * override parameters, concrete-first precedence), mirroring the runtime
 * {@code AnnotationResolver.resolveParameterAnnotations} merge; and the reflective fallback resolves
 * the <em>same</em> merged set at runtime, so an annotation-sensitive
 * {@code jakarta.ws.rs.ext.ParamConverterProvider} sees byte-for-byte identical annotations on a
 * codegen route and a reflectively-scanned route.
 */
final class ParameterAnnotationMaterializer {

    private static final String GENERATOR_NAMESPACE = "JaxRs";

    private static final ClassName REFLECTIVE_ANNOTATIONS =
            ClassName.get("dev.vertique.rest.jaxrs.runtime", "GeneratedJaxRsReflectiveAnnotations");

    private final CodegenContext ctx;

    /**
     * Creates a materializer bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    ParameterAnnotationMaterializer(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Materializes {@code pc}'s annotations into a standalone {@code ParameterMetadata} implementation
     * named {@code generatedName}, writing it (and any newly required {@code <Ann>$JaxRsLiteral} classes,
     * deduplicated via {@code emittedLiteralFqns}) through {@code writer}, and returns a
     * {@code new <generatedName>()} expression for embedding in the caller's {@code ParamMeta}
     * constructor.
     *
     * @param pc                   the parameter contract (its {@code annotationSources()} supply the
     *                             merged annotation mirrors; its declaring method + index seed the
     *                             reflective fallback)
     * @param method               the enclosing concrete method (source of the declaring class,
     *                             method name, and erased parameter-type FQNs for the reflective
     *                             fallback lookup)
     * @param paramIndex           the zero-based parameter index
     * @param generatedName        the {@link ClassName} for the generated standalone impl
     * @param emittedLiteralFqns   the per-round shared dedup set of {@code <Ann>$JaxRsLiteral} FQNs
     * @param writer               sink for generated {@link com.palantir.javapoet.JavaFile}s (each
     *                             emitter routes {@code IOException} to its own diagnostic)
     * @return a {@code new <generatedName>()} {@link CodeBlock} expression
     */
    CodeBlock materialize(
            EffectiveParamContract pc,
            ExecutableElement method,
            int paramIndex,
            ClassName generatedName,
            Set<String> emittedLiteralFqns,
            Consumer<com.palantir.javapoet.JavaFile> writer) {
        boolean[] hasUnsupported = {false};
        List<MetadataEmitter.AnnotationLiteralRef> refs =
                materializeLiterals(pc.annotationSources(), emittedLiteralFqns, writer, hasUnsupported);

        // Parity-first: only wire a reflective fallback when at least one runtime-retained annotation
        // could not be rendered into a literal. When every annotation is materializable, the generated
        // metadata stays purely literal-backed and never reflects.
        CodeBlock fallback = hasUnsupported[0] ? reflectiveFallbackSupplier(generatedName, method, paramIndex) : null;

        writer.accept(MetadataEmitter.emitParameterMetadata(
                generatedName, paramIndex, pc.name(), pc.type(), ctx.types(), refs, fallback));

        return CodeBlock.of("new $T()", generatedName);
    }

    /**
     * Materializes the union of runtime-retained annotations across {@code annotationSources} (by
     * annotation type, concrete-first precedence) into literal refs, writing each distinct
     * {@code <Ann>$JaxRsLiteral} class once. An annotation reported by the retained compatibility hook
     * is skipped from the literal set and flips {@code hasUnsupported[0]} so the caller wires a
     * reflective fallback.
     *
     * <p><strong>Same-type / differing-member parity.</strong> This literal fast path dedups by
     * annotation <em>type</em> FQN (first-wins), but the runtime reflective merge
     * ({@code AnnotationResolver.resolveParameterAnnotations}) dedups by {@code LinkedHashSet}
     * <em>equality</em> (type + member values). When the same runtime-retained annotation type appears
     * on both the concrete parameter and a superclass/interface override with <em>different</em> member
     * values, the reflective path keeps BOTH instances but a pure type-dedup would collapse them to one.
     * To stay byte-for-byte in parity without re-implementing equality-dedup in the literal emitter,
     * a duplicate runtime-retained annotation type forces the parameter onto the reflective fallback
     * (via {@code hasUnsupported[0]}), which resolves the exact reflective set. The literal fast path is
     * thus taken only when every annotation type on the parameter is distinct, where type-dedup and
     * equality-dedup cannot diverge.
     *
     * @param annotationSources  the parameter elements to merge annotations from (precedence order)
     * @param emittedLiteralFqns the per-round shared dedup set
     * @param writer             sink for emitted {@code <Ann>$JaxRsLiteral} classes
     * @param hasUnsupported     single-element flag set to {@code true} when any runtime-retained
     *                           annotation could not be materialized, or when a runtime-retained
     *                           annotation type appears more than once (forcing the reflective fallback)
     * @return the materialized literal refs, one per distinct materializable annotation type
     */
    private List<MetadataEmitter.AnnotationLiteralRef> materializeLiterals(
            List<VariableElement> annotationSources,
            Set<String> emittedLiteralFqns,
            Consumer<com.palantir.javapoet.JavaFile> writer,
            boolean[] hasUnsupported) {
        List<MetadataEmitter.AnnotationLiteralRef> refs = new ArrayList<>();
        Set<String> seenAnnotationTypeFqns = new LinkedHashSet<>();
        for (VariableElement source : annotationSources) {
            for (AnnotationMirror mirror : source.getAnnotationMirrors()) {
                TypeElement annType = (TypeElement) mirror.getAnnotationType().asElement();
                String annTypeFqn = annType.getQualifiedName().toString();
                if (!seenAnnotationTypeFqns.add(annTypeFqn)) {
                    // Already handled from an earlier, higher-precedence source. If this repeated type
                    // is runtime-retained, the reflective merge may keep multiple instances with
                    // differing member values (equality-dedup) where the literal type-dedup would keep
                    // only one — force the reflective fallback to preserve exact parity.
                    if (isRuntimeRetained(annType)) {
                        hasUnsupported[0] = true;
                    }
                    continue;
                }
                if (!isRuntimeRetained(annType)) {
                    continue;
                }
                if (AnnotationLiteralEmitter.firstUnsupportedAttribute(annType).isPresent()) {
                    // Cannot render this annotation into a literal — the reflective fallback will
                    // supply it at runtime (parity-first).
                    hasUnsupported[0] = true;
                    continue;
                }
                ClassName literalClass =
                        AnnotationLiteralEmitter.literalClassName(annType, ctx.elements(), GENERATOR_NAMESPACE);
                if (emittedLiteralFqns.add(literalClass.canonicalName())) {
                    writer.accept(AnnotationLiteralEmitter.emit(
                            annType, mirror, ctx.elements(), ctx.types(), GENERATOR_NAMESPACE));
                }
                CodeBlock args = AnnotationLiteralEmitter.constructorArgs(mirror, ctx.elements(), ctx.types());
                refs.add(new MetadataEmitter.AnnotationLiteralRef(ClassName.get(annType), literalClass, args));
            }
        }
        return refs;
    }

    /**
     * Builds the {@code Supplier<Annotation[]>} code block that lazily resolves the full merged
     * effective annotation set for the parameter via
     * {@code GeneratedJaxRsReflectiveAnnotations.mergedParameterAnnotations(declaringClassFqn,
     * methodName, {paramTypeFqns...}, paramIndex)}.
     *
     * <p>The generated metadata class ({@code generatedName}) passes itself as the {@code loaderSource}
     * class token, so the helper resolves the declaring class and parameter types with the loader that
     * definitely sees the resource (they are co-located), not the thread context classloader.
     *
     * @param generatedName the generated metadata class, passed as the reflective helper's
     *                      {@code loaderSource} class token
     * @param method        the enclosing concrete method
     * @param paramIndex    the parameter index
     * @return the fallback supplier {@link CodeBlock}
     */
    private CodeBlock reflectiveFallbackSupplier(ClassName generatedName, ExecutableElement method, int paramIndex) {
        String declaringClassFqn = TypeMirrorFqn.binaryName((TypeElement) method.getEnclosingElement(), ctx);
        String methodName = method.getSimpleName().toString();
        CodeBlock paramTypeFqns = method.getParameters().stream()
                .map(p -> CodeBlock.of("$S", TypeMirrorFqn.erasedFqn(p.asType(), ctx)))
                .collect(CodeBlock.joining(", "));
        return CodeBlock.of(
                "() -> $T.mergedParameterAnnotations($T.class, $S, $S, new $T[] {$L}, $L)",
                REFLECTIVE_ANNOTATIONS,
                generatedName,
                declaringClassFqn,
                methodName,
                String.class,
                paramTypeFqns,
                paramIndex);
    }

    /** Returns {@code true} when the annotation type is declared with {@code @Retention(RUNTIME)}. */
    private boolean isRuntimeRetained(TypeElement annotationType) {
        return ctx.annotations()
                .find(annotationType, java.lang.annotation.Retention.class)
                .flatMap(m -> ctx.annotations().attribute(m, "value", VariableElement.class))
                .map(v -> v.getSimpleName().toString().equals("RUNTIME"))
                .orElse(false);
    }
}
