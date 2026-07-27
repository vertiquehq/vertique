package ${package}.repository;

import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.postgresql.PgSqlRepository;
import ${package}.model.Item;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.UUID;

/**
 * PostgreSQL repository for {@link Item} entities.
 *
 * <p>Every query runs through the base class's builder, so database failures are translated by
 * {@link PgDbExceptionMapper} into typed data-access exceptions before they propagate.
 */
@Singleton
public final class ItemRepository extends PgSqlRepository {

    /**
     * Creates a new {@code ItemRepository}.
     *
     * @param pool            the PostgreSQL connection pool
     * @param exceptionMapper the PostgreSQL exception mapper used for failure translation
     */
    @Inject
    public ItemRepository(Pool pool, PgDbExceptionMapper exceptionMapper) {
        super(pool, exceptionMapper);
    }

    /**
     * Inserts a new item.
     *
     * @param name        the item name
     * @param description optional description
     * @return a future completing with the created item, including its generated identifier
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
     * Finds an item by its identifier.
     *
     * @param id the item identifier
     * @return a future completing with the item, or with {@code null} when no such item exists
     */
    public Future<Item> findById(UUID id) {
        return this.<Item>query("SELECT id, name, description FROM items WHERE id = $1")
                .params(Tuple.of(id))
                .mapping(Item::fromRow)
                .one()
                .map(item -> item.orElse(null));
    }

    /**
     * Updates an existing item and returns the stored record.
     *
     * @param id          the item identifier
     * @param name        the new name
     * @param description the new description
     * @return a future completing with the updated item, or with {@code null} when no such item exists
     */
    public Future<Item> update(UUID id, String name, String description) {
        return this.<Item>query("UPDATE items SET name = $2, description = $3 WHERE id = $1"
                        + " RETURNING id, name, description")
                .params(Tuple.of(id, name, description))
                .mapping(Item::fromRow)
                .returningOptional()
                .map(item -> item.orElse(null));
    }

    /**
     * Deletes an item.
     *
     * @param id the item identifier
     * @return a future completing with {@code true} when a row was deleted, {@code false} otherwise
     */
    public Future<Boolean> delete(UUID id) {
        return this.<Void>query("DELETE FROM items WHERE id = $1")
                .params(Tuple.of(id))
                .execute()
                .map(rowCount -> rowCount > 0);
    }
}
