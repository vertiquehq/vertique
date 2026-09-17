// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation.corpus;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;

/**
 * The frozen schema-corpus fixture set: every body shape whose generated document is pinned as a
 * golden file, in one fixed order.
 *
 * <p><strong>One list, one order.</strong> Every consumer — the legacy pre-change generation run, the
 * profiled generation runs, and the unit comparison — iterates {@link #FIXTURES}. A delta list that
 * compares different subjects, or the same subjects in a different order, proves nothing; so no
 * consumer may filter, re-order, or re-declare the set.
 *
 * <p>The set is frozen to the body shapes the schema-source unit proof already exercised (a required
 * property, string constraints carried by property metadata, a nested object, the swagger default
 * sentinel, a mixed scalar object, and a generic collection body) plus the shapes this task adds to
 * surface property-model deltas and gate behaviour: an {@code Optional<String>}, an {@code Instant},
 * a {@code LocalDate}, an enum carrying {@code @JsonEnumDefaultValue}, a property literally named
 * {@code pattern}, and the four {@code BigDecimal} positions (root, nested, array element, and map
 * key) a profile override must treat differently.
 */
public final class SchemaCorpus {

    /** The generic collection body {@code List<CollectionItemDto>}, which no class literal expresses. */
    public static final CorpusFixture COLLECTION_BODY =
            CorpusFixture.of("CollectionItemDtoList", List.class, listOf(CollectionItemDto.class));

    /**
     * The frozen fixture set, in the frozen order. Grouped by concern: the shapes inherited from the
     * pre-existing unit proof first, then the {@code BigDecimal} positions, then the property-model
     * and gate shapes this task adds.
     */
    public static final List<CorpusFixture> FIXTURES = List.of(
            CorpusFixture.of(RequiredPropertyDto.class),
            CorpusFixture.of(ConstrainedStringDto.class),
            CorpusFixture.of(NestedObjectDto.class),
            CorpusFixture.of(DefaultSentinelDto.class),
            CorpusFixture.of(CollectionItemDto.class),
            COLLECTION_BODY,
            CorpusFixture.of(RootDecimalDto.class),
            CorpusFixture.of(NestedDecimalDto.class),
            CorpusFixture.of(DecimalListDto.class),
            CorpusFixture.of(DecimalMapKeyDto.class),
            CorpusFixture.of(OptionalPropertyDto.class),
            CorpusFixture.of(InstantPropertyDto.class),
            CorpusFixture.of(LocalDatePropertyDto.class),
            CorpusFixture.of(EnumDefaultValueDto.class),
            CorpusFixture.of(PatternNamedPropertyDto.class));

    private SchemaCorpus() {}

    /**
     * Looks a fixture up by its corpus name.
     *
     * @param name the fixture name
     * @return the fixture, or empty when the set declares no such name
     */
    public static Optional<CorpusFixture> byName(String name) {
        return FIXTURES.stream().filter(fixture -> fixture.name().equals(name)).findFirst();
    }

    /**
     * Builds the resolved type {@code List<elementType>}.
     *
     * @param elementType the element class
     * @return a {@link ParameterizedType} for {@code List<elementType>}
     */
    private static Type listOf(Class<?> elementType) {
        return new ParameterizedType() {

            @Override
            public Type[] getActualTypeArguments() {
                return new Type[] {elementType};
            }

            @Override
            public Type getRawType() {
                return List.class;
            }

            @Override
            public Type getOwnerType() {
                return null;
            }

            @Override
            public String toString() {
                return List.class.getName() + "<" + elementType.getName() + ">";
            }
        };
    }
}
