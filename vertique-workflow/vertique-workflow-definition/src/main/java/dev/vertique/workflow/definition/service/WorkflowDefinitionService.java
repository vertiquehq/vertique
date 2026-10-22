// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.service;

import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.registry.store.DefinitionActivation;
import dev.vertique.workflow.registry.store.DefinitionFilter;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import io.vertx.core.Future;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Optional;

/** Asynchronous management of document candidates and their publication. */
public interface WorkflowDefinitionService {
    /**
     * Parses and compiles a candidate into the named scope without publishing it.
     *
     * <p>The scope argument is the only source of the document's scope: a document never names one, and a
     * top-level {@code scope} property fails the parse like any other unknown property. The scope key is
     * required, nonblank, at most 255 code points and free of control characters; a missing key fails the
     * returned future with {@link NullPointerException} and any other invalid key with
     * {@link IllegalArgumentException}, before the document is read. The definition id needs no scope prefix, and
     * each scope numbers its own versions of an id. Which caller may load into which scope is for the application
     * to authorize; the service does not check identity.
     *
     * <p>The document resolves its contract, state and payload type names through the type-name index
     * only, which holds the types of the contributed catalogs and registered definitions. A name the
     * index does not hold fails the returned future with {@code WorkflowUnknownTypeNameException},
     * naming the document field and the name when it is a valid name; no class is loaded by name.
     */
    Future<CompiledDefinitionRef> load(String scopeKey, byte[] content, DocumentFormat format, SourceMetadata metadata);
    /**
     * Publishes an existing candidate through a fenced claim, so that the version becomes available and can be
     * started and resolved, in addition to every other version and scope of the same id. A version that is
     * already available is accepted without a change; a failed publication leaves the candidate a candidate.
     */
    Future<Void> publish(DefinitionKey key);
    /**
     * Deprecates an available definition so it is refused for new starts, whether named or selected by an
     * activation record, and as a migration target, while instances already pinned to it keep running. The status is
     * committed durably before it is projected into the local cache and registry. The version that the default
     * scope's activation record of the id names is refused with {@code INVALID_TRANSITION} until another version is
     * activated in the default scope or {@link #deactivateDefault(String)} clears the record. A durable registry is
     * required; without one the returned future fails.
     */
    Future<Void> deprecate(DefinitionKey key);
    /**
     * Retires a candidate, or a deprecated definition once it has cooled and no instance is pinned to it, so it
     * can no longer be published or resolved. The durable status is committed first, together with the cooling
     * and pin checks, and then projected into the local cache; a refused retirement fails typed and changes
     * nothing. The local runtime is removed only when it is the registration published for this key. The version
     * that the default scope's activation record of the id names is refused like a deprecation of it. A
     * durable registry is required; without one the returned future fails.
     */
    Future<Void> retire(DefinitionKey key);
    /**
     * Reinstates a retired or deprecated version, which becomes available. A retired version needs its executable
     * source: the deployed code for a code version, the stored text for a document version. A deprecated version is
     * still registered, so only its status and deprecation mark change. The future fails typed with
     * {@code CODE_UNAVAILABLE}, {@code DRIFT}, {@code LOADER_MISSING} or {@code INVALID_TRANSITION}. When the
     * durable change fails, the version keeps its status and only the registration this call published is removed;
     * a registration that existed before the call stays. A durable registry is required; without one the returned
     * future fails.
     */
    Future<Void> reinstate(DefinitionKey key);
    /**
     * Makes a version the current version of the definition id in a scope, so that a start in that scope without a
     * version runs it. The target scope is the scope itself or the default scope, and the default scope may target
     * only itself; any other combination fails the future with {@link IllegalArgumentException}. The target version
     * must be available in the target scope, otherwise the future fails typed with {@code INVALID_TRANSITION}
     * naming the target key. The scope's earlier record is replaced. Without a durable registry the record lives
     * only in this node's view and is lost on restart.
     */
    Future<Void> activate(String scopeKey, String definitionId, String targetScopeKey, long version);
    /**
     * Clears the default scope's record of the definition id, which is the only way to remove it and is accepted
     * without a change when there is none. Afterwards the formerly current version can be deprecated and retired,
     * and scopes that follow the default start without a version only while the default scope still holds a
     * startable version. No definition row changes and no other scope's record is touched. A non-default scope
     * returns to following the default with {@link #follow(String, String)}.
     */
    Future<Void> deactivateDefault(String definitionId);
    /**
     * Removes a non-default scope's record of the definition id, so the scope follows the default scope's current
     * version again; an absent record is accepted without a change. Following the default scope fails the future
     * with {@link IllegalArgumentException}.
     */
    Future<Void> follow(String scopeKey, String definitionId);
    /**
     * Lists the activation records of one scope, or of every scope when {@code scopeKey} is {@code null}, ordered by
     * definition id and then scope. With a durable registry the records are read from it; without one they are this
     * node's view.
     */
    Future<List<DefinitionActivation>> activations(@Nullable String scopeKey);
    /** Returns an immutable, source-free metadata snapshot in the selected scope. */
    Future<List<WorkflowDefinitionRecord>> list(DefinitionFilter filter);
    /** Returns the source attribution of a definition without reading its source body. */
    Future<Optional<SourceMetadata>> sourceMetadata(DefinitionKey key);
}
