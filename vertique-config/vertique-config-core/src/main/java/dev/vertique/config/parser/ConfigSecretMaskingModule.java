// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.parser;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonStreamContext;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.PropertyName;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import dev.vertique.core.config.ConfigSecretRenderer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Jackson module that masks secrets when the config mapper <em>serializes</em> a configuration
 * record, so a debug dump of a parsed record follows the same rule as the record's {@code toString()}.
 *
 * <p>Every bean property whose dotted path within the serialized document
 * {@link ConfigSecretRenderer#isSensitivePath(String) names a secret} is written as
 * {@link ConfigSecretRenderer#MASK} ({@code "***"}) when it has a value and omitted when it is
 * {@code null} or an empty {@link Optional}. The path is the chain of enclosing object keys plus the
 * property name ({@code ssl.keyStorePassword} for the keystore password of an {@code ssl} record
 * nested in an HTTP record); array levels add nothing, as in
 * {@link ConfigSecretRenderer#redactBag(JsonObject)}. The record keeps the live value: only its
 * rendering changes. A sensitive property masks as one {@code "***"} whatever its type, so a nested
 * record or list under a secret-named key is never descended into, and a boolean or number whose
 * name happens to match the rule masks too.
 *
 * <p>A property that is not itself sensitive but whose value (after unwrapping an {@link Optional}) is
 * an open bag ({@link Map}, {@link JsonObject}, {@link Collection}, {@link JsonArray}) is written with
 * its sensitive entries masked at every depth, the same walk {@code redactBag} applies to a
 * {@code toString()}; a sensitive entry that is {@code null} is dropped, as an absent field is.
 *
 * <p>A {@link String} at a path that {@link ConfigSecretRenderer#isUriPath(String) names a URL}
 * ({@code jdbcUrl}, {@code baseUrl}, {@code endpoints}, {@code uri}) — a field, an {@link Optional},
 * a list element or a bag entry — is written as {@link ConfigSecretRenderer#redactUri(String)} renders
 * it: the authority userinfo and credential-named query-parameter values are masked, scheme, host,
 * port and path stay visible. A URL without credentials, and any other string, serializes as given
 * through the regular writer. The secret rule wins: a path that is both ({@code tokenUrl}) renders as
 * {@code "***"}. This is one difference from {@code toString()}: {@code redactBag} does not apply the
 * URL rule to bag leaves. A masked bag or URL is written through the provider's default serializer for
 * its value, so a property-level serializer annotation on such a field is bypassed.
 *
 * <p>The URL rule covers {@code scheme://user:pass@host} and {@code ?name=value} forms only: a
 * scheme-less or semicolon-style connection string, and a URL under a name without one of the tokens
 * ({@code address}), serialize as given. Deserialization is untouched; the matching guard on input is
 * {@code ConfigParser}'s rejection of a literal {@code "***"} at a sensitive path, which does not
 * recognise a masked URL ({@code //***@host}). The module is registered on every config mapper
 * ({@code DefaultConfigMapper}) and on nothing else: a record serialized through another
 * {@code ObjectMapper} is not masked by it. Properties written by a custom serializer registered for
 * the record type and {@code @JsonAnyGetter} entries are outside its reach; the members of a
 * {@code @JsonUnwrapped} property mask by their own (prefixed) names but lose the enclosing property's
 * name from their path, so a secret-named unwrapped record is not masked as a whole.
 */
final class ConfigSecretMaskingModule extends SimpleModule {

    private static final long serialVersionUID = 1L;

    ConfigSecretMaskingModule() {
        super(ConfigSecretMaskingModule.class.getSimpleName());
        setSerializerModifier(new Modifier());
    }

    /** Wraps every plain property writer of every bean the config mapper serializes. */
    private static final class Modifier extends BeanSerializerModifier {

        private static final long serialVersionUID = 1L;

        @Override
        public List<BeanPropertyWriter> changeProperties(
                SerializationConfig config, BeanDescription beanDesc, List<BeanPropertyWriter> beanProperties) {
            for (int i = 0; i < beanProperties.size(); i++) {
                BeanPropertyWriter writer = beanProperties.get(i);
                // Only the plain writer is wrapped: specialised writers (unwrapping, any-getter, virtual)
                // carry behaviour a subclass copy would lose.
                if (writer.getClass() == BeanPropertyWriter.class) {
                    beanProperties.set(i, new MaskingPropertyWriter(writer));
                }
            }
            return beanProperties;
        }
    }

    /**
     * A property writer that masks or omits its property when the dotted path is sensitive, scrubs an
     * open bag it holds, redacts a URL-named string, and otherwise behaves exactly like the writer it
     * was copied from.
     */
    static final class MaskingPropertyWriter extends BeanPropertyWriter {

        private static final long serialVersionUID = 1L;

        MaskingPropertyWriter(BeanPropertyWriter base) {
            super(base);
        }

        private MaskingPropertyWriter(BeanPropertyWriter base, PropertyName name) {
            super(base, name);
        }

        /** Keeps the masking when Jackson renames the property (e.g. for an unwrapping parent). */
        @Override
        protected BeanPropertyWriter _new(PropertyName newName) {
            return new MaskingPropertyWriter(this, newName);
        }

        @Override
        public void serializeAsField(Object bean, JsonGenerator gen, SerializerProvider prov) throws Exception {
            String path = ConfigSecretPaths.join(enclosingPath(gen), getName());
            if (ConfigSecretRenderer.isSensitivePath(path)) {
                Object value = get(bean);
                if (!isAbsent(value)) {
                    gen.writeFieldName(getName());
                    gen.writeString(ConfigSecretRenderer.MASK);
                }
                return;
            }
            Object bag = unwrap(get(bean));
            Object masked = ConfigSecretPaths.maskValue(path, bag);
            if (masked != bag) {
                gen.writeFieldName(getName());
                prov.defaultSerializeValue(masked, gen);
                return;
            }
            super.serializeAsField(bean, gen, prov);
        }

        /** Array-shaped beans have no field names, so an absent secret writes {@code null}. */
        @Override
        public void serializeAsElement(Object bean, JsonGenerator gen, SerializerProvider prov) throws Exception {
            String path = ConfigSecretPaths.join(enclosingPath(gen), getName());
            if (ConfigSecretRenderer.isSensitivePath(path)) {
                if (isAbsent(get(bean))) {
                    gen.writeNull();
                } else {
                    gen.writeString(ConfigSecretRenderer.MASK);
                }
                return;
            }
            Object bag = unwrap(get(bean));
            Object masked = ConfigSecretPaths.maskValue(path, bag);
            if (masked != bag) {
                prov.defaultSerializeValue(masked, gen);
                return;
            }
            super.serializeAsElement(bean, gen, prov);
        }

        /** {@code null}, an empty {@link Optional} or an empty primitive optional counts as absent. */
        private static boolean isAbsent(Object value) {
            return value == null
                    || (value instanceof Optional<?> optional && optional.isEmpty())
                    || (value instanceof OptionalInt optionalInt && optionalInt.isEmpty())
                    || (value instanceof OptionalLong optionalLong && optionalLong.isEmpty())
                    || (value instanceof OptionalDouble optionalDouble && optionalDouble.isEmpty());
        }

        /** Unwraps an {@link Optional} so a bag held through it is scrubbed like a bare one. */
        private static Object unwrap(Object value) {
            return value instanceof Optional<?> optional ? optional.orElse(null) : value;
        }

        /**
         * Builds the dotted path of the object currently being written from the generator's output
         * context: the key of every enclosing object level, outermost first, skipping array levels and
         * the root. The current level's own key is the previous sibling, so the walk starts at the
         * parent.
         */
        private static String enclosingPath(JsonGenerator gen) {
            JsonStreamContext context = gen.getOutputContext();
            if (context == null) {
                return "";
            }
            Deque<String> keys = new ArrayDeque<>();
            for (JsonStreamContext level = context.getParent(); level != null; level = level.getParent()) {
                if (level.inObject() && level.hasCurrentName()) {
                    keys.addFirst(level.getCurrentName());
                }
            }
            return String.join(".", keys);
        }
    }
}
