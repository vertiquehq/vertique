// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dev.vertique.rest.core.interceptor.RequestInterceptor;
import dev.vertique.rest.core.lifecycle.RouterLifecycleHook;
import dev.vertique.rest.core.middleware.ContentTypeValidationMiddleware;
import dev.vertique.rest.core.middleware.Middleware;
import dev.vertique.rest.core.middleware.MiddlewareScope;
import dev.vertique.rest.core.router.MountCompositionValidator;
import dev.vertique.rest.core.router.MountCustomizer;
import dev.vertique.rest.core.router.MountMeta;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.JaxRsRouterMount;
import dev.vertique.rest.jaxrs.application.RestApplications;
import dev.vertique.rest.jaxrs.application.RestApplications.ContractOrigin;
import io.vertx.ext.web.RoutingContext;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The documentation module's composition validator. It is contributed only when at least one
 * document is enabled, and checks the mounts of a composition before any of them creates its router.
 * Of the mounts it reads only each one's {@link RouterMount#mountPath()} and {@link
 * RouterMount#meta()}; of the declared applications it reads the {@link RestApplications} view.
 *
 * <p>An enabled document whose application's contract origin is {@link ContractOrigin#CONFIGURATION}
 * or {@link ContractOrigin#ANNOTATION} serves the contract at the application's effective contract
 * location. Its location is normalized with {@link Path#of(String, String...)} and {@link
 * Path#normalize()}, so {@code ./a.yaml} and {@code a.yaml} are one location. The validator reports:
 *
 * <ul>
 *   <li>each group of two or more such applications whose locations are one location, naming the
 *       applications in name order and never the location;
 *   <li>each such application whose location cannot be parsed as a path, naming the application and
 *       the setting that supplied the location, never its value or the parser's message.
 * </ul>
 *
 * <p>A JAX-RS mount is an instance of {@link JaxRsRouterMount}, whether it serves a declared
 * application or was built by hand. Its normalized path is its mount path without the trailing
 * {@code /*}, and {@code /} for {@code /*}. Of the mounts, the validator also reports:
 *
 * <ul>
 *   <li>each enabled document for which no JAX-RS mount carries the document's name as its
 *       application name; matching is by application name only, never by path;
 *   <li>each JAX-RS mount whose normalized path is the documentation prefix or lies under it;
 *   <li>each JAX-RS mount whose path contains {@code :}, <code>{</code>, or <code>}</code> and whose
 *       literal part before the first such character is a prefix of the documentation prefix, since
 *       such a mount can reach the document URLs and collision checks cover literal paths only.
 * </ul>
 *
 * <p>Mounts that are not JAX-RS mounts are never reported. Only when it reports nothing does the
 * validator mark each documentation mount of the composition validated, which is the only state it
 * writes; it keeps no state of its own between compositions.
 *
 * <p>When it reports nothing and the composition holds a documentation mount, the validator also
 * warns, through {@link DocumentWarnings}, about the mount-scoped controls that apply to each enabled
 * document's application mount but not to the document routes. For each document, in name order, the
 * controls of its application mount are:
 *
 * <ul>
 *   <li>each {@link MountCustomizer} that matches the application mount's meta but not the
 *       documentation mount's meta; a customizer matching both is applied to the documentation
 *       router too, so it covers the document routes;
 *   <li>when the application mount has resources: each {@link Middleware} of scope
 *       {@link MiddlewareScope#API} other than the framework's {@link ContentTypeValidationMiddleware},
 *       each {@link RouterLifecycleHook}, and each {@link RequestInterceptor} that overrides
 *       {@link RequestInterceptor#beforeRequest(RoutingContext)}. A mount without resources creates
 *       its router before any of them runs, so none applies to it.
 * </ul>
 *
 * <p>Besides {@link MountCustomizer#matches(MountMeta)} and {@link Middleware#scope()}, no callback
 * of a control is invoked. The warning lists each control as {@code <kind> <binary class name>}, the
 * kinds in the order above and then by class name, and is logged once per document per component. A
 * public document's warning also states that no {@code OperationHandlerContributor} runs for its
 * document routes; a protected document's routes run every contributor, so its warning omits that.
 * A document whose list is empty logs nothing.
 */
final class DocsCompositionValidator implements MountCompositionValidator {

    /** The warning kind of the uncovered-control warning. */
    private static final String UNCOVERED_CONTROLS = "uncovered-controls";

    private final EnabledDocuments documents;
    private final String prefix;
    private final RestApplications applications;
    private final DocumentWarnings warnings;
    private final Set<MountCustomizer> mountCustomizers;
    private final Set<Middleware> middlewares;
    private final Set<RouterLifecycleHook> lifecycleHooks;
    private final Set<RequestInterceptor> requestInterceptors;

    /**
     * Creates the validator.
     *
     * @param documents the enabled documents, at least one
     * @param prefix the configured documentation prefix, without a trailing slash
     * @param applications the declared applications of the component, whose effective contract
     *     locations are compared
     * @param warnings the documentation module's warnings of the component
     * @param mountCustomizers the component's mount customizers
     * @param middlewares the component's middlewares
     * @param lifecycleHooks the component's router lifecycle hooks
     * @param requestInterceptors the component's request interceptors
     */
    DocsCompositionValidator(
            EnabledDocuments documents,
            String prefix,
            RestApplications applications,
            DocumentWarnings warnings,
            Set<MountCustomizer> mountCustomizers,
            Set<Middleware> middlewares,
            Set<RouterLifecycleHook> lifecycleHooks,
            Set<RequestInterceptor> requestInterceptors) {
        this.documents = documents;
        this.prefix = prefix;
        this.applications = applications;
        this.warnings = warnings;
        this.mountCustomizers = mountCustomizers;
        this.middlewares = middlewares;
        this.lifecycleHooks = lifecycleHooks;
        this.requestInterceptors = requestInterceptors;
    }

    @Override
    public List<String> validate(List<RouterMount> mounts) {
        List<String> violations = new ArrayList<>();
        for (EnabledDocuments.EnabledDocument document : documents.all()) {
            if (applicationMount(mounts, document.name()) == null) {
                violations.add("apidocs.documents." + document.name() + ": application '" + document.name()
                        + "' (declared by " + document.declaringType().getName()
                        + ") has an enabled document, but the composition holds no mount for it");
            }
        }
        violations.addAll(contractLocationViolations());
        for (RouterMount mount : mounts) {
            if (!(mount instanceof JaxRsRouterMount)) {
                continue;
            }
            String mountPath = mount.mountPath();
            String normalized = normalize(mountPath);
            String subject = "Mount " + mount.meta().mountId() + " at '" + mountPath + "' ";
            if (normalized.equals(prefix) || normalized.startsWith(prefix + "/")) {
                violations.add(subject + "lies at or under the documentation prefix '" + prefix
                        + "' ('apidocs.path'); move the mount or choose another apidocs.path");
            } else if (patternReachesPrefix(mountPath)) {
                violations.add(subject + "has a pattern path whose literal part is a prefix of 'apidocs.path' ('"
                        + prefix + "'); collision checks are defined for literal JAX-RS mount paths only, so"
                        + " documents cannot be enabled beside a pattern mount that can reach the document URLs");
            }
        }
        if (!violations.isEmpty()) {
            Collections.sort(violations);
            return List.copyOf(violations);
        }
        MountMeta docsMeta = null;
        for (RouterMount mount : mounts) {
            if (mount instanceof DocsRouterMount docsMount) {
                docsMount.markValidated();
                if (docsMeta == null) {
                    docsMeta = docsMount.meta();
                }
            }
        }
        if (docsMeta != null) {
            warnUncoveredControls(mounts, docsMeta);
        }
        return List.of();
    }

    /**
     * Reports the contract locations of the enabled documents whose applications serve their own
     * contracts: one violation per group of two or more applications whose effective locations are
     * equal once normalized, and one per application whose location cannot be parsed as a path. No
     * violation names a location, and none carries the parser's exception.
     *
     * @return the violations, unsorted
     */
    private List<String> contractLocationViolations() {
        List<String> violations = new ArrayList<>();
        Map<Path, List<String>> byLocation = new LinkedHashMap<>();
        for (EnabledDocuments.EnabledDocument document : documents.all()) {
            if (document.contractOrigin() == ContractOrigin.GLOBAL) {
                continue;
            }
            Optional<RestApplications.Entry> entry =
                    applications.byName(document.name()).filter(RestApplications.Entry::active);
            if (entry.isEmpty()) {
                continue;
            }
            RestApplications.Entry application = entry.get();
            if (application.effectiveOpenapiPath() == null) {
                violations.add("apidocs.documents." + application.name() + ": application '" + application.name()
                        + "' (declared by " + application.declaringType().getName()
                        + ") serves its own contract as its document, but has no contract location");
                continue;
            }
            Path location;
            try {
                location = Path.of(application.effectiveOpenapiPath()).normalize();
            } catch (InvalidPathException unparseable) {
                violations.add("apidocs.documents." + application.name() + ": application '" + application.name()
                        + "' (declared by " + application.declaringType().getName()
                        + ") serves its own contract as its document, but its contract location ("
                        + ServedContractSource.setting(application) + ") cannot be parsed as a path");
                continue;
            }
            byLocation.computeIfAbsent(location, key -> new ArrayList<>()).add(application.name());
        }
        for (List<String> names : byLocation.values()) {
            if (names.size() < 2) {
                continue;
            }
            String quoted =
                    names.stream().sorted().map(name -> "'" + name + "'").collect(Collectors.joining(", "));
            violations.add("apidocs.documents: applications " + quoted
                    + " serve documents with one contract location; each application serving its own contract"
                    + " as its document needs a contract location of its own");
        }
        return violations;
    }

    /**
     * Warns, once per document, about the mount-scoped controls of each enabled document's
     * application mount that do not apply to the document routes.
     *
     * @param mounts the mounts of the composition
     * @param docsMeta the documentation mount's meta
     */
    private void warnUncoveredControls(List<RouterMount> mounts, MountMeta docsMeta) {
        for (EnabledDocuments.EnabledDocument document : documents.all()) {
            RouterMount mount = applicationMount(mounts, document.name());
            if (mount == null) {
                continue;
            }
            List<String> controls = uncoveredControls(mount.meta(), docsMeta);
            if (controls.isEmpty()) {
                continue;
            }
            String configPath = "apidocs.documents." + document.name();
            StringBuilder message = new StringBuilder()
                    .append(configPath)
                    .append(": mount '")
                    .append(mount.mountPath())
                    .append("' has mount-scoped controls that do not cover the document routes, which bypass the"
                            + " described mount's controls while main-router middleware still applies: ")
                    .append(String.join(", ", controls));
            if (document.access() != ApiDocs.Access.PROTECTED) {
                message.append("; no OperationHandlerContributor runs for the document routes");
            }
            warnings.warnOnce(UNCOVERED_CONTROLS, document.name(), message.toString());
        }
    }

    /**
     * Lists the mount-scoped controls that apply to a mount but not to the document routes, as
     * {@code <kind> <binary class name>}, the kinds in a fixed order and then by class name.
     *
     * @param meta the application mount's meta
     * @param docsMeta the documentation mount's meta
     * @return the entries, empty when every applying control also covers the document routes
     */
    private List<String> uncoveredControls(MountMeta meta, MountMeta docsMeta) {
        List<String> controls = new ArrayList<>();
        controls.addAll(entries(
                "MountCustomizer",
                mountCustomizers.stream()
                        .filter(customizer -> customizer.matches(meta) && !customizer.matches(docsMeta))
                        .map(Object::getClass)
                        .toList()));
        if (!meta.resourceTypes().isEmpty()) {
            controls.addAll(entries(
                    "API middleware",
                    middlewares.stream()
                            .filter(middleware -> middleware.scope() == MiddlewareScope.API
                                    && middleware.getClass() != ContentTypeValidationMiddleware.class)
                            .map(Object::getClass)
                            .toList()));
            controls.addAll(entries(
                    "RouterLifecycleHook",
                    lifecycleHooks.stream().map(Object::getClass).toList()));
            controls.addAll(entries(
                    "RequestInterceptor",
                    requestInterceptors.stream()
                            .filter(DocsCompositionValidator::overridesBeforeRequest)
                            .map(Object::getClass)
                            .toList()));
        }
        return controls;
    }

    /** Formats one kind's classes as {@code <kind> <binary class name>} entries, sorted by class name. */
    private static List<String> entries(String kind, List<Class<?>> types) {
        return types.stream()
                .map(Class::getName)
                .sorted()
                .map(name -> kind + " " + name)
                .toList();
    }

    /** Reports whether an interceptor's class declares its own {@code beforeRequest}. */
    private static boolean overridesBeforeRequest(RequestInterceptor interceptor) {
        try {
            return interceptor
                            .getClass()
                            .getMethod("beforeRequest", RoutingContext.class)
                            .getDeclaringClass()
                    != RequestInterceptor.class;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("RequestInterceptor declares beforeRequest(RoutingContext)", e);
        }
    }

    /** Returns the first JAX-RS mount carrying the given application name, or {@code null}. */
    private static RouterMount applicationMount(List<RouterMount> mounts, String name) {
        for (RouterMount mount : mounts) {
            if (mount instanceof JaxRsRouterMount && name.equals(mount.meta().applicationName())) {
                return mount;
            }
        }
        return null;
    }

    /** Strips the trailing {@code /*} of a mount path, giving {@code /} for {@code /*}. */
    private static String normalize(String mountPath) {
        if (mountPath.equals("/*")) {
            return "/";
        }
        if (mountPath.endsWith("/*")) {
            return mountPath.substring(0, mountPath.length() - 2);
        }
        return mountPath;
    }

    /**
     * Reports whether a mount path holds a pattern character and its literal part before the first
     * one is a prefix of the documentation prefix.
     */
    private boolean patternReachesPrefix(String mountPath) {
        int first = -1;
        for (int i = 0; i < mountPath.length(); i++) {
            char c = mountPath.charAt(i);
            if (c == ':' || c == '{' || c == '}') {
                first = i;
                break;
            }
        }
        return first >= 0 && prefix.startsWith(mountPath.substring(0, first));
    }
}
