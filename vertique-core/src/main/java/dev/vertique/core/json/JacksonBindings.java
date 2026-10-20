// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.json;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.introspect.AnnotatedWithParams;
import java.util.ArrayList;
import java.util.List;

/**
 * Decides, from a mapper's deserialization introspection, whether the properties it reports are what
 * the mapper actually binds a type through.
 *
 * <p>INTERNAL framework seam for sibling framework modules that read a mapper's declaration view (the
 * field-name projection and the JSON pointer resolver); outside the Stable maturity promise and may
 * change without a deprecation cycle. Not an application extension point.
 *
 * <p>Uses only public Jackson introspection and holds no state.
 */
public final class JacksonBindings {

    private JacksonBindings() {}

    /**
     * Reports whether the mapper binds a type through the properties its introspection reports,
     * which is the only case in which those properties say what the mapper binds into.
     *
     * <p>A custom deserializer, a converter, a builder, or a delegating creator — one declared
     * {@link JsonCreator.Mode#DELEGATING}, or a single-parameter creator whose parameter carries no
     * name — binds whatever it reads, and the declaration view says nothing about it.
     *
     * @param config      the deserialization configuration of the mapper that binds the type
     * @param description the type's deserialization-side bean description from {@code config}
     * @return {@code true} when the reported properties are what the mapper binds
     */
    public static boolean bindsThroughItsProperties(DeserializationConfig config, BeanDescription description) {
        AnnotationIntrospector introspector = config.getAnnotationIntrospector();
        if (introspector.findDeserializer(description.getClassInfo()) != null
                || introspector.findDeserializationConverter(description.getClassInfo()) != null
                || description.findPOJOBuilder() != null) {
            return false;
        }
        List<AnnotatedWithParams> creators = new ArrayList<>(description.getConstructors());
        creators.addAll(description.getFactoryMethods());
        for (AnnotatedWithParams creator : creators) {
            JsonCreator.Mode mode = introspector.findCreatorAnnotation(config, creator);
            if (mode == JsonCreator.Mode.DELEGATING) {
                return false;
            }
            if (mode == JsonCreator.Mode.DEFAULT
                    && creator.getParameterCount() == 1
                    && introspector.findNameForDeserialization(creator.getParameter(0)) == null) {
                return false;
            }
        }
        return true;
    }
}
