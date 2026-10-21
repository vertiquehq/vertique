// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.application.VertiqueApplicationBootstrap;
import dev.vertique.application.VertiqueApplicationHandle;
import dev.vertique.core.VertiqueRuntime;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.db.exception.ConnectionException;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.parser.WorkflowDefinitionParserProbe;
import dev.vertique.workflow.definition.service.CompiledDefinitionRef;
import dev.vertique.workflow.definition.source.DefinitionResource;
import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowPersistenceException;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.AppComponent;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.BootObservation;
import dev.vertique.workflow.registry.store.DefinitionFilter;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionProvenance;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.WorkflowDefinitionDocument;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/** Verifies source normalization, guarded body transfer and DOCUMENT-only database encoding policy. */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class DefinitionDocumentPolicyIT {

    private static final int DOCUMENT_LIMIT = 400_000;
    private static final int STORED_LIMIT = 600_000;
    private static final int DEFAULT_DOCUMENT_LIMIT = 262_144;

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_definition_document_policy_it")
            .withMigration("classpath:db/migration/workflow");

    static final PostgresContainer overflowDb = new PostgresContainer()
            .withDatabaseName("workflow_definition_document_overflow_it")
            .withMigration("classpath:db/migration/workflow");

    @Test
    @DisplayName("documents round-trip within the text envelope and every oversized transfer fails closed")
    void roundTripsTextAndRejectsOversizedRowsBeforeTransfer(Vertx vertx, VertxTestContext context) {
        List<Pool> pools = new ArrayList<>();
        List<VertiqueApplicationHandle<?>> handles = new ArrayList<>();
        WorkflowDefinitionSqlObserver observer = new WorkflowDefinitionSqlObserver();
        Pool mainPool = ManagedDefinitionDatabase.openPool(vertx, db);
        Pool observedMainPool = observer.observe(mainPool);
        pools.add(observedMainPool);
        String yamlSource = raisedYamlSource();
        String jsonSource = expandedJsonSource();
        byte[] yamlBytes = yamlSource.getBytes(StandardCharsets.UTF_8);
        byte[] jsonBytes = utf16Be(jsonSource);
        SourceMetadata yamlMetadata = metadata("raised-yaml");
        SourceMetadata jsonMetadata = metadata("utf16-json");
        DefinitionKey yamlKey = new DefinitionKey(WorkflowScopes.DEFAULT, "order-fulfillment-doc", 1);
        DefinitionKey jsonKey = new DefinitionKey(WorkflowScopes.DEFAULT, "order-fulfillment-doc", 2);
        WorkflowDefinitionSqlObserver retryObserver = new WorkflowDefinitionSqlObserver();
        String nonUtf8Database =
                "workflow_defs_non_utf8_" + UUID.randomUUID().toString().replace("-", "");

        JsonObject raisedConfig = new JsonObject()
                .put(
                        "workflow",
                        new JsonObject()
                                .put("definitions", new JsonObject().put("maxDocumentSizeBytes", DOCUMENT_LIMIT)));
        var staticSource = (dev.vertique.workflow.definition.source.WorkflowDefinitionSource)
                () -> List.of(new DefinitionResource(yamlBytes, DocumentFormat.YAML, yamlMetadata));
        BootObservation initialObservation = new BootObservation("order-fulfillment-doc", 1, "unused", false);
        WorkflowDefinitionParserProbe initialParserProbe = new WorkflowDefinitionParserProbe();
        AtomicReference<String> jsonPlanHash = new AtomicReference<>();
        AppComponent first = ManagedDefinitionDatabase.app(
                vertx,
                observedMainPool,
                raisedConfig,
                initialObservation,
                staticSource,
                false,
                true,
                initialParserProbe);
        String yamlPlanHash = first.definitionPipeline()
                .load(new DefinitionResource(yamlBytes, DocumentFormat.YAML, yamlMetadata))
                .planHash();
        int parserCallsBeforeStartup = initialParserProbe.calls();

        Future<Void> work = observedMainPool
                .preparedQuery("DELETE FROM workflow_definitions WHERE definition_id = $1")
                .execute(Tuple.of("order-fulfillment-doc"))
                .compose(ignored -> VertiqueApplicationBootstrap.start(
                        VertiqueRuntime.of(vertx, raisedConfig), ignoredRuntime -> first))
                .compose(handle -> {
                    handles.add(handle);
                    assertThat(initialParserProbe.calls()).isGreaterThan(parserCallsBeforeStartup);
                    assertThat(first.workflowRegistry()
                                    .resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "order-fulfillment-doc", 1))
                                    .plan()
                                    .planHash())
                            .isEqualTo(yamlPlanHash);
                    assertThat(first.workflowDefinitionsConfig().maxDocumentSizeBytes())
                            .isEqualTo(DOCUMENT_LIMIT);
                    assertThat(first.workflowDefinitionsConfig().maxStoredDocumentSizeBytes())
                            .isEqualTo(STORED_LIMIT);
                    assertThat(STORED_LIMIT).isEqualTo((DOCUMENT_LIMIT * 3L) / 2L);
                    assertThat(yamlBytes.length).isGreaterThan(DOCUMENT_LIMIT);
                    assertThat(yamlSource.codePointCount(0, yamlSource.length()))
                            .isGreaterThan(262_144)
                            .isLessThan(DOCUMENT_LIMIT);
                    assertThat(yamlSource.getBytes(StandardCharsets.UTF_8).length)
                            .isLessThanOrEqualTo(STORED_LIMIT);
                    assertThat(yamlSource).contains("😀");
                    assertThat(jsonBytes.length).isLessThanOrEqualTo(DOCUMENT_LIMIT);
                    assertThat(jsonSource.codePointCount(0, jsonSource.length()))
                            .isLessThan(DOCUMENT_LIMIT);
                    assertThat(jsonSource.getBytes(StandardCharsets.UTF_8).length)
                            .isGreaterThan(DOCUMENT_LIMIT);
                    assertThat(jsonSource.getBytes(StandardCharsets.UTF_8).length)
                            .isLessThanOrEqualTo(STORED_LIMIT);
                    assertThat((long) jsonSource.getBytes(StandardCharsets.UTF_8).length)
                            .isLessThanOrEqualTo((DOCUMENT_LIMIT * 3L) / 2L);

                    return assertStoredSource(first, yamlKey, yamlSource, DocumentFormat.YAML, yamlPlanHash, observer)
                            .compose(ignored -> assertSqlTextShape(observedMainPool, yamlKey, yamlSource))
                            .compose(ignored -> {
                                int statementStart = observer.statements().size();
                                return first.definitionService()
                                        .list(new DefinitionFilter(WorkflowScopes.DEFAULT, null, null))
                                        .map(records -> {
                                            assertThat(records)
                                                    .extracting(WorkflowDefinitionRecord::key)
                                                    .containsExactly(yamlKey);
                                            assertThat(observer.queries()).hasSizeGreaterThanOrEqualTo(2);
                                            assertNoDocumentSqlSince(observer, statementStart);
                                            return (Void) null;
                                        });
                            })
                            .compose(ignored -> assertRuntimeAdmissionLimit(vertx, observedMainPool))
                            .compose(ignored -> first.definitionService()
                                    .load(WorkflowScopes.DEFAULT, jsonBytes, DocumentFormat.JSON, jsonMetadata))
                            .compose(compiled -> {
                                jsonPlanHash.set(compiled.planHash());
                                assertThat(compiled.planHash()).isNotBlank();
                                return first.definitionRegistryStore()
                                        .find(jsonKey)
                                        .map(record -> {
                                            assertThat(record).isPresent();
                                            assertThat(record.orElseThrow().status())
                                                    .isEqualTo(DefinitionStatus.CANDIDATE);
                                            assertThat(record.orElseThrow().planHash())
                                                    .isEqualTo(compiled.planHash());
                                            return (Void) null;
                                        });
                            })
                            .compose(ignored -> first.definitionService().activate(jsonKey))
                            .compose(ignored -> assertStoredSource(
                                    first, jsonKey, jsonSource, DocumentFormat.JSON, jsonPlanHash.get(), observer))
                            .compose(ignored -> assertSqlTextShape(observedMainPool, jsonKey, jsonSource))
                            .compose(ignored -> insertUtf8OversizedRow(
                                    observedMainPool, "oversized-utf8-candidate", "CANDIDATE", 300_001))
                            .compose(ignored -> first.definitionRegistryStore()
                                    .findDocument(
                                            new DefinitionKey(WorkflowScopes.DEFAULT, "oversized-utf8-candidate", 1))
                                    .transform(result -> {
                                        assertThat(result.failed()).isTrue();
                                        assertThat(result.cause()).isInstanceOf(WorkflowDefinitionException.class);
                                        assertThat(result.cause().getMessage())
                                                .contains("oversized-utf8-candidate")
                                                .contains(Integer.toString(STORED_LIMIT + 2));
                                        assertThat(observer.queries()).anySatisfy(query -> assertThat(query.rows())
                                                .anySatisfy(row -> {
                                                    assertThat(row.definitionId())
                                                            .isEqualTo("oversized-utf8-candidate");
                                                    assertThat(row.documentBody())
                                                            .isNull();
                                                    assertThat(((Number) row.documentSizeBytes()).intValue())
                                                            .isEqualTo(STORED_LIMIT + 2);
                                                    assertThat(((Number) row.documentCodePoints()).intValue())
                                                            .isEqualTo(300_001);
                                                }));
                                        return Future.succeededFuture();
                                    }))
                            .compose(ignored -> first.definitionRegistryStore()
                                    .list(new DefinitionFilter(WorkflowScopes.DEFAULT, null, null))
                                    .map(records -> {
                                        assertThat(records)
                                                .extracting(WorkflowDefinitionRecord::key)
                                                .contains(new DefinitionKey(
                                                        WorkflowScopes.DEFAULT, "oversized-utf8-candidate", 1));
                                        return (Void) null;
                                    }))
                            .compose(ignored -> first.definitionService()
                                    .sourceMetadata(yamlKey)
                                    .map(source -> {
                                        assertThat(source).contains(yamlMetadata);
                                        return (Void) null;
                                    }))
                            .compose(ignored -> handle.shutdown())
                            .compose(ignored -> observedMainPool.close())
                            .compose(ignored -> {
                                Pool restoredRawPool = ManagedDefinitionDatabase.openPool(vertx, db);
                                WorkflowDefinitionSqlObserver restoredObserver = new WorkflowDefinitionSqlObserver();
                                Pool restoredPool = restoredObserver.observe(restoredRawPool);
                                pools.add(restoredPool);
                                BootObservation restoredObservation = new BootObservation(
                                        jsonKey.definitionId(), jsonKey.definitionVersion(), jsonPlanHash.get(), true);
                                AppComponent restored = ManagedDefinitionDatabase.app(
                                        vertx, restoredPool, raisedConfig, restoredObservation, List::of, false, true);
                                return VertiqueApplicationBootstrap.start(
                                                VertiqueRuntime.of(vertx, raisedConfig), ignoredRuntime -> restored)
                                        .compose(restoredHandle -> {
                                            handles.add(restoredHandle);
                                            assertThat(restored.workflowRegistry()
                                                            .resolve(new DefinitionKey(
                                                                    WorkflowScopes.DEFAULT,
                                                                    yamlKey.definitionId(),
                                                                    yamlKey.definitionVersion()))
                                                            .plan()
                                                            .planHash())
                                                    .isEqualTo(yamlPlanHash);
                                            assertThat(restored.workflowRegistry()
                                                            .resolve(new DefinitionKey(
                                                                    WorkflowScopes.DEFAULT,
                                                                    jsonKey.definitionId(),
                                                                    jsonKey.definitionVersion()))
                                                            .plan()
                                                            .planHash())
                                                    .isEqualTo(jsonPlanHash.get());
                                            assertThat(restoredObservation.events())
                                                    .containsExactly(
                                                            "bootstrap-complete", "bootstrap-verticle-deployed");
                                            return assertStoredSource(
                                                            restored,
                                                            jsonKey,
                                                            jsonSource,
                                                            DocumentFormat.JSON,
                                                            jsonPlanHash.get(),
                                                            restoredObserver)
                                                    .compose(ignoredRestore -> restored.definitionService()
                                                            .list(new DefinitionFilter(
                                                                    WorkflowScopes.DEFAULT, null, null))
                                                            .map(records -> {
                                                                assertThat(records.stream()
                                                                                .filter(record -> record.key()
                                                                                        .equals(yamlKey))
                                                                                .findFirst())
                                                                        .get()
                                                                        .extracting(WorkflowDefinitionRecord::status)
                                                                        .isEqualTo(DefinitionStatus.SUPERSEDED);
                                                                assertThat(records.stream()
                                                                                .filter(record -> record.key()
                                                                                        .equals(jsonKey))
                                                                                .findFirst())
                                                                        .get()
                                                                        .extracting(WorkflowDefinitionRecord::status)
                                                                        .isEqualTo(DefinitionStatus.ACTIVE);
                                                                return (Void) null;
                                                            }))
                                                    .compose(ignoredRestore -> restoredHandle.shutdown())
                                                    .compose(ignoredShutdown -> exerciseInvalidConfigBeforeParsing(
                                                            vertx, db, pools, staticSource));
                                        });
                            });
                })
                .compose(ignored -> {
                    Pool startupPool = ManagedDefinitionDatabase.openPool(vertx, db);
                    WorkflowDefinitionSqlObserver startupObserver = new WorkflowDefinitionSqlObserver();
                    Pool observedStartupPool = startupObserver.observe(startupPool);
                    pools.add(observedStartupPool);
                    AppComponent restarted = ManagedDefinitionDatabase.app(
                            vertx,
                            observedStartupPool,
                            raisedConfig,
                            new BootObservation("order-fulfillment-doc", 1, "unused", false),
                            List::of,
                            false,
                            true);
                    return insertOversizedRow(startupPool, "oversized-codepoints-active", "ACTIVE", DOCUMENT_LIMIT + 1)
                            .compose(ignoredInsert -> VertiqueApplicationBootstrap.start(
                                    VertiqueRuntime.of(vertx, raisedConfig), ignoredRuntime -> restarted))
                            .transform(started -> {
                                assertThat(started.failed()).isTrue();
                                assertThat(started.cause()).isInstanceOf(WorkflowDefinitionException.class);
                                assertThat(started.cause().getMessage())
                                        .contains("oversized-codepoints-active")
                                        .contains(Integer.toString(DOCUMENT_LIMIT));
                                assertThat(startupObserver.queries()).anySatisfy(query -> assertThat(query.rows())
                                        .anySatisfy(row -> {
                                            assertThat(row.definitionId()).isEqualTo("oversized-codepoints-active");
                                            assertThat(row.documentBody()).isNull();
                                            assertThat(((Number) row.documentSizeBytes()).intValue())
                                                    .isEqualTo(DOCUMENT_LIMIT + 1);
                                            assertThat(((Number) row.documentCodePoints()).intValue())
                                                    .isEqualTo(DOCUMENT_LIMIT + 1);
                                        }));
                                return Future.succeededFuture();
                            });
                })
                .compose(ignored -> exerciseOverflowEnvelope(vertx, pools, handles))
                .compose(ignored -> exerciseStrictDurableText(vertx, overflowDb, pools, handles))
                .compose(ignored -> exerciseLowerLimitRestore(vertx, overflowDb, pools, handles))
                .compose(ignored -> createNonUtf8Database(vertx, db, nonUtf8Database))
                .compose(ignored -> exerciseNonUtf8Database(vertx, db, nonUtf8Database, pools, handles))
                .compose(ignored -> exerciseEncodingLookupRetry(vertx, db, yamlKey, retryObserver, pools));

        finish(context, vertx, work, handles, pools, nonUtf8Database);
    }

    @Test
    @DisplayName("the startup parser-call assertion detects typed configuration resolved too late")
    void parserObservationDetectsLateTypedValidation(Vertx vertx, VertxTestContext context) {
        Pool pool = ManagedDefinitionDatabase.openPool(vertx, db);
        WorkflowDefinitionParserProbe parserProbe = new WorkflowDefinitionParserProbe();
        JsonObject invalidConfig = new JsonObject()
                .put("workflow", new JsonObject().put("definitions", new JsonObject().put("maxDocumentSizeBytes", 0)));
        AppComponent validComponent = ManagedDefinitionDatabase.app(
                vertx,
                pool,
                new JsonObject(),
                new BootObservation("unused", 1, "unused", false),
                List::of,
                false,
                false,
                parserProbe);
        AppComponent invalidPolicyComponent = ManagedDefinitionDatabase.app(
                vertx,
                pool,
                invalidConfig,
                new BootObservation("unused", 1, "unused", false),
                List::of,
                false,
                false,
                parserProbe);

        Throwable failure = null;
        try {
            var parsed = validComponent
                    .definitionPipeline()
                    .load(new DefinitionResource(
                            ManagedDefinitionDatabase.yamlFixture(),
                            DocumentFormat.YAML,
                            metadata("parser-order-calibration")));
            assertThat(parsed.planHash()).hasSize(64);
            assertThat(parserProbe.calls()).isEqualTo(1);
            assertThatThrownBy(invalidPolicyComponent::workflowDefinitionsConfig)
                    .isInstanceOf(ConfigurationException.class);
            assertThatThrownBy(() -> assertThat(parserProbe.calls()).isZero()).isInstanceOf(AssertionError.class);
        } catch (Throwable observed) {
            failure = observed;
        }

        Throwable proofFailure = failure;
        pool.close().onComplete(closed -> {
            if (proofFailure != null) {
                if (closed.failed()) {
                    proofFailure.addSuppressed(closed.cause());
                }
                context.failNow(proofFailure);
            } else if (closed.failed()) {
                context.failNow(closed.cause());
            } else {
                context.completeNow();
            }
        });
    }

    private static Future<Void> assertStoredSource(
            AppComponent component,
            DefinitionKey key,
            String source,
            DocumentFormat format,
            String expectedPlanHash,
            WorkflowDefinitionSqlObserver observer) {
        return component.definitionRegistryStore().findDocument(key).compose(found -> {
            assertThat(found).isPresent();
            WorkflowDefinitionDocument document = found.orElseThrow();
            assertThat(document.key()).isEqualTo(key);
            assertThat(document.document()).isEqualTo(source);
            assertThat(document.documentFormat()).isEqualTo(format.name());
            assertThat(component
                            .workflowRegistry()
                            .resolve(new DefinitionKey(
                                    WorkflowScopes.DEFAULT, key.definitionId(), key.definitionVersion()))
                            .plan()
                            .planHash())
                    .isEqualTo(expectedPlanHash);
            int statementStart = observer.statements().size();
            return component
                    .definitionService()
                    .list(new DefinitionFilter(WorkflowScopes.DEFAULT, null, null))
                    .map(records -> {
                        assertThat(records.stream()
                                        .filter(record -> record.key().equals(key))
                                        .findFirst())
                                .isPresent()
                                .get()
                                .extracting(WorkflowDefinitionRecord::planHash)
                                .isEqualTo(expectedPlanHash);
                        assertNoDocumentSqlSince(observer, statementStart);
                        return (Void) null;
                    });
        });
    }

    private static Future<Void> assertSqlTextShape(Pool pool, DefinitionKey key, String source) {
        return pool.preparedQuery("""
                        SELECT octet_length(document) AS source_bytes, char_length(document) AS source_characters
                        FROM workflow_definitions
                        WHERE scope_key = 'default' AND definition_id = $1 AND definition_version = $2
                        """)
                .execute(Tuple.of(key.definitionId(), key.definitionVersion()))
                .map(rows -> {
                    Row row = rows.iterator().next();
                    assertThat(row.getLong("source_bytes"))
                            .isEqualTo((long) source.getBytes(StandardCharsets.UTF_8).length);
                    assertThat(row.getLong("source_characters"))
                            .isEqualTo((long) source.codePointCount(0, source.length()));
                    return (Void) null;
                });
    }

    private static void assertNoDocumentSqlSince(WorkflowDefinitionSqlObserver observer, int start) {
        assertThat(observer.statements().subList(start, observer.statements().size()))
                .noneMatch(DefinitionDocumentPolicyIT::referencesDocumentBody);
    }

    private static boolean referencesDocumentBody(String sql) {
        return java.util.regex.Pattern.compile("\\bdocument\\b")
                .matcher(sql.toLowerCase(java.util.Locale.ROOT))
                .find();
    }

    private static Future<Void> assertRuntimeAdmissionLimit(Vertx vertx, Pool pool) {
        AppComponent defaultPolicy = ManagedDefinitionDatabase.app(
                vertx, pool, new JsonObject(), new BootObservation("unused", 1, "unused", false), List::of);
        byte[] oversized = new byte[DEFAULT_DOCUMENT_LIMIT + 1];
        java.util.Arrays.fill(oversized, (byte) ' ');
        Future<CompiledDefinitionRef> load = defaultPolicy
                .definitionService()
                .load(WorkflowScopes.DEFAULT, oversized, DocumentFormat.JSON, metadata("oversized-runtime"));
        return load.transform(result -> {
            assertThat(result.failed()).isTrue();
            assertThat(result.cause()).isInstanceOf(WorkflowDefinitionException.class);
            assertThat(result.cause())
                    .isNotInstanceOf(dev.vertique.workflow.definition.parser.WorkflowDefinitionParseException.class);
            return Future.succeededFuture();
        });
    }

    private static Future<Void> exerciseInvalidConfigBeforeParsing(
            Vertx vertx,
            PostgresContainer postgres,
            List<Pool> pools,
            dev.vertique.workflow.definition.source.WorkflowDefinitionSource source) {
        Pool pool = ManagedDefinitionDatabase.openPool(vertx, postgres);
        pools.add(pool);
        JsonObject invalidConfig = new JsonObject()
                .put("workflow", new JsonObject().put("definitions", new JsonObject().put("maxDocumentSizeBytes", 0)));
        WorkflowDefinitionParserProbe parserProbe = new WorkflowDefinitionParserProbe();
        AppComponent component = ManagedDefinitionDatabase.app(
                vertx,
                pool,
                invalidConfig,
                new BootObservation("unused", 1, "unused", false),
                source,
                false,
                false,
                parserProbe);
        return VertiqueApplicationBootstrap.start(VertiqueRuntime.of(vertx, invalidConfig), ignoredRuntime -> component)
                .transform(started -> {
                    assertThat(started.failed()).isTrue();
                    assertThat(started.cause()).isInstanceOf(ConfigurationException.class);
                    assertThat(started.cause().getMessage()).contains("maxDocumentSizeBytes");
                    assertThat(parserProbe.calls()).isZero();
                    return Future.succeededFuture();
                });
    }

    private static Future<Void> exerciseStrictDurableText(
            Vertx vertx, PostgresContainer postgres, List<Pool> pools, List<VertiqueApplicationHandle<?>> handles) {
        Pool pool = ManagedDefinitionDatabase.openPool(vertx, postgres);
        pools.add(pool);
        AppComponent component = ManagedDefinitionDatabase.app(
                vertx, pool, new JsonObject(), new BootObservation("unused", 1, "unused", false), List::of);
        DefinitionKey key = new DefinitionKey(WorkflowScopes.DEFAULT, "order-fulfillment-doc", 2);
        SourceMetadata provenance = metadata("strict-text");
        byte[] validRoot = ManagedDefinitionDatabase.jsonFixture();
        byte[] malformedTail = append(validRoot, (byte) 0xff);
        byte[] nulTail = append(validRoot, (byte) 0);
        String escapedSource = new String(validRoot, StandardCharsets.UTF_8) + "\n\"\\u0000\"";

        return VertiqueApplicationBootstrap.start(VertiqueRuntime.of(vertx, new JsonObject()), ignored -> component)
                .compose(handle -> {
                    handles.add(handle);
                    Future<CompiledDefinitionRef> malformed = component
                            .definitionService()
                            .load(WorkflowScopes.DEFAULT, malformedTail, DocumentFormat.JSON, provenance);
                    return malformed
                            .transform(result -> {
                                assertThat(result.failed()).isTrue();
                                assertThat(result.cause()).isInstanceOf(WorkflowDefinitionException.class);
                                assertThat(result.cause())
                                        .isNotInstanceOf(
                                                dev.vertique.workflow.definition.parser.WorkflowDefinitionParseException
                                                        .class);
                                return Future.succeededFuture();
                            })
                            .compose(ignoredMalformed -> {
                                Future<CompiledDefinitionRef> nulSource = component
                                        .definitionService()
                                        .load(WorkflowScopes.DEFAULT, nulTail, DocumentFormat.JSON, provenance);
                                return nulSource.transform(result -> {
                                    assertThat(result.failed()).isTrue();
                                    assertThat(result.cause()).isInstanceOf(WorkflowDefinitionException.class);
                                    assertThat(result.cause())
                                            .isNotInstanceOf(
                                                    dev.vertique.workflow.definition.parser
                                                            .WorkflowDefinitionParseException.class);
                                    return Future.succeededFuture();
                                });
                            })
                            .compose(ignoredNul -> component
                                    .definitionService()
                                    .load(
                                            WorkflowScopes.DEFAULT,
                                            escapedSource.getBytes(StandardCharsets.UTF_8),
                                            DocumentFormat.JSON,
                                            provenance))
                            .compose(ignoredLoaded -> component
                                    .definitionRegistryStore()
                                    .findDocument(key)
                                    .map(found -> {
                                        assertThat(found).isPresent();
                                        assertThat(found.orElseThrow().document())
                                                .isEqualTo(escapedSource);
                                        assertThat(found.orElseThrow().document())
                                                .doesNotContain(Character.toString(0));
                                        return (Void) null;
                                    }))
                            .compose(ignoredText -> handle.shutdown());
                });
    }

    private static byte[] append(byte[] source, byte value) {
        byte[] result = java.util.Arrays.copyOf(source, source.length + 1);
        result[result.length - 1] = value;
        return result;
    }

    private static Future<Void> insertOversizedRow(Pool pool, String definitionId, String status, int size) {
        return pool.preparedQuery("""
                        INSERT INTO workflow_definitions
                            (scope_key, definition_id, definition_version, kind, contract_name, state_type_name, plan_hash, status,
                             document, document_format, document_digest, source_type, loaded_at)
                        VALUES ('default', $1, 1, 'DOCUMENT', 'example.Contract', 'example.State', repeat('a', 64), $2, repeat('x', $3),
                                'JSON', encode(sha256(convert_to(repeat('x', $3), 'UTF8')), 'hex'),
                                'policy-test', now())
                        """)
                .execute(Tuple.of(definitionId, status, size))
                .mapEmpty();
    }

    private static Future<Void> insertUtf8OversizedRow(Pool pool, String definitionId, String status, int codePoints) {
        return pool.preparedQuery("""
                        INSERT INTO workflow_definitions
                            (scope_key, definition_id, definition_version, kind, contract_name, state_type_name, plan_hash, status,
                             document, document_format, document_digest, source_type, loaded_at)
                        VALUES ('default', $1, 1, 'DOCUMENT', 'example.Contract', 'example.State', repeat('a', 64), $2, repeat('é', $3),
                                'JSON', encode(sha256(convert_to(repeat('é', $3), 'UTF8')), 'hex'),
                                'policy-test', now())
                        """)
                .execute(Tuple.of(definitionId, status, codePoints))
                .mapEmpty();
    }

    private static Future<Void> exerciseOverflowEnvelope(
            Vertx vertx, List<Pool> pools, List<VertiqueApplicationHandle<?>> handles) {
        Pool rawPool = ManagedDefinitionDatabase.openPool(vertx, overflowDb);
        pools.add(rawPool);
        byte[] jsonBytes = utf16Be(new String(ManagedDefinitionDatabase.jsonFixture(), StandardCharsets.UTF_8));
        int maxStoredBytes = (1_000 * 3) / 2;
        assertThat(jsonBytes.length).isGreaterThan(1_000);
        assertThat(new String(ManagedDefinitionDatabase.jsonFixture(), StandardCharsets.UTF_8)
                        .getBytes(StandardCharsets.UTF_8)
                        .length)
                .isGreaterThan(maxStoredBytes);
        var source = (dev.vertique.workflow.definition.source.WorkflowDefinitionSource)
                () -> List.of(new DefinitionResource(jsonBytes, DocumentFormat.JSON, metadata("envelope-overflow")));
        JsonObject smallConfig = new JsonObject()
                .put(
                        "workflow",
                        new JsonObject().put("definitions", new JsonObject().put("maxDocumentSizeBytes", 1_000)));
        AppComponent invalid = ManagedDefinitionDatabase.app(
                vertx, rawPool, smallConfig, new BootObservation("order-fulfillment-doc", 2, "unused", false), source);
        return VertiqueApplicationBootstrap.start(VertiqueRuntime.of(vertx, smallConfig), ignored -> invalid)
                .transform(started -> {
                    assertThat(started.failed()).isTrue();
                    assertThat(started.cause()).isInstanceOf(WorkflowDefinitionException.class);
                    assertThat(started.cause().getMessage())
                            .contains("order-fulfillment-doc")
                            .contains(Integer.toString(maxStoredBytes));
                    return Future.succeededFuture();
                });
    }

    private static Future<Void> exerciseLowerLimitRestore(
            Vertx vertx, PostgresContainer postgres, List<Pool> pools, List<VertiqueApplicationHandle<?>> handles) {
        String definitionId = "order-fulfillment-doc";
        DefinitionKey key = new DefinitionKey(WorkflowScopes.DEFAULT, definitionId, 2);
        byte[] source = ManagedDefinitionDatabase.jsonFixture();
        int lowerLimit = 1_000;
        JsonObject loweredConfig = new JsonObject()
                .put(
                        "workflow",
                        new JsonObject().put("definitions", new JsonObject().put("maxDocumentSizeBytes", lowerLimit)));

        Pool initialPool = ManagedDefinitionDatabase.openPool(vertx, postgres);
        pools.add(initialPool);
        AppComponent initial = ManagedDefinitionDatabase.app(
                vertx,
                initialPool,
                new JsonObject(),
                new BootObservation(definitionId, key.definitionVersion(), "unused", false),
                List::of);
        Future<Void> saved = initialPool
                .preparedQuery("DELETE FROM workflow_definitions WHERE definition_id = $1")
                .execute(Tuple.of(definitionId))
                .compose(ignored -> VertiqueApplicationBootstrap.start(
                        VertiqueRuntime.of(vertx, new JsonObject()), ignoredRuntime -> initial))
                .compose(initialHandle -> {
                    handles.add(initialHandle);
                    SourceMetadata metadata = metadata("lower-limit-restore");
                    return initial.definitionService()
                            .load(WorkflowScopes.DEFAULT, source, DocumentFormat.JSON, metadata)
                            .compose(compiled ->
                                    initial.definitionService().activate(key).map(compiled))
                            .compose(compiled -> initial.definitionRegistryStore()
                                    .findDocument(key)
                                    .map(document -> {
                                        assertThat(document).isPresent();
                                        assertThat(source.length).isLessThan(262_144);
                                        assertThat(document.orElseThrow().document())
                                                .isEqualTo(new String(source, StandardCharsets.UTF_8));
                                        assertThat(compiled.planHash()).isNotBlank();
                                        return compiled;
                                    }))
                            .compose(compiled -> initialHandle.shutdown().map(compiled))
                            .compose(compiled -> initialPool.close().map(compiled))
                            .mapEmpty();
                });

        return saved.compose(ignored -> {
            Pool restorePool = ManagedDefinitionDatabase.openPool(vertx, postgres);
            pools.add(restorePool);
            BootObservation observation = new BootObservation(definitionId, key.definitionVersion(), "unused", false);
            AppComponent lowered =
                    ManagedDefinitionDatabase.app(vertx, restorePool, loweredConfig, observation, List::of);
            return VertiqueApplicationBootstrap.start(
                            VertiqueRuntime.of(vertx, loweredConfig), ignoredRuntime -> lowered)
                    .transform(started -> {
                        assertThat(started.failed()).isTrue();
                        assertThat(started.cause()).isInstanceOf(WorkflowDefinitionException.class);
                        assertThat(started.cause().getMessage()).contains(definitionId);
                        assertThat(List.of(lowerLimit, (lowerLimit * 3) / 2).stream()
                                        .map(String::valueOf)
                                        .anyMatch(started.cause().getMessage()::contains))
                                .isTrue();
                        assertThat(observation.events()).isEmpty();
                        return restorePool.close();
                    });
        });
    }

    private static Future<Void> createNonUtf8Database(Vertx vertx, PostgresContainer postgres, String databaseName) {
        Promise<Void> result = Promise.promise();
        CompletableFuture.runAsync(() -> {
                    try (Connection connection = DriverManager.getConnection(
                                    postgres.jdbcUrl(), postgres.username(), postgres.password());
                            Statement statement = connection.createStatement()) {
                        statement.execute("CREATE DATABASE \"" + databaseName
                                + "\" WITH ENCODING 'SQL_ASCII' LC_COLLATE 'C' LC_CTYPE 'C' TEMPLATE template0");
                    } catch (SQLException e) {
                        throw new CompletionException(e);
                    }
                    Flyway.configure()
                            .dataSource(databaseUrl(postgres, databaseName), postgres.username(), postgres.password())
                            .locations("classpath:db/migration/workflow")
                            .load()
                            .migrate();
                })
                .whenComplete((ignored, failure) -> vertx.runOnContext(ignoredContext -> {
                    if (failure == null) {
                        result.complete();
                    } else {
                        result.fail(failure);
                    }
                }));
        return result.future();
    }

    private static Future<Void> exerciseNonUtf8Database(
            Vertx vertx,
            PostgresContainer postgres,
            String databaseName,
            List<Pool> pools,
            List<VertiqueApplicationHandle<?>> handles) {
        Pool rawPool = ManagedDefinitionDatabase.openPool(vertx, postgres, databaseName);
        WorkflowDefinitionSqlObserver observer = new WorkflowDefinitionSqlObserver();
        Pool pool = observer.observe(rawPool);
        pools.add(pool);
        DefinitionKey documentKey = new DefinitionKey(WorkflowScopes.DEFAULT, "non-utf8-document-candidate", 1);
        WorkflowDefinitionRecord documentRecord = new WorkflowDefinitionRecord(
                documentKey,
                DefinitionKind.DOCUMENT,
                "example.Contract",
                "example.State",
                "a".repeat(64),
                DefinitionStatus.CANDIDATE,
                new DefinitionProvenance("non-utf8-test", null, null, null, null, Instant.now()),
                "JSON",
                Instant.now(),
                Instant.now());
        WorkflowDefinitionDocument document = new WorkflowDefinitionDocument(documentKey, "{}", "JSON");
        var noSources = (dev.vertique.workflow.definition.source.WorkflowDefinitionSource) List::of;
        AppComponent app = ManagedDefinitionDatabase.app(
                vertx,
                pool,
                new JsonObject(),
                new BootObservation("definition-policy-code", 1, "unused", false),
                noSources,
                true);

        return pool.preparedQuery("""
                        INSERT INTO workflow_definitions
                            (scope_key, definition_id, definition_version, kind, contract_name, state_type_name, plan_hash, status,
                             document, document_format, document_digest, source_type, loaded_at)
                        VALUES ('default', $1, 1, 'DOCUMENT', 'example.Contract', 'example.State', repeat('a', 64), 'CANDIDATE', '{}',
                                'JSON',
                                -- SHA-256 of the two-character text {}, spelled out because this database is SQL_ASCII
                                '44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a',
                                'non-utf8-test', now()),
                               ('default', 'definition-policy-code', 1, 'CODE', 'example.Contract', 'example.State', repeat('b', 64), 'ACTIVE',
                                NULL, NULL, NULL, 'fixture-code', now())
                        """)
                .execute(Tuple.of(documentKey.definitionId()))
                .compose(ignored -> VertiqueApplicationBootstrap.start(
                        VertiqueRuntime.of(vertx, new JsonObject()), ignoredRuntime -> app))
                .compose(handle -> {
                    handles.add(handle);
                    return pool.query("SELECT current_setting('server_encoding') AS encoding")
                            .execute()
                            .map(rows -> {
                                assertThat(rows.iterator().next().getString("encoding"))
                                        .isEqualTo("SQL_ASCII");
                                return (Void) null;
                            })
                            .compose(ignored -> app.definitionRegistryStore()
                                    .list(new DefinitionFilter(WorkflowScopes.DEFAULT, null, null))
                                    .map(records -> {
                                        assertThat(records)
                                                .extracting(WorkflowDefinitionRecord::key)
                                                .contains(
                                                        documentKey,
                                                        new DefinitionKey(
                                                                WorkflowScopes.DEFAULT, "definition-policy-code", 1));
                                        return (Void) null;
                                    }))
                            .compose(ignored -> {
                                int statementStart = observer.statements().size();
                                return app.definitionRegistryStore()
                                        .findDocument(documentKey)
                                        .transform(result -> {
                                            assertThat(result.failed()).isTrue();
                                            assertThat(result.cause()).isInstanceOf(WorkflowDefinitionException.class);
                                            assertThat(result.cause().getMessage())
                                                    .contains("UTF8");
                                            assertNoDocumentSqlSince(observer, statementStart);
                                            assertThat(observer.queries()).noneSatisfy(query -> assertThat(query.rows())
                                                    .anySatisfy(row -> assertThat(row.definitionId())
                                                            .isEqualTo(documentKey.definitionId())));
                                            return Future.succeededFuture();
                                        });
                            })
                            .compose(ignored -> {
                                int statementStart = observer.statements().size();
                                return app.definitionRegistryStore()
                                        .upsertDocument(documentRecord, document)
                                        .transform(result -> {
                                            assertThat(result.failed()).isTrue();
                                            assertThat(result.cause()).isInstanceOf(WorkflowDefinitionException.class);
                                            assertThat(result.cause().getMessage())
                                                    .contains("UTF8");
                                            assertNoDocumentSqlSince(observer, statementStart);
                                            assertThat(observer.queries()).noneSatisfy(query -> assertThat(query.rows())
                                                    .anySatisfy(row -> assertThat(row.definitionId())
                                                            .isEqualTo(documentKey.definitionId())));
                                            return Future.succeededFuture();
                                        });
                            })
                            .compose(ignored -> handle.shutdown());
                });
    }

    private static Future<Void> exerciseEncodingLookupRetry(
            Vertx vertx,
            PostgresContainer postgres,
            DefinitionKey key,
            WorkflowDefinitionSqlObserver observer,
            List<Pool> pools) {
        Pool rawPool = ManagedDefinitionDatabase.openPool(vertx, postgres);
        Pool pool = observer.observe(rawPool);
        pools.add(pool);
        PgException driverFailure = observer.failNextEncodingLookup();
        AppComponent component = ManagedDefinitionDatabase.app(
                vertx,
                pool,
                new JsonObject()
                        .put(
                                "workflow",
                                new JsonObject()
                                        .put(
                                                "definitions",
                                                new JsonObject().put("maxDocumentSizeBytes", DOCUMENT_LIMIT))),
                new BootObservation(key.definitionId(), key.definitionVersion(), "unused", false),
                List::of);

        int statementStart = observer.statements().size();
        int queryStart = observer.queries().size();
        Future<java.util.Optional<WorkflowDefinitionDocument>> firstAttempt =
                component.definitionRegistryStore().findDocument(key);
        return firstAttempt
                .transform(first -> {
                    assertThat(first.failed()).isTrue();
                    assertThat(first.cause()).isInstanceOf(WorkflowPersistenceException.class);
                    WorkflowPersistenceException persistenceFailure = (WorkflowPersistenceException) first.cause();
                    assertThat(persistenceFailure.retryable()).isTrue();
                    assertThat(persistenceFailure.getCause()).isInstanceOf(ConnectionException.class);
                    ConnectionException connectionFailure = (ConnectionException) persistenceFailure.getCause();
                    assertThat(connectionFailure.sqlState()).isEqualTo("57P01");
                    assertThat(connectionFailure.getCause()).isSameAs(driverFailure);
                    assertNoDocumentSqlSince(observer, statementStart);
                    assertThat(observer.queries()
                                    .subList(queryStart, observer.queries().size()))
                            .noneSatisfy(
                                    query -> assertThat(query.rows()).anySatisfy(row -> assertThat(row.documentBody())
                                            .isNotNull()));
                    assertThat(observer.encodingLookupCount()).isEqualTo(1);
                    return component.definitionRegistryStore().findDocument(key).map(found -> {
                        assertThat(found).isPresent();
                        assertThat(found.orElseThrow().key()).isEqualTo(key);
                        assertThat(observer.encodingLookupCount()).isEqualTo(2);
                        return (Void) null;
                    });
                })
                .compose(ignored -> component
                        .definitionRegistryStore()
                        .findDocument(key)
                        .map(found -> {
                            assertThat(found).isPresent();
                            assertThat(observer.encodingLookupCount()).isEqualTo(2);
                            return (Void) null;
                        }))
                .compose(ignored -> pool.close());
    }

    private static Future<Void> finish(
            VertxTestContext context,
            Vertx vertx,
            Future<Void> work,
            List<VertiqueApplicationHandle<?>> handles,
            List<Pool> pools,
            String nonUtf8Database) {
        work.onComplete(result -> {
            Future<Void> cleanup = Future.succeededFuture();
            List<Throwable> cleanupFailures = new ArrayList<>();
            for (int index = handles.size() - 1; index >= 0; index--) {
                VertiqueApplicationHandle<?> handle = handles.get(index);
                cleanup = cleanup.compose(ignored -> handle.shutdown().recover(cause -> {
                    cleanupFailures.add(cause);
                    return Future.succeededFuture();
                }));
            }
            for (int index = pools.size() - 1; index >= 0; index--) {
                Pool pool = pools.get(index);
                cleanup = cleanup.compose(ignored -> pool.close().recover(cause -> {
                    cleanupFailures.add(cause);
                    return Future.succeededFuture();
                }));
            }
            cleanup.compose(ignored -> dropNonUtf8Database(vertx, db, nonUtf8Database)
                            .recover(cause -> {
                                cleanupFailures.add(cause);
                                return Future.succeededFuture();
                            }))
                    .onComplete(cleaned -> {
                        if (cleaned.failed()) {
                            cleanupFailures.add(cleaned.cause());
                        }
                        if (result.failed()) {
                            cleanupFailures.forEach(result.cause()::addSuppressed);
                            context.failNow(result.cause());
                        } else if (!cleanupFailures.isEmpty()) {
                            Throwable failure = cleanupFailures.get(0);
                            cleanupFailures.stream().skip(1).forEach(failure::addSuppressed);
                            context.failNow(failure);
                        } else {
                            context.completeNow();
                        }
                    });
        });
        return Future.succeededFuture();
    }

    private static Future<Void> dropNonUtf8Database(Vertx vertx, PostgresContainer postgres, String databaseName) {
        Promise<Void> result = Promise.promise();
        CompletableFuture.runAsync(() -> {
                    try (Connection connection = DriverManager.getConnection(
                                    postgres.jdbcUrl(), postgres.username(), postgres.password());
                            Statement statement = connection.createStatement()) {
                        statement.execute("DROP DATABASE IF EXISTS \"" + databaseName + "\" WITH (FORCE)");
                    } catch (SQLException e) {
                        throw new CompletionException(e);
                    }
                })
                .whenComplete((ignored, failure) -> vertx.runOnContext(ignoredContext -> {
                    if (failure == null) {
                        result.complete();
                    } else {
                        result.fail(failure);
                    }
                }));
        return result.future();
    }

    private static String databaseUrl(PostgresContainer postgres, String databaseName) {
        String url = postgres.jdbcUrl();
        String withoutQuery = url.substring(0, url.indexOf('?') < 0 ? url.length() : url.indexOf('?'));
        return withoutQuery.substring(0, withoutQuery.lastIndexOf('/') + 1) + databaseName + "?sslmode=disable";
    }

    private static String raisedYamlSource() {
        String original =
                new String(ManagedDefinitionDatabase.yamlFixture(), StandardCharsets.UTF_8).replace("\n", "\r\n");
        return "\uFEFF" + original + "\r\n# accepted Unicode source padding 😀 " + "é".repeat(280_000);
    }

    private static String expandedJsonSource() {
        return new String(ManagedDefinitionDatabase.jsonFixture(), StandardCharsets.UTF_8)
                .replace("doc.order.fromPlaceOrder", ManagedDefinitionDatabase.LONG_UNICODE_MAPPER_ID);
    }

    private static byte[] utf16Be(String source) {
        return source.getBytes(StandardCharsets.UTF_16BE);
    }

    private static SourceMetadata metadata(String uri) {
        return new SourceMetadata(
                "document-policy-test", uri, "revision-1", null, null, Instant.parse("2026-10-18T00:00:00.123456Z"));
    }
}
