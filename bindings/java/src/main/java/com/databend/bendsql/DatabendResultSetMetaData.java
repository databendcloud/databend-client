package com.databend.bendsql;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;


public class DatabendResultSetMetaData implements ResultSetMetaData {
    private final List<DatabendColumnInfo> databendColumnInfo;

    DatabendResultSetMetaData(List<DatabendColumnInfo> databendColumnInfo) {
        this.databendColumnInfo = databendColumnInfo;
    }

    static String getTypeClassName(int type) {
        // see javax.sql.rowset.RowSetMetaDataImpl
        switch (type) {
            case Types.NUMERIC:
            case Types.DECIMAL:
                return BigDecimal.class.getName();
            case Types.BOOLEAN:
            case Types.BIT:
                return Boolean.class.getName();
            case Types.TINYINT:
                return Byte.class.getName();
            case Types.SMALLINT:
                return Short.class.getName();
            case Types.INTEGER:
                return Integer.class.getName();
            case Types.BIGINT:
                return Long.class.getName();
            case Types.REAL:
                return Float.class.getName();
            case Types.FLOAT:
            case Types.DOUBLE:
                return Double.class.getName();
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
                return "byte[]";
            case Types.DATE:
                return Date.class.getName();
            case Types.TIME:
                return Time.class.getName();
            case Types.TIMESTAMP:
                return Timestamp.class.getName();
            case Types.BLOB:
                return Blob.class.getName();
            case Types.CLOB:
                return Clob.class.getName();
            case Types.ARRAY:
                return Array.class.getName();
            case Types.NULL:
                return "unknown";
        }
        return String.class.getName();
    }

    @Override
    public int getColumnCount()
            throws SQLException {
        if (this.databendColumnInfo == null) {
            return 0;
        }
        return this.databendColumnInfo.size();
    }

    @Override
    public boolean isAutoIncrement(int i)
            throws SQLException {
        return false;
    }

    @Override
    public boolean isCaseSensitive(int i)
            throws SQLException {
        return false;
    }

    @Override
    public boolean isSearchable(int i)
            throws SQLException {
        return true;
    }

    @Override
    public boolean isCurrency(int i)
            throws SQLException {
        return false;
    }

    @Override
    public int isNullable(int i)
            throws SQLException {
        DatabendColumnInfo.Nullable nullable = column(i).getNullable();
        switch (nullable) {
            case NO_NULLS:
                return columnNoNulls;
            case NULLABLE:
                return columnNullable;
            case UNKNOWN:
                return columnNullableUnknown;
        }
        throw new SQLException("Unhandled nullable type: " + nullable);
    }

    @Override
    public boolean isSigned(int i)
            throws SQLException {
        return column(i).isSigned();
    }

    @Override
    public int getColumnDisplaySize(int i)
            throws SQLException {
        return column(i).getColumnDisplaySize();
    }

    @Override
    public String getColumnLabel(int i)
            throws SQLException {
        return column(i).getColumnLabel();
    }

    @Override
    public String getColumnName(int i)
            throws SQLException {
        return column(i).getColumnName();
    }

    @Override
    public String getSchemaName(int i)
            throws SQLException {
        return column(i).getSchemaName();
    }

    @Override
    public int getPrecision(int i)
            throws SQLException {
        return column(i).getPrecision();
    }

    @Override
    public int getScale(int i)
            throws SQLException {
        return column(i).getScale();
    }

    @Override
    public String getTableName(int i)
            throws SQLException {
        return column(i).getTableName();
    }

    @Override
    public String getCatalogName(int i)
            throws SQLException {
        return column(i).getCatalogName();
    }

    @Override
    public int getColumnType(int i)
            throws SQLException {
        return column(i).getColumnType();
    }

    @Override
    public String getColumnTypeName(int i)
            throws SQLException {
        return column(i).getColumnTypeName();
    }

    @Override
    public boolean isReadOnly(int i)
            throws SQLException {
        return true;
    }

    @Override
    public boolean isWritable(int i)
            throws SQLException {
        return false;
    }

    @Override
    public boolean isDefinitelyWritable(int i)
            throws SQLException {
        return false;
    }

    @Override
    public String getColumnClassName(int i)
            throws SQLException {
        return getTypeClassName(column(i).getColumnType());
    }

    @Override
    public <T> T unwrap(Class<T> aClass)
            throws SQLException {
        return null;
    }

    @Override
    public boolean isWrapperFor(Class<?> aClass)
            throws SQLException {
        return false;
    }

    private DatabendColumnInfo column(int column)
            throws SQLException {
        if ((column <= 0) || (column > this.databendColumnInfo.size())) {
            throw new SQLException("Invalid column index: " + column);
        }
        return this.databendColumnInfo.get(column - 1);
    }
}
