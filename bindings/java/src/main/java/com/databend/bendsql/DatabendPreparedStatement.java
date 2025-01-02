package com.databend.bendsql;

import com.databend.client.data.DatabendRawType;
import com.databend.jdbc.RawStatementWrapper;
import com.databend.jdbc.StatementInfoWrapper;
import com.databend.jdbc.StatementUtil;
import com.databend.jdbc.parser.BatchInsertUtils;
import com.solidfire.gson.Gson;
import lombok.NonNull;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static com.databend.bendsql.ObjectCasts.*;
import static com.databend.jdbc.StatementUtil.replaceParameterMarksWithValues;
import static java.lang.String.format;
import static java.time.format.DateTimeFormatter.ISO_LOCAL_DATE;
import static java.time.format.DateTimeFormatter.ISO_LOCAL_TIME;
import static java.util.Objects.requireNonNull;

public class DatabendPreparedStatement extends DatabendStatement implements PreparedStatement {
    private static final Logger logger = Logger.getLogger(DatabendPreparedStatement.class.getPackage().getName());
    static final DateTimeFormatter DATE_FORMATTER = ISODateTimeFormat.date();
    private final RawStatementWrapper rawStatement;
    static final DateTimeFormatter TIME_FORMATTER = DateTimeFormat.forPattern("HH:mm:ss.SSS");
    static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private final DatabendParameterMetaData paramMetaData;
    private static final java.time.format.DateTimeFormatter LOCAL_DATE_TIME_FORMATTER =
            new DateTimeFormatterBuilder()
                    .append(ISO_LOCAL_DATE)
                    .appendLiteral(' ')
                    .append(ISO_LOCAL_TIME)
                    .toFormatter();
    private static final java.time.format.DateTimeFormatter OFFSET_TIME_FORMATTER =
            new DateTimeFormatterBuilder()
                    .append(ISO_LOCAL_TIME)
                    .appendOffset("+HH:mm", "+00:00")
                    .toFormatter();
    private final String originalSql;
    private final List<String[]> batchValues;
    private final Optional<BatchInsertUtils> batchInsertUtils;

    DatabendPreparedStatement(DatabendConnection connection, String sql) {
        super(connection);
        this.originalSql = requireNonNull(sql, "sql is null");
        this.batchValues = new ArrayList<>();
        this.batchInsertUtils = BatchInsertUtils.tryParseInsertSql(sql);
        this.rawStatement = StatementUtil.parseToRawStatementWrapper(sql);
        Map<Integer, String> params = StatementUtil.extractColumnTypes(sql);
        List<DatabendColumnInfo> list = params.entrySet().stream().map(entry -> {
            String type = entry.getValue();
            DatabendRawType databendRawType = new DatabendRawType(type);
            return DatabendColumnInfo.of(entry.getKey().toString(), databendRawType);
        }).collect(Collectors.toList());
        this.paramMetaData = new DatabendParameterMetaData(Collections.unmodifiableList(list));
    }

    private static String formatBooleanLiteral(boolean x) {
        return Boolean.toString(x);
    }

    private static String formatByteLiteral(byte x) {
        return Byte.toString(x);
    }

    private static String formatShortLiteral(short x) {
        return Short.toString(x);
    }

    private static String formatIntLiteral(int x) {
        return Integer.toString(x);
    }

    private static String formatLongLiteral(long x) {
        return Long.toString(x);
    }

    private static String formatFloatLiteral(float x) {
        return Float.toString(x);
    }

    private static String formatDoubleLiteral(double x) {
        return Double.toString(x);
    }

    private static String formatBigDecimalLiteral(BigDecimal x) {
        if (x == null) {
            return "null";
        }

        return x.toString();
    }


    private static String formatBytesLiteral(byte[] x) {
        return new String(x, StandardCharsets.UTF_8);
    }

    static IllegalArgumentException invalidConversion(Object x, String toType) {
        return new IllegalArgumentException(format("Cannot convert instance of %s to %s", x.getClass().getName(), toType));
    }

    @Override
    public void close()
            throws SQLException {
        super.close();
    }

    public int[] executeBatchByAttachment() throws SQLException {
        int[] batchUpdateCounts = new int[batchValues.size()];
        if (!batchInsertUtils.isPresent() || batchValues == null || batchValues.isEmpty()) {
//            super.execute(this.originalSql);
            return batchUpdateCounts;
        }
        File saved = null;
        try {
            saved = batchInsertUtils.get().saveBatchToCSV(batchValues);
            DatabendConnection c = (DatabendConnection) getConnection();
            c.getNativeConnection().execInsertWithAttachment(batchInsertUtils.get().getSql(), saved.getAbsolutePath());
        } finally {
            if (saved != null) {
                saved.delete();
            }
            clearBatch();
        }
        return batchUpdateCounts;

   }

