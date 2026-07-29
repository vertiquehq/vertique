// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime.fixture;

import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.core.security.SecurityPolicyViolation;
import dev.vertique.rest.jaxrs.ResourceMethodMeta;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorSupport;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsResourceDescriptor;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;
import java.util.SortedSet;

/**
 * Test fixture: generated descriptor companion for {@link SortedSetBodyResource}, reproducing the
 * {@code ParamMeta} shape the codegen emitters actually produce for a collection-typed
 * <em>entity body</em> parameter — {@code source = BODY} <em>with</em> a resolved
 * {@code componentType}, because {@code EffectiveJaxRsContractResolver.resolvesComponentType} returns
 * {@code true} for BODY.
 *
 * <p>The reflective scanner cannot produce this shape (it hard-codes {@code componentType = null} for
 * BODY), so a hand-written companion is the only way to drive the real
 * {@code JaxRsRouteRegistrar}/{@code RouteValidator} with it from this module.
 *
 * <p>The class FQN follows {@code GeneratedJaxRsDescriptorRegistry.derivedFqn}: same package, simple
 * name, plus the {@code _JaxRsDescriptor} suffix.
 */
public final class SortedSetBodyResource_JaxRsDescriptor
        implements GeneratedJaxRsResourceDescriptor<SortedSetBodyResource> {

    /** The operationId the descriptor reports for the single resource method. */
    public static final String OPERATION_ID = "sortedSetBody";

    /** No-arg constructor required for reflective instantiation by the registry. */
    public SortedSetBodyResource_JaxRsDescriptor() {}

    @Override
    public Class<SortedSetBodyResource> resourceType() {
        return SortedSetBodyResource.class;
    }

    /**
     * Returns the single resource method's metadata with a BODY parameter carrying both the declared
     * {@code SortedSet} type and the resolved element type, exactly as the generated descriptor emits
     * it.
     *
     * @param resource   the resource instance
     * @param support    the runtime helper bag (unused — every class is resolved statically here)
     * @param violations the violation sink (nothing is appended — the fixture is conflict-free)
     * @return a singleton list holding the method metadata
     */
    @Override
    public List<ResourceMethodMeta> describe(
            SortedSetBodyResource resource,
            GeneratedJaxRsDescriptorSupport support,
            List<SecurityPolicyViolation> violations) {
        Method method;
        try {
            method = SortedSetBodyResource.class.getMethod("post", SortedSet.class);
        } catch (NoSuchMethodException noSuchMethod) {
            throw new IllegalStateException("SortedSetBodyResource.post(SortedSet) not found — fixture mismatch");
        }
        ResourceMethodMeta.ParamMeta body = new ResourceMethodMeta.ParamMeta(
                null,
                ResourceMethodMeta.ParamSource.BODY,
                SortedSet.class,
                SortedSetBodyResource.BodyElement.class,
                method.getGenericParameterTypes()[0],
                null,
                (Annotation[]) null);
        return List.of(new ResourceMethodMeta(
                resource,
                method,
                OPERATION_ID,
                "POST",
                "/sorted-body",
                List.of(body),
                String.class,
                false,
                false,
                new SecurityPolicy.None(),
                new ResourceMethodMeta.MediaTypes(List.of("application/json"), List.of("text/plain")),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of()));
    }
}
