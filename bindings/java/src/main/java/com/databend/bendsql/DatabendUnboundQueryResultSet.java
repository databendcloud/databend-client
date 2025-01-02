package com.databend.bendsql;

import java.sql.Statement;
import java.sql.SQLException;
import java.util.List;
import java.util.Iterator;
import java.util.Optional;

import com.databend.client.QueryRowField;

public class DatabendUnboundQueryResultSet  extends AbstractDatabendResultSet {
       private boolean closed = false;

    DatabendUnboundQueryResultSet(Optional<Statement> statement, List<QueryRowField> schema, Iterator<List<Object>> results) {
        super(statement, schema, results);
    }

    @Override
    public void close() throws SQLException {
        this.closed = true;
    }

    @Override
    public boolean isClosed() {
        return closed;
    } 
}
