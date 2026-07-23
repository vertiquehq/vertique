// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import java.util.List;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

/**
 * Immutable compile-time representation of the resolved JAX-RS parameter contract for a single
 * method parameter.
 *
 * <p>Built by {@link EffectiveJaxRsContractResolver} as part of assembling an
 * {@link EffectiveMethodContract}. Each instance corresponds to one {@link VariableElement} on
 * the concrete resource method; the annotation data is resolved according to the precedence rule
 * (direct → superclass → BFS interfaces).
 *
 * @param concreteParameter    the concrete parameter element; never {@code null}
 * @param annotationSources    the parameter elements whose annotation mirrors back the effective
 *                             annotation set for literal materialization (GitHub issue #162):
 *                             {@code concreteParameter} first, followed by the matching parameter on
 *                             each superclass/interface override that declares it (BFS order),
 *                             mirroring the runtime {@code AnnotationResolver.resolveParameterAnnotations}
 *                             merge so a converter-decision marker annotation declared only on an
 *                             interface method is not lost on the codegen path; never {@code null},
 *                             never empty (always contains at least {@code concreteParameter})
 * @param source                the classification slot from {@link JaxRsParamSource}; never
 *                             {@code null}
 * @param name                 the param name value (e.g. from {@code @PathParam("id")}), may be
 *                             {@code null} if the annotation is absent, or blank if the annotation
 *                             declares an empty name (CG-009 blank-name parity)
 * @param defaultValue         the value from {@code @DefaultValue}, or {@code null} if absent
 * @param type                 the parameter type mirror; never {@code null}
 * @param componentType        the element type if the parameter is a generic collection type (e.g.
 *                             {@code List<String>} → {@code String}), or {@code null} otherwise
 * @param beanParamType        the declared type if the parameter is a composite bean parameter
 *                             ({@code @BeanParam} or {@code @RequestParams}), or {@code null}
 *                             otherwise
 * @param genericType          the fully-parameterized type mirror for {@link JaxRsParamSource#BODY}
 *                             parameters (e.g. {@code List<Foo>}), or {@code null} for non-body
 *                             parameters and body parameters that are not parameterized
 * @param canonicalizers       ordered list of canonicalizer class type mirrors resolved for this
 *                             parameter (route-level chain overridden by any parameter-level
 *                             {@code @Canonicalize}/{@code @SkipCanonicalization}); never
 *                             {@code null}, empty when no canonicalization applies
 * @param sanitizers           ordered list of sanitizer class type mirrors resolved for this
 *                             parameter (route-level chain overridden by any parameter-level
 *                             {@code @Sanitize}/{@code @SkipSanitization}); never {@code null},
 *                             empty when no sanitization applies
 */
public record EffectiveParamContract(
        VariableElement concreteParameter,
        List<VariableElement> annotationSources,
        JaxRsParamSource source,
        String name,
        String defaultValue,
        TypeMirror type,
        TypeMirror componentType,
        TypeMirror beanParamType,
        TypeMirror genericType,
        List<TypeMirror> canonicalizers,
        List<TypeMirror> sanitizers) {}
