package dev.vertique.workflow.ops;

import dev.vertique.core.context.DurableMetadata;
import jakarta.annotation.Nullable;

/**
 * Resolves the tenant key of a new workflow instance from the durable context the engine captures
 * when the instance is started.
 *
 * <p>The engine evaluates the resolver once per newly created instance, at the point it captures
 * the ambient durable context and inside the start transaction. The result is stamped on the
 * instance and never changes afterwards; the instance's tasks inherit it. A start that replays an
 * existing idempotent start never calls the resolver.
 *
 * <p>When no resolver is bound, or the resolver returns {@code null}, the instance has no tenant.
 *
 * <h2>Contract for implementations</h2>
 *
 * <ul>
 *   <li>The call runs inside the start transaction, so an implementation must be non-blocking.
 *   <li>The implementation must not retain or log the metadata.
 *   <li>The durable context captured at start includes values bound from inbound transport headers
 *       (for example message consumers) without sanitization, and a namespace that refuses explicit
 *       signal carriers is still reachable from such headers. Derive the tenant only from an
 *       integrity-protected value, such as a verified identity snapshot, never from a namespace
 *       whose decoder performs no integrity check.
 *   <li>The resolver runs synchronously in the context of the caller that starts the workflow, so
 *       the simplest safe source is that caller's authenticated identity, or a verified identity
 *       snapshot; do not parse the raw body of a namespace whose decoder checks nothing.
 *   <li>A non-null result must be nonblank, at most 255 Unicode code points, and free of NUL, other
 *       control characters, unpaired surrogates and the {@code ':'} character. Otherwise the start
 *       fails. Leading or trailing whitespace and invisible formatting characters are not rejected:
 *       normalize them in the resolver.
 * </ul>
 *
 * <p>A resolver that throws, including with an undeclared checked exception, also fails the start. In both failure cases the failure carries a fixed
 * message: it contains neither the captured context nor anything the resolver's exception said, and
 * the resolver's exception is not attached as a cause.
 *
 * <p>The tenant key is information and a filter, not an isolation boundary.
 */
@FunctionalInterface
public interface WorkflowTenantResolver {

    /**
     * Returns the tenant key for a new instance.
     *
     * @param captured the durable context captured at start; empty when nothing is bound
     * @return the tenant key, or {@code null} when the instance has no tenant
     */
    @Nullable
    String tenantOf(DurableMetadata captured);
}