    public int[] executeBatchDelete() throws SQLException {
        if (!batchInsertUtils.isPresent() || batchValues == null || batchValues.isEmpty()) {
            return new int[]{};
        }
        int[] batchUpdateCounts = new int[batchValues.size()];
        try {
            String sql = convertSQLWithBatchValues(this.originalSql, this.batchValues);
            logger.fine(String.format("use copy into instead of normal insert, copy into SQL: %s", sql));
            super.execute(sql);
            ResultSet r = getResultSet();
            while (r.next()) {

            }
            return batchUpdateCounts;
        } catch (RuntimeException e) {
            throw new SQLException(e);
        }
    }

    public static String convertSQLWithBatchValues(String baseSql, List<String[]> batchValues) {
        StringBuilder convertedSqlBuilder = new StringBuilder();

        if (batchValues != null && !batchValues.isEmpty()) {
            for (String[] values : batchValues) {
                if (values != null && values.length > 0) {
                    String convertedSql = baseSql;
                    for (int i = 0; i < values.length; i++) {
                        convertedSql = convertedSql.replaceFirst("\\?", values[i]);
                    }
                    convertedSqlBuilder.append(convertedSql).append(";\n");
                }
            }
        }

        return convertedSqlBuilder.toString();
    }


    @Override
    public int[] executeBatch() throws SQLException {
        if (originalSql.toLowerCase().contains("delete from")) {
            return executeBatchDelete();
        }
        return executeBatchByAttachment();
    }

    @Override
    public ResultSet executeQuery()
            throws SQLException {
        String sql = replaceParameterMarksWithValues(batchInsertUtils.get().getProvideParams(), this.originalSql)
                .get(0)
                .getSql();
        executeQuery(sql);
        return getResultSet();
    }

    private List<StatementInfoWrapper> prepareSQL(@NonNull Map<Integer, String> params) {
        return replaceParameterMarksWithValues(params, this.rawStatement);
    }

    @Override
    public boolean execute()
            throws SQLException {
        boolean r;
        try {
            r = this.execute(prepareSQL(batchInsertUtils.get().getProvideParams()));
        } catch (Exception e) {
            throw new SQLException(e);
        } finally {
            clearBatch();
        }
        return r;
    }

    protected boolean execute(List<StatementInfoWrapper> statements) throws SQLException {
        try {
            for (int i = 0; i < statements.size(); i++) {
                String sql = statements.get(i).getSql();
                if (sql.toLowerCase().contains("insert into") && !sql.toLowerCase().contains("select")) {
                    handleBatchInsert();
                } else {
                    execute(sql);
                }
                return true;
            }
        } catch (Exception e) {
            throw new SQLException(e);
        } finally {
        }
        return true;
    }

    protected void handleBatchInsert() throws SQLException {
        try {
            addBatch();
            executeBatch();
        } catch (Exception e) {
            throw new SQLException(e);
        }
    }

    @Override
    public int executeUpdate() throws SQLException {
        this.execute(prepareSQL(batchInsertUtils.get().getProvideParams()));
        return batchInsertUtils.get().getProvideParams().size();
    }

    @Override
    public void setNull(int i, int i1)
            throws SQLException {
        checkClosed();
        if (this.originalSql.toLowerCase().contains("insert") ||
                this.originalSql.toLowerCase().contains("replace")) {
            // Databend uses \N as default null representation for csv and tsv format
            // https://github.com/datafuselabs/databend/pull/6453
            batchInsertUtils.ifPresent(insertUtils -> insertUtils.setPlaceHolderValue(i, "\\N"));
        } else {
            batchInsertUtils.ifPresent(insertUtils -> insertUtils.setPlaceHolderValue(i, "null"));
        }
    }

    @Override
    public void setBoolean(int i, boolean b)
            throws SQLException {
        checkClosed();
        batchInsertUtils.ifPresent(insertUtils -> insertUtils.setPlaceHolderValue(i, formatBooleanLiteral(b)));
    }

    @Override
    public void setByte(int i, byte b)
            throws SQLException {
        checkClosed();
        batchInsertUtils.ifPresent(insertUtils -> insertUtils.setPlaceHolderValue(i, formatByteLiteral(b)));
    }

