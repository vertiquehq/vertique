// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Checks the consumer-facing validation and snapshot behavior of durable definition values. */
class DefinitionStoreValuesTest {

    private static final Instant NOW = Instant.parse("2026-10-18T00:00:00Z");
    private static final String HASH = "a".repeat(64);

    @Test
    @DisplayName("definition keys validate identity by Unicode code point and positive version")
    void validatesDefinitionKey() {
        String maxCodePoints = "😀".repeat(255);

        assertThat(new DefinitionKey(maxCodePoints, "valid-id", 1).tenantKey()).isEqualTo(maxCodePoints);
        assertThat(new DefinitionKey(null, maxCodePoints, 1).definitionId()).isEqualTo(maxCodePoints);
        assertThatThrownBy(() -> new DefinitionKey(null, " ", 1)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new DefinitionKey(null, "x".repeat(256), 1)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new DefinitionKey(null, "valid-id", 0)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new DefinitionKey(" ", "valid-id", 1)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new DefinitionKey("x".repeat(256), "valid-id", 1))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new DefinitionKey(null, maxCodePoints + "x", 1))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("definition filters enforce explicit tenant scope and hold immutable status snapshots")
    void validatesAndSnapshotsDefinitionFilter() {
        Set<DefinitionStatus> supplied = new HashSet<>(Set.of(DefinitionStatus.CANDIDATE));
        DefinitionFilter filter = new DefinitionFilter(DefinitionFilter.TenantScope.TENANT, "tenant-a", supplied);
        supplied.add(DefinitionStatus.ACTIVE);

        assertThat(filter.statuses()).containsExactly(DefinitionStatus.CANDIDATE);
        assertThatThrownBy(() -> filter.statuses().add(DefinitionStatus.RETIRED))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(new DefinitionFilter(DefinitionFilter.TenantScope.ALL, null, Set.of()).statuses())
                .isEmpty();
        assertThatThrownBy(() -> new DefinitionFilter(DefinitionFilter.TenantScope.ALL, "tenant-a", null))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new DefinitionFilter(DefinitionFilter.TenantScope.GLOBAL, "tenant-a", null))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new DefinitionFilter(DefinitionFilter.TenantScope.TENANT, null, null))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("provenance and metadata validate bounded text and kind-specific fields")
    void validatesProvenanceAndDefinitionRecord() {
        DefinitionProvenance provenance =
                new DefinitionProvenance("s".repeat(64), null, "r".repeat(255), "a".repeat(255), "u".repeat(255), NOW);
        DefinitionKey globalKey = new DefinitionKey(null, "orders", 1);
        WorkflowDefinitionRecord code = new WorkflowDefinitionRecord(
                globalKey, DefinitionKind.CODE, HASH, DefinitionStatus.ACTIVE, provenance, null, NOW, NOW);

        assertThat(code.key()).isEqualTo(globalKey);
        assertThatThrownBy(() -> new DefinitionProvenance(" ", null, null, null, null, NOW))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new DefinitionProvenance("x".repeat(65), null, null, null, null, NOW))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new DefinitionProvenance("static", null, null, null, null, null))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new DefinitionProvenance("upload", null, "x".repeat(256), null, null, NOW))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new DefinitionProvenance("upload", null, null, "x".repeat(256), null, NOW))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new DefinitionProvenance("upload", null, null, null, "x".repeat(256), NOW))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new WorkflowDefinitionRecord(
                        null, DefinitionKind.CODE, HASH, DefinitionStatus.ACTIVE, provenance, null, NOW, NOW))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new WorkflowDefinitionRecord(
                        globalKey, null, HASH, DefinitionStatus.ACTIVE, provenance, null, NOW, NOW))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new WorkflowDefinitionRecord(
                        globalKey, DefinitionKind.CODE, HASH, null, provenance, null, NOW, NOW))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new WorkflowDefinitionRecord(
                        globalKey, DefinitionKind.CODE, HASH, DefinitionStatus.ACTIVE, null, null, NOW, NOW))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new WorkflowDefinitionRecord(
                        globalKey, DefinitionKind.CODE, HASH, DefinitionStatus.ACTIVE, provenance, null, null, NOW))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new WorkflowDefinitionRecord(
                        globalKey,
                        DefinitionKind.CODE,
                        "A".repeat(64),
                        DefinitionStatus.ACTIVE,
                        provenance,
                        null,
                        NOW,
                        NOW))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new WorkflowDefinitionRecord(
                        globalKey,
                        DefinitionKind.CODE,
                        "a".repeat(63),
                        DefinitionStatus.ACTIVE,
                        provenance,
                        null,
                        NOW,
                        NOW))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new WorkflowDefinitionRecord(
                        globalKey,
                        DefinitionKind.CODE,
                        "g".repeat(64),
                        DefinitionStatus.ACTIVE,
                        provenance,
                        null,
                        NOW,
                        NOW))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new WorkflowDefinitionRecord(
                        new DefinitionKey("tenant-a", "orders", 1),
                        DefinitionKind.CODE,
                        HASH,
                        DefinitionStatus.ACTIVE,
                        provenance,
                        null,
                        NOW,
                        NOW))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new WorkflowDefinitionRecord(
                        globalKey, DefinitionKind.CODE, HASH, DefinitionStatus.ACTIVE, provenance, "YAML", NOW, NOW))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("document carriers preserve Unicode source without a constructor size cap")
    void validatesDocumentTextAndCanonicalFormat() {
        DefinitionKey key = new DefinitionKey(null, "orders", 1);
        String text = "# keep source characters\r\n" + "x".repeat(262_145);
        WorkflowDefinitionDocument document = new WorkflowDefinitionDocument(key, text, "YAML");

        assertThat(document.document()).isEqualTo(text);
        assertThat(document.documentFormat()).isEqualTo("YAML");
        assertThatThrownBy(() -> new WorkflowDefinitionDocument(key, "bad\u0000text", "YAML"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new WorkflowDefinitionDocument(key, "bad\ud800text", "YAML"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new WorkflowDefinitionDocument(key, "{}", "yaml"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new WorkflowDefinitionDocument(key, null, "JSON"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new WorkflowDefinitionDocument(key, "{}", null))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("definition records require their carrier metadata and documents to use matching keys")
    void requiresDefinitionRecordFieldsAndMatchingDocumentKeys() {
        DefinitionKey key = new DefinitionKey(null, "orders", 1);
        DefinitionProvenance provenance = new DefinitionProvenance("static", null, null, null, null, NOW);
        WorkflowDefinitionRecord documentRecord = new WorkflowDefinitionRecord(
                key, DefinitionKind.DOCUMENT, HASH, DefinitionStatus.CANDIDATE, provenance, "JSON", NOW, NOW);
        WorkflowDefinitionDocument body = new WorkflowDefinitionDocument(key, "{}", "JSON");

        assertThat(documentRecord.key()).isEqualTo(body.key());
        assertThatThrownBy(() -> new WorkflowDefinitionRecord(
                        key, DefinitionKind.DOCUMENT, HASH, DefinitionStatus.CANDIDATE, provenance, null, NOW, NOW))
                .isInstanceOf(RuntimeException.class);
        assertThatCode(() -> new WorkflowDefinitionDocument(
                        new DefinitionKey(null, "other", 1), body.document(), body.documentFormat()))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new DocumentOrigin(new DefinitionKey(null, "other", 1), body, provenance, HASH))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new DocumentOrigin(key, body, null, HASH)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new DocumentOrigin(key, body, provenance, null))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new ActivationClaim(null, java.util.UUID.randomUUID()))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new ActivationClaim(key, null)).isInstanceOf(RuntimeException.class);
        assertThat(EnumSet.allOf(DefinitionStatus.class))
                .contains(
                        DefinitionStatus.CANDIDATE,
                        DefinitionStatus.ACTIVATING,
                        DefinitionStatus.ACTIVE,
                        DefinitionStatus.SUPERSEDED,
                        DefinitionStatus.DEPRECATED,
                        DefinitionStatus.RETIRED);
    }
}
