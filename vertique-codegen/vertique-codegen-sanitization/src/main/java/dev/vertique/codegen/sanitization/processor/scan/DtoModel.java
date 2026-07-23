// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.sanitization.processor.scan;

import java.util.LinkedHashMap;
import java.util.List;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

/**
 * Immutable APT-time description of a DTO participating in structured-body sanitization, produced
 * by {@link AnnotationCollector} and consumed by
 * {@link dev.vertique.codegen.sanitization.processor.emit.InputProcessorEmitter}.
 *
 * <p>Each instance corresponds to one generated {@code {DTO}_InputProcessor} source file.
 *
 * @param origin              the source {@link TypeElement} for this DTO; never {@code null}
 * @param qualifiedName       the fully-qualified name of the DTO type; never {@code null}
 * @param objectCanonChain    class-level canonicalizer chain (from {@code @Canonicalize} on the type);
 *                            never {@code null}, may be empty
 * @param objectSanitChain    class-level sanitizer chain (from {@code @Sanitize} on the type);
 *                            never {@code null}, may be empty
 * @param skipCanonicalization whether the type declares {@code @SkipCanonicalization}
 * @param skipSanitization    whether the type declares {@code @SkipSanitization}
 * @param fields              ordered map of field-name to {@link FieldModel}, in declaration order;
 *                            never {@code null}
 */
public record DtoModel(
        TypeElement origin,
        String qualifiedName,
        List<TypeMirror> objectCanonChain,
        List<TypeMirror> objectSanitChain,
        boolean skipCanonicalization,
        boolean skipSanitization,
        LinkedHashMap<String, FieldModel> fields) {}
