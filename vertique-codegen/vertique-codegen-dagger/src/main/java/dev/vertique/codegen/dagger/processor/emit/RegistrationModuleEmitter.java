// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.dagger.processor.emit;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.TypeVisibility;
import dev.vertique.codegen.dagger.processor.Registration;
import dev.vertique.codegen.dagger.processor.support.FilerWriter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Modifier;

/**
 * Emits the explicit Dagger module for generic registration annotations.
 *
 * <p>The generated module is abstract because every method is an abstract {@code @Binds}
 * declaration. Direct registrations use {@code @Binds}; set registrations add
 * {@code @IntoSet} to the same method.
 */
public final class RegistrationModuleEmitter {

    private static final String GENERATED_MODULE_SIMPLE_NAME = "GeneratedRegistrationsModule";
    private static final ClassName DAGGER_MODULE = ClassName.get("dagger", "Module");
    private static final ClassName DAGGER_BINDS = ClassName.get("dagger", "Binds");
    private static final ClassName DAGGER_INTO_SET = ClassName.get("dagger.multibindings", "IntoSet");
    private static final ClassName GENERATED = ClassName.get("javax.annotation.processing", "Generated");

    private final CodegenContext ctx;

    /**
     * Constructs an emitter bound to the processing context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public RegistrationModuleEmitter(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Emits {@code GeneratedRegistrationsModule} for the supplied registrations.
     *
     * <p>Registrations whose implementation or target cannot be referenced from the resolved
     * output package are skipped with a mandatory warning, avoiding uncompilable generated source.
     *
     * @param registrations the valid registrations to emit; must not be {@code null}
     * @param packageName   the package for the generated module; must not be {@code null}
     */
    public void emit(List<Registration> registrations, String packageName) {
        ClassName moduleName = ClassName.get(packageName, GENERATED_MODULE_SIMPLE_NAME);
        TypeSpec.Builder module = TypeSpec.classBuilder(moduleName.simpleName())
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addAnnotation(AnnotationSpec.builder(GENERATED)
                        .addMember("value", "$S", "dev.vertique.codegen.dagger.processor.AutoWireProcessor")
                        .build())
                .addAnnotation(AnnotationSpec.builder(DAGGER_MODULE).build());
        Set<String> methodNames = new HashSet<>();
        int emittedCount = 0;

        for (Registration registration : registrations) {
            if (!isReferenceable(registration, packageName)) {
                continue;
            }
            String methodName = uniqueMethodName(registration, methodNames);
            MethodSpec.Builder method = MethodSpec.methodBuilder(methodName)
                    .addModifiers(Modifier.ABSTRACT)
                    .addAnnotation(AnnotationSpec.builder(DAGGER_BINDS).build())
                    .returns(ClassName.get(registration.target()))
                    .addParameter(registration.implementation(), "implementation");
            if (registration.intoSet()) {
                method.addAnnotation(AnnotationSpec.builder(DAGGER_INTO_SET).build());
            }
            module.addMethod(method.build());
            emittedCount++;
        }

        if (emittedCount == 0) {
            return;
        }
        FilerWriter.write(JavaFile.builder(packageName, module.build()).build(), ctx, moduleName.canonicalName());
    }

    private boolean isReferenceable(Registration registration, String packageName) {
        boolean implementationReferenceable = TypeVisibility.isReferenceableFrom(registration.origin(), packageName);
        boolean targetReferenceable = TypeVisibility.isReferenceableFrom(registration.target(), packageName);
        if (implementationReferenceable && targetReferenceable) {
            return true;
        }
        ctx.diagnostics()
                .mandatoryWarning(
                        registration.origin(),
                        "Skipping generic Dagger registration for %s: %s type is not referenceable from generated package %s",
                        registration.origin().getQualifiedName(),
                        implementationReferenceable ? "target" : "implementation",
                        packageName);
        return false;
    }

    private static String uniqueMethodName(Registration registration, Set<String> methodNames) {
        String base = "bind" + registration.implementation().simpleName();
        String candidate = validName(base);
        if (methodNames.add(candidate)) {
            return candidate;
        }

        String targetSuffix = "To" + registration.target().getSimpleName();
        candidate = validName(base + targetSuffix);
        int suffix = 2;
        while (!methodNames.add(candidate)) {
            candidate = validName(base + targetSuffix + suffix++);
        }
        return candidate;
    }

    private static String validName(String candidate) {
        return SourceVersion.isName(candidate) ? candidate : candidate + "_";
    }
}