    @Override
    public void setShort(int i, short i1)
            throws SQLException {
        checkClosed();
        batchInsertUtils.ifPresent(insertUtils -> insertUtils.setPlaceHolderValue(i, formatShortLiteral(i1)));
    }

    @Override
    public void setInt(int i, int i1)
            throws SQLException {
        checkClosed();
        batchInsertUtils.ifPresent(insertUtils -> insertUtils.setPlaceHolderValue(i, formatIntLiteral(i1)));
    }

    @Override
    public void setLong(int i, long l)
            throws SQLException {
        checkClosed();
        batchInsertUtils.ifPresent(insertUtils -> insertUtils.setPlaceHolderValue(i, formatLongLiteral(l)));
    }

    @Override
    public void setFloat(int i, float v)
            throws SQLException {
        checkClosed();
        batchInsertUtils.ifPresent(insertUtils -> insertUtils.setPlaceHolderValue(i, formatFloatLiteral(v)));
    }

    @Override
    public void setDouble(int i, double v)
            throws SQLException {
        checkClosed();
        batchInsertUtils.ifPresent(insertUtils -> insertUtils.setPlaceHolderValue(i, formatDoubleLiteral(v)));
    }

    @Override
    public void setBigDecimal(int i, BigDecimal bigDecimal)
            throws SQLException {
        checkClosed();
        batchInsertUtils.ifPresent(insertUtils -> insertUtils.setPlaceHolderValue(i, formatBigDecimalLiteral(bigDecimal)));
    }

    @Override
    public void setString(int i, String s)
            throws SQLException {
        checkClosed();
        if (originalSql.toLowerCase().startsWith("insert") ||
                originalSql.toLowerCase().startsWith("replace")) {
            String finalS1 = s;
            batchInsertUtils.ifPresent(insertUtils -> insertUtils.setPlaceHolderValue(i, finalS1));
        } else {
            if (s.contains("'")) {
                s = s.replace("'", "\\\'");
            }
            String finalS = s;
            batchInsertUtils.ifPresent(insertUtils -> insertUtils.setPlaceHolderValue(i, String.format("%s%s%s", "'", finalS, "'")));
        }
    }

    @Override
    public void setBytes(int i, byte[] bytes)
            throws SQLException {
        checkClosed();
        batchInsertUtils.ifPresent(insertUtils -> insertUtils.setPlaceHolderValue(i, formatBytesLiteral(bytes)));
    }

    @Override
    public void setDate(int i, Date date)
            throws SQLException {
        checkClosed();
        if (date == null) {
            batchInsertUtils.ifPresent(insertUtils -> insertUtils.setPlaceHolderValue(i, null));
        } else {
            if (originalSql.toLowerCase().startsWith("select")) {
                batchInsertUtils.ifPresent(insertUtils -> insertUtils.setPlaceHolderValue(i, String.format("%s%s%s", "'", date, "'")));
            } else {
                batchInsertUtils.ifPresent(insertUtils -> insertUtils.setPlaceHolderValue(i, toDateLiteral(date)));
            }
        }
    }

    @Override
    public void setTime(int i, Time time)
            throws SQLException {
        checkClosed();
        if (time == null) {
            batchInsertUtils.ifPresent(insertUtils -> insertUtils.setPlaceHolderValue(i, null));
        } else {
            if (originalSql.toLowerCase().startsWith("select")) {
                batchInsertUtils.ifPresent(insertUtils -> insertUtils.setPlaceHolderValue(i, String.format("%s%s%s", "'", time, "'")));
            } else {
                batchInsertUtils.ifPresent(insertUtils -> insertUtils.setPlaceHolderValue(i, toTimeLiteral(time)));
            }
        }
    }

    @Override
    public void setTimestamp(int i, Timestamp timestamp)
            throws SQLException {
        checkClosed();
        if (timestamp == null) {
            batchInsertUtils.ifPresent(insertUtils -> insertUtils.setPlaceHolderValue(i, null));
        } else {
            if (originalSql.toLowerCase().startsWith("select")) {
                batchInsertUtils.ifPresent(insertUtils -> insertUtils.setPlaceHolderValue(i, String.format("%s%s%s", "'", timestamp, "'")));
            } else {
                batchInsertUtils.ifPresent(insertUtils -> insertUtils.setPlaceHolderValue(i, toTimestampLiteral(timestamp)));
            }
        }
    }

