package com.example.iamdb;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

public final class ConnectionPool implements AutoCloseable {

    private final HikariDataSource ds;

    public ConnectionPool(DbConfig cfg) {
        HikariConfig hc = new HikariConfig();

        // jdbc:postgresql:///<db> is the form required by the Cloud SQL socket factory
        // (host/port are not used — the factory dials the instance directly).
        hc.setJdbcUrl("jdbc:postgresql:///" + cfg.database());

        // IAM-authenticated user. For a service account this is the email WITHOUT
        // the ".gserviceaccount.com" suffix, e.g. "app-sa@my-proj.iam".
        // For a human user it's the full email.
        hc.setUsername(cfg.iamUser());

        // No password: the socket factory mints an OAuth2 access token from ADC
        // and uses it as the Postgres password on each connect.
        hc.addDataSourceProperty("socketFactory",
                "com.google.cloud.sql.postgres.SocketFactory");
        hc.addDataSourceProperty("cloudSqlInstance", cfg.instanceConnectionName());
        hc.addDataSourceProperty("enableIamAuth", "true");

        // Pool sizing — a long-lived app server typically keeps a modest, steady pool.
        hc.setMaximumPoolSize(cfg.maxPoolSize());
        hc.setMinimumIdle(cfg.minIdle());

        // Recycle before GCP's 1h access-token lifetime so we never hand out a
        // connection whose underlying auth token is about to expire.
        hc.setMaxLifetime(30 * 60 * 1000L);      // 30 min
        hc.setIdleTimeout(10 * 60 * 1000L);      // 10 min
        hc.setConnectionTimeout(10_000L);
        hc.setKeepaliveTime(2 * 60 * 1000L);

        hc.setPoolName("gcp-iam-pg-pool");

        this.ds = new HikariDataSource(hc);
    }

    public Connection getConnection() throws SQLException {
        return ds.getConnection();
    }

    public DataSource dataSource() {
        return ds;
    }

    @Override
    public void close() {
        ds.close();
    }

    public record DbConfig(
            String instanceConnectionName,   // "project:region:instance"
            String database,
            String iamUser,
            int maxPoolSize,
            int minIdle
    ) {
        public static DbConfig fromEnv() {
            return new DbConfig(
                    required("CLOUD_SQL_INSTANCE"),
                    required("DB_NAME"),
                    required("DB_IAM_USER"),
                    intEnv("DB_POOL_MAX", 10),
                    intEnv("DB_POOL_MIN_IDLE", 2)
            );
        }

        private static String required(String k) {
            String v = System.getenv(k);
            if (v == null || v.isBlank()) {
                throw new IllegalStateException("Missing env var: " + k);
            }
            return v;
        }

        private static int intEnv(String k, int dflt) {
            String v = System.getenv(k);
            return (v == null || v.isBlank()) ? dflt : Integer.parseInt(v);
        }
    }
}
