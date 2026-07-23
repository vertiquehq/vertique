// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.rest.client.processor.scan;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.rest.client.processor.ParamModel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

/**
 * Scans a {@code @BeanParam} bean type and produces a {@link BeanModel} describing its fields.
 *
 * <p>Two scan strategies are used depending on the type kind:
 * <ul>
 *   <li><strong>Record</strong> ({@code ElementKind.RECORD}) — iterates record components; each
 *       component's annotations are inspected for JAX-RS param annotations.</li>
 *   <li><strong>Class</strong> — walks the declared fields on the type element (does not walk
 *       the superclass chain in the APT model — that requires reflective {@code Class} access).
 *       Only direct fields are scanned.</li>
 * </ul>
 *
 * <p>Access-path resolution for {@link BeanModel#fullyGeneratable()}:
 * <ol>
 *   <li>Record component → record accessor method ({@code name()}) is always accessible.</li>
 *   <li>Public getter ({@code getName()} / {@code isName()}) on the type → accessible.</li>
 *   <li>Public or package-private field → accessible (the generated accessor lives in the same
 *       package as the bean).</li>
 *   <li>None of the above → not generatable; the entire bean falls back to the reflective
 *       accessor.</li>
 * </ol>
 */
public final class BeanParamScanner {

    // --- JAX-RS annotation FQNs ---
    private static final String QUERY_PARAM_FQN = "jakarta.ws.rs.QueryParam";
    private static final String HEADER_PARAM_FQN = "jakarta.ws.rs.HeaderParam";
    private static final String PATH_PARAM_FQN = "jakarta.ws.rs.PathParam";
    private static final String COOKIE_PARAM_FQN = "jakarta.ws.rs.CookieParam";
    private static final String BEAN_PARAM_FQN = "jakarta.ws.rs.BeanParam";
    private static final String DEFAULT_VALUE_FQN = "jakarta.ws.rs.DefaultValue";

    private final CodegenContext ctx;

    /**
     * Creates a new scanner bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public BeanParamScanner(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Scans the given bean type and returns a {@link BeanModel}.
     *
     * <p>If the type is a record, record components are scanned. Otherwise, declared fields
     * are scanned. In both cases, each entry must carry a supported JAX-RS param annotation
     * ({@code @QueryParam}, {@code @HeaderParam}, {@code @PathParam}, {@code @CookieParam},
     * {@code @BeanParam}) to be included in the model.
     *
     * @param beanType the type element to scan; must not be {@code null}
     * @return a {@link BeanModel} with resolved fields and a {@code fullyGeneratable} flag
     */
    public BeanModel scan(TypeElement beanType) {
        if (beanType.getKind() == ElementKind.RECORD) {
            return scanRecord(beanType);
        }
        return scanClass(beanType);
    }

    // --- Private scanning ---

    /**
     * Scans a record type using its record components.
     *
     * <p>JAX-RS annotations like {@code @QueryParam} carry {@code @Target({FIELD, METHOD, PARAMETER})}
     * but NOT {@code RECORD_COMPONENT}, so they do NOT appear on the {@link RecordComponentElement}
     * itself. They land on the synthetic accessor method instead. This method therefore checks the
     * accessor method's annotations first, then falls back to the component element's own
     * annotations for any annotation that does target {@code RECORD_COMPONENT}.
     *
     * <p>The {@code accessExpression} for each record component is the accessor method call
     * {@code bean.componentName()} since record accessors are always public.
     *
     * @param beanType the record type element
     * @return the resulting {@link BeanModel}
     */
    private BeanModel scanRecord(TypeElement beanType) {
        List<ParamModel> fields = new ArrayList<>();
        // Record components are always accessible via their accessor methods
        for (RecordComponentElement component : beanType.getRecordComponents()) {
            ExecutableElement accessor = component.getAccessor();
            // Check accessor method first — that's where @QueryParam/@HeaderParam etc. land
            // because their @Target includes METHOD but not RECORD_COMPONENT
            ParamModel.Kind kind = resolveKind(accessor);
            if (kind == null) {
                // Fallback: check the component element itself for RECORD_COMPONENT-targeted annotations
                kind = resolveKind((Element) component);
            }
            if (kind == null) {
                // No JAX-RS annotation — skip
                continue;
            }
            // Use accessor for name resolution too (annotations reliably present there)
            String wireName = resolveParamName(accessor, kind);
            TypeMirror type = component.asType();
            // Record accessor: bean.componentName()
            String componentName = component.getSimpleName().toString();
            String accessExpr = "bean." + componentName + "()";
            // @DefaultValue may appear on the accessor method or on the component element
            String defaultValue = resolveDefaultValue(accessor);
            if (defaultValue == null) {
                defaultValue = resolveDefaultValue((Element) component);
            }
            // javaName is the Java source identifier (component simple name);
            // wireName is the JAX-RS annotation value (e.g. "q" from @QueryParam("q"))
            fields.add(new ParamModel(kind, wireName, componentName, type, null, accessExpr, defaultValue));
        }
        return new BeanModel(beanType, List.copyOf(fields), true);
    }