    @Override
    public void setAsciiStream(int i, InputStream inputStream, int i1)
            throws SQLException {
        throw new SQLFeatureNotSupportedException("setAsciiStream not supported");
    }

    @Override
    public void setUnicodeStream(int i, InputStream inputStream, int i1)
            throws SQLException {
        throw new SQLFeatureNotSupportedException("setUnicodeStream not supported");
    }

    @Override
    public void setBinaryStream(int i, InputStream inputStream, int i1)
            throws SQLException {
        throw new SQLFeatureNotSupportedException("setBinaryStream not supported");
    }

    @Override
    public void clearParameters()
            throws SQLException {
        checkClosed();
        batchInsertUtils.ifPresent(BatchInsertUtils::clean);
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType)
            throws SQLException {
        checkClosed();
        if (x == null) {
            setNull(parameterIndex, Types.NULL);
            return;
        }
        switch (targetSqlType) {
            case Types.BOOLEAN:
            case Types.BIT:
                setBoolean(parameterIndex, castToBoolean(x, targetSqlType));
                return;
            case Types.TINYINT:
                setByte(parameterIndex, castToByte(x, targetSqlType));
                return;
            case Types.SMALLINT:
                setShort(parameterIndex, castToShort(x, targetSqlType));
                return;
            case Types.INTEGER:
                setInt(parameterIndex, castToInt(x, targetSqlType));
                return;
            case Types.BIGINT:
                setLong(parameterIndex, castToLong(x, targetSqlType));
                return;
            case Types.FLOAT:
            case Types.REAL:
                setFloat(parameterIndex, castToFloat(x, targetSqlType));
                return;
            case Types.DOUBLE:
                setDouble(parameterIndex, castToDouble(x, targetSqlType));
                return;
            case Types.DECIMAL:
            case Types.NUMERIC:
                setBigDecimal(parameterIndex, castToBigDecimal(x, targetSqlType));
                return;
            case Types.CHAR:
            case Types.NCHAR:
            case Types.VARCHAR:
            case Types.NVARCHAR:
            case Types.LONGVARCHAR:
            case Types.LONGNVARCHAR:
                setString(parameterIndex, x.toString());
                return;
            case Types.BINARY:
                InputStream blobInputStream = new ByteArrayInputStream(x.toString().getBytes());
                setBinaryStream(parameterIndex, blobInputStream);
                return;
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
                setBytes(parameterIndex, castToBinary(x, targetSqlType));
                return;
            case Types.DATE:
                setString(parameterIndex, toDateLiteral(x));
                return;
            case Types.TIME:
                setString(parameterIndex, toTimeLiteral(x));
                return;
            case Types.TIME_WITH_TIMEZONE:
                setString(parameterIndex, toTimeWithTimeZoneLiteral(x));
                return;
            case Types.TIMESTAMP:
                setString(parameterIndex, toTimestampLiteral(x));
                return;
            case Types.TIMESTAMP_WITH_TIMEZONE:
                setString(parameterIndex, toTimestampWithTimeZoneLiteral(x));
                return;
        }
        throw new SQLException("Unsupported target SQL type: " + targetSqlType);
    }

    @Override
    public void setObject(int parameterIndex, Object x)
            throws SQLException {
        checkClosed();
        if (x == null) {
            setNull(parameterIndex, Types.NULL);
        } else if (x instanceof Boolean) {
            setBoolean(parameterIndex, (Boolean) x);
        } else if (x instanceof Byte) {
            setByte(parameterIndex, (Byte) x);
        } else if (x instanceof Short) {
            setShort(parameterIndex, (Short) x);
        } else if (x instanceof Integer) {
            setInt(parameterIndex, (Integer) x);
        } else if (x instanceof Long) {
            setLong(parameterIndex, (Long) x);
        } else if (x instanceof Float) {
            setFloat(parameterIndex, (Float) x);
        } else if (x instanceof Double) {
            setDouble(parameterIndex, (Double) x);
        } else if (x instanceof BigDecimal) {
            setBigDecimal(parameterIndex, (BigDecimal) x);
        } else if (x instanceof String) {
            setString(parameterIndex, (String) x);
        } else if (x instanceof byte[]) {
            setBytes(parameterIndex, (byte[]) x);
        } else if (x instanceof Date) {
            setDate(parameterIndex, (Date) x);
        } else if (x instanceof LocalDate) {
            setString(parameterIndex, toDateLiteral(x));
        } else if (x instanceof Time) {
            setTime(parameterIndex, (Time) x);
        }
        // TODO (https://github.com/trinodb/trino/issues/6299) LocalTime -> setAsTime
        else if (x instanceof OffsetTime) {
            setString(parameterIndex, toTimeWithTimeZoneLiteral(x));
        } else if (x instanceof Timestamp) {
            setTimestamp(parameterIndex, (Timestamp) x);
        } else if (x instanceof Map) {
            setString(parameterIndex, convertToJsonString((Map<?, ?>) x));
        } else if (x instanceof Array) {
            setString(parameterIndex, convertArrayToString((Array) x));
        } else if (x instanceof ArrayList) {
            setString(parameterIndex, convertArrayListToString((ArrayList<?>) x));
        } else {
            throw new SQLException("Unsupported object type: " + x.getClass().getName());
        }
    }

