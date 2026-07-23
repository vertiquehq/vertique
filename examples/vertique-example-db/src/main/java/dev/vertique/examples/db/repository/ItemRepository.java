// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.db.repository;

import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.postgresql.PgSqlRepository;
import dev.vertique.db.query.PageCursor;
import dev.vertique.db.query.PagedResult;
import dev.vertique.examples.db.model.Item;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.UUID;

/**
 * PostgreSQL repository for {@link Item} entities.
 *
 * <p>Provides full CRUD operations against the {@code items} table. All database exceptions are
 * translated via {@link PgDbExceptionMapper} into typed {@link dev.vertique.db.exception.DataAccessException}
 * subclasses before being propagated.
 */
@Singleton
public class ItemRepository extends PgSqlRepository {

    /**
     * Creates a new {@code ItemRepository}.
     *
     * @param pool          the PostgreSQL connection pool
     * @param exceptionMapper the PostgreSQL failure mapper for exception translation
     */
    @Inject
    public ItemRepository(Pool pool, PgDbExceptionMapper exceptionMapper) {
        super(pool, exceptionMapper);
    }

    /**
     * Inserts a new item into the database.
     *
     * @param name        the item name
     * @param description optional description
     * @return a future containing the created {@link Item} with its generated ID
     */
    public Future<Item> create(String name, String description) {
        UUID id = UUID.randomUUID();
        return this.<Item>query("INSERT INTO items (id, name, description) VALUES ($1, $2, $3)"
                        + " RETURNING id, name, description")
                .params(Tuple.of(id, name, description))
                .mapping(Item::fromRow)
                .returning();
    }

    /**
     * Finds an item by its unique ID.
     *
     * @param id the item UUID
     * @return a future containing the found {@link Item}, or {@code null} if not found
     */
    public Future<Item> findById(UUID id) {
        return this.<Item>query("SELECT id, name, description FROM items WHERE id = $1")
                .params(Tuple.of(id))
                .mapping(Item::fromRow)
                .one()
                .map(opt -> opt.orElse(null));
    }

    /**
     * Returns all items ordered by name.
     *
     * @return a future containing the list of all {@link Item} instances
     */
    public Future<List<Item>> findAll() {
        return this.<Item>query("SELECT id, name, description FROM items ORDER BY name")
                .mapping(Item::fromRow)
                .list();
    }

    /**
     * Updates an existing item by ID and returns the updated record.
     *
     * @param id          the item UUID
     * @param name        the new name
     * @param description the new description
     * @return a future containing the updated {@link Item}, or {@code null} if not found
     */
    public Future<Item> update(UUID id, String name, String description) {
        return this.<Item>query("UPDATE items SET name = $2, description = $3 WHERE id = $1"
                        + " RETURNING id, name, description")
                .params(Tuple.of(id, name, description))
                .mapping(Item::fromRow)
                .returningOptional()
                .map(opt -> opt.orElse(null));
    }

    /**
     * Deletes an item by ID.
     *
     * @param id the item UUID
     * @return a future containing {@code true} if the item was deleted, {@code false} if not found
     */
    public Future<Boolean> delete(UUID id) {
        return this.<Void>query("DELETE FROM items WHERE id = $1")
                .params(Tuple.of(id))
                .execute()
                .map(rowCount -> rowCount > 0);
    }

    /**
     * Returns a paginated list of items using keyset pagination. Items are sorted by name (ascending)
     * with ID as a tiebreaker for deterministic ordering.
     *
     * @param cursor the page cursor (use {@link PageCursor#first(int)} for the first page)
     * @return a future containing the paged result with cursor tokens for navigation
     */
    public Future<PagedResult<Item>> findItems(PageCursor cursor) {
        return this.<Item>pagedQuery("SELECT id, name, description FROM items")
                .mapping(Item::fromRow)
                .orderBy("name", "id")
                .page(cursor);
    }
}
