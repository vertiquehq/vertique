// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.meta;

import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.WildcardTypeName;
import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * INTERNAL framework seam — processor-authoring substrate consumed by sibling framework modules;
 * not an application contract and outside the maturity promise. An application uses the wiring
 * annotations this module documents and never calls this type.
 *
 * <p>Reusable JavaPoet emitter that generates a contract-correct annotation literal — a
 * {@code final class <Ann>$<Namespace>Literal implements <Ann>} — whose member accessors return the
 * processor-read attribute values baked as compile-time constants, with no {@code getAnnotation}
 * reflection at call time.
 *
 * <p>The generated literal follows the shape proven by the slice-0.3 feasibility spike
 * (branch (a), OQ-2): a per-member accessor returning the stored constant, an
 * {@link Annotation#annotationType() annotationType()} returning the annotation's {@code Class}
 * literal, and {@code equals}/{@code hashCode} implemented to the {@link Annotation} contract so a
 * generated literal is interchangeable with the JDK reflective proxy in hash-based collections:
 * <ul>
 *   <li>{@code equals} compares each member against the annotation <em>interface</em> (so the
 *       literal equals the JDK proxy, preserving symmetry);
 *   <li>{@code hashCode} is the sum, over every member, of
 *       {@code (127 * memberName.hashCode()) ^ memberValueHashCode}.
 * </ul>
 *
 * <p><strong>Supported attribute kinds:</strong> all legal annotation member kinds, including the
 * primitive types, {@code String}, {@code Class<?>}, enum constants, nested annotations, and arrays
 * of those. Primitive floating-point values are rendered from their exact bit patterns so the
 * generated literal preserves values such as NaN, infinities, and negative zero.
 *
 * <p>The literal is emitted as a top-level class in the annotation's own package, named
 * {@code <AnnSimpleName>$<Namespace>Literal} (binary-name style); the consuming processor
 * references it as a {@code static final <Ann>} constant.
 */
public final class AnnotationLiteralEmitter {

    private AnnotationLiteralEmitter() {}

    /** Retained result type for the compatibility hook that historically identified unsupported kinds. */
    public record UnsupportedAttribute(String member, String kind) {}

    /**
     * Retained compatibility hook for consumers that historically detected member kinds the emitter
     * could not render. All legal annotation member kinds are now renderable, so this method always
     * returns {@link java.util.Optional#empty()}.
     *
     * @param annotationType the annotation type element; retained for signature compatibility
     * @return always {@link java.util.Optional#empty()}
     */
    public static java.util.Optional<UnsupportedAttribute> firstUnsupportedAttribute(TypeElement annotationType) {
        return java.util.Optional.empty();
    }

    /**
     * Computes the binary class name of the literal generated for the given annotation type
     * ({@code <annPackage>.<AnnSimpleName>$<Namespace>Literal}).
     *
     * @param annotationType the aspect annotation type element; must not be {@code null}
     * @param elements       the {@link Elements} utility used to resolve the package; must not be
     *                       {@code null}
     * @param generatorNamespace the processor-owned namespace inserted into the generated type name
     * @return the literal's {@link ClassName} (binary-name style, with a processor-owned namespace)
     * @throws IllegalArgumentException when {@code generatorNamespace} is blank or is not a Java
     *                                  identifier fragment
     */
    public static ClassName literalClassName(TypeElement annotationType, Elements elements, String generatorNamespace) {
        validateGeneratorNamespace(generatorNamespace);
        String pkg = elements.getPackageOf(annotationType).getQualifiedName().toString();
        return ClassName.get(pkg, annotationType.getSimpleName() + "$" + generatorNamespace + "Literal");
    }

    /**
     * Emits the {@code <Ann>$<Namespace>Literal implements <Ann>} class for the given annotation
     * mirror.
     *
     * @param annotationType the aspect annotation type element; must not be {@code null}
     * @param mirror         the annotation mirror present on the intercepted method, read for its
     *                       member values (defaults included); must not be {@code null}
     * @param elements       the {@link Elements} utility (for default values and package); must not
     *                       be {@code null}
     * @param types          the {@link Types} utility (for erasing {@code Class} attribute values);
     *                       must not be {@code null}
     * @param generatorNamespace the processor-owned namespace inserted into the generated type name
     * @return a {@link JavaFile} containing the generated literal class
     * @throws IllegalArgumentException when {@code generatorNamespace} is blank or is not a Java
     *                                  identifier fragment
     */
    public static JavaFile emit(
            TypeElement annotationType,
            AnnotationMirror mirror,
            Elements elements,
            Types types,
            String generatorNamespace) {
        validateGeneratorNamespace(generatorNamespace);
        ClassName annClass = ClassName.get(annotationType);
        ClassName literalClass = literalClassName(annotationType, elements, generatorNamespace);

        List<Map.Entry<ExecutableElement, AnnotationValue>> values = orderedValues(mirror, elements);

        TypeSpec.Builder type = TypeSpec.classBuilder(literalClass.simpleName())
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(annClass);

        // One field + constructor-arg + accessor per member.
        MethodSpec.Builder ctor = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC);
        CodeBlock.Builder hashBody = CodeBlock.builder();
        CodeBlock.Builder equalsChecks = CodeBlock.builder();
        boolean first = true;

        for (Map.Entry<ExecutableElement, AnnotationValue> entry : values) {
            String member = entry.getKey().getSimpleName().toString();
            TypeMirror memberType = entry.getKey().getReturnType();
            TypeName fieldType = memberFieldType(memberType, types);

            boolean isArray = memberType.getKind() == TypeKind.ARRAY;

            type.addField(fieldType, member, Modifier.PRIVATE, Modifier.FINAL);
            ctor.addParameter(fieldType, member).addStatement("this.$N = $N", member, member);
            // Array members defensively clone on read (the Annotation array-member contract — each
            // call returns a fresh copy so callers cannot mutate the literal's stored array).
            type.addMethod(MethodSpec.methodBuilder(member)
                    .addAnnotation(Override.class)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(fieldType)
                    .addStatement(isArray ? "return $N.clone()" : "return $N", member)
                    .build());

            // hashCode term: (127 * "member".hashCode()) ^ <valueHash>
            CodeBlock valueHash = isArray ? arrayValueHash(member) : memberValueHash(member, fieldType);
            if (first) {
                hashBody.add("return ((127 * $S.hashCode()) ^ $L)", member, valueHash);
            } else {
                hashBody.add("\n+ ((127 * $S.hashCode()) ^ $L)", member, valueHash);
            }

            // equals term: <member-equals>(other.member())
            equalsChecks.add(first ? "" : "\n&& ");
            equalsChecks.add(isArray ? arrayEquals(member, (ArrayType) memberType) : memberEquals(member, fieldType));
            first = false;
        }

        type.addMethod(ctor.build());

        // annotationType() -> Ann.class
        type.addMethod(MethodSpec.methodBuilder("annotationType")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(
                        ClassName.get(Class.class), WildcardTypeName.subtypeOf(ClassName.get(Annotation.class))))
                .addStatement("return $T.class", annClass)
                .build());

        // equals(Object) — compared against the annotation interface (symmetry with the JDK proxy)
        CodeBlock equalsExpr = first ? CodeBlock.of("true") : equalsChecks.build();
        type.addMethod(MethodSpec.methodBuilder("equals")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(boolean.class)
                .addParameter(Object.class, "o")
                .beginControlFlow("if (!(o instanceof $T other))", annClass)
                .addStatement("return false")
                .endControlFlow()
                .addStatement("return $L", equalsExpr)
                .build());

        // hashCode() — Annotation contract
        CodeBlock hashExpr = first ? CodeBlock.of("return 0") : hashBody.build();
        type.addMethod(MethodSpec.methodBuilder("hashCode")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(int.class)
                .addStatement("$L", hashExpr)
                .build());

        return JavaFile.builder(literalClass.packageName(), type.build()).build();
    }

    private static void validateGeneratorNamespace(String generatorNamespace) {
        if (generatorNamespace == null
                || generatorNamespace.isBlank()
                || !SourceVersion.isIdentifier(generatorNamespace)) {
            throw new IllegalArgumentException(
                    "generatorNamespace must be a non-blank Java identifier fragment: " + generatorNamespace);
        }
    }

    // --- member rendering helpers ---

    /**
     * Resolves the Java field/accessor type for an annotation member: a primitive stays primitive, a
     * {@code Class} attribute preserves its <em>declared</em> parameterization (e.g. a
     * {@code Class<? extends Number>} member stays {@code Class<? extends Number>}), an enum/String
     * becomes its declared type.
     *
     * <p>The {@code Class} member's declared type is preserved — rather than normalized to
     * {@code Class<?>} — so the generated {@code <Ann>$<Namespace>Literal} accessor's return type matches the
     * annotation interface method exactly and therefore overrides it. A normalized {@code Class<?>}
     * accessor is <em>not</em> covariant with a bounded {@code Class<? extends Number>} interface
     * method, so it would fail to override and the generated literal source would not compile. The
     * actual {@code .class} value <em>expression</em> is still erased (see {@link #valueLiteral}); only
     * the field/accessor <em>type</em> is preserved here.
     *
     * @param memberType the member's declared return type
     * @param types      the {@link Types} utility for erasure
     * @return the field/accessor {@link TypeName}
     */
    private static TypeName memberFieldType(TypeMirror memberType, Types types) {
        if (memberType.getKind() == TypeKind.ARRAY) {
            TypeMirror component = ((ArrayType) memberType).getComponentType();
            return ArrayTypeName.of(memberFieldType(component, types));
        }
        if (isClassMember(memberType)) {
            // Preserve the declared Class parameterization so the accessor overrides the interface
            // method (the value-side .class literal is erased separately in valueLiteral).
            return TypeName.get(memberType);
        }
        return TypeName.get(types.erasure(memberType));
    }

    /** Returns {@code true} when the member's type is {@code java.lang.Class} (or a parameterization). */
    private static boolean isClassMember(TypeMirror memberType) {
        return memberType.getKind() == TypeKind.DECLARED
                && ((DeclaredType) memberType).asElement() instanceof TypeElement element
                && element.getQualifiedName().contentEquals("java.lang.Class");
    }

    /**
     * Builds the per-member hashCode value term, boxing primitives so the {@code .hashCode()}
     * call is valid (the {@link Annotation} spec uses the boxed value's hash for primitive members).
     *
     * <p>Routes on {@link TypeName#isPrimitive()} so every primitive kind, including
     * {@code char}, {@code float}, and {@code double}, uses the corresponding boxed value's
     * contract-defined hash implementation.
     *
     * @param member    the annotation member name
     * @param fieldType the member's resolved field {@link TypeName}
     * @return the {@link CodeBlock} computing the member's value hash
     */
    private static CodeBlock memberValueHash(String member, TypeName fieldType) {
        if (fieldType.isPrimitive()) {
            if (fieldType.equals(TypeName.INT)) {
                return CodeBlock.of("$T.valueOf($N).hashCode()", Integer.class, member);
            }
            if (fieldType.equals(TypeName.LONG)) {
                return CodeBlock.of("$T.valueOf($N).hashCode()", Long.class, member);
            }
            if (fieldType.equals(TypeName.BOOLEAN)) {
                return CodeBlock.of("$T.valueOf($N).hashCode()", Boolean.class, member);
            }
            if (fieldType.equals(TypeName.SHORT)) {
                return CodeBlock.of("$T.valueOf($N).hashCode()", Short.class, member);
            }
            if (fieldType.equals(TypeName.BYTE)) {
                return CodeBlock.of("$T.valueOf($N).hashCode()", Byte.class, member);
            }
            if (fieldType.equals(TypeName.CHAR)) {
                return CodeBlock.of("$T.valueOf($N).hashCode()", Character.class, member);
            }
            if (fieldType.equals(TypeName.FLOAT)) {
                return CodeBlock.of("$T.valueOf($N).hashCode()", Float.class, member);
            }
            if (fieldType.equals(TypeName.DOUBLE)) {
                return CodeBlock.of("$T.valueOf($N).hashCode()", Double.class, member);
            }
        }
        // String, Class, enum — reference types with a direct hashCode()
        return CodeBlock.of("$N.hashCode()", member);
    }

    /**
     * Builds the per-member hashCode value term for an <em>array</em> member:
     * {@code java.util.Arrays.hashCode(<field>)} using the overload matching the component kind
     * ({@code Arrays.hashCode(Object[])} for String/Class/enum component arrays, the primitive
     * overload for primitive-component arrays).
     *
     * @param member    the annotation member name
     * @return the {@link CodeBlock} computing the member's value hash via {@code Arrays.hashCode}
     */
    private static CodeBlock arrayValueHash(String member) {
        return CodeBlock.of("$T.hashCode($N)", Arrays.class, member);
    }

    /**
     * Builds the per-member equality check for an <em>array</em> member:
     * {@code java.util.Arrays.equals(this.<field>, other.<member>())} using the overload matching the
     * component kind ({@code Arrays.equals(Object[], Object[])} for reference-component arrays, the
     * primitive overload for primitive-component arrays).
     *
     * @param member    the annotation member name
     * @param arrayType the member's array type
     * @return the {@link CodeBlock} comparing the array against {@code other.<member>()}
     */
    private static CodeBlock arrayEquals(String member, ArrayType arrayType) {
        return CodeBlock.of("$T.equals(this.$N, other.$N())", Arrays.class, member, member);
    }

    /** Builds the per-member equality check against {@code other.<member>()}. */
    private static CodeBlock memberEquals(String member, TypeName fieldType) {
        if (fieldType.equals(TypeName.FLOAT)) {
            return CodeBlock.of("$T.compare(this.$N, other.$N()) == 0", Float.class, member, member);
        }
        if (fieldType.equals(TypeName.DOUBLE)) {
            return CodeBlock.of("$T.compare(this.$N, other.$N()) == 0", Double.class, member, member);
        }
        if (fieldType.isPrimitive()) {
            return CodeBlock.of("$N == other.$N()", member, member);
        }
        return CodeBlock.of("$N.equals(other.$N())", member, member);
    }

    /**
     * Builds the constructor-argument list (a {@code CodeBlock} of comma-joined value literals) the
     * proxy uses to instantiate this literal, in the member-declaration order matching
     * {@link #emit}'s constructor.
     *
     * @param mirror   the annotation mirror to read values from; must not be {@code null}
     * @param elements the {@link Elements} utility (defaults); must not be {@code null}
     * @param types    the {@link Types} utility (Class-value erasure); must not be {@code null}
     * @return a {@link CodeBlock} of comma-separated constant literals
     */
    public static CodeBlock constructorArgs(AnnotationMirror mirror, Elements elements, Types types) {
        List<Map.Entry<ExecutableElement, AnnotationValue>> values = orderedValues(mirror, elements);
        List<CodeBlock> args = values.stream()
                .map(e -> valueLiteral(e.getKey().getReturnType(), e.getValue(), elements, types))
                .toList();
        return CodeBlock.join(args, ", ");
    }

    /** Renders an annotation-member value as a Java constant literal. */
    private static CodeBlock valueLiteral(
            TypeMirror memberType, AnnotationValue value, Elements elements, Types types) {
        Object raw = value.getValue();
        if (memberType.getKind() == TypeKind.ARRAY) {
            return arrayLiteral((ArrayType) memberType, raw, elements, types);
        }
        if (isClassMember(memberType)) {
            // Class<?> attribute — value is a TypeMirror; emit <Erased>.class
            TypeMirror classValue = (TypeMirror) raw;
            return CodeBlock.of("$T.class", TypeName.get(types.erasure(classValue)));
        }
        if (raw instanceof String s) {
            return CodeBlock.of("$S", s);
        }
        if (raw instanceof VariableElement enumConstant) {
            // enum constant — reference Type.CONSTANT
            TypeElement enumType = (TypeElement) enumConstant.getEnclosingElement();
            return CodeBlock.of(
                    "$T.$N",
                    ClassName.get(enumType),
                    enumConstant.getSimpleName().toString());
        }
        TypeKind kind = memberType.getKind();
        if (kind == TypeKind.CHAR) {
            // Use a numeric cast instead of a Unicode escape: escapes for line terminators,
            // quotes, and backslashes can change the generated source before it is tokenized.
            return CodeBlock.of("(char) $L", (int) (Character) raw);
        }
        if (kind == TypeKind.FLOAT) {
            return CodeBlock.of("$T.intBitsToFloat($L)", Float.class, Float.floatToRawIntBits((Float) raw));
        }
        if (kind == TypeKind.DOUBLE) {
            long bits = Double.doubleToRawLongBits((Double) raw);
            return CodeBlock.of("$T.longBitsToDouble($L)", Double.class, bits + "L");
        }
        // Nested-annotation member — the raw value is an AnnotationMirror.
        if (raw instanceof AnnotationMirror nested) {
            return nestedLiteral(nested, elements, types);
        }
        // int / long / boolean / short / byte — toString yields a valid literal
        return CodeBlock.of("$L", String.valueOf(raw));
    }

    /**
     * Renders an array-typed annotation member value as a Java array-initializer literal:
     * {@code new T[]{<elem>, <elem>, ...}}, recursing {@link #valueLiteral} on each element with the
     * array's <em>component</em> type, or {@code new T[0]} for an empty array (the default-empty
     * {@code {}} case included). The element type {@code T} of the {@code new T[]} expression is the
     * component's erasure (so a {@code Class<?>[]} member emits {@code new Class[]{...}}).
     *
     * @param arrayType the member's array type
     * @param raw       the {@link AnnotationValue#getValue()} of an array member — a
     *                  {@code List<? extends AnnotationValue>}
     * @param types     the {@link Types} utility (component erasure, element recursion)
     * @return the {@link CodeBlock} for the array initializer
     */
    private static CodeBlock arrayLiteral(ArrayType arrayType, Object raw, Elements processingElements, Types types) {
        TypeMirror component = arrayType.getComponentType();
        TypeName componentTypeName = TypeName.get(types.erasure(component));

        @SuppressWarnings("unchecked")
        List<? extends AnnotationValue> values = (List<? extends AnnotationValue>) raw;

        if (values.isEmpty()) {
            return CodeBlock.of("new $T[0]", componentTypeName);
        }

        List<CodeBlock> elementLiterals = values.stream()
                .map(e -> valueLiteral(component, e, processingElements, types))
                .toList();
        return CodeBlock.of("new $T[]{$L}", componentTypeName, CodeBlock.join(elementLiterals, ", "));
    }

    /** Returns annotation values in declaration order, matching the generated literal constructor. */
    private static List<Map.Entry<ExecutableElement, AnnotationValue>> orderedValues(
            AnnotationMirror mirror, Elements elements) {
        Map<? extends ExecutableElement, ? extends AnnotationValue> values =
                elements.getElementValuesWithDefaults(mirror);
        TypeMirror annotationType = mirror.getAnnotationType();
        if (!(annotationType instanceof DeclaredType declared)) {
            return values.entrySet().stream()
                    .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                    .toList();
        }

        List<Map.Entry<ExecutableElement, AnnotationValue>> ordered = new java.util.ArrayList<>();
        for (ExecutableElement member : javax.lang.model.util.ElementFilter.methodsIn(
                declared.asElement().getEnclosedElements())) {
            AnnotationValue value = values.get(member);
            if (value != null) {
                ordered.add(Map.entry(member, value));
            }
        }
        // Keep the Mockito/unit-test fallback and any unusual inherited members deterministic.
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : values.entrySet()) {
            if (ordered.stream().noneMatch(existing -> existing.getKey().equals(entry.getKey()))) {
                ordered.add(Map.entry(entry.getKey(), entry.getValue()));
            }
        }
        return ordered;
    }

    /** Emits a self-contained annotation literal expression for a nested annotation member. */
    private static CodeBlock nestedLiteral(AnnotationMirror mirror, Elements elements, Types types) {
        TypeElement annotationType = (TypeElement) mirror.getAnnotationType().asElement();
        List<Map.Entry<ExecutableElement, AnnotationValue>> values = orderedValues(mirror, elements);
        CodeBlock.Builder expression = CodeBlock.builder().add("new $T() {\n", ClassName.get(annotationType));
        for (Map.Entry<ExecutableElement, AnnotationValue> entry : values) {
            ExecutableElement member = entry.getKey();
            expression.add(
                    "@Override public $T $N() { return $L; }\n",
                    TypeName.get(member.getReturnType()),
                    member.getSimpleName().toString(),
                    valueLiteral(member.getReturnType(), entry.getValue(), elements, types));
        }
        expression.add(
                "@Override public $T annotationType() { return $T.class; }\n",
                ParameterizedTypeName.get(
                        ClassName.get(Class.class), WildcardTypeName.subtypeOf(ClassName.get(Annotation.class))),
                ClassName.get(annotationType));
        expression.add(
                "@Override public boolean equals(Object other) { return other instanceof $T that && ",
                ClassName.get(annotationType));
        boolean first = true;
        for (Map.Entry<ExecutableElement, AnnotationValue> entry : values) {
            ExecutableElement member = entry.getKey();
            if (!first) expression.add(" && ");
            first = false;
            String name = member.getSimpleName().toString();
            if (member.getReturnType().getKind() == TypeKind.ARRAY) {
                expression.add("$T.equals($N(), that.$N())", Arrays.class, name, name);
            } else if (member.getReturnType().getKind() == TypeKind.FLOAT) {
                expression.add("$T.compare($N(), that.$N()) == 0", Float.class, name, name);
            } else if (member.getReturnType().getKind() == TypeKind.DOUBLE) {
                expression.add("$T.compare($N(), that.$N()) == 0", Double.class, name, name);
            } else if (member.getReturnType().getKind().isPrimitive()) {
                expression.add("$N() == that.$N()", name, name);
            } else {
                expression.add("$T.equals($N(), that.$N())", java.util.Objects.class, name, name);
            }
        }
        expression.add(";}\n@Override public int hashCode() { return ");
        first = true;
        for (Map.Entry<ExecutableElement, AnnotationValue> entry : values) {
            ExecutableElement member = entry.getKey();
            if (!first) expression.add(" + ");
            first = false;
            String name = member.getSimpleName().toString();
            CodeBlock valueHash = member.getReturnType().getKind() == TypeKind.ARRAY
                    ? CodeBlock.of("$T.hashCode($N())", Arrays.class, name)
                    : member.getReturnType().getKind().isPrimitive()
                            ? CodeBlock.of(
                                    "$T.valueOf($N()).hashCode()",
                                    boxedType(member.getReturnType().getKind()),
                                    name)
                            : CodeBlock.of("$T.hashCode($N())", java.util.Objects.class, name);
            expression.add("((127 * $S.hashCode()) ^ $L)", name, valueHash);
        }
        expression.add("; }\n}");
        return expression.build();
    }

    private static Class<?> boxedType(TypeKind kind) {
        return switch (kind) {
            case BOOLEAN -> Boolean.class;
            case BYTE -> Byte.class;
            case SHORT -> Short.class;
            case INT -> Integer.class;
            case LONG -> Long.class;
            case CHAR -> Character.class;
            case FLOAT -> Float.class;
            case DOUBLE -> Double.class;
            default -> throw new IllegalArgumentException("not a primitive type: " + kind);
        };
    }
}
