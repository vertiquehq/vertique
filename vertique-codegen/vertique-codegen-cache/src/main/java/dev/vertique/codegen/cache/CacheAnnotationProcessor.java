// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.cache;

import dev.vertique.cache.aop.CacheEvict;
import dev.vertique.cache.aop.Cacheable;
import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.ProxyabilityValidator;
import dev.vertique.codegen.SelectorPathValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
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
@SupportedAnnotationTypes({"dev.vertique.cache.aop.Cacheable", "dev.vertique.cache.aop.CacheEvict"})
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
    private SelectorPathValidator selectorPathValidator;
    private ProxyabilityValidator proxyabilityValidator;

    @Override
    public synchronized void init(javax.annotation.processing.ProcessingEnvironment environment) {
        super.init(environment);
        types = environment.getTypeUtils();
        elements = environment.getElementUtils();
        CodegenContext context = new CodegenContext(environment);
        selectorPathValidator = new SelectorPathValidator(context, "cache");
        proxyabilityValidator = new ProxyabilityValidator(context, "cacheable");
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
                if (element.getAnnotation(Cacheable.class) != null) {
                    error(element, "@Cacheable and @CacheEvict must be declared on different methods");
                    continue;
                }
                proxyabilityValidator.validate((ExecutableElement) element);
                for (EvictionDeclaration declaration : evictionDeclarations(element)) {
                    if (declaration.clear() && declaration.keyExplicit()) {
                        error(element, "cache eviction must specify exactly one of clear=true or an explicit key");
                        continue;
                    }
                    if (!declaration.clear() && !declaration.keyExplicit()) {
                        error(element, "cache eviction must specify exactly one of clear=true or an explicit key");
                        continue;
                    }
                    if (!declaration.clear()) {
                        validateSelector((ExecutableElement) element, declaration.paths());
                    }
                }
            }
        }
        return false;
    }

    private void validateMethod(ExecutableElement method, String[] paths, boolean resultRequired) {
        proxyabilityValidator.validate(method);
        if (resultRequired && method.getReturnType().getKind() == TypeKind.VOID) {
            error(method, "@Cacheable methods must return a value");
        }
        if (isRawOrWildcardFuture(method.getReturnType())) {
            error(method, "@Cacheable Future return types must be concrete Future<T>");
        }
        validateRestResult(method);
        validateSelector(method, paths);
    }

    private void validateSelector(ExecutableElement method, String[] paths) {
        // An explicitly empty declared component list is the value-independent constant key.
        selectorPathValidator.validate(method, paths);
    }

    private record EvictionDeclaration(boolean clear, boolean keyExplicit, String[] paths) {}

    private List<EvictionDeclaration> evictionDeclarations(Element element) {
        List<EvictionDeclaration> declarations = new ArrayList<>();
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            String type = mirror.getAnnotationType().toString();
            if (type.equals("dev.vertique.cache.aop.CacheEvict")) {
                declarations.add(evictionDeclaration(mirror));
            } else if (type.equals("dev.vertique.cache.aop.CacheEvict.List")) {
                for (var entry : mirror.getElementValues().entrySet()) {
                    if (!entry.getKey().getSimpleName().contentEquals("value")) continue;
                    if (entry.getValue().getValue() instanceof List<?> nested) {
                        for (Object value : nested) {
                            if (value instanceof AnnotationValue annotationValue
                                    && annotationValue.getValue() instanceof AnnotationMirror nestedMirror) {
                                declarations.add(evictionDeclaration(nestedMirror));
                            }
                        }
                    }
                }
            }
        }
        return declarations;
    }

    private EvictionDeclaration evictionDeclaration(AnnotationMirror mirror) {
        boolean clear = false;
        boolean keyExplicit = false;
        List<String> paths = new ArrayList<>();
        for (var entry : mirror.getElementValues().entrySet()) {
            String member = entry.getKey().getSimpleName().toString();
            Object value = entry.getValue().getValue();
            if (member.equals("clear") && value instanceof Boolean explicit) {
                clear = explicit;
            } else if (member.equals("key") && value instanceof List<?> declared) {
                keyExplicit = true;
                for (Object path : declared) {
                    if (path instanceof AnnotationValue annotationValue
                            && annotationValue.getValue() instanceof String text) {
                        paths.add(text);
                    }
                }
            }
        }
        return new EvictionDeclaration(clear, keyExplicit, paths.toArray(String[]::new));
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

    private void validateRestResult(ExecutableElement method) {
        if (!hasGetAnnotation(method)) {
            return;
        }
        TypeMirror result = method.getReturnType();
        if (result.getKind() != TypeKind.DECLARED) {
            return;
        }
        DeclaredType declared = (DeclaredType) result;
        String erased = types.erasure(declared).toString();
        if (isUnsupportedRestResult(declared)) {
            error(method, "REST cacheable methods must return an entity result, not " + erased);
            return;
        }
        if (FUTURE_FQN.equals(erased) && declared.getTypeArguments().size() == 1) {
            TypeMirror entity = declared.getTypeArguments().getFirst();
            if (isUnsupportedRestResult(entity)) {
                error(method, "REST cacheable Future result must contain an entity, not " + entity);
            }
        }
    }

    private boolean isUnsupportedRestResult(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) {
            return false;
        }
        String erased = types.erasure(type).toString();
        if (UNSUPPORTED_REST_RESULTS.contains(erased)) {
            return true;
        }
        for (String unsupported : UNSUPPORTED_REST_RESULTS) {
            TypeElement unsupportedType = elements.getTypeElement(unsupported);
            if (unsupportedType != null
                    && types.isAssignable(types.erasure(type), types.erasure(unsupportedType.asType()))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasGetAnnotation(ExecutableElement method) {
        return AnnotationMirrors.isPresent(method, GET_FQN) || AnnotationMirrors.isPresent(method, LEGACY_GET_FQN);
    }

    private void error(Element element, String message) {
        processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR, message, element);
    }
}
