package com.example.iamdb;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Not actually pooled — every getConnection() opens a fresh TCP connection and
 * mints a new OAuth token via the socket factory. Kept next to ConnectionPool
 * so the two approaches can be compared directly.
 */
public final class ConnectionPool2 implements AutoCloseable {

    private final String jdbcUrl;
    private final Properties props;

    public ConnectionPool2(ConnectionPool.DbConfig cfg) {
        this.jdbcUrl = "jdbc:postgresql:///" + cfg.database();

        this.props = new Properties();
        props.setProperty("user", cfg.iamUser());
        props.setProperty("socketFactory",
                "com.google.cloud.sql.postgres.SocketFactory");
        props.setProperty("cloudSqlInstance", cfg.instanceConnectionName());
        props.setProperty("enableIamAuth", "true");
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, props);
    }

    @Override
    public void close() {
        // nothing to release — no pool, no long-lived state
    }
}
