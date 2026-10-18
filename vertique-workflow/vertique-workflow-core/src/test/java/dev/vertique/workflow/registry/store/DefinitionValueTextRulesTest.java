// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Checks the text rules of definition keys and provenance: bounds in code points and storable characters only. */
class DefinitionValueTextRulesTest {

    private static final Instant NOW = Instant.parse("2026-10-18T00:00:00Z");
    private static final String SUPPLEMENTARY = "😀";

    /** The provenance text fields, each able to build a provenance with the given text in just that field. */
    enum ProvenanceField {
        SOURCE_TYPE {
            @Override
            DefinitionProvenance with(String text) {
                return new DefinitionProvenance(text, "memory:ok", "rev", "author", "upload", NOW);
            }
        },
        URI {
            @Override
            DefinitionProvenance with(String text) {
                return new DefinitionProvenance("static", text, "rev", "author", "upload", NOW);
            }
        },
        REVISION {
            @Override
            DefinitionProvenance with(String text) {
                return new DefinitionProvenance("static", "memory:ok", text, "author", "upload", NOW);
            }
        },
        AUTHOR {
            @Override
            DefinitionProvenance with(String text) {
                return new DefinitionProvenance("static", "memory:ok", "rev", text, "upload", NOW);
            }
        },
        UPLOAD_ID {
            @Override
            DefinitionProvenance with(String text) {
                return new DefinitionProvenance("static", "memory:ok", "rev", "author", text, NOW);
            }
        };

        abstract DefinitionProvenance with(String text);
    }

    /** Text that cannot be stored: a NUL character, an unpaired high surrogate and an unpaired low surrogate. */
    private static Stream<Arguments> unstorableProvenanceText() {
        Stream.Builder<Arguments> cases = Stream.builder();
        for (ProvenanceField field : ProvenanceField.values()) {
            cases.add(Arguments.of(field, "NUL", "a\u0000b"));
            cases.add(Arguments.of(field, "lone high surrogate", "a\uD800b"));
            cases.add(Arguments.of(field, "lone low surrogate", "a\uDC00b"));
            cases.add(Arguments.of(field, "trailing high surrogate", "ab\uD83D"));
            cases.add(Arguments.of(field, "reversed surrogate pair", "a\uDE00\uD83Db"));
        }
        return cases.build();
    }

    @Test
    @DisplayName("a provenance uri is accepted at 2048 code points and rejected at 2049")
    void boundsProvenanceUriAtTwoThousandFortyEightCodePoints() {
        String atBound = "u".repeat(2048);
        String supplementaryAtBound = SUPPLEMENTARY.repeat(2048);

        assertThat(new DefinitionProvenance("static", atBound, null, null, null, NOW).uri())
                .isEqualTo(atBound);
        assertThat(new DefinitionProvenance("static", supplementaryAtBound, null, null, null, NOW).uri())
                .as("2048 supplementary characters are 4096 UTF-16 units but 2048 code points")
                .isEqualTo(supplementaryAtBound);
        assertThat(new DefinitionProvenance("static", null, null, null, null, NOW).uri())
                .isNull();
        assertThatThrownBy(() -> new DefinitionProvenance("static", "u".repeat(2049), null, null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DefinitionProvenance("static", SUPPLEMENTARY.repeat(2049), null, null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "{0} rejects {1}")
    @MethodSource("unstorableProvenanceText")
    void rejectsUnstorableProvenanceText(ProvenanceField field, String label, String text) {
        assertThatThrownBy(() -> field.with(text)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "{0} accepts a supplementary character")
    @EnumSource(ProvenanceField.class)
    void acceptsSupplementaryCharacterInProvenanceText(ProvenanceField field) {
        String text = "a" + SUPPLEMENTARY + "b";

        assertThatCode(() -> field.with(text)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "a key part containing code point {0} is rejected")
    @ValueSource(ints = {0x0000, 0x000A, 0x000D, 0x0009, 0x001F, 0x007F, 0x0085, 0x009F})
    void rejectsIsoControlCharactersInKeyText(int controlCodePoint) {
        String text = "wf" + new String(Character.toChars(controlCodePoint)) + "x";

        assertThatThrownBy(() -> new DefinitionKey(null, text, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DefinitionKey(text, "wf", 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "an unpaired surrogate variant {index} is rejected")
    @ValueSource(strings = {"wf\uD800x", "wf\uDC00x", "wf\uD83D", "wf\uDE00\uD83Dx"})
    void rejectsUnpairedSurrogatesInKeyText(String text) {
        assertThatThrownBy(() -> new DefinitionKey(null, text, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DefinitionKey(text, "wf", 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("key text accepts ordinary and supplementary characters and still counts code points")
    void acceptsOrdinaryAndSupplementaryKeyText() {
        String ordinary = "orders.v2-ä_ü:ß/x";
        String supplementary = "wf-" + SUPPLEMENTARY;
        String atBound = SUPPLEMENTARY.repeat(255);

        assertThat(new DefinitionKey("tenant-" + SUPPLEMENTARY, ordinary, 1).definitionId())
                .isEqualTo(ordinary);
        assertThat(new DefinitionKey(null, supplementary, 1).definitionId()).isEqualTo(supplementary);
        assertThat(new DefinitionKey(atBound, atBound, 1).tenantKey()).isEqualTo(atBound);
        assertThatThrownBy(() -> new DefinitionKey(null, atBound + SUPPLEMENTARY, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DefinitionKey(atBound + SUPPLEMENTARY, "wf", 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
