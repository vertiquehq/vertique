// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs.processor.emit;

import com.palantir.javapoet.CodeBlock;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.jaxrs.EffectiveParamContract;
import java.util.List;
import java.util.function.Supplier;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;

/**
 * Builds the {@code genericType} argument of a generated {@code ParamMeta}, so that a generated
 * descriptor or execution plan reports the same type the runtime scanner does: the parameter's declared
 * type resolved against the resource class, whether it is a plain class or a parameterized type.
 *
 * <p>The expression takes the cheapest form that is exact:
 *
 * <ul>
 *   <li>a type without type arguments reuses the parameter's {@code Class} expression;
 *   <li>a parameterized type or an array of one is written out as an anonymous {@code TypeReference}
 *       literal, which captures it without reflection;
 *   <li>a type that mentions a type variable, or names a type the generated class cannot reference,
 *       cannot be written out, so the supplied expression resolves it by reflection at startup, with the
 *       runtime scanner's own resolver.
 * </ul>
 */
final class GenericTypeExpressions {

    private final CodegenContext ctx;

    GenericTypeExpressions(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Returns the {@code genericType} expression for a parameter.
     *
     * @param pc          the parameter contract; its {@code genericType} is {@code null} for a source that
     *                    carries none
     * @param fromPackage the package the generated class lives in, which decides which types it can name
     * @param plainClass  an expression for the parameter's {@code Class}
     * @param atRuntime   an expression that resolves the type by reflection, used when it cannot be written
     *                    out
     * @return the code block
     */
    CodeBlock of(EffectiveParamContract pc, String fromPackage, CodeBlock plainClass, Supplier<CodeBlock> atRuntime) {
        TypeMirror type = pc.genericType();
        if (type == null) {
            return CodeBlock.of("null");
        }
        if (mentionsTypeVariable(type) || !isNameable(type, fromPackage)) {
            return atRuntime.get();
        }
        if (type.getKind().isPrimitive()
                || ctx.types().isSameType(type, ctx.types().erasure(type))) {
            return plainClass;
        }
        return CodeBlock.of("new com.fasterxml.jackson.core.type.TypeReference<$L>() {}.getType()", render(type));
    }

    private boolean mentionsTypeVariable(TypeMirror type) {
        return switch (type.getKind()) {
            case TYPEVAR -> true;
            case ARRAY -> mentionsTypeVariable(((javax.lang.model.type.ArrayType) type).getComponentType());
            case WILDCARD -> {
                WildcardType wildcard = (WildcardType) type;
                yield (wildcard.getExtendsBound() != null && mentionsTypeVariable(wildcard.getExtendsBound()))
                        || (wildcard.getSuperBound() != null && mentionsTypeVariable(wildcard.getSuperBound()));
            }
            case DECLARED -> {
                DeclaredType declared = (DeclaredType) type;
                yield declared.getTypeArguments().stream().anyMatch(this::mentionsTypeVariable)
                        || mentionsTypeVariable(declared.getEnclosingType());
            }
            default -> false;
        };
    }

    /**
     * Whether code in {@code fromPackage} can write {@code type} out: every class it names is
     * accessible from that package and none is private or nested in a private class.
     *
     * @param type        the type to check
     * @param fromPackage the package the generated class lives in
     * @return {@code true} when the type can be written in generated source
     */
    boolean isNameable(TypeMirror type, String fromPackage) {
        return switch (type.getKind()) {
            case ARRAY -> isNameable(((javax.lang.model.type.ArrayType) type).getComponentType(), fromPackage);
            case WILDCARD -> {
                WildcardType wildcard = (WildcardType) type;
                yield (wildcard.getExtendsBound() == null || isNameable(wildcard.getExtendsBound(), fromPackage))
                        && (wildcard.getSuperBound() == null || isNameable(wildcard.getSuperBound(), fromPackage));
            }
            case DECLARED -> {
                DeclaredType declared = (DeclaredType) type;
                yield isNameable(declared.asElement(), fromPackage)
                        && declared.getTypeArguments().stream().allMatch(arg -> isNameable(arg, fromPackage))
                        && isNameable(declared.getEnclosingType(), fromPackage);
            }
            default -> true;
        };
    }

    private boolean isNameable(Element element, String fromPackage) {
        if (element == null || element.getModifiers().contains(Modifier.PRIVATE)) {
            return false;
        }
        boolean samePackage =
                ctx.elements().getPackageOf(element).getQualifiedName().contentEquals(fromPackage);
        if (!element.getModifiers().contains(Modifier.PUBLIC) && !samePackage) {
            return false;
        }
        Element owner = element.getEnclosingElement();
        return owner == null || !(owner instanceof TypeElement) || isNameable(owner, fromPackage);
    }

    /** Renders a type that mentions no type variable in the source form a type argument takes. */
    private String render(TypeMirror type) {
        return switch (type.getKind()) {
            case ARRAY -> render(((javax.lang.model.type.ArrayType) type).getComponentType()) + "[]";
            case WILDCARD -> {
                WildcardType wildcard = (WildcardType) type;
                if (wildcard.getExtendsBound() != null) {
                    yield "? extends " + render(wildcard.getExtendsBound());
                }
                yield wildcard.getSuperBound() != null ? "? super " + render(wildcard.getSuperBound()) : "?";
            }
            case DECLARED -> renderDeclared((DeclaredType) type);
            default ->
                type.getKind().isPrimitive()
                        ? type.getKind().name().toLowerCase(java.util.Locale.ROOT)
                        : type.toString();
        };
    }

    private String renderDeclared(DeclaredType declared) {
        TypeElement element = (TypeElement) declared.asElement();
        TypeMirror enclosing = declared.getEnclosingType();
        StringBuilder out = new StringBuilder();
        if (enclosing.getKind() == TypeKind.DECLARED && hasTypeArguments((DeclaredType) enclosing)) {
            out.append(render(enclosing)).append('.').append(element.getSimpleName());
        } else {
            out.append(element.getQualifiedName());
        }
        List<? extends TypeMirror> arguments = declared.getTypeArguments();
        if (!arguments.isEmpty()) {
            out.append('<');
            for (int i = 0; i < arguments.size(); i++) {
                out.append(i == 0 ? "" : ", ").append(render(arguments.get(i)));
            }
            out.append('>');
        }
        return out.toString();
    }

    private boolean hasTypeArguments(DeclaredType declared) {
        if (!declared.getTypeArguments().isEmpty()) {
            return true;
        }
        TypeMirror enclosing = declared.getEnclosingType();
        return enclosing.getKind() == TypeKind.DECLARED && hasTypeArguments((DeclaredType) enclosing);
    }
}
