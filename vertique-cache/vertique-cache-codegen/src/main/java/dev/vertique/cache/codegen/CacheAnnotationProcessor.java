// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.codegen;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.TypeSpec;
import dev.vertique.cache.CacheEvict;
import dev.vertique.cache.Cacheable;
import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.PackageResolver;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/** Compile-time validation for cache selectors and supported method result shapes. */
@SupportedAnnotationTypes({"dev.vertique.cache.Cacheable", "dev.vertique.cache.CacheEvict"})
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class CacheAnnotationProcessor extends AbstractProcessor {
    private static final String FUTURE_FQN = "io.vertx.core.Future";
    private static final String GET_FQN = "jakarta.ws.rs.GET";
    private static final String LEGACY_GET_FQN = "javax.ws.rs.GET";
    private static final Set<String> UNSUPPORTED_REST_RESULTS = Set.of(
            "jakarta.ws.rs.core.Response",
            "javax.ws.rs.core.Response",
            "io.vertx.core.http.HttpServerResponse",
            "io.vertx.ext.web.RoutingContext",
            "io.vertx.core.buffer.Buffer",
            "io.vertx.core.streams.ReadStream",
            "java.util.stream.Stream",
            "java.util.concurrent.Flow.Publisher",
            "org.reactivestreams.Publisher",
            "io.smallrye.mutiny.Multi");

    private Types types;
    private Elements elements;
    private CodegenContext context;
    private boolean generatedModule;
    private final Set<Element> proxyabilityValidated = new HashSet<>();

    @Override
    public synchronized void init(javax.annotation.processing.ProcessingEnvironment environment) {
        super.init(environment);
        context = new CodegenContext(environment);
        types = environment.getTypeUtils();
        elements = environment.getElementUtils();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnvironment) {
        List<Element> cacheOrigins = new ArrayList<>();
        for (Element element : roundEnvironment.getElementsAnnotatedWith(Cacheable.class)) {
            if (element.getKind() == ElementKind.METHOD) {
                cacheOrigins.add(element);
                Cacheable annotation = element.getAnnotation(Cacheable.class);
                validateMethod((ExecutableElement) element, annotation.key(), true);
            }
        }
        for (Element element : roundEnvironment.getElementsAnnotatedWith(CacheEvict.class)) {
            if (element.getKind() == ElementKind.METHOD) {
                cacheOrigins.add(element);
                CacheEvict annotation = element.getAnnotation(CacheEvict.class);
                validateProxyability((ExecutableElement) element);
                if (annotation != null
                        && !annotation.clear()
                        && !annotation.key().isBlank()) {
                    validateSelector((ExecutableElement) element, annotation.key());
                }
            }
        }
        if (!cacheOrigins.isEmpty() && !generatedModule && !roundEnvironment.processingOver()) {
            emitGeneratedCacheModule(cacheOrigins);
        }
        return false;
    }

    private void emitGeneratedCacheModule(List<? extends Element> origins) {
        String packageName = new PackageResolver(context.env()).resolve(origins, context);
        if (packageName == null) {
            return;
        }
        TypeSpec module = TypeSpec.classBuilder("GeneratedCacheModule")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addAnnotation(AnnotationSpec.builder(ClassName.get("dagger", "Module"))
                        .addMember(
                                "includes", "$T.class", ClassName.get("dev.vertique.cache.injvm", "CacheInJvmModule"))
                        .build())
                .addAnnotation(AnnotationSpec.builder(ClassName.get("javax.annotation.processing", "Generated"))
                        .addMember("value", "$S", CacheAnnotationProcessor.class.getName())
                        .build())
                .build();
        try {
            JavaFile.builder(packageName, module).build().writeTo(context.filer());
            generatedModule = true;
        } catch (IOException failure) {
            context.diagnostics().error(null, "Failed to write GeneratedCacheModule: %s", failure.getMessage());
        }
    }

    private void validateMethod(ExecutableElement method, String template, boolean resultRequired) {
        validateProxyability(method);
        if (resultRequired && method.getReturnType().getKind() == TypeKind.VOID) {
            error(method, "@Cacheable methods must return a value");
        }
        if (isRawOrWildcardFuture(method.getReturnType())) {
            error(method, "@Cacheable Future return types must be concrete Future<T>");
        }
        validateRestResult(method);
        validateSelector(method, template);
    }

    private void validateSelector(ExecutableElement method, String template) {
        if (template.isBlank()) {
            error(method, "cache key must not be blank");
            return;
        }
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
        for (String segment : segments) {
            if (!isIdentifier(segment) && !segment.equals(segments[0])) {
                error(method, "cache key property path contains an invalid identifier: " + segment);
                return;
            }
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
                        && method.getParameters().isEmpty()
                        && method.getModifiers().contains(Modifier.PUBLIC)
                        && !method.getModifiers().contains(Modifier.STATIC)) {
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
                || declared.getTypeArguments().getFirst().getKind() == TypeKind.WILDCARD
                || declared.getTypeArguments().getFirst().getKind() == TypeKind.TYPEVAR;
    }

    private void validateProxyability(ExecutableElement method) {
        if (!proxyabilityValidated.add(method)) {
            return;
        }
        Element enclosing = method.getEnclosingElement();
        if (!(enclosing instanceof TypeElement bean)) {
            return;
        }
        if (!bean.getModifiers().contains(Modifier.PUBLIC)) {
            error(method, "cacheable methods must be declared on a public Dagger-managed class");
        }
        if (bean.getModifiers().contains(Modifier.FINAL)) {
            error(method, "cacheable methods cannot be declared on a final class");
        }
        if (!hasInjectConstructor(bean)) {
            error(method, "cacheable methods require exactly one @Inject constructor");
        }
        Set<Modifier> modifiers = method.getModifiers();
        if (modifiers.contains(Modifier.FINAL)
                || modifiers.contains(Modifier.PRIVATE)
                || modifiers.contains(Modifier.STATIC)
                || modifiers.contains(Modifier.ABSTRACT)) {
            error(method, "cacheable methods must be instance methods that can be overridden");
        }
    }

    private boolean hasInjectConstructor(TypeElement bean) {
        List<? extends ExecutableElement> constructors = ElementFilter.constructorsIn(bean.getEnclosedElements());
        return constructors.size() == 1 && hasInject(constructors.getFirst());
    }

    private boolean hasInject(Element element) {
        return AnnotationMirrors.isPresent(element, "jakarta.inject.Inject")
                || AnnotationMirrors.isPresent(element, "javax.inject.Inject");
    }

    private void validateRestResult(ExecutableElement method) {
        if (!hasGetAnnotation(method)) {
            return;
        }
        TypeMirror result = method.getReturnType();
        if (result.getKind() != TypeKind.DECLARED) {
            return;
        }
        DeclaredType declared = (DeclaredType) result;
        TypeElement type = (TypeElement) declared.asElement();
        String erased = types.erasure(declared).toString();
        if (UNSUPPORTED_REST_RESULTS.contains(erased)) {
            error(method, "REST cacheable methods must return an entity result, not " + erased);
            return;
        }
        if (FUTURE_FQN.equals(erased) && declared.getTypeArguments().size() == 1) {
            TypeMirror entity = declared.getTypeArguments().getFirst();
            if (entity.getKind() == TypeKind.DECLARED
                    && UNSUPPORTED_REST_RESULTS.contains(types.erasure(entity).toString())) {
                error(method, "REST cacheable Future result must contain an entity, not " + entity);
            }
        }
        if (type.getQualifiedName().contentEquals("io.vertx.core.streams.ReadStream")) {
            error(method, "REST cacheable methods must not return streaming results");
        }
    }

    private boolean hasGetAnnotation(ExecutableElement method) {
        return AnnotationMirrors.isPresent(method, GET_FQN) || AnnotationMirrors.isPresent(method, LEGACY_GET_FQN);
    }

    private static boolean isIdentifier(String value) {
        if (value.isEmpty() || !Character.isJavaIdentifierStart(value.charAt(0))) {
            return false;
        }
        for (int index = 1; index < value.length(); index++) {
            if (!Character.isJavaIdentifierPart(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLiteral(char character) {
        return Character.isLetterOrDigit(character) || "._~:/-=%".indexOf(character) >= 0;
    }

    private void error(Element element, String message) {
        processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR, message, element);
    }
}
