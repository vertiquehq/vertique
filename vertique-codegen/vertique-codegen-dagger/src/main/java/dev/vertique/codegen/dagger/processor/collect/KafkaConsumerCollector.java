// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.dagger.processor.collect;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.dagger.processor.Binding;
import java.util.List;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;

/**
 * Annotation-rooted collector for Kafka consumer classes annotated with {@code @KafkaListener}.
 *
 * <p>Bindings go to the {@link dev.vertique.codegen.dagger.processor.Qualifier#KAFKA_CONSUMERS}
 * qualifier. {@code @KafkaSource} is not a marker here: it targets methods, so a class-rooted
 * binding can never come from it, and Model 1 consumers are found at runtime through the
 * service contract registry rather than through {@code @KafkaConsumers}.
 *
 * <p>Concrete consumer classes must have exactly one {@code @Inject} constructor.
 *
 * <p><b>Model 3 routers (interfaces) are NOT auto-wired.</b> Vertique's Kafka runtime treats
 * {@code @KafkaListener} interfaces with {@code @KafkaHandler} methods as Model 3 routers — the
 * registry expects a {@code Class<?>} literal contributed to the multibinding set, not an
 * instance. Auto-wiring would emit an instance binding (the wrong shape) AND fail Dagger's
 * missing-binding check (interfaces have no constructor). Users with Model 3 routers should keep
 * the manual {@code @Provides @IntoSet @KafkaConsumers Class<?>} contribution; this collector
 * emits a {@code NOTE} to make the skip explicit.
 */
public final class KafkaConsumerCollector extends AnnotationRootedCollector {

    private static final String KAFKA_LISTENER_FQN = "dev.vertique.kafka.KafkaListener";

    /**
     * Constructs a {@code KafkaConsumerCollector} bound to the given context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public KafkaConsumerCollector(CodegenContext ctx) {
        super(ctx);
    }

    /**
     * Returns the Kafka marker annotation FQN.
     *
     * @return a list containing {@code "dev.vertique.kafka.KafkaListener"}
     */
    @Override
    protected List<String> markerFqns() {
        return List.of(KAFKA_LISTENER_FQN);
    }

    /**
     * Skips interfaces (Model 3 routers) with a {@code NOTE}. Concrete classes proceed through
     * the standard {@code @Inject}-constructor validation in {@link AnnotationRootedCollector}.
     */
    @Override
    protected Binding createBinding(TypeElement type) {
        if (type.getKind() == ElementKind.INTERFACE) {
            ctx.diagnostics()
                    .note(
                            type,
                            "%s is a @KafkaListener interface (Model 3 router) — keep the manual "
                                    + "@Provides @IntoSet @KafkaConsumers Class<?> contribution; not auto-wired",
                            type.getQualifiedName());
            return null;
        }
        return super.createBinding(type);
    }
}
