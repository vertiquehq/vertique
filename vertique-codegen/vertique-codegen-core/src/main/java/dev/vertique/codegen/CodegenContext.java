// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen;

import java.util.List;
import java.util.Optional;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * INTERNAL framework seam — processor-authoring substrate consumed by sibling framework modules;
 * not an application contract and outside the maturity promise. An application uses the wiring
 * annotations this module documents and never calls this type.
 *
 * <p>Central context object wrapping {@link ProcessingEnvironment} with convenience helpers for
 * annotation processing.
 *
 * <p>Provides lazy-initialized access to {@link TypeResolver}, {@link AnnotationMirrors}, and
 * {@link Diagnostics}, along with higher-level utilities for record discrimination, constructor
 * discovery, Vert.x {@code Future<T>} type unwrapping, and output package resolution.
 *
 * <p>Create one instance per processor and share it across processing rounds:
 *
 * <pre>{@code
 * public class MyProcessor extends AbstractProcessor {
 *     private CodegenContext ctx;
 *
 *     @Override
 *     public synchronized void init(ProcessingEnvironment env) {
 *         super.init(env);
 *         ctx = new CodegenContext(env);
 *     }
 * }
 * }</pre>
 */
public final class CodegenContext {

    private static final String JAKARTA_INJECT = "jakarta.inject.Inject";
    private static final String JAVAX_INJECT = "javax.inject.Inject";
    private static final String FUTURE_FQN = "io.vertx.core.Future";

    private final ProcessingEnvironment env;
    private final Elements elements;
    private final Types types;
    private final Filer filer;
    private final Messager messager;

    private TypeResolver typeResolver;
    private AnnotationMirrors annotations;
    private Diagnostics diagnostics;

    /**
     * Constructs a {@code CodegenContext} wrapping the given {@link ProcessingEnvironment}.
     *
     * @param env the processing environment provided by the compiler; must not be {@code null}
     */
    public CodegenContext(ProcessingEnvironment env) {
        this.env = env;
        this.elements = env.getElementUtils();
        this.types = env.getTypeUtils();
        this.filer = env.getFiler();
        this.messager = env.getMessager();
    }

    // --- APT utility accessors ---

    /**
     * Returns the {@link Elements} utility from the wrapped processing environment.
     *
     * @return the element utilities
     */
    public Elements elements() {
        return elements;
    }

    /**
     * Returns the {@link Types} utility from the wrapped processing environment.
     *
     * @return the type utilities
     */
    public Types types() {
        return types;
    }

    /**
     * Returns the {@link Filer} for creating new source and resource files.
     *
     * @return the filer
     */
    public Filer filer() {
        return filer;
    }

    /**
     * Returns the {@link Messager} for emitting compiler diagnostics.
     *
     * @return the messager
     */
    public Messager messager() {
        return messager;
    }

    /**
     * Returns the raw {@link ProcessingEnvironment} for cases not covered by this context.
     *
     * @return the underlying processing environment
     */
    public ProcessingEnvironment env() {
        return env;
    }

    // --- Lazy helpers ---

    /**
     * Returns a lazily-initialized {@link TypeResolver} bound to this context's {@link Types} and
     * {@link Elements} utilities.
     *
     * @return the shared type resolver instance
     */
    public TypeResolver typeResolver() {
        if (typeResolver == null) {
            typeResolver = new TypeResolver(types, elements);
        }
        return typeResolver;
    }

    /**
     * Returns a lazily-initialized {@link AnnotationMirrors} helper bound to this context's
     * {@link Elements} utility.
     *
     * @return the shared annotation mirror helper instance
     */
    public AnnotationMirrors annotations() {
        if (annotations == null) {
            annotations = new AnnotationMirrors(elements);
        }
        return annotations;
    }

    /**
     * Returns a lazily-initialized {@link Diagnostics} helper bound to this context's
     * {@link Messager}.
     *
     * @return the shared diagnostics instance
     */
    public Diagnostics diagnostics() {
        if (diagnostics == null) {
            diagnostics = new Diagnostics(messager);
        }
        return diagnostics;
    }

    // --- Type helpers ---

    /**
     * Returns {@code true} if the given type element represents a Java record.
     *
     * @param type the type element to inspect; must not be {@code null}
     * @return {@code true} when the element's kind is {@link ElementKind#RECORD}
     */
    public boolean isRecord(TypeElement type) {
        return type.getKind() == ElementKind.RECORD;
    }

