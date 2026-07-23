// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.keyed;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.deser.BeanDeserializerBuilder;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.SettableBeanProperty;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.module.SimpleModule;
import dev.vertique.core.json.KeyedBy;
import java.util.ArrayList;
import java.util.List;

/**
 * Jackson {@link com.fasterxml.jackson.databind.Module} that enables keyed-object deserialization
 * for {@code List<T>} fields annotated with {@link KeyedBy}.
 *
 * <p>Registering this module on an {@link com.fasterxml.jackson.databind.ObjectMapper} installs a
 * {@link BeanDeserializerModifier} that, for every bean property carrying a {@link KeyedBy}
 * annotation, swaps in a {@link KeyedCollectionDeserializer}. That deserializer reads a keyed JSON
 * object {@code {key:{...}}} into a {@code List<T>} with each key injected into the element's
 * identity property.
 */
public final class KeyedCollectionModule extends SimpleModule {

    /**
     * Creates the module and registers the keyed-collection deserializer support.
     */
    public KeyedCollectionModule() {
        super("KeyedCollectionModule");
    }

    /**
     * Installs the {@link BeanDeserializerModifier} that rebinds {@link KeyedBy}-annotated
     * properties to a {@link KeyedCollectionDeserializer}.
     *
     * @param context the Jackson module setup context
     */
    @Override
    public void setupModule(SetupContext context) {
        super.setupModule(context);
        context.addBeanDeserializerModifier(new KeyedByDeserializerModifier());
    }

    /**
     * Bean deserializer modifier that replaces the value deserializer of every {@link KeyedBy}
     * property with a fresh {@link KeyedCollectionDeserializer}, which Jackson then contextualizes
     * against the property's annotation and element type.
     */
    private static final class KeyedByDeserializerModifier extends BeanDeserializerModifier {

        /**
         * Rebinds each {@link KeyedBy}-annotated property to the keyed-collection deserializer.
         *
         * @param config the active deserialization config
         * @param beanDesc the bean being built
         * @param builder the deserializer builder for the bean
         * @return the supplied builder, with keyed properties rebound
         */
        @Override
        public BeanDeserializerBuilder updateBuilder(
                DeserializationConfig config, BeanDescription beanDesc, BeanDeserializerBuilder builder) {
            // Collect rebindings first, then apply — mutating the builder mid-iteration would
            // invalidate the property iterator.
            List<SettableBeanProperty> rebindings = new ArrayList<>();
            var properties = builder.getProperties();
            while (properties.hasNext()) {
                SettableBeanProperty property = properties.next();
                AnnotatedMember member = property.getMember();
                if (member != null && member.getAnnotation(KeyedBy.class) != null) {
                    rebindings.add(property.withValueDeserializer(new KeyedCollectionDeserializer()));
                }
            }
            for (SettableBeanProperty rebound : rebindings) {
                builder.addOrReplaceProperty(rebound, true);
            }
            return builder;
        }
    }
}
