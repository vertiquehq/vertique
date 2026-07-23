// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.dagger.processor.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.ParameterSpec;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.dagger.DaggerModuleWriter;
import dev.vertique.codegen.dagger.processor.Binding;
import dev.vertique.codegen.dagger.processor.Qualifier;
import dev.vertique.codegen.dagger.processor.support.FilerWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.SourceVersion;

/**
 * Emitter that generates a Dagger {@code @Module} containing
 * {@code @Provides @Singleton InterfaceType provideXxx(RestClientFactory factory)} methods for
 * each {@code @RestClient}-annotated interface.
 *
 * <p>The generated emit shape differs from multibinding qualifiers: each {@code @RestClient}
 * interface gets a direct {@code @Singleton} binding (not an {@code @IntoSet} contribution).
 * The generated body delegates to {@code RestClientFactory.builder().build(InterfaceType.class)},
 * mirroring the manual pattern:
 *
 * <pre>{@code
 * @Provides
 * @Singleton
 * static UserClient provideUserClient(RestClientFactory factory) {
 *     return factory.builder().build(UserClient.class);
 * }
 * }</pre>
 *
 * <p>The method name is derived by prepending {@code "provide"} to the interface simple name
 * (e.g., {@code UserClient} → {@code provideUserClient}).
 */
public final class RestClientModuleEmitter {

    private static final ClassName REST_CLIENT_FACTORY = ClassName.get("dev.vertique.rest.client", "RestClientFactory");

    private final CodegenContext ctx;

    /**
     * Constructs a {@code RestClientModuleEmitter} bound to the given context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public RestClientModuleEmitter(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Emits a generated Dagger module for all {@code @RestClient} bindings in the given list.
     *
     * <p>Each binding produces one {@code @Provides @Singleton} method. All methods are collected
     * into a single {@code GeneratedRestClientsModule} in the given package.
     *
     * @param bindings    the non-empty list of REST client interface bindings; must not be
     *                    {@code null} or empty
     * @param packageName the fully-qualified package name for the generated module; must not be
     *                    {@code null}
     */
    public void emit(List<Binding> bindings, String packageName) {
        if (hasSimpleNameCollisions(bindings)) {
            return;
        }
        ClassName moduleName = ClassName.get(packageName, Qualifier.REST_CLIENTS.moduleSimpleName);
        DaggerModuleWriter writer = DaggerModuleWriter.named(moduleName).concrete();

        ParameterSpec factoryParam =
                ParameterSpec.builder(REST_CLIENT_FACTORY, "factory").build();

        for (Binding binding : bindings) {
            ClassName interfaceType = binding.implType();
            String methodName = provideMethodName(interfaceType.simpleName());
            CodeBlock body = CodeBlock.of("return factory.builder().build($T.class);", interfaceType);
            writer.addSingletonProvides(interfaceType, methodName, body, factoryParam);
        }

        JavaFile file = writer.build();
        FilerWriter.write(file, ctx, moduleName.canonicalName());
    }

    /**
     * Detects two {@code @RestClient} interfaces with the same simple name (e.g., {@code foo.UserClient}
     * and {@code bar.UserClient}) that would produce conflicting {@code provide{Name}} methods, and
     * emits a {@code Diagnostics.error} for each colliding pair so the user can rename one or add
     * {@code @NoAutoWire}. Returns {@code true} when a collision was found — the caller should skip
     * the emit phase, since the resulting module would not compile.
     */
    private boolean hasSimpleNameCollisions(List<Binding> bindings) {
        Map<String, Binding> bySimpleName = new HashMap<>();
        boolean anyConflict = false;
        for (Binding b : bindings) {
            String simple = b.implType().simpleName();
            Binding prior = bySimpleName.putIfAbsent(simple, b);
            if (prior != null) {
                ctx.diagnostics()
                        .error(
                                b.origin(),
                                "@RestClient %s collides with %s in the generated %s — both produce "
                                        + "the same provider method (simple name '%s'). Rename one type or "
                                        + "add @NoAutoWire to keep a manual binding.",
                                b.implType().canonicalName(),
                                prior.implType().canonicalName(),
                                Qualifier.REST_CLIENTS.moduleSimpleName,
                                simple);
                anyConflict = true;
            }
        }
        return anyConflict;
    }

    // --- Internal helpers ---

    /**
     * Derives the {@code @Provides} method name by prepending {@code "provide"} to the interface
     * simple name with proper capitalisation.
     *
     * <p>For example, {@code "UserClient"} → {@code "provideUserClient"}.
     * {@code "uRLClient"} → {@code "provideuRLClient"} (Introspector decapitalize is NOT applied
     * to the "provide" prefix — we want the interface name to remain as-is after "provide").
     *
     * <p>If the result would be a Java keyword, a trailing underscore is appended.
     *
     * @param simpleName the simple interface name
     * @return the derived method name
     */
    private static String provideMethodName(String simpleName) {
        String name = "provide" + simpleName;
        return SourceVersion.isName(name) ? name : name + "_";
    }
}
