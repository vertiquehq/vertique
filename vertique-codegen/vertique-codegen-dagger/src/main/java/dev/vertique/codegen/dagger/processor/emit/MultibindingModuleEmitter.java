// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.dagger.processor.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.JavaFile;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.dagger.DaggerModuleWriter;
import dev.vertique.codegen.dagger.processor.Binding;
import dev.vertique.codegen.dagger.processor.Qualifier;
import dev.vertique.codegen.dagger.processor.support.FilerWriter;
import java.beans.Introspector;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.SourceVersion;

/**
 * Emitter that generates a Dagger {@code @Module} containing
 * {@code @Provides @IntoSet @Qualifier Object methodName(ImplType impl)} methods for the
 * multibinding qualifiers ({@code @Services}, {@code @JaxRsResources}, {@code @KafkaConsumers},
 * {@code @DelayedJobs}).
 *
 * <p>The produced type for all four multibinding qualifiers is {@code Object} — this matches the
 * {@code Set<Object>} multibindings declared by the framework modules
 * ({@code DispatchModule}, {@code RestCoreModule}, {@code KafkaModule},
 * {@code DelayedJobModule}).
 *
 * <p>Method names are derived via {@link Identifiers#generatedMethodName} to ensure they are
 * valid Java identifiers and do not collide with Java keywords.
 */
public final class MultibindingModuleEmitter {

    private static final ClassName OBJECT = ClassName.get("java.lang", "Object");

    private final CodegenContext ctx;

    /**
     * Constructs a {@code MultibindingModuleEmitter} bound to the given context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public MultibindingModuleEmitter(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Emits a generated Dagger module for the given qualifier and its accumulated bindings.
     *
     * <p>The module is written via the context's {@code Filer} under the given package name. If writing
     * fails, a compiler error is emitted via {@link CodegenContext#diagnostics()} and the method
     * returns without throwing.
     *
     * @param qualifier   the qualifier whose bindings are being emitted; must not be {@code null}
     * @param bindings    the non-empty list of bindings to emit; must not be {@code null} or empty
     * @param packageName the fully-qualified package name for the generated module; must not be
     *                    {@code null}
     */
    public void emit(Qualifier qualifier, List<Binding> bindings, String packageName) {
        if (hasSimpleNameCollisions(qualifier, bindings)) {
            return;
        }
        ClassName moduleName = ClassName.get(packageName, qualifier.moduleSimpleName);
        ClassName qualifierClass = qualifier.qualifierFqn != null ? ClassName.bestGuess(qualifier.qualifierFqn) : null;

        DaggerModuleWriter writer = DaggerModuleWriter.named(moduleName).concrete();

        for (Binding binding : bindings) {
            String methodName = bindingMethodName(binding.implType().simpleName());
            writer.addIntoSetProvides(qualifierClass, OBJECT, methodName, binding.implType());
        }

        JavaFile file = writer.build();
        FilerWriter.write(file, ctx, moduleName.canonicalName());
    }

    /**
     * Detects two annotated types that produce the same {@code @Provides} method name (driven by
     * simple class name) and emits a {@code Diagnostics.error} for each colliding pair so the user
     * can rename one of them or add {@code @NoAutoWire}. Returns {@code true} when a collision was
     * found — the caller should skip the emit phase, since the resulting module would not compile.
     */
    private boolean hasSimpleNameCollisions(Qualifier qualifier, List<Binding> bindings) {
        Map<String, Binding> bySimpleName = new HashMap<>();
        boolean anyConflict = false;
        for (Binding b : bindings) {
            String simple = b.implType().simpleName();
            Binding prior = bySimpleName.putIfAbsent(simple, b);
            if (prior != null) {
                ctx.diagnostics()
                        .error(
                                b.origin(),
                                "Auto-wired %s collides with %s in the generated %s — both produce "
                                        + "the same provider method (simple name '%s'). Rename one type or "
                                        + "add @NoAutoWire to keep a manual binding.",
                                b.implType().canonicalName(),
                                prior.implType().canonicalName(),
                                qualifier.moduleSimpleName,
                                simple);
                anyConflict = true;
            }
        }
        return anyConflict;
    }

    // --- Internal helpers ---

    /**
     * Derives a camelCase method name from an implementation type's simple class name by
     * decapitalizing it and appending {@code "Binding"}.
     *
     * <p>Uses {@link Introspector#decapitalize} so that acronym-leading names keep their casing
     * ({@code URLProvider} → {@code URLProviderBinding}), while normal PascalCase names are
     * lowercased ({@code UserServiceImpl} → {@code userServiceImplBinding}).
     *
     * <p>If the result would be a Java keyword, a trailing underscore is appended.
     *
     * @param simpleName the simple class name of the implementation type
     * @return a valid camelCase Java method name
     */
    private static String bindingMethodName(String simpleName) {
        String decap = Introspector.decapitalize(simpleName) + "Binding";
        return SourceVersion.isName(decap) ? decap : decap + "_";
    }
}