    public static String convertToJsonString(Map<?, ?> map) {
        Gson gson = new Gson();
        return gson.toJson(map);
    }

    public static String convertArrayToString(Array array) {
        return array.toString();
    }

    public static String convertArrayListToString(ArrayList<?> arrayList) {
        StringBuilder builder = new StringBuilder();
        builder.append("[");
        for (int i = 0; i < arrayList.size(); i++) {
            builder.append(arrayList.get(i));
            if (i < arrayList.size() - 1) {
                builder.append(", ");
            }
        }
        builder.append("]");

        return builder.toString();
    }


    @Override
    public void addBatch()
            throws SQLException {
        checkClosed();
        if (batchInsertUtils.isPresent()) {
            String[] val = batchInsertUtils.get().getValues();
            batchValues.add(val);
            batchInsertUtils.get().clean();
            
        }
    }

    @Override
    public void clearBatch() throws SQLException {
        checkClosed();
        batchValues.clear();
        batchInsertUtils.ifPresent(BatchInsertUtils::clean);
    }

    @Override
    public void setCharacterStream(int i, Reader reader, int i1)
            throws SQLException {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setCharacterStream");
    }

    @Override
    public void setRef(int i, Ref ref)
            throws SQLException {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setRef");
    }

    @Override
    public void setBlob(int i, Blob x)
            throws SQLException {
        if (x != null) {
            setBinaryStream(i, x.getBinaryStream());
        } else {
            setNull(i, Types.BLOB);
        }
    }

    @Override
    public void setClob(int i, Clob x)
            throws SQLException {
        if (x != null) {
            setCharacterStream(i, x.getCharacterStream());
        } else {
            setNull(i, Types.CLOB);
        }
    }

    @Override
    public void setArray(int i, Array array)
            throws SQLException {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setArray");
    }

    @Override
    public ResultSetMetaData getMetaData()
            throws SQLException {
        return null;
    }

    @Override
    public void setDate(int i, Date date, Calendar calendar)
            throws SQLException {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setDate");
    }

    @Override
    public void setTime(int i, Time time, Calendar calendar)
            throws SQLException {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setTime");
    }

    @Override
    public void setTimestamp(int i, Timestamp timestamp, Calendar calendar)
            throws SQLException {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setTimestamp");
    }

    @Override
    public void setNull(int i, int i1, String s)
            throws SQLException {
        setNull(i, i1);
    }

    @Override
    public void setURL(int i, URL url)
            throws SQLException {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setURL");
    }

    // If you want to use ps.getParameterMetaData().* methods, you need to use a valid sql such as
    // insert into table_name (col1 type1, col2 typ2, col3 type3) values (?, ?, ?)
    @Override
    public ParameterMetaData getParameterMetaData() throws SQLException {
        return paramMetaData;
    }

    @Override
    public void setRowId(int i, RowId rowId)
            throws SQLException {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setRowId");
    }

    @Override
    public void setNString(int i, String s)
            throws SQLException {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setNString");
    }

    @Override
    public void setNCharacterStream(int i, Reader reader, long l)
            throws SQLException {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setNCharacterStream");
    }

    @Override
    public void setNClob(int i, NClob nClob)
            throws SQLException {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setNClob");
    }

    @Override
    public void setClob(int i, Reader reader, long l)
            throws SQLException {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setClob");
    }

    @Override
    public void setBlob(int i, InputStream inputStream, long l)
            throws SQLException {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setBlob");
    }

    @Override
    public void setNClob(int i, Reader reader, long l)
            throws SQLException {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setNClob");
    }

