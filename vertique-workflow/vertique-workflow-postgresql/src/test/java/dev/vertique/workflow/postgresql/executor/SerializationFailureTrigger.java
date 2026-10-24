// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;

import io.vertx.sqlclient.Pool;

/**
 * A trigger on {@code workflow_instances} that raises SQLSTATE 40001 on its first firings. A sequence counts the
 * firings, because a sequence value survives the rollback of the transaction that took it. The trigger and the
 * sequence are dropped on close, so a later test over the same database finds neither.
 */
final class SerializationFailureTrigger implements AutoCloseable {

    private static final String SEQUENCE = "executor_it_serialization_seq";
    private static final String FUNCTION = "executor_it_serialization_failure";

    private final int firings;
    private Pool pool;

    private SerializationFailureTrigger(int firings) {
        this.firings = firings;
    }

    /**
     * A trigger that fails the first {@code firings} writes of an instance row.
     *
     * @param firings how many firings fail
     * @return the trigger, not yet installed
     */
    static SerializationFailureTrigger onFirstWrites(int firings) {
        return new SerializationFailureTrigger(firings);
    }

    /**
     * Installs the trigger.
     *
     * @param pool the pool of the database under test
     * @return this trigger
     */
    SerializationFailureTrigger installOn(Pool pool) {
        this.pool = pool;
        drop();
        await(pool.query("CREATE SEQUENCE " + SEQUENCE).execute());
        await(pool.query("CREATE FUNCTION " + FUNCTION + "() RETURNS trigger AS $$ BEGIN IF nextval('" + SEQUENCE
                        + "') <= " + firings + " THEN RAISE EXCEPTION 'serialization failure injected by the test'"
                        + " USING ERRCODE = '40001'; END IF; RETURN NEW; END; $$ LANGUAGE plpgsql")
                .execute());
        await(pool.query("CREATE TRIGGER " + FUNCTION + " BEFORE UPDATE ON workflow_instances FOR EACH ROW"
                        + " EXECUTE FUNCTION " + FUNCTION + "()")
                .execute());
        return this;
    }

    /** How many times the trigger fired so far. */
    long timesFired() {
        var row = await(pool.query("SELECT last_value, is_called FROM " + SEQUENCE)
                        .execute())
                .iterator()
                .next();
        return row.getBoolean("is_called") ? row.getLong("last_value") : 0L;
    }

    @Override
    public void close() {
        if (pool != null) {
            drop();
        }
    }

    private void drop() {
        await(pool.query("DROP TRIGGER IF EXISTS " + FUNCTION + " ON workflow_instances")
                .execute());
        await(pool.query("DROP FUNCTION IF EXISTS " + FUNCTION + "()").execute());
        await(pool.query("DROP SEQUENCE IF EXISTS " + SEQUENCE).execute());
    }
}
