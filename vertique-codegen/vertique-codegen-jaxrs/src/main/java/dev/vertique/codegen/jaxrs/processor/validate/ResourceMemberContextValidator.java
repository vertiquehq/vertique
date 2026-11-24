// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs.processor.validate;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.JaxRsAnnotations;
import dev.vertique.codegen.jaxrs.JaxRsHierarchy;
import dev.vertique.rest.core.context.RestContextMessages;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;

/**
 * Rejects {@code @Context} on a resource field or on a resource constructor parameter at compile
 * time, mirroring the runtime {@code ResourceClassValidator}. The framework injects {@code @Context}
 * only into resource-method parameters and the application's dependency graph builds the resource,
 * so such a member would stay unset. The resource and every superclass are checked.
 */
public final class ResourceMemberContextValidator {

    private final CodegenContext ctx;

    /**
     * Creates a validator bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public ResourceMemberContextValidator(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Emits an {@code ERROR} on each {@code @Context} field and constructor parameter of the resource
     * class and its superclasses.
     *
     * @param resource the concrete resource class; must not be {@code null}
     * @return {@code true} when no offending member was found
     */
    public boolean validate(TypeElement resource) {
        boolean valid = true;
        TypeElement current = resource;
        while (current != null
                && !"java.lang.Object".equals(current.getQualifiedName().toString())) {
            String className = current.getSimpleName().toString();
            for (Element member : current.getEnclosedElements()) {
                if (member.getKind() == ElementKind.FIELD
                        && AnnotationMirrors.isPresent(member, JaxRsAnnotations.CONTEXT)) {
                    ctx.diagnostics()
                            .error(
                                    member,
                                    RestContextMessages.contextOnResourceMember(
                                            className, "field '" + member.getSimpleName() + "'"));
                    valid = false;
                } else if (member.getKind() == ElementKind.CONSTRUCTOR) {
                    List<? extends VariableElement> parameters = ((ExecutableElement) member).getParameters();
                    for (int i = 0; i < parameters.size(); i++) {
                        if (AnnotationMirrors.isPresent(parameters.get(i), JaxRsAnnotations.CONTEXT)) {
                            ctx.diagnostics()
                                    .error(
                                            parameters.get(i),
                                            RestContextMessages.contextOnResourceMember(
                                                    className, "parameter " + (i + 1) + " of a constructor"));
                            valid = false;
                        }
                    }
                }
            }
            current = JaxRsHierarchy.superClass(ctx, current);
        }
        return valid;
    }
}
