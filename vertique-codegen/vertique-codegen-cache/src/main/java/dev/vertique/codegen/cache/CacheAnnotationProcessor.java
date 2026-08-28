// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.cache;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import dev.vertique.cache.CacheEvict;
import dev.vertique.cache.Cacheable;
import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.PackageResolver;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
    private static final ClassName GENERATED_METADATA =
            ClassName.get("dev.vertique.cache.spi", "GeneratedCacheMetadata");
    private static final ClassName OPERATION_ID = GENERATED_METADATA.nestedClass("OperationId");
    private static final ClassName CACHEABLE_DECLARATION = GENERATED_METADATA.nestedClass("CacheableDeclaration");
    private static final ClassName EVICTION_DECLARATION = GENERATED_METADATA.nestedClass("EvictionDeclaration");
    private static final ClassName SELECTOR = GENERATED_METADATA.nestedClass("Selector");
    private static final ClassName SELECTOR_COMPONENT = GENERATED_METADATA.nestedClass("SelectorComponent");
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
                validateProxyability((ExecutableElement) element);
                for (CacheEvict annotation : element.getAnnotationsByType(CacheEvict.class)) {
                    if (annotation.clear() == !annotation.key().isBlank()) {
                        error(element, "cache eviction must specify exactly one of clear=true or a nonblank key");
                        continue;
                    }
                    if (!annotation.clear() && !annotation.key().isBlank()) {
                        validateSelector((ExecutableElement) element, annotation.key());
                    }
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
        TypeSpec.Builder module = TypeSpec.classBuilder("GeneratedCacheModule")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addAnnotation(AnnotationSpec.builder(ClassName.get("dagger", "Module"))
                        .addMember(
                                "includes",
                                "$T.class",
                                ClassName.get("dev.vertique.cache.caffeine", "CacheCaffeineModule"))
                        .build())
                .addAnnotation(AnnotationSpec.builder(ClassName.get("javax.annotation.processing", "Generated"))
                        .addMember("value", "$S", CacheAnnotationProcessor.class.getName())
                        .build());
        int ordinal = 0;
        for (Element origin : new LinkedHashSet<>(origins)) {
            if (origin instanceof ExecutableElement method) {
                module.addMethod(metadataProvider(method, ordinal++));
            }
        }
        try {
            JavaFile generated = JavaFile.builder(packageName, module.build()).build();
            generated.writeTo(context.filer());
            generatedModule = true;
        } catch (IOException failure) {
            context.diagnostics().error(null, "Failed to write GeneratedCacheModule: %s", failure.getMessage());
        }
    }

    private MethodSpec metadataProvider(ExecutableElement method, int ordinal) {
        CodeBlock.Builder body = CodeBlock.builder();
        body.add("return new $T() {\n", GENERATED_METADATA);
        body.add(
                "@Override public $T operationId() { return new $T($S, $S, $T.of(",
                OPERATION_ID,
                OPERATION_ID,
                binaryName((TypeElement) method.getEnclosingElement()),
                method.getSimpleName(),
                List.class);
        for (int index = 0; index < method.getParameters().size(); index++) {
            if (index > 0) body.add(", ");
            body.add("$S", binaryTypeName(method.getParameters().get(index).asType()));
        }
        body.add(")); }\n");
        body.add("@Override public boolean synchronous() { return $L; }\n", !isFuture(method.getReturnType()));
        Cacheable cacheable = method.getAnnotation(Cacheable.class);
        if (cacheable == null) {
            body.add(
                    "@Override public java.util.Optional<$T> cacheable() { return java.util.Optional.empty(); }\n",
                    CACHEABLE_DECLARATION);
        } else {
            body.add(
                    "@Override public java.util.Optional<$T> cacheable() { return java.util.Optional.of(new $T(",
                    CACHEABLE_DECLARATION,
                    CACHEABLE_DECLARATION);
            body.add(
                    "$S, $L, $T.$L, $L, $T.$L, $T.$L, $L",
                    cacheable.name(),
                    genericTypeExpression(cacheValueType(method)),
                    ClassName.get("dev.vertique.cache", "CacheMode"),
                    cacheable.mode().name(),
                    cacheable.ttlSeconds(),
                    ClassName.get("dev.vertique.cache", "CacheIdentity"),
                    cacheable.identity().name(),
                    ClassName.get("dev.vertique.cache", "AnonymousCachePolicy"),
                    cacheable.anonymous().name(),
                    selectorExpression(method, cacheable.key()));
            body.add(")); }\n");
        }
        body.add("@Override public java.util.List<$T> evictions() { return $T.of(", EVICTION_DECLARATION, List.class);
        List<CacheEvict> evictions = normalizedEvictions(method);
        for (int index = 0; index < evictions.size(); index++) {
            if (index > 0) body.add(", ");
            CacheEvict eviction = evictions.get(index);
            body.add("new $T($S, ", EVICTION_DECLARATION, eviction.name());
            if (eviction.clear()) body.add("java.util.Optional.empty()");
            else body.add("java.util.Optional.of($L)", selectorExpression(method, eviction.key()));
            body.add(")");
        }
        body.add("); }\n");
        body.add("};\n");
        return MethodSpec.methodBuilder("provideCacheMetadata" + ordinal)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addAnnotation(ClassName.get("dagger", "Provides"))
                .addAnnotation(ClassName.get("dagger.multibindings", "IntoSet"))
                .returns(GENERATED_METADATA)
                .addCode(body.build())
                .build();
    }

    private List<CacheEvict> normalizedEvictions(ExecutableElement method) {
        List<CacheEvict> result = new ArrayList<>();
        Set<String> cleared = new HashSet<>();
        Set<String> exact = new HashSet<>();
        for (CacheEvict eviction : method.getAnnotationsByType(CacheEvict.class)) {
            if (eviction.clear()) {
                result.removeIf(existing -> existing.name().equals(eviction.name()) && !existing.clear());
                if (cleared.add(eviction.name())) result.add(eviction);
            } else if (!cleared.contains(eviction.name()) && exact.add(eviction.name() + "\u0000" + eviction.key())) {
                result.add(eviction);
            }
        }
        return result;
    }

    private CodeBlock selectorExpression(ExecutableElement method, String template) {
        List<String> tokens = selectorTokens(template);
        CodeBlock.Builder selector = CodeBlock.builder();
        selector.add("new $T($S, $T.of(", SELECTOR, template, List.class);
        for (int index = 0; index < tokens.size(); index++) {
            if (index > 0) selector.add(", ");
            String token = tokens.get(index);
            String[] segments = token.split("\\.", -1);
            int parameter = parameterIndex(method, segments[0]);
            TypeMirror rootType = method.getParameters().get(parameter).asType();
            TypeMirror current = rootType;
            for (int segment = 1; segment < segments.length; segment++) {
                current = propertyType(current, segments[segment]);
            }
            selector.add(
                    "new $T($L, $S, (Object[] arguments) -> $L)",
                    SELECTOR_COMPONENT,
                    classLiteral(current),
                    token,
                    accessorExpression(method, parameter, rootType, segments));
        }
        selector.add(")");
        selector.add(")");
        return selector.build();
    }

    private CodeBlock accessorExpression(
            ExecutableElement method, int parameter, TypeMirror rootType, String[] segments) {
        CodeBlock.Builder expression = CodeBlock.builder();
        expression.add("(($L) arguments[$L])", sourceTypeName(rootType), parameter);
        TypeMirror current = rootType;
        for (int index = 1; index < segments.length; index++) {
            expression.add(".$N()", accessorName(current, segments[index]));
            current = propertyType(current, segments[index]);
        }
        return expression.build();
    }

    private String accessorName(TypeMirror type, String property) {
        TypeElement element = (TypeElement) ((DeclaredType) type).asElement();
        String suffix = Character.toUpperCase(property.charAt(0)) + property.substring(1);
        for (Element member : elements.getAllMembers(element)) {
            if (member.getKind() == ElementKind.METHOD
                    && member instanceof ExecutableElement method
                    && method.getParameters().isEmpty()
                    && method.getModifiers().contains(Modifier.PUBLIC)
                    && !method.getModifiers().contains(Modifier.STATIC)) {
                String name = method.getSimpleName().toString();
                if (name.equals(property) || name.equals("get" + suffix) || name.equals("is" + suffix)) return name;
            }
        }
        throw new IllegalArgumentException("missing cache selector accessor: " + property);
    }

    private int parameterIndex(ExecutableElement method, String root) {
        try {
            return Integer.parseInt(root);
        } catch (NumberFormatException ignored) {
            for (int index = 0; index < method.getParameters().size(); index++) {
                if (root.contentEquals(method.getParameters().get(index).getSimpleName())) return index;
            }
            throw new IllegalArgumentException("unknown cache selector parameter: " + root);
        }
    }

    private List<String> selectorTokens(String template) {
        List<String> tokens = new ArrayList<>();
        for (int index = 0; index < template.length(); index++) {
            if (template.charAt(index) != '{') continue;
            if (index + 1 < template.length() && template.charAt(index + 1) == '{') {
                index++;
                continue;
            }
            int end = template.indexOf('}', index + 1);
            if (end >= 0) {
                tokens.add(template.substring(index + 1, end));
                index = end;
            }
        }
        return tokens;
    }

    private TypeMirror cacheValueType(ExecutableElement method) {
        TypeMirror returnType = method.getReturnType();
        if (isFuture(returnType))
            return ((DeclaredType) returnType).getTypeArguments().getFirst();
        return returnType;
    }

    private boolean isFuture(TypeMirror type) {
        return type.getKind() == TypeKind.DECLARED
                && types.erasure(type).toString().equals(FUTURE_FQN)
                && ((DeclaredType) type).getTypeArguments().size() == 1;
    }

    private CodeBlock classLiteral(TypeMirror mirror) {
        return CodeBlock.of("$T.class", TypeName.get(types.erasure(mirror)));
    }

    private CodeBlock sourceTypeName(TypeMirror mirror) {
        if (mirror.getKind() == TypeKind.DECLARED) {
            return CodeBlock.of("$T", ClassName.get((TypeElement) ((DeclaredType) mirror).asElement()));
        }
        return CodeBlock.of("$T", TypeName.get(mirror));
    }

    private CodeBlock genericTypeExpression(TypeMirror mirror) {
        if (mirror.getKind() != TypeKind.DECLARED) return classLiteral(mirror);
        DeclaredType declared = (DeclaredType) mirror;
        TypeElement element = (TypeElement) declared.asElement();
        if (declared.getTypeArguments().isEmpty()) return CodeBlock.of("$T.class", ClassName.get(element));
        CodeBlock.Builder expression = CodeBlock.builder();
        expression.add("new java.lang.reflect.ParameterizedType() {\n");
        expression.add(
                "@Override public java.lang.reflect.Type[] getActualTypeArguments() { return new java.lang.reflect.Type[] { ");
        for (int index = 0; index < declared.getTypeArguments().size(); index++) {
            if (index > 0) expression.add(", ");
            expression.add(
                    "$L", genericTypeExpression(declared.getTypeArguments().get(index)));
        }
        expression.add(" }; }\n");
        expression.add(
                "@Override public java.lang.reflect.Type getRawType() { return $T.class; }\n", ClassName.get(element));
        expression.add("@Override public java.lang.reflect.Type getOwnerType() { return null; }\n}");
        return expression.build();
    }

    private String binaryName(TypeElement element) {
        return elements.getBinaryName(element).toString();
    }

    private String binaryTypeName(TypeMirror mirror) {
        if (mirror.getKind() == TypeKind.ARRAY)
            return binaryTypeName(((javax.lang.model.type.ArrayType) mirror).getComponentType()) + "[]";
        if (mirror.getKind().isPrimitive()) return mirror.getKind().name().toLowerCase(java.util.Locale.ROOT);
        if (mirror.getKind() == TypeKind.DECLARED) return binaryName((TypeElement) ((DeclaredType) mirror).asElement());
        return binaryTypeName(types.erasure(mirror));
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
        if (template.length() > 256) {
            error(method, "cache key must not exceed 256 ASCII characters");
            return;
        }
        List<Integer> tokenStarts = new ArrayList<>();
        List<Integer> tokenEnds = new ArrayList<>();
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
                tokenStarts.add(index);
                tokenEnds.add(end + 1);
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
        for (int index = 1; index < tokenStarts.size(); index++) {
            String literal = template.substring(tokenEnds.get(index - 1), tokenStarts.get(index))
                    .replace("{{", "{")
                    .replace("}}", "}");
            if (literal.chars()
                    .noneMatch(value -> value == ':' || value == '/' || value == '=' || value == '{' || value == '}')) {
                error(method, "cache key components require a raw boundary character");
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
        if (segments.length > 8 || segments[0].isBlank()) {
            error(method, "cache key property paths are limited to eight segments including the root parameter");
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
                || Set.of(
                                "java.time.Instant",
                                "java.time.LocalDate",
                                "java.time.LocalDateTime",
                                "java.time.OffsetDateTime",
                                "java.time.ZonedDateTime")
                        .contains(name);
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
        return character <= 0x7f
                && (character >= 'A' && character <= 'Z'
                        || character >= 'a' && character <= 'z'
                        || character >= '0' && character <= '9'
                        || "._~:/-=%".indexOf(character) >= 0);
    }

    private void error(Element element, String message) {
        processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR, message, element);
    }
}