    /**
     * Scans a regular class type using its declared fields. Walks superclass chain via the
     * enclosed element model up to but not including {@code Object}.
     *
     * @param beanType the class type element
     * @return the resulting {@link BeanModel}
     */
    private BeanModel scanClass(TypeElement beanType) {
        // Reserve names from EVERY non-static field declared in the chain (annotated or not), so
        // that a more-derived declaration hides any same-named superclass entry. Java field-hiding
        // resolves {@code bean.name} to the most-derived declaration; a metadata entry that came
        // from a hidden superclass field would point at a non-existent annotation source.
        Set<String> declaredNames = new HashSet<>();
        LinkedHashMap<String, ParamModel> fieldsByJavaName = new LinkedHashMap<>();
        boolean fullyGeneratable = true;
        // The generated accessor lives in {@link CodegenContext#outputPackage(TypeElement)} (which
        // honors any -Avertique.codegen.package override). Use that — not the bean's declaring
        // package — for the same-package access check, otherwise an override that relocates the
        // accessor to a foreign package would emit uncompilable {@code bean.field} expressions for
        // package-private fields in the bean's real package.
        String accessorPackage = ctx.outputPackage(beanType);

        TypeElement current = beanType;
        while (current != null && !isObjectType(current)) {
            String declaringPackage = ctx.packageNameOf(current);
            for (Element enclosed : current.getEnclosedElements()) {
                if (enclosed.getKind() != ElementKind.FIELD) {
                    continue;
                }
                VariableElement field = (VariableElement) enclosed;
                if (field.getModifiers().contains(Modifier.STATIC)) {
                    continue;
                }
                String javaName = field.getSimpleName().toString();
                if (!declaredNames.add(javaName)) {
                    // A more-derived declaration (annotated or not) already reserved this name.
                    continue;
                }
                ParamModel.Kind kind = resolveKind(field);
                if (kind == null) {
                    continue;
                }
                String wireName = resolveParamName(field, kind);
                TypeMirror type = field.asType();
                String defaultValue = resolveDefaultValue(field);

                String accessExpr;
                Set<Modifier> mods = field.getModifiers();
                boolean fieldIsPublic = mods.contains(Modifier.PUBLIC);
                boolean samePackage = declaringPackage.equals(accessorPackage);
                boolean directAccessAllowed = !mods.contains(Modifier.PRIVATE) && (fieldIsPublic || samePackage);

                if (directAccessAllowed) {
                    accessExpr = "bean." + javaName;
                } else {
                    // Private field, OR cross-package protected/package-private field — require getter
                    String getter = resolveGetterName(beanType, field);
                    if (getter == null) {
                        fullyGeneratable = false;
                        accessExpr = "bean." + javaName; // placeholder; bean falls back to reflective
                    } else {
                        accessExpr = "bean." + getter + "()";
                    }
                }
                fieldsByJavaName.put(
                        javaName, new ParamModel(kind, wireName, javaName, type, field, accessExpr, defaultValue));
            }
            current = superclassElement(current);
        }

        return new BeanModel(beanType, List.copyOf(fieldsByJavaName.values()), fullyGeneratable);
    }

    // --- Access-path helpers ---

    /**
     * Resolves the public getter method name for the given private field, or {@code null} if none.
     *
     * <p>Candidates (first match wins):
     * <ol>
     *   <li>{@code getName()} — conventional JavaBeans getter.</li>
     *   <li>{@code isName()} — conventional boolean getter.</li>
     *   <li>{@code name()} — fluent accessor (same name as field).</li>
     * </ol>
     *
     * @param type the type to search for a getter
     * @param field the field to find a getter for
     * @return the matching getter method name, or {@code null} if not found
     */
    private String resolveGetterName(TypeElement type, VariableElement field) {
        String fieldName = field.getSimpleName().toString();
        String capitalised = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        String getterName = "get" + capitalised;
        String boolGetterName = "is" + capitalised;

        // Walk the superclass chain so an inherited getter (e.g. private @QueryParam field on
        // Base with public getX() on Base, instance is Derived) is still resolvable.
        TypeElement current = type;
        while (current != null && !isObjectType(current)) {
            for (Element enclosed : current.getEnclosedElements()) {
                if (enclosed.getKind() != ElementKind.METHOD) {
                    continue;
                }
                ExecutableElement method = (ExecutableElement) enclosed;
                if (!method.getModifiers().contains(Modifier.PUBLIC)) {
                    continue;
                }
                if (method.getParameters().isEmpty()) {
                    String name = method.getSimpleName().toString();
                    if (name.equals(getterName)) {
                        return getterName;
                    }
                    if (name.equals(boolGetterName)) {
                        return boolGetterName;
                    }
                    if (name.equals(fieldName)) {
                        // Fluent accessor (same name as field) also accepted
                        return fieldName;
                    }
                }
            }
            current = superclassElement(current);
        }
        return null;
    }

