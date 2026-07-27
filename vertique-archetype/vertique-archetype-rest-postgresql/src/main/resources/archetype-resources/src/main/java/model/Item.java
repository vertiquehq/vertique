package ${package}.model;

import io.vertx.sqlclient.Row;
import java.util.UUID;

/**
 * Domain model for an item.
 *
 * @param id          the unique identifier
 * @param name        the item name
 * @param description optional description
 */
public record Item(UUID id, String name, String description) {

    /**
     * Maps a database row to an {@link Item}.
     *
     * @param row the database row
     * @return the mapped item
     */
    public static Item fromRow(Row row) {
        return new Item(row.getUUID("id"), row.getString("name"), row.getString("description"));
    }
}
