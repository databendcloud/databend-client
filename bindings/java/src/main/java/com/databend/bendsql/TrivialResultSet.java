package com.databend.bendsql;

import java.sql.*;

public abstract class TrivialResultSet extends NoUpdateDataSet {

    @Override
    public boolean rowDeleted() throws SQLException {
        throw new UnsupportedOperationException("Unimplemented method 'rowDeleted'");
    }

    @Override
    public RowId getRowId(int columnIndex) throws SQLException {
        throw new UnsupportedOperationException("Unimplemented method 'getRowId'");
    }

    @Override
    public RowId getRowId(String columnLabel) throws SQLException {
        throw new UnsupportedOperationException("Unimplemented method 'getRowId'");
    }

    @Override
    public boolean wasNull() throws SQLException {
        throw new SQLFeatureNotSupportedException("Method wasNull() not implemented");
    }

    @Override
    public int findColumn(String columnLabel) throws SQLException {
        throw new SQLFeatureNotSupportedException("Method findColumn(String) not implemented");
    }

    @Override
    public boolean isBeforeFirst() throws SQLException {
        throw new SQLFeatureNotSupportedException("Method isBeforeFirst() not implemented");
    }

    @Override
    public boolean isAfterLast() throws SQLException {
        throw new SQLFeatureNotSupportedException("Method isAfterLast() not implemented");
    }

    @Override
    public boolean isFirst() throws SQLException {
        throw new SQLFeatureNotSupportedException("Method isFirst() not implemented");
    }

    @Override
    public boolean isLast() throws SQLException {
        throw new SQLFeatureNotSupportedException("Method isLast() not implemented");
    }

    @Override
    public void beforeFirst() throws SQLException {
        throw new SQLFeatureNotSupportedException("Method beforeFirst() not implemented");
    }

    @Override
    public void afterLast() throws SQLException {
        throw new SQLFeatureNotSupportedException("Method afterLast() not implemented");
    }

    @Override
    public boolean first() throws SQLException {
        throw new SQLFeatureNotSupportedException("Method first() not implemented");
    }

    @Override
    public boolean last() throws SQLException {
        throw new SQLFeatureNotSupportedException("Method last() not implemented");
    }

    @Override
    public boolean absolute(int row) throws SQLException {
        throw new SQLFeatureNotSupportedException("Method absolute(int) not implemented");
    }

    @Override
    public boolean relative(int rows) throws SQLException {
        throw new SQLFeatureNotSupportedException("Method relative(int) not implemented");
    }

    @Override
    public boolean previous() throws SQLException {
        throw new SQLFeatureNotSupportedException("Method previous() not implemented");
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        if (direction != ResultSet.FETCH_FORWARD) {
            throw new SQLFeatureNotSupportedException("Only FETCH_FORWARD is supported");
        }
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {
    }

    @Override
    public void insertRow() throws SQLException {
        throw new SQLFeatureNotSupportedException("Method insertRow() not implemented");
    }

    @Override
    public void updateRow() throws SQLException {
        throw new SQLFeatureNotSupportedException("Method updateRow() not implemented");
    }

    @Override
    public void deleteRow() throws SQLException {
        throw new SQLFeatureNotSupportedException("Method deleteRow() not implemented");
    }

    @Override
    public void refreshRow() throws SQLException {
        throw new SQLFeatureNotSupportedException("Method refreshRow() not implemented");
    }

    @Override
    public void cancelRowUpdates() throws SQLException {
        throw new SQLFeatureNotSupportedException("Method cancelRowUpdates() not implemented");
    }

    @Override
    public void moveToInsertRow() throws SQLException {
        throw new SQLFeatureNotSupportedException("Method moveToInsertRow() not implemented");
    }

    @Override
    public void moveToCurrentRow() throws SQLException {
        throw new SQLFeatureNotSupportedException("Method moveToCurrentRow() not implemented");
    }
} 