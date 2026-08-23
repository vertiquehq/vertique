// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.codegen;

import dev.vertique.cache.CacheEvict;
import dev.vertique.cache.Cacheable;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/** Compile-time validation for cache selectors and supported method result shapes. */
@SupportedAnnotationTypes({"dev.vertique.cache.Cacheable", "dev.vertique.cache.CacheEvict"})
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class CacheAnnotationProcessor extends AbstractProcessor {
    private Types types;
    private Elements elements;

    @Override
    public synchronized void init(javax.annotation.processing.ProcessingEnvironment environment) {
        super.init(environment);
        types = environment.getTypeUtils();
        elements = environment.getElementUtils();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnvironment) {
        for (Element element : roundEnvironment.getElementsAnnotatedWith(Cacheable.class)) {
            if (element.getKind() == ElementKind.METHOD) {
                Cacheable annotation = element.getAnnotation(Cacheable.class);
                validateMethod((ExecutableElement) element, annotation.key(), true);
            }
        }
        for (Element element : roundEnvironment.getElementsAnnotatedWith(CacheEvict.class)) {
            if (element.getKind() == ElementKind.METHOD) {
                CacheEvict annotation = element.getAnnotation(CacheEvict.class);
                if (annotation != null
                        && !annotation.clear()
                        && !annotation.key().isBlank()) {
                    validateSelector((ExecutableElement) element, annotation.key());
                }
            }
        }
        return false;
    }

    private void validateMethod(ExecutableElement method, String template, boolean resultRequired) {
        if (resultRequired && method.getReturnType().getKind() == TypeKind.VOID) {
            error(method, "@Cacheable methods must return a value");
        }
        if (isRawOrWildcardFuture(method.getReturnType())) {
            error(method, "@Cacheable Future return types must be concrete Future<T>");
        }
        validateSelector(method, template);
    }

    private void validateSelector(ExecutableElement method, String template) {
        for (int index = 0; index < template.length(); index++) {
            char character = template.charAt(index);
            if (character == '{' && index + 1 < template.length() && template.charAt(index + 1) == '{') {
                index++;
                continue;
            }
            if (character == '}' && index + 1 < template.length() && template.charAt(index + 1) == '}') {
                index++;
                continue;
            }
            if (character == '{') {
                int end = template.indexOf('}', index + 1);
                if (end < 0) {
                    error(method, "cache key contains an unmatched '{'");
                    return;
                }
                validateSelectorToken(method, template.substring(index + 1, end));
                index = end;
            } else if (character == '}') {
                error(method, "cache key contains an unmatched '}'");
                return;
            } else if (!isLiteral(character)) {
                error(method, "cache key contains an unsupported literal character");
                return;
            }
        }
    }

    private void validateSelectorToken(ExecutableElement method, String token) {
        if (token.isBlank()) {
            error(method, "cache key selector must not be blank");
            return;
        }
        String[] segments = token.split("\\.", -1);
        if (segments.length > 4 || segments[0].isBlank()) {
            error(method, "cache key property paths are limited to three properties");
            return;
        }
        int parameterIndex = -1;
        try {
            parameterIndex = Integer.parseInt(segments[0]);
        } catch (NumberFormatException ignored) {
            for (int index = 0; index < method.getParameters().size(); index++) {
                if (segments[0].contentEquals(method.getParameters().get(index).getSimpleName())) {
                    parameterIndex = index;
                    break;
                }
            }
        }
        if (parameterIndex < 0 || parameterIndex >= method.getParameters().size()) {
            error(method, "cache key selector does not resolve to a method parameter: " + segments[0]);
            return;
        }
        TypeMirror type = method.getParameters().get(parameterIndex).asType();
        for (int index = 1; index < segments.length; index++) {
            type = propertyType(type, segments[index]);
            if (type == null) {
                error(method, "cache key property is not an accessible record or bean accessor: " + segments[index]);
                return;
            }
        }
        if (!isScalar(type)) {
            error(method, "cache key selector must end in a supported scalar type");
        }
    }

    private TypeMirror propertyType(TypeMirror type, String property) {
        if (type.getKind() != TypeKind.DECLARED) {
            return null;
        }
        TypeElement element = (TypeElement) ((DeclaredType) type).asElement();
        String suffix = Character.toUpperCase(property.charAt(0)) + property.substring(1);
        for (Element member : elements.getAllMembers(element)) {
            if (member.getKind() == ElementKind.METHOD && member instanceof ExecutableElement method) {
                String name = method.getSimpleName().toString();
                if ((name.equals(property) || name.equals("get" + suffix) || name.equals("is" + suffix))
                        && method.getParameters().isEmpty()) {
                    return method.getReturnType();
                }
            }
        }
        return null;
    }

    private boolean isScalar(TypeMirror type) {
        if (type.getKind().isPrimitive()) {
            return type.getKind() != TypeKind.VOID;
        }
        if (type.getKind() != TypeKind.DECLARED) {
            return false;
        }
        TypeElement element = (TypeElement) ((DeclaredType) type).asElement();
        String name = element.getQualifiedName().toString();
        return name.equals(String.class.getName())
                || name.equals(Character.class.getName())
                || name.equals(Boolean.class.getName())
                || name.equals("java.lang.Byte")
                || name.equals("java.lang.Short")
                || name.equals("java.lang.Integer")
                || name.equals("java.lang.Long")
                || name.equals("java.lang.Float")
                || name.equals("java.lang.Double")
                || name.equals("java.math.BigInteger")
                || name.equals("java.math.BigDecimal")
                || name.equals("java.util.UUID")
                || element.getKind() == ElementKind.ENUM
                || types.isAssignable(
                        type,
                        elements.getTypeElement("java.time.temporal.TemporalAccessor")
                                .asType());
    }

    private boolean isRawOrWildcardFuture(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) {
            return false;
        }
        DeclaredType declared = (DeclaredType) type;
        TypeElement element = (TypeElement) declared.asElement();
        if (!element.getQualifiedName().contentEquals("io.vertx.core.Future")) {
            return false;
        }
        return declared.getTypeArguments().size() != 1
                || declared.getTypeArguments().getFirst().getKind() == TypeKind.WILDCARD;
    }

    private static boolean isLiteral(char character) {
        return Character.isLetterOrDigit(character) || "._~:/-=%".indexOf(character) >= 0;
    }

    private void error(Element element, String message) {
        processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR, message, element);
    }
}
