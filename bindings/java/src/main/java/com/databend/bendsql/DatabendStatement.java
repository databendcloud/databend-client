package com.databend.bendsql;

import java.sql.*;

public class DatabendStatement extends TrivialStatement {
    private final DatabendConnection connection;
    private ResultSet currentResultSet;
    private boolean isClosed;
    private int updateCount = 0;

    DatabendStatement(DatabendConnection connection) {
        this.connection = connection;
        this.isClosed = false;
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        checkClosed();
        try {
            NativeConnection nativeConnection = connection.getNativeConnection();
            NativeRowBatchIterator iterator = nativeConnection.execute(sql);
            if (iterator == null) {
                throw new SQLException("Query does not return result set: " + sql);
            }
            currentResultSet = new DatabendResultSet(this, iterator);
            return currentResultSet;
        } catch (Exception e) {
            throw new SQLException("Failed to execute query: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        checkClosed();
        try {
            NativeConnection nativeConnection = connection.getNativeConnection();
            NativeRowBatchIterator iterator = nativeConnection.execute(sql);
            if (iterator == null) {
                return false;
            } else {
                currentResultSet = new DatabendResultSet(this, iterator);
                return true;
            }
        } catch (Exception e) {
            throw new SQLException("Failed to execute: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() throws SQLException {
        if (!isClosed) {
            if (currentResultSet != null) {
                currentResultSet.close();
            }
            isClosed = true;
        }
    }

    protected void checkClosed() throws SQLException {
        if (isClosed) {
            throw new SQLException("Statement is closed");
        }
    }

    @Override
    public boolean isClosed() throws SQLException {
        return isClosed;
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (isWrapperFor(iface)) {
            return iface.cast(this);
        }
        throw new SQLException("Cannot unwrap to " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isAssignableFrom(getClass());
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
        checkClosed();
        return currentResultSet;
    }

    @Override
    public int getUpdateCount() throws SQLException {
        checkClosed();
        return updateCount;
    }

    protected final DatabendConnection connection()
        throws SQLException {
        if (connection == null) {
            throw new SQLException("Statement is closed");
        }
        if (connection.isClosed()) {
            throw new SQLException("Connection is closed");
        }
        return connection;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return connection();
    }
}