    /**
     * Finds the first constructor on the given type annotated with {@code @Inject} (either
     * {@code jakarta.inject.Inject} or {@code javax.inject.Inject}).
     *
     * <p>Both inject annotation flavours are recognised so that downstream processors remain
     * compatible with codebases using either Jakarta EE or {@code javax.inject}.
     *
     * @param type the type element to inspect; must not be {@code null}
     * @return an {@link Optional} containing the {@code @Inject}-annotated constructor, or
     *         {@link Optional#empty()} if no such constructor exists
     */
    public Optional<ExecutableElement> injectConstructor(TypeElement type) {
        return type.getEnclosedElements().stream()
                .filter(e -> e.getKind() == ElementKind.CONSTRUCTOR)
                .map(e -> (ExecutableElement) e)
                .filter(ctor -> ctor.getAnnotationMirrors().stream().anyMatch(m -> {
                    var annotationElement = m.getAnnotationType().asElement();
                    String fqn;
                    if (annotationElement instanceof TypeElement typeEl) {
                        fqn = typeEl.getQualifiedName().toString();
                    } else {
                        fqn = annotationElement.toString();
                    }
                    return JAKARTA_INJECT.equals(fqn) || JAVAX_INJECT.equals(fqn);
                }))
                .findFirst();
    }

    /**
     * If {@code type} is a parameterized {@code io.vertx.core.Future<X>}, returns the type
     * argument {@code X}; otherwise returns {@code type} unchanged.
     *
     * <p>Resolution walks the direct supertype hierarchy using erasure comparison so that
     * {@code Future} subclasses (unusual but permitted) are also handled.
     *
     * @param type the type mirror to inspect; must not be {@code null}
     * @return the unwrapped type argument if {@code type} is a {@code Future}, or {@code type}
     *         itself otherwise
     */
    public TypeMirror unwrapFuture(TypeMirror type) {
        if (!(type instanceof DeclaredType declared)) {
            return type;
        }
        // Check if the erased form of this type is Future
        TypeMirror erased = types.erasure(declared);
        if (erased.toString().equals(FUTURE_FQN)) {
            List<? extends TypeMirror> args = declared.getTypeArguments();
            if (!args.isEmpty()) {
                return args.get(0);
            }
            return type;
        }
        // Walk supertypes to handle Future subclasses
        for (TypeMirror supertype : types.directSupertypes(declared)) {
            TypeMirror result = unwrapFuture(supertype);
            if (result != supertype) {
                return result;
            }
        }
        return type;
    }

    /**
     * Determines the output package for generated source files derived from the given origin
     * element.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>The processor option {@code -Avertique.codegen.package} if set in the processing
     *       environment options map.
     *   <li>The package of the origin element ({@code Elements.getPackageOf(origin)}).
     * </ol>
     *
     * <p>This per-origin strategy mirrors what Dagger and AutoValue do: each generated class lands
     * in the same package as the type it was derived from, unless a global override is configured.
     *
     * @param origin the type element whose package is used as the fallback; must not be
     *               {@code null}
     * @return the fully-qualified package name for generated sources; never {@code null}
     */
    public String outputPackage(TypeElement origin) {
        String override = env.getOptions().get(OPTION_OUTPUT_PACKAGE);
        if (override != null && !override.isBlank()) {
            return override;
        }
        return packageNameOf(origin);
    }

    /**
     * Returns the fully-qualified package name of the given type element, ignoring any
     * {@link #OPTION_OUTPUT_PACKAGE} override. Use this when you need the type's actual package
     * (e.g. for cross-package access checks); use {@link #outputPackage(TypeElement)} when picking
     * the destination package for generated sources.
     *
     * @param type the type element whose declaring package to resolve; must not be {@code null}
     * @return the fully-qualified package name, possibly empty for the unnamed package
     */
    public String packageNameOf(TypeElement type) {
        return elements.getPackageOf(type).getQualifiedName().toString();
    }

    /**
     * Resolves the given {@link TypeMirror} to a {@link TypeElement} when it represents a declared
     * type. Returns an empty {@link Optional} for primitive, array, type-variable, wildcard, error,
     * or no-type mirrors, or when the mirror is {@code null}.
     *
     * @param type the type mirror to resolve; may be {@code null}
     * @return the type element, or empty when the mirror is not a declared type
     */
    public Optional<TypeElement> asTypeElement(TypeMirror type) {
        if (type == null) return Optional.empty();
        Element el = types.asElement(type);
        return el instanceof TypeElement te ? Optional.of(te) : Optional.empty();
    }

    // --- Constants ---

    /**
     * The processor option key used to override the default per-origin output package.
     * Passed as {@code -Avertique.codegen.package=com.example.generated} on the compiler
     * command line.
     */
    public static final String OPTION_OUTPUT_PACKAGE = "vertique.codegen.package";
}
