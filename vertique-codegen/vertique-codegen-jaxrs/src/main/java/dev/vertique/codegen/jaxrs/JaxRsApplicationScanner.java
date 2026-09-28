// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.NoAutoWire;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Types;

/**
 * Warns about concrete {@code jakarta.ws.rs.core.Application} subclasses, which the processor never
 * registers: a REST application is declared with {@code @RestApplication} instead.
 *
 * <p>{@link #scan(RoundEnvironment, CodegenContext)} walks the round's root elements for concrete
 * {@code Application} subclasses, top-level or nested at any depth, static or not (local and
 * anonymous classes are not reachable from the root elements), and reports one mandatory warning per
 * subclass, in fully-qualified-name order. The warning is the subclass's binary name followed by
 * static text, the same for every subclass, since neither a {@code getClasses()} body nor another
 * compilation unit's declarations are visible at compile time:
 *
 * <pre>{@code
 * {binary name}: @ApplicationPath and getClasses() have no effect; with no @RestApplication
 * declared in the component, its resources are served on the legacy default mount at
 * jaxrs.basePath; otherwise they are served only where a @RestApplication lists them; declare an
 * application with @RestApplication
 * }</pre>
 *
 * <p>(The warning is one line; it is wrapped here for readability.) It is the only diagnostic a
 * subclass gets, whatever its constructors, annotations, or {@code @ApplicationPath}, and whether or
 * not {@code -Avertique.codegen.autoWire=false} is set. It never fails compilation by itself, though
 * a build that treats warnings as errors fails on it. A subclass annotated {@code @NoAutoWire} gets
 * no diagnostic at all.
 *
 * <p>Since {@code jakarta.ws.rs-api} is a test-scope dependency of this module,
 * {@code jakarta.ws.rs.core.Application} is located by fully-qualified name through
 * {@link javax.lang.model.util.Elements#getTypeElement(CharSequence)} rather than imported; when it
 * is not resolvable, no class is a subclass and nothing is reported.
 */
public final class JaxRsApplicationScanner {

    /** FQN of {@code jakarta.ws.rs.core.Application}. */
    private static final String APPLICATION_FQN = "jakarta.ws.rs.core.Application";

    /** The warning's text after the subclass's binary name; the same for every subclass. */
    private static final String WARNING_TEXT = ": @ApplicationPath and getClasses() have no effect;"
            + " with no @RestApplication declared in the component, its resources are served on the legacy"
            + " default mount at jaxrs.basePath; otherwise they are served only where a @RestApplication"
            + " lists them; declare an application with @RestApplication";

    private JaxRsApplicationScanner() {}

    // --- Public API ---

    /**
     * Walks the round's root elements for concrete {@code Application} subclasses, top-level or
     * nested at any depth, and reports one mandatory warning per subclass not annotated
     * {@code @NoAutoWire}, in fully-qualified-name order: the subclass's binary name followed by
     * this class's static warning text.
     *
     * @param roundEnv the current annotation processing round environment; must not be {@code null}
     * @param ctx      the shared codegen context; must not be {@code null}
     */
    public static void scan(RoundEnvironment roundEnv, CodegenContext ctx) {
        TypeElement applicationType = ctx.elements().getTypeElement(APPLICATION_FQN);
        if (applicationType == null) {
            return;
        }

        List<TypeElement> subclasses = new ArrayList<>();
        for (Element root : roundEnv.getRootElements()) {
            if (root instanceof TypeElement rootType) {
                collectApplicationSubtypes(rootType, applicationType, ctx.types(), subclasses);
            }
        }
        subclasses.sort(Comparator.comparing(t -> t.getQualifiedName().toString()));

        for (TypeElement subclass : subclasses) {
            if (subclass.getAnnotation(NoAutoWire.class) != null) {
                continue;
            }
            // The message is complete as passed, with no format arguments, so it is printed verbatim.
            ctx.diagnostics().mandatoryWarning(subclass, warningFor(subclass, ctx));
        }
    }

    // --- Internal helpers ---

    /** The warning for {@code subclass}: its binary name followed by the static warning text. */
    private static String warningFor(TypeElement subclass, CodegenContext ctx) {
        return ctx.elements().getBinaryName(subclass) + WARNING_TEXT;
    }

    /**
     * Recursively collects every concrete {@code Application} subtype reachable from {@code type},
     * regardless of nesting depth or static-ness.
     */
    private static void collectApplicationSubtypes(
            TypeElement type, TypeElement applicationType, Types types, List<TypeElement> out) {
        if (isConcreteClass(type) && types.isAssignable(type.asType(), applicationType.asType())) {
            out.add(type);
        }
        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed instanceof TypeElement nested) {
                collectApplicationSubtypes(nested, applicationType, types, out);
            }
        }
    }

    private static boolean isConcreteClass(TypeElement type) {
        return type.getKind() == ElementKind.CLASS && !type.getModifiers().contains(Modifier.ABSTRACT);
    }
}
