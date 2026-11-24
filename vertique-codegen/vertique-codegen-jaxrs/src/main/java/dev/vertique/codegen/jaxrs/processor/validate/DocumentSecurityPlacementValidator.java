// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs.processor.validate;

import dev.vertique.codegen.CodegenContext;
import java.util.Collection;
import java.util.Map;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

/**
 * Refuses at compile time an {@code @OpenAPIDefinition} with a non-empty {@code security} on any
 * type other than a {@code @RestApplication} declaring interface.
 *
 * <p>The runtime enforces document-level security only from an application's declaring interface.
 * Anywhere else, for example on a stand-alone configuration class that only the build-time OpenAPI
 * generator reads, the annotation would make the generated document state that operations are
 * secured while the runtime serves them without credentials. The runtime cannot see such a class,
 * so this check is the only place that can refuse it; a build without this processor does not run
 * it. A resource class carrying the annotation, directly or through a supertype, also fails startup.
 */
public final class DocumentSecurityPlacementValidator {

    /** FQN of {@code io.swagger.v3.oas.annotations.OpenAPIDefinition}. */
    private static final String OPEN_API_DEFINITION_FQN = "io.swagger.v3.oas.annotations.OpenAPIDefinition";

    private final CodegenContext ctx;

    /**
     * Creates the validator.
     *
     * @param ctx the shared processor context
     */
    public DocumentSecurityPlacementValidator(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Reports a compile error on every type of the round that carries {@code @OpenAPIDefinition}
     * with a non-empty {@code security} and is not one of {@code declarations}.
     *
     * @param roundEnv the current round
     * @param declarations the round's {@code @RestApplication} declaring interfaces
     */
    public void validate(RoundEnvironment roundEnv, Collection<TypeElement> declarations) {
        TypeElement openApiDefinition = ctx.elements().getTypeElement(OPEN_API_DEFINITION_FQN);
        if (openApiDefinition == null) {
            return;
        }
        for (Element element : roundEnv.getElementsAnnotatedWith(openApiDefinition)) {
            if (!(element instanceof TypeElement type) || declarations.contains(type)) {
                continue;
            }
            if (declaresSecurity(type, openApiDefinition)) {
                ctx.diagnostics()
                        .error(
                                type,
                                "%s carries @OpenAPIDefinition(security), which secures nothing at runtime:"
                                        + " document-level security is enforced only from an application's"
                                        + " @RestApplication interface. Declare it there, or declare"
                                        + " @SecurityRequirement on the resource classes or methods.",
                                type.getQualifiedName());
            }
        }
    }

    private static boolean declaresSecurity(TypeElement type, TypeElement openApiDefinition) {
        for (AnnotationMirror mirror : type.getAnnotationMirrors()) {
            if (!mirror.getAnnotationType().asElement().equals(openApiDefinition)) {
                continue;
            }
            for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
                    mirror.getElementValues().entrySet()) {
                if ("security".contentEquals(entry.getKey().getSimpleName())
                        && entry.getValue().getValue() instanceof java.util.List<?> requirements
                        && !requirements.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }
}
