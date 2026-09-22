package net.nosial.spb.classes.interfaces;

import java.sql.Connection;
import java.sql.SQLException;

public interface TransactionWork<T>
{
    /**
     * Runs the statements of the transaction. Must not commit, roll back, or close the
     * connection: the caller does that.
     *
     * @param connection the transactional connection
     * @return the result of the unit of work
     * @throws SQLException If a statement fails, which rolls the transaction back
     */
    T execute(Connection connection) throws SQLException;
}
