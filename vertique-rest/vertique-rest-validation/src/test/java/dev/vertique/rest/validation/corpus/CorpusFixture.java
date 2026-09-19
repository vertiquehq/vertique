// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation.corpus;

import java.lang.reflect.Type;
import java.util.Objects;

/**
 * One subject of the schema corpus: a stable name and the body type it stands for.
 *
 * <p>The name is the corpus identity. It names the golden file in every profile directory
 * ({@code <name>.json}), so it must never change once a golden document is checked in: renaming a
 * fixture silently orphans its pinned documents instead of failing the comparison.
 *
 * <p>A fixture carries both the raw class and the optional full generic type, because a body may be a
 * parameterized type ({@code List<CollectionItemDto>}) that no class literal can express. Schema
 * generation uses {@link #generatedType()}; a caller building a {@code BodyDescriptor} passes
 * {@link #rawType()} and {@link #genericType()} straight through.
 *
 * @param name        the corpus identity and golden file base name; never blank
 * @param rawType     the body's raw class; never {@code null}
 * @param genericType the body's full generic type, or {@code null} when the raw class is complete
 */
public record CorpusFixture(String name, Class<?> rawType, Type genericType) {

    /** The extension every golden corpus document carries. */
    public static final String FILE_EXTENSION = ".json";

    /**
     * Validates the fixture's identity.
     *
     * @throws NullPointerException     if {@code name} or {@code rawType} is {@code null}
     * @throws IllegalArgumentException if {@code name} is blank
     */
    public CorpusFixture {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(rawType, "rawType");
        if (name.isBlank()) {
            throw new IllegalArgumentException("a corpus fixture name must not be blank");
        }
    }

    /**
     * Creates a fixture for a plain (non-generic) body class, named after the class.
     *
     * @param bodyType the body class
     * @return the fixture
     */
    public static CorpusFixture of(Class<?> bodyType) {
        return new CorpusFixture(bodyType.getSimpleName(), bodyType, null);
    }

    /**
     * Creates a fixture for a generic body type under an explicit corpus name.
     *
     * @param name        the corpus identity and golden file base name
     * @param rawType     the body's raw class
     * @param genericType the body's full generic type
     * @return the fixture
     */
    public static CorpusFixture of(String name, Class<?> rawType, Type genericType) {
        return new CorpusFixture(name, rawType, genericType);
    }

    /**
     * Returns the type schema generation runs against: the full generic type when one is declared,
     * otherwise the raw class.
     *
     * @return the resolved body type
     */
    public Type generatedType() {
        return genericType != null ? genericType : rawType;
    }

    /**
     * Returns this fixture's golden file name within a corpus directory.
     *
     * @return {@code <name>.json}
     */
    public String fileName() {
        return name + FILE_EXTENSION;
    }
}