    @Override
    public void setSQLXML(int i, SQLXML sqlxml)
            throws SQLException {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setSQLXML");
    }

    @Override
    public void setObject(int i, Object o, int i1, int i2)
            throws SQLException {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setObject");
    }

    @Override
    public void setAsciiStream(int i, InputStream inputStream, long l)
            throws SQLException {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setAsciiStream");
    }

    @Override
    public void setBinaryStream(int i, InputStream inputStream, long l)
            throws SQLException {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setBinaryStream");
    }

    @Override
    public void setCharacterStream(int i, Reader reader, long l)
            throws SQLException {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setCharacterStream");
    }

    @Override
    public void setAsciiStream(int i, InputStream inputStream)
            throws SQLException {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setAsciiStream");
    }

    @Override
    public void setBinaryStream(int i, InputStream inputStream)
            throws SQLException {
        checkClosed();
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int nRead;
            byte[] data = new byte[1024];
            while ((nRead = inputStream.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
            buffer.flush();
            byte[] bytes = buffer.toByteArray();
            // TODO: use base64 which is more efficent if server is new enough to support option
            // before that, use the default hex     
            /// String textString = bytesToBase64(bytes);
            String textString = bytesToHex(bytes);
            batchInsertUtils.ifPresent(insertUtils -> insertUtils.setPlaceHolderValue(i, textString));
        } catch (IOException e) {
            throw new SQLException("Error reading InputStream", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String bytesToBase64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Override
    public void setCharacterStream(int i, Reader reader)
            throws SQLException {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setCharacterStream");
    }

    @Override
    public void setNCharacterStream(int i, Reader reader)
            throws SQLException {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setNCharacterStream");
    }

    @Override
    public void setClob(int i, Reader reader)
            throws SQLException {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setClob");
    }

    @Override
    public void setBlob(int i, InputStream inputStream)
            throws SQLException {
        setBinaryStream(i, inputStream);
    }

    @Override
    public void setNClob(int i, Reader reader)
            throws SQLException {
        throw new SQLFeatureNotSupportedException("PreparedStatement", "setNClob");
    }


    private String toDateLiteral(Object value) throws IllegalArgumentException {
        requireNonNull(value, "value is null");
        if (value instanceof java.util.Date) {
            return DATE_FORMATTER.print(((java.util.Date) value).getTime());
        }
        if (value instanceof LocalDate) {
            return ISO_LOCAL_DATE.format(((LocalDate) value));
        }
        if (value instanceof LocalDateTime) {
            return ISO_LOCAL_DATE.format(((LocalDateTime) value));
        }
        if (value instanceof String) {
            // TODO validate proper format
            return (String) value;
        }
        throw invalidConversion(value, "date");
    }

    private String toTimeLiteral(Object value)
            throws IllegalArgumentException {
        if (value instanceof java.util.Date) {
            return TIME_FORMATTER.print(((java.util.Date) value).getTime());
        }
        if (value instanceof LocalTime) {
            return ISO_LOCAL_TIME.format((LocalTime) value);
        }
        if (value instanceof LocalDateTime) {
            return ISO_LOCAL_TIME.format((LocalDateTime) value);
        }
        if (value instanceof String) {
            // TODO validate proper format
            return (String) value;
        }
        throw invalidConversion(value, "time");
    }

    private String toTimestampLiteral(Object value)
            throws IllegalArgumentException {
        if (value instanceof java.util.Date) {
            return TIMESTAMP_FORMATTER.print(((java.util.Date) value).getTime());
        }
        if (value instanceof LocalDateTime) {
            return LOCAL_DATE_TIME_FORMATTER.format(((LocalDateTime) value));
        }
        if (value instanceof String) {
            // TODO validate proper format
            return (String) value;
        }
        throw invalidConversion(value, "timestamp");
    }

    private String toTimestampWithTimeZoneLiteral(Object value)
            throws SQLException {
        if (value instanceof String) {
            return (String) value;
        } else if (value instanceof OffsetDateTime) {
            return OFFSET_TIME_FORMATTER.format((OffsetDateTime) value);
        }
        throw invalidConversion(value, "timestamp with time zone");
    }

    private String toTimeWithTimeZoneLiteral(Object value)
            throws SQLException {
        if (value instanceof OffsetTime) {
            return OFFSET_TIME_FORMATTER.format((OffsetTime) value);
        }
        if (value instanceof String) {
            // TODO validate proper format
            return (String) value;
        }
        throw invalidConversion(value, "time with time zone");
    }

}
