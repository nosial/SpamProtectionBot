package net.nosial.spb.classes.interfaces;

import java.sql.PreparedStatement;
import java.sql.SQLException;

public interface StatementBinder
{
    /**
     * Sets every placeholder of the statement.
     *
     * @param statement the statement to bind
     * @throws SQLException If a parameter cannot be bound
     */
    void bind(PreparedStatement statement) throws SQLException;
}
