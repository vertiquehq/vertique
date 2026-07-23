// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.application;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeSpec;
import dev.vertique.codegen.CodegenContext;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import javax.annotation.processing.Filer;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

/**
 * JavaPoet emitter that generates an application's {@link dev.vertique.core.VertiqueComponentFactory}
 * implementation from a {@code @VertiqueApp}-annotated Dagger {@code @Component}, plus the matching
 * {@code META-INF/services} registration.
 *
 * <p>For a component {@code AppComponent} in package {@code com.example}, the emitter produces, in
 * that same package, a {@code final class AppComponentVertiqueComponentFactory implements
 * VertiqueComponentFactory<AppComponent>} whose {@code build(VertiqueRuntime runtime)} body is
 * exactly:
 *
 * <pre>{@code
 * return DaggerAppComponent.builder()
 *         .vertxModule(new VertxModule(runtime.vertx(), runtime.config()))
 *         .build();
 * }</pre>
 *
 * <p>The Dagger-generated builder type {@code Dagger<ComponentSimpleName>} is referenced
 * <strong>by name</strong> ({@code Dagger} + the component's simple name, same package) — never
 * loaded or inspected. Dagger generates that type in the same compilation, so the reference resolves
 * at the final javac compile with no reflection.
 *
 * <p>The emitter also writes the resource
 * {@code META-INF/services/dev.vertique.core.VertiqueComponentFactory} containing the generated
 * factory's fully-qualified name on a single line — the exact file the framework's runtime
 * {@code ServiceLoader} path reads.
 */
public final class ComponentFactoryEmitter {

    private static final ClassName VERTIQUE_COMPONENT_FACTORY =
            ClassName.get("dev.vertique.core", "VertiqueComponentFactory");
    private static final ClassName VERTIQUE_RUNTIME = ClassName.get("dev.vertique.core", "VertiqueRuntime");
    private static final ClassName VERTX_MODULE = ClassName.get("dev.vertique.core", "VertxModule");

    /** Suffix appended to the component's simple name to form the generated factory's simple name. */
    private static final String FACTORY_SUFFIX = "VertiqueComponentFactory";

    /** The SPI resource path the generated factory FQN is registered under. */
    private static final String SERVICE_RESOURCE_PATH = "META-INF/services/dev.vertique.core.VertiqueComponentFactory";

    private final CodegenContext ctx;

    /**
     * Constructs a {@code ComponentFactoryEmitter} bound to the given context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public ComponentFactoryEmitter(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Emits the {@code VertiqueComponentFactory} implementation source and the
     * {@code META-INF/services} registration for the given {@code @VertiqueApp} component.
     *
     * <p>On an {@link IOException} writing either artifact, a compiler error is emitted via
     * {@link CodegenContext#diagnostics()} and the method returns without throwing.
     *
     * @param component the {@code @VertiqueApp}-annotated component interface; must not be
     *                  {@code null}
     */
    public void emit(TypeElement component) {
        String packageName = ctx.packageNameOf(component);
        String componentSimpleName = component.getSimpleName().toString();
        ClassName componentType = ClassName.get(packageName, componentSimpleName);
        ClassName daggerComponentType = ClassName.get(packageName, "Dagger" + componentSimpleName);
        String factorySimpleName = componentSimpleName + FACTORY_SUFFIX;
        String factoryFqn = packageName.isEmpty() ? factorySimpleName : packageName + "." + factorySimpleName;

        MethodSpec build = MethodSpec.methodBuilder("build")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(componentType)
                .addParameter(VERTIQUE_RUNTIME, "runtime")
                .addStatement(
                        "return $T.builder().vertxModule(new $T(runtime.vertx(), runtime.config())).build()",
                        daggerComponentType,
                        VERTX_MODULE)
                .build();

        TypeSpec factory = TypeSpec.classBuilder(factorySimpleName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(ParameterizedTypeName.get(VERTIQUE_COMPONENT_FACTORY, componentType))
                .addMethod(build)
                .build();

        JavaFile file = JavaFile.builder(packageName, factory).build();
        try {
            file.writeTo(ctx.filer());
        } catch (IOException e) {
            ctx.diagnostics()
                    .error(component, "Failed to write generated factory '%s': %s", factoryFqn, e.getMessage());
            return;
        }

        writeServiceResource(component, factoryFqn);
    }

    // --- Internal helpers ---

    /**
     * Writes the {@code META-INF/services/dev.vertique.core.VertiqueComponentFactory} resource
     * containing the generated factory's FQN on a single line.
     *
     * @param component   the originating component element (for error attribution); must not be
     *                    {@code null}
     * @param factoryFqn  the fully-qualified name of the generated factory; must not be {@code null}
     */
    private void writeServiceResource(TypeElement component, String factoryFqn) {
        Filer filer = ctx.filer();
        try {
            FileObject resource =
                    filer.createResource(StandardLocation.CLASS_OUTPUT, "", SERVICE_RESOURCE_PATH, component);
            try (OutputStream out = resource.openOutputStream();
                    Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                writer.write(factoryFqn);
                writer.write(System.lineSeparator());
            }
        } catch (IOException e) {
            ctx.diagnostics()
                    .error(
                            component,
                            "Failed to write service registration '%s' for factory '%s': %s",
                            SERVICE_RESOURCE_PATH,
                            factoryFqn,
                            e.getMessage());
        }
    }
}
