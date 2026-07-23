// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.rest.client.processor;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.NoAutoWire;
import dev.vertique.codegen.rest.client.processor.emit.BeanAccessorEmitter;
import dev.vertique.codegen.rest.client.processor.emit.ProxyEmitter;
import dev.vertique.codegen.rest.client.processor.scan.BeanModel;
import dev.vertique.codegen.rest.client.processor.scan.BeanParamScanner;
import dev.vertique.codegen.rest.client.processor.scan.ClientInterfaceScanner;
import dev.vertique.codegen.rest.client.processor.validate.HttpVerbValidator;
import dev.vertique.codegen.rest.client.processor.validate.PathPlaceholderValidator;
import dev.vertique.codegen.rest.client.processor.validate.ReturnTypeValidator;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;

/**
 * Annotation processor that generates static proxy classes and {@code BeanParamAccessor}
 * implementations for {@code @RestClient}-annotated interfaces.
 *
 * <p>For each {@code @RestClient} interface in the compilation unit, this processor:
 * <ol>
 *   <li>Scans the interface to produce a {@link ClientInterfaceModel}.</li>
 *   <li>Validates: return types ({@link ReturnTypeValidator}), path placeholders
 *       ({@link PathPlaceholderValidator}), HTTP verbs ({@link HttpVerbValidator}).</li>
 *   <li>Emits {@code {Client}_RestClientProxy} via {@link ProxyEmitter}.</li>
 *   <li>Emits {@code {Bean}_BeanParamAccessor} for each {@code @BeanParam} type via
 *       {@link BeanAccessorEmitter} (deduplicated across all interfaces in the round).</li>
 * </ol>
 *
 * <p>Interfaces annotated with {@link NoAutoWire} are skipped (respects the CG-002 opt-out
 * convention).
 *
 * <p>The processor runs a single emit round via the {@code emitted} guard flag — no accumulation
 * across rounds. This mirrors the pattern established by CG-002's {@code AutoWireProcessor}.
 *
 * <p>Processor options:
 * <ul>
 *   <li>{@code vertique.codegen.package} — overrides the output package for generated sources
 *       (default: same package as the origin type, per {@link CodegenContext#outputPackage}).</li>
 *   <li>{@code vertique.codegen.restclient.warnExternalBeans} — when {@code true} (default),
 *       a {@code WARNING} is emitted for {@code @BeanParam} types not visible in the compilation
 *       unit; set to {@code false} to suppress.</li>
 * </ul>
 */
@SupportedAnnotationTypes("dev.vertique.rest.client.RestClient")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
@SupportedOptions({"vertique.codegen.package", "vertique.codegen.restclient.warnExternalBeans"})
public final class RestClientProcessor extends AbstractProcessor {

    private static final String REST_CLIENT_FQN = "dev.vertique.rest.client.RestClient";
    private static final String NO_AUTO_WIRE_FQN = NoAutoWire.class.getName();
    private static final String OPTION_WARN_EXTERNAL = "vertique.codegen.restclient.warnExternalBeans";

    private CodegenContext ctx;
    private ClientInterfaceScanner interfaceScanner;
    private BeanParamScanner beanParamScanner;
    private ReturnTypeValidator returnTypeValidator;
    private PathPlaceholderValidator pathPlaceholderValidator;
    private HttpVerbValidator httpVerbValidator;
    private ProxyEmitter proxyEmitter;
    private BeanAccessorEmitter beanAccessorEmitter;
    private boolean warnExternalBeans;
    private boolean emitted;

    /**
     * Constructs a new {@code RestClientProcessor}. Required by the {@link java.util.ServiceLoader}
     * mechanism used to load annotation processors.
     */
    public RestClientProcessor() {}

