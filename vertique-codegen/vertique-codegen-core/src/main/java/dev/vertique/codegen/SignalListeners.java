// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen;

import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;

/**
 * INTERNAL seam — shared by the annotation processors of this framework and of other repositories, which call it
 * across modules; not an application contract and outside the maturity promise of {@code vertique-codegen-core}. An
 * application never calls this type.
 *
 * <p>Holds the one test that decides whether an interface is a signal listener, and the one rule that names the class
 * generated for it, so that every processor agrees on both. The annotations are identified by fully qualified name,
 * because this module takes no dependency on the Kafka or workflow modules.
 *
 * <p>A signal listener is an interface annotated {@code dev.vertique.kafka.KafkaListener} with at least one method that
 * carries {@code dev.vertique.workflow.source.WorkflowSignalTarget} or its repeatable container, declared on the
 * interface or inherited from a super-interface; an inherited target is then refused by the shared scan. The class generated
 * for it is {@code <Interface>_SignalSource} in the interface's package; a nested interface is named from its nesting,
 * joined by {@code _}, as {@code Outer_Inner_SignalSource}.
 */
public final class SignalListeners {

    /** The fully qualified name of the listener annotation. */
    static final String KAFKA_LISTENER_FQN = "dev.vertique.kafka.KafkaListener";

    /** The fully qualified name of the repeatable target annotation. */
    static final String SIGNAL_TARGET_FQN = "dev.vertique.workflow.source.WorkflowSignalTarget";

    /** The fully qualified name of the container the compiler wraps repeated targets in. */
    static final String SIGNAL_TARGETS_FQN = "dev.vertique.workflow.source.WorkflowSignalTargets";

    private static final String GENERATED_SUFFIX = "_SignalSource";

    private SignalListeners() {}

    /**
     * Returns whether the type is a signal listener.
     *
     * @param type the type to test; must not be {@code null}
     * @return {@code true} for an interface annotated {@code @KafkaListener} that has a method carrying a signal target
     *     or its container, declared or inherited
     */
    public static boolean isSignalListener(TypeElement type) {
        if (type.getKind() != ElementKind.INTERFACE || !AnnotationMirrors.isPresent(type, KAFKA_LISTENER_FQN)) {
            return false;
        }
        return !targetMethods(type).isEmpty() || !inheritedTargetMethods(type).isEmpty();
    }

    /**
     * Returns the methods that a type itself declares and that carry a signal target or its container.
     *
     * @param type the type to read; must not be {@code null}
     * @return the methods in declaration order
     */
    static List<ExecutableElement> targetMethods(TypeElement type) {
        return ElementFilter.methodsIn(type.getEnclosedElements()).stream()
                .filter(method -> AnnotationMirrors.isPresent(method, SIGNAL_TARGET_FQN)
                        || AnnotationMirrors.isPresent(method, SIGNAL_TARGETS_FQN))
                .toList();
    }

    /**
     * Returns the methods that carry a signal target or its container and that a super-interface of the type
     * declares.
     *
     * @param type the type to read; must not be {@code null}
     * @return the inherited target methods, nearest super-interface first
     */
    static List<ExecutableElement> inheritedTargetMethods(TypeElement type) {
        List<ExecutableElement> inherited = new ArrayList<>();
        for (TypeMirror parent : type.getInterfaces()) {
            if (parent instanceof DeclaredType declared && declared.asElement() instanceof TypeElement element) {
                inherited.addAll(targetMethods(element));
                inherited.addAll(inheritedTargetMethods(element));
            }
        }
        return inherited;
    }

    /**
     * Returns the fully qualified name of the class generated for a signal listener.
     *
     * @param listener the signal listener; must not be {@code null}
     * @return {@code <package>.<Interface>_SignalSource}, with the names of the enclosing types joined by {@code _} for a
     *     nested interface; the simple name alone in the unnamed package
     */
    public static String generatedName(TypeElement listener) {
        List<String> names = new ArrayList<>();
        Element current = listener;
        while (current instanceof TypeElement enclosing) {
            names.add(0, enclosing.getSimpleName().toString());
            current = enclosing.getEnclosingElement();
        }
        String simple = String.join("_", names) + GENERATED_SUFFIX;
        if (current instanceof PackageElement pkg && !pkg.isUnnamed()) {
            return pkg.getQualifiedName() + "." + simple;
        }
        return simple;
    }
}