    // --- Annotation helpers ---

    /**
     * Reads the {@code value} attribute of {@code @DefaultValue} on the given element.
     *
     * @param element the element to inspect (field, record component accessor, or method)
     * @return the default value string, or {@code null} if {@code @DefaultValue} is absent
     */
    private String resolveDefaultValue(Element element) {
        return AnnotationMirrors.findByFqn(element, DEFAULT_VALUE_FQN)
                .flatMap(mirror -> ctx.annotations().attribute(mirror, "value", String.class))
                .orElse(null);
    }

    /**
     * Resolves the {@link ParamModel.Kind} for the given element by inspecting its annotations.
     *
     * @param element the element to inspect (field, record component, or parameter)
     * @return the resolved kind, or {@code null} if no JAX-RS param annotation is present
     */
    private ParamModel.Kind resolveKind(Element element) {
        if (AnnotationMirrors.isPresent(element, PATH_PARAM_FQN)) return ParamModel.Kind.PATH;
        if (AnnotationMirrors.isPresent(element, QUERY_PARAM_FQN)) return ParamModel.Kind.QUERY;
        if (AnnotationMirrors.isPresent(element, HEADER_PARAM_FQN)) return ParamModel.Kind.HEADER;
        if (AnnotationMirrors.isPresent(element, COOKIE_PARAM_FQN)) return ParamModel.Kind.COOKIE;
        if (AnnotationMirrors.isPresent(element, BEAN_PARAM_FQN)) return ParamModel.Kind.BEAN;
        return null;
    }

    /**
     * Extracts the annotation value (e.g. the path param name) for the given kind.
     * Falls back to the element's simple name when the annotation value is absent.
     *
     * @param element the element to read from
     * @param kind the resolved parameter kind
     * @return the annotation value or element name
     */
    private String resolveParamName(Element element, ParamModel.Kind kind) {
        // JAX-RS 4.0.0 has no "blank means use Java identifier" convention for any of @PathParam,
        // @QueryParam, @HeaderParam, or @CookieParam — that is a Spring @RequestParam behavior.
        // Runtime client (ClientInterfaceScanner / RestClientRequestFactory) and server
        // (ParameterExtractor) both preserve the literal annotation value end-to-end. Codegen
        // must mirror that so generated proxies and reflective runtime emit the same wire request
        // for the same source. A blank value lands in the model as-is; PathPlaceholderValidator
        // surfaces a "no matching placeholder" diagnostic for blank @PathParam, and a blank
        // @QueryParam/@HeaderParam/@CookieParam produces the same wire output as runtime ("=v").
        String annotationFqn =
                switch (kind) {
                    case PATH -> PATH_PARAM_FQN;
                    case QUERY -> QUERY_PARAM_FQN;
                    case HEADER -> HEADER_PARAM_FQN;
                    case COOKIE -> COOKIE_PARAM_FQN;
                    case BEAN, BODY, URL -> null;
                };
        if (annotationFqn == null) {
            return element.getSimpleName().toString();
        }
        return AnnotationMirrors.findByFqn(element, annotationFqn)
                .flatMap(mirror -> ctx.annotations().attribute(mirror, "value", String.class))
                .orElse("");
    }

    // --- Type helpers ---

    /**
     * Returns the superclass as a {@link TypeElement}, or {@code null} if the superclass is
     * {@code Object} or unavailable.
     *
     * @param type the type element whose superclass is needed
     * @return the superclass element, or {@code null}
     */
    private TypeElement superclassElement(TypeElement type) {
        var superclass = type.getSuperclass();
        if (superclass == null) {
            return null;
        }
        var element = ctx.types().asElement(superclass);
        if (!(element instanceof TypeElement te)) {
            return null;
        }
        if (isObjectType(te)) {
            return null;
        }
        return te;
    }

    /**
     * Returns {@code true} when the type element represents {@code java.lang.Object}.
     *
     * @param type the type element to check
     * @return {@code true} if this is {@code Object}
     */
    private boolean isObjectType(TypeElement type) {
        return "java.lang.Object".equals(type.getQualifiedName().toString());
    }
}