    /**
     * {@inheritDoc}
     *
     * <p>Initialises the shared {@link CodegenContext}, scanners, validators, and emitters.
     */
    @Override
    public synchronized void init(javax.annotation.processing.ProcessingEnvironment env) {
        super.init(env);
        ctx = new CodegenContext(env);
        interfaceScanner = new ClientInterfaceScanner(ctx);
        beanParamScanner = new BeanParamScanner(ctx);
        returnTypeValidator = new ReturnTypeValidator(ctx);
        pathPlaceholderValidator = new PathPlaceholderValidator(ctx);
        httpVerbValidator = new HttpVerbValidator(ctx);
        proxyEmitter = new ProxyEmitter(ctx);
        beanAccessorEmitter = new BeanAccessorEmitter(ctx);
        String warnOption = env.getOptions().get(OPTION_WARN_EXTERNAL);
        warnExternalBeans = !"false".equalsIgnoreCase(warnOption);
        emitted = false;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Processes {@code @RestClient}-annotated types in the first non-{@code processingOver}
     * round. Subsequent rounds (including Dagger's generated-source rounds) are skipped via the
     * {@code emitted} guard.
     *
     * <p>Always returns {@code false} so that other processors continue to see the same elements.
     *
     * @param annotations the annotation types being processed in this round
     * @param roundEnv the round environment
     * @return {@code false} always
     */
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver() || emitted) {
            return false;
        }

        Set<BeanModel> allBeanModels = new LinkedHashSet<>();
        Set<String> externalBeanTypes = new LinkedHashSet<>();

        for (Element element : roundEnv.getElementsAnnotatedWith(ctx.elements().getTypeElement(REST_CLIENT_FQN))) {
            if (!(element instanceof TypeElement typeElement)) {
                continue;
            }
            // Only interfaces
            if (typeElement.getKind() != ElementKind.INTERFACE) {
                continue;
            }
            // Respect @NoAutoWire opt-out
            if (AnnotationMirrors.isPresent(typeElement, NO_AUTO_WIRE_FQN)) {
                continue;
            }

            // Scan
            ClientInterfaceModel model = interfaceScanner.scan(typeElement);

            // Validate (emit errors but keep going to surface all errors in one compile)
            boolean verbsOk = httpVerbValidator.validate(model);
            boolean returnTypesOk = true;
            for (var method : model.methods()) {
                returnTypesOk &= returnTypeValidator.validate(method);
            }
            boolean pathsOk = pathPlaceholderValidator.validate(model);

            if (!verbsOk || !returnTypesOk || !pathsOk) {
                // Do not emit if validation failed
                emitted = true;
                return false;
            }

            // Resolve bean models for this interface BEFORE proxy emit so the proxy can emit
            // per-field accessor.extract(...) calls inline. If any referenced bean is external
            // (out of compilation unit) we cannot inline per-field calls — skip proxy emission for
            // this interface so the runtime falls back to the JDK reflective proxy (the
            // RestClientBuilder.build() Class.forName lookup misses cleanly). Per-field accessor
            // generation for in-compilation beans still happens.
            java.util.Map<String, BeanModel> beanModelsByFqn = new java.util.LinkedHashMap<>();
            boolean hasExternalBean = false;
            for (TypeElement beanType : model.referencedBeans()) {
                String fqn = beanType.getQualifiedName().toString();
                if (isExternalType(beanType, roundEnv)) {
                    if (warnExternalBeans) {
                        ctx.diagnostics()
                                .warning(
                                        typeElement,
                                        "@BeanParam type %s is not in the compilation unit; no proxy generated for %s,"
                                                + " runtime reflective JDK proxy applies",
                                        fqn,
                                        typeElement.getQualifiedName());
                    }
                    externalBeanTypes.add(fqn);
                    hasExternalBean = true;
                    continue;
                }
                BeanModel beanModel = beanParamScanner.scan(beanType);
                beanModelsByFqn.put(fqn, beanModel);
                allBeanModels.add(beanModel);
            }

            if (!hasExternalBean) {
                proxyEmitter.emit(model, beanModelsByFqn);
            }
        }

        // Emit bean accessors (deduplicated)
        beanAccessorEmitter.emit(allBeanModels, externalBeanTypes);

        emitted = true;
        return false;
    }

    // --- Internal helpers ---

    /**
     * Returns {@code true} when the given type is not in the current compilation unit (i.e., it
     * comes from an already-compiled class on the classpath rather than from source being compiled
     * in this round).
     *
     * <p>Root elements in APT are top-level types only — nested/static inner classes are NOT root
     * elements even when their enclosing type is being compiled. To correctly classify nested
     * types, this method walks the enclosing element chain until it finds a top-level type and
     * checks whether THAT type is a root element.
     *
     * @param beanType the bean type element to check
     * @param roundEnv the current round environment
     * @return {@code true} if external; {@code false} if it is in the compilation unit
     */
    private boolean isExternalType(TypeElement beanType, RoundEnvironment roundEnv) {
        // Walk up to find the top-level enclosing type
        TypeElement topLevel = beanType;
        while (topLevel.getEnclosingElement() instanceof TypeElement enclosing) {
            topLevel = enclosing;
        }
        return !roundEnv.getRootElements().contains(topLevel);
    }
}
