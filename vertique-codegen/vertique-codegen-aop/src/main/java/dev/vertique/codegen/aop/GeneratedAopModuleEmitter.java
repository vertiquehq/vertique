// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import com.palantir.javapoet.ClassName;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.PackageResolver;
import dev.vertique.codegen.dagger.DaggerModuleWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.TypeElement;

/**
 * Accumulates the generated AOP proxies of one compilation unit and emits a single
 * {@code GeneratedAopModule} Dagger {@code @Module} whose {@code @Binds} substitutes each proxy for
 * its bean.
 *
 * <p>Each binding replicates the bean's <em>declared</em> scope — a {@code @Singleton} bean yields a
 * {@code @Singleton}-scoped binding, an unscoped bean yields an unscoped binding (FR-013-03). The
 * scope is never hard-coded.
 *
 * <p>Output-package resolution mirrors the sibling {@code GeneratedServicesModule} emitter: the
 * {@code -Avertique.codegen.package} option wins; otherwise the longest common package prefix of all
 * bound beans is used; a disjoint set falls back to {@code vertique.generated.aop}.
 *
 * <p>The generated module type carries a class-level
 * {@code @javax.annotation.processing.Generated("dev.vertique.codegen.aop.AopProcessor")} marker
 * (FR-013-16), stamped via {@link DaggerModuleWriter#generatedBy(String)}.
 *
 * <p>The {@code @Binds} declarations are built via {@link DaggerModuleWriter#addBinds}, which renders
 * the proxy {@link ClassName} verbatim. The generated proxy is a <em>top-level</em> class whose name
 * literally contains a {@code $} ({@code Greeter$AopProxy}); since {@code $} is a legal identifier
 * character, JavaPoet emits it unchanged.
 */
final class GeneratedAopModuleEmitter {

    private static final String MODULE_SIMPLE_NAME = "GeneratedAopModule";
    private static final String FALLBACK_PACKAGE = "vertique.generated.aop";

    /** FQN of the processor stamped into the module's {@code @Generated} marker (FR-013-16). */
    private static final String PROCESSOR_FQN = "dev.vertique.codegen.aop.AopProcessor";

    private final CodegenContext ctx;
    private final List<Binding> bindings = new ArrayList<>();

    GeneratedAopModuleEmitter(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Records a proxy binding for inclusion in the generated module.
     *
     * @param bean        the bean type element (the bound type); must not be {@code null}
     * @param proxy       the generated proxy {@link ClassName} (binary-name style); must not be
     *                    {@code null}
     * @param scopeOrNull the bean's declared scope annotation, or {@code null} when unscoped
     */
    void add(TypeElement bean, ClassName proxy, ClassName scopeOrNull) {
        bindings.add(new Binding(ClassName.get(bean), proxy, scopeOrNull, ctx.packageNameOf(bean)));
    }

    /**
     * Reports whether any proxy binding has been recorded.
     *
     * @return {@code true} when at least one binding is pending emission
     */
    boolean hasBindings() {
        return !bindings.isEmpty();
    }

    /** Emits the {@code GeneratedAopModule} when at least one binding was recorded. */
    void emit() {
        if (bindings.isEmpty()) {
            return;
        }

        String pkg = resolvePackage();
        ClassName moduleName = ClassName.get(pkg, MODULE_SIMPLE_NAME);

        DaggerModuleWriter writer = DaggerModuleWriter.named(moduleName).generatedBy(PROCESSOR_FQN);
        for (Binding binding : bindings) {
            // The proxy is a top-level class literally named e.g. Greeter$AopProxy; addBinds renders
            // its ClassName verbatim, so the $ is emitted unchanged.
            writer.addBinds(binding.boundType, binding.proxy, bindMethodName(binding.boundType), binding.scopeOrNull);
        }

        try {
            writer.build().writeTo(ctx.filer());
        } catch (IOException e) {
            ctx.diagnostics().error(null, "Failed to write %s: %s", MODULE_SIMPLE_NAME, e.getMessage());
        }
    }

    // --- helpers ---

    /** Method name {@code bind<BeanSimpleName>} (Dagger {@code @Binds} method names are arbitrary). */
    private static String bindMethodName(ClassName boundType) {
        return "bind" + boundType.simpleName();
    }

    /**
     * Resolves the module's output package: option override, then longest common package prefix of
     * the bound beans, then the fallback.
     */
    private String resolvePackage() {
        String override = ctx.env().getOptions().get(CodegenContext.OPTION_OUTPUT_PACKAGE);
        if (override != null && !override.isBlank()) {
            return override;
        }
        String lcp = bindings.stream()
                .map(b -> b.beanPackage)
                .reduce(PackageResolver::longestCommonPrefix)
                .orElse(FALLBACK_PACKAGE);
        return lcp.isBlank() ? FALLBACK_PACKAGE : lcp;
    }

    /** One proxy binding: bean → proxy, with the bean's declared scope and package. */
    private record Binding(ClassName boundType, ClassName proxy, ClassName scopeOrNull, String beanPackage) {}
}
