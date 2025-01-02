package com.databend.bendsql;

import java.sql.*;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class DatabendConnection extends TrivialConnection {
    private final NativeConnection nativeConnection;

    private final AtomicBoolean isClosed;
    private final AtomicBoolean autoCommit;
    private final AtomicReference<String> schema = new AtomicReference<>();

        
    public DatabendConnection(String url, Properties info) throws SQLException {
        try {
            this.nativeConnection = NativeConnection.of(url);
            this.isClosed = new AtomicBoolean(false);
            this.autoCommit = new AtomicBoolean(true);
        } catch (Exception e) {
            throw new SQLException("Failed to create connection: " + e.getMessage(), e);
        }
    }

    @Override
    public DatabaseMetaData getMetaData()
            throws SQLException {
        return new DatabendDatabaseMetaData(this);
    }

    @Override
    public Statement createStatement() throws SQLException {
        checkClosed();
        return new DatabendStatement(this);
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        checkClosed();
        return new DatabendPreparedStatement(this, sql);
    }

    @Override
    public void close() throws SQLException {
        if (isClosed.compareAndSet(false, true)) {
            nativeConnection.close();
        }
    }

    @Override
    public boolean isClosed() throws SQLException {
        return isClosed.get();
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        this.autoCommit.set(autoCommit);
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        return autoCommit.get();
    }

    private void checkClosed() throws SQLException {
        if (isClosed.get()) {
            throw new SQLException("Connection is closed");
        }
    }

    @Override
    public void commit() throws SQLException {
        throw new SQLFeatureNotSupportedException("commit is not supported");
    }

    @Override
    public void rollback() throws SQLException {
        throw new SQLFeatureNotSupportedException("rollback is not supported");
    }

    public NativeConnection getNativeConnection() {
        return nativeConnection;
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

    @Override
    public String getSchema()
            throws SQLException {
        checkClosed();
        return schema.get();
    }

    @Override
    public void setSchema(String schema)
            throws SQLException {
        checkClosed();
        this.schema.set(schema);
        //TODO: this.startQuery("use " + schema);
    }

    public Object getURI() {
        // TODO
        throw new UnsupportedOperationException("Unimplemented method 'getURI'");
    }

} 