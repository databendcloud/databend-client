package com.databend.bendsql;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.databend.bendsql.jni_utils.NativeLibrary;


public class DatabendDriver implements Driver {

    static final String DRIVER_VERSION = "0.1.0";
    static final int DRIVER_VERSION_MAJOR = 0;
    static final int DRIVER_VERSION_MINOR = 1;

    private static final String JDBC_URL_START = "jdbc:databend://";

    static {
        NativeLibrary.loadLibrary();
        try {
            DriverManager.registerDriver(new DatabendDriver());
        } catch (SQLException e) {
            Logger.getLogger(DatabendDriver.class.getPackage().getName())
                    .log(Level.SEVERE, "Failed to register driver", e);
            throw new RuntimeException("Failed to register DatabendDriver", e);
        }
    }

    @Override
    public boolean acceptsURL(String url)
            throws SQLException {
        if (url == null) {
            throw new SQLException("URL is null");
        }
        return url.startsWith(JDBC_URL_START);
    }

    @Override
    public Connection connect(String url, Properties info)
            throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        return new DatabendConnection(url, info);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info)
            throws SQLException {
        throw new SQLFeatureNotSupportedException("setMaxRows is not supported");
    }

    @Override
    public int getMajorVersion() {
        return DRIVER_VERSION_MAJOR;
    }

    @Override
    public int getMinorVersion() {
        return DRIVER_VERSION_MINOR;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger()
            throws SQLFeatureNotSupportedException {
        // TODO: support java.util.Logging
        throw new SQLFeatureNotSupportedException();
    }
}
