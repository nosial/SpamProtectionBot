package net.nosial.spb.classes.interfaces;

import java.sql.ResultSet;
import java.sql.SQLException;

public interface RowMapper<T>
{
    /**
     * Maps the row the result set is positioned on. Implementations must not advance it.
     *
     * @param results the result set, positioned on the row to map
     * @return the mapped value
     * @throws SQLException If a column cannot be read
     */
    T map(ResultSet results) throws SQLException;
}
