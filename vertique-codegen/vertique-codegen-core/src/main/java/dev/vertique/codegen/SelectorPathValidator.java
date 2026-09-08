// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen;

import java.util.Set;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * INTERNAL framework seam — processor-authoring substrate consumed by sibling framework modules;
 * not an application contract and outside the maturity promise. An application uses the wiring
 * annotations this module documents and never calls this type.
 *
 * <p>Validates compile-time selector paths for an annotation family.
 */
public final class SelectorPathValidator {

    /** Maximum number of UTF-16 code units permitted in one selector path. */
    public static final int MAX_PATH_LENGTH = 256;

    /** Maximum number of segments permitted in one selector path, including its root parameter. */
    public static final int MAX_SEGMENTS = 8;

    private static final Set<String> SCALAR_TYPE_NAMES = Set.of(
            "java.lang.String",
            "java.lang.Character",
            "java.lang.Boolean",
            "java.lang.Byte",
            "java.lang.Short",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Float",
            "java.lang.Double",
            "java.math.BigInteger",
            "java.math.BigDecimal",
            "java.util.UUID",
            "java.time.Instant",
            "java.time.LocalDate",
            "java.time.LocalDateTime",
            "java.time.OffsetDateTime",
            "java.time.ZonedDateTime");

    private final CodegenContext context;
    private final String family;

    /**
     * Creates a selector-path validator.
     *
     * @param context annotation-processing context used for type inspection and diagnostics
     * @param family diagnostic family prefix
     */
    public SelectorPathValidator(CodegenContext context, String family) {
        this.context = context;
        this.family = family;
    }

    /**
     * Reports every invalid path through the context diagnostics.
     *
     * @param method method whose parameters and source location are used for validation
     * @param paths selector paths to validate
     * @return {@code true} when all paths are valid
     */
    public boolean validate(ExecutableElement method, String[] paths) {
        boolean valid = true;
        for (String path : paths) {
            valid &= validatePath(method, path);
        }
        return valid;
    }

    private boolean validatePath(ExecutableElement method, String path) {
        if (path.isBlank()) {
            context.diagnostics().error(method, Diagnostics.selectorPathBlank(family));
            return false;
        }
        if (path.length() > MAX_PATH_LENGTH) {
            context.diagnostics().error(method, Diagnostics.selectorPathTooLong(family));
            return false;
        }

        String[] segments = path.split("\\.", -1);
        if (segments.length > MAX_SEGMENTS || segments[0].isBlank()) {
            context.diagnostics().error(method, Diagnostics.propertyPathsTooDeep(family));
            return false;
        }
        for (int index = 1; index < segments.length; index++) {
            String segment = segments[index];
            if (!isIdentifier(segment)) {
                context.diagnostics().error(method, Diagnostics.propertyPathInvalidIdentifier(family, segment));
                return false;
            }
        }

        int parameterIndex = parameterIndex(method, segments[0]);
        if (parameterIndex < 0 || parameterIndex >= method.getParameters().size()) {
            context.diagnostics().error(method, Diagnostics.selectorParameterNotFound(family, segments[0]));
            return false;
        }

        TypeMirror type = method.getParameters().get(parameterIndex).asType();
        for (int index = 1; index < segments.length; index++) {
            type = propertyType(type, segments[index]);
            if (type == null) {
                context.diagnostics().error(method, Diagnostics.propertyAccessorNotFound(family, segments[index]));
                return false;
            }
        }
        if (!isScalar(type)) {
            context.diagnostics().error(method, Diagnostics.selectorNotScalar(family));
            return false;
        }
        return true;
    }

    private int parameterIndex(ExecutableElement method, String root) {
        try {
            return Integer.parseInt(root);
        } catch (NumberFormatException ignored) {
            for (int index = 0; index < method.getParameters().size(); index++) {
                if (root.contentEquals(method.getParameters().get(index).getSimpleName())) {
                    return index;
                }
            }
            return -1;
        }
    }

    private TypeMirror propertyType(TypeMirror type, String property) {
        if (type.getKind() != TypeKind.DECLARED) {
            return null;
        }
        TypeElement element = (TypeElement) ((DeclaredType) type).asElement();
        boolean bareNameEligible = element.getKind() == ElementKind.RECORD;
        String suffix = Character.toUpperCase(property.charAt(0)) + property.substring(1);
        for (Element member : context.elements().getAllMembers(element)) {
            if (member.getKind() == ElementKind.METHOD && member instanceof ExecutableElement method) {
                String name = method.getSimpleName().toString();
                boolean bareNameMatch = bareNameEligible && name.equals(property);
                if ((bareNameMatch || name.equals("get" + suffix) || name.equals("is" + suffix))
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
        return element.getKind() == ElementKind.ENUM
                || SCALAR_TYPE_NAMES.contains(element.getQualifiedName().toString());
    }

    private static boolean isIdentifier(String value) {
        return SourceVersion.isIdentifier(value);
    }
}
