// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor.scan;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.services.processor.ServiceAnnotations;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ExecutableElement;

/**
 * APT-layer replica of the runtime {@code dev.vertique.services.OperationIdResolver}.
 *
 * <p>Resolves the operation segment and optional stable operation id for a service contract
 * method using {@link AnnotationMirror} rather than reflective {@link java.lang.reflect.Method}
 * objects.
 *
 * <p>Runtime mirror rules (from {@code OperationIdResolver.java:37-68}):
 * <ul>
 *   <li><b>Operation name</b> — {@code @ServiceOperation#value()} when present and non-blank,
 *       otherwise the Java method name.</li>
 *   <li><b>Stable operation id</b> — {@code @ServiceOperation#value()} when present and
 *       non-blank; {@code null} when the annotation is absent (not stable-target-eligible).
 *       Blank value is an error — emitted via {@link CodegenContext#diagnostics()} and
 *       {@code null} is returned.</li>
 * </ul>
 */
final class AptOperationIdResolver {

    private final CodegenContext ctx;

    /**
     * Constructs the resolver bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    AptOperationIdResolver(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Resolves the operation name for the runtime event bus address.
     *
     * <p>Returns {@code @ServiceOperation#value()} if the annotation is present and non-blank,
     * otherwise falls back to the Java method name.
     *
     * @param method the contract method element to resolve; must not be {@code null}
     * @return the operation name; never {@code null} or blank
     */
    String resolveOperationName(ExecutableElement method) {
        String stable = resolveStableOperationId(method);
        if (stable != null) {
            return stable;
        }
        return method.getSimpleName().toString();
    }

    /**
     * Resolves the stable operation id for durable target references.
     *
     * <p>Returns {@code @ServiceOperation#value()} when present and non-blank. Returns
     * {@code null} when the annotation is absent. Emits a compiler error and returns
     * {@code null} when the annotation is present but its value is blank.
     *
     * @param method the contract method element to resolve; must not be {@code null}
     * @return the stable operation id, or {@code null} if not explicitly annotated
     */
    String resolveStableOperationId(ExecutableElement method) {
        var mirror = AnnotationMirrors.findByFqn(method, ServiceAnnotations.SERVICE_OPERATION);
        if (mirror.isEmpty()) {
            return null;
        }
        AnnotationMirror am = mirror.get();
        var value = ctx.annotations().attribute(am, "value", String.class);
        if (value.isEmpty() || value.get().isBlank()) {
            ctx.diagnostics()
                    .error(
                            method,
                            "@ServiceOperation on %s.%s() has a blank value — the operation id must be non-blank",
                            method.getEnclosingElement().getSimpleName(),
                            method.getSimpleName());
            return null;
        }
        return value.get();
    }
}
