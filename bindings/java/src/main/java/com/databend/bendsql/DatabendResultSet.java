package com.databend.bendsql;

import java.sql.*;
import java.util.Optional;


public class DatabendResultSet extends AbstractDatabendResultSet {
    private final Statement statement;
    private final NativeRowBatchIterator iterator;
    private boolean isClosed;

    public DatabendResultSet(Statement statement, NativeRowBatchIterator batchIterator) {
        super(Optional.of(statement), batchIterator.getSchema(), new BatchToRowIterator(batchIterator, batchIterator.getSchema()));
        this.statement = statement;
        this.isClosed = false;
        this.iterator = batchIterator;
    }

    @Override
    public void close() throws SQLException {
        synchronized (this) {
            if (isClosed) {
                return;
            }
            isClosed = true;
            iterator.close();
        }
    }

    @Override
    public boolean isClosed() throws SQLException {
        return isClosed;
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isAssignableFrom(getClass())) {
            return iface.cast(this);
        }
        throw new SQLException("Cannot unwrap to " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isAssignableFrom(getClass());
    }
}
