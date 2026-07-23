// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.kafka.processor.scan;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.kafka.processor.KafkaCodegenAnnotations;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

/**
 * APT-side scanner that collects {@link dev.vertique.kafka.KafkaSource @KafkaSource}-annotated
 * methods from a service-implementation class element and produces a {@link KafkaSourceModel}.
 *
 * <p>Scanning is method-level: for each declared method on the given {@link TypeElement} that
 * carries {@code @KafkaSource}, one {@link KafkaSourceMethodModel} is produced. Annotation
 * attributes are extracted directly; runtime-dependent data (binding-name derivation, event-bus
 * address, payload type from {@code ServiceMethodMeta}) is NOT extracted here — it is deferred
 * to the runtime loader.
 *
 * <p>Interface elements must NOT be passed to this scanner — the
 * {@link dev.vertique.codegen.kafka.processor.validate.KafkaSourceValidator validator} guards
 * against {@code @KafkaSource} on interfaces and skips those elements before they reach the
 * processor's emit path.
 */
public final class KafkaSourceScanner {

    private final CodegenContext ctx;

    /**
     * Constructs a scanner bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public KafkaSourceScanner(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Scans all declared methods on {@code implType} for {@link dev.vertique.kafka.KafkaSource
     * @KafkaSource} annotations and returns a model containing the annotated methods.
     *
     * <p>Methods that carry no {@code @KafkaSource} are silently skipped. If no methods carry the
     * annotation, the returned model's {@link KafkaSourceModel#methods()} list is empty (the
     * caller is responsible for not emitting companions for empty models).
     *
     * @param implType the class or enum element to scan; must not be {@code null}; must not be an
     *                 interface (callers must filter interfaces via the validator first)
     * @return the scanned model; never {@code null}
     */
    public KafkaSourceModel scan(TypeElement implType) {
        List<KafkaSourceMethodModel> methodModels = new ArrayList<>();

        for (var enclosed : implType.getEnclosedElements()) {
            if (!(enclosed instanceof ExecutableElement method)) {
                continue;
            }
            Optional<AnnotationMirror> mirror =
                    AnnotationMirrors.findByFqn(method, KafkaCodegenAnnotations.KAFKA_SOURCE);
            if (mirror.isEmpty()) {
                continue;
            }
            methodModels.add(scanMethod(method, mirror.get()));
        }

        return new KafkaSourceModel(implType, methodModels);
    }

    // --- Internal helpers ---

    /**
     * Scans a single {@code @KafkaSource}-annotated method into a {@link KafkaSourceMethodModel}.
     *
     * @param method the annotated implementation method
     * @param mirror the {@code @KafkaSource} annotation mirror
     * @return the method-level model
     */
    private KafkaSourceMethodModel scanMethod(ExecutableElement method, AnnotationMirror mirror) {
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

        String errorStrategy = KafkaAttrs.readEnumConstant(ctx, mirror, KafkaAttrs.ATTR_ERROR_STRATEGY, "SKIP");
        String commitStrategy = KafkaAttrs.readEnumConstant(ctx, mirror, KafkaAttrs.ATTR_COMMIT_STRATEGY, "AUTO");

        String targetOperation = method.getSimpleName().toString();

        return new KafkaSourceMethodModel(
                method, name, topic, groupId, errorStrategy, commitStrategy, deadLetterTopic, targetOperation);
    }
}
