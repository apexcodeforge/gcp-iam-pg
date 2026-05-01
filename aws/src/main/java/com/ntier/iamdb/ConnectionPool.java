package com.ntier.iamdb;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

public final class ConnectionPool implements AutoCloseable {

    private final HikariDataSource ds;

    public ConnectionPool(DbConfig cfg) {
        // The AWS SDK's DefaultCredentialsProvider resolves `aws.profile`
        // (system property) and AWS_PROFILE (env) when picking a profile.
        // Set the sysprop here so the wrapper sees it on the first connect,
        // regardless of how the JVM was launched.
        if (cfg.awsProfile() != null && !cfg.awsProfile().isBlank()) {
            System.setProperty("aws.profile", cfg.awsProfile());
        }

        HikariConfig hc = new HikariConfig();

        // The aws-wrapper:postgresql:// scheme tells the AWS Advanced JDBC
        // Wrapper to delegate to the Postgres driver while intercepting auth
        // (and optionally failover/host-list/etc).
        hc.setJdbcUrl("jdbc:aws-wrapper:postgresql://"
                + cfg.host() + ":" + cfg.port() + "/" + cfg.database());

        // Postgres role with `GRANT rds_iam` applied. Not the IAM principal —
        // the IAM principal is whoever owns the credentials in the default
        // AWS provider chain (instance profile, env, ~/.aws/credentials).
        hc.setUsername(cfg.dbUser());

        // No password: the IAM plugin signs a SigV4 auth token (~15 min TTL)
        // and feeds it to the driver as the password on each new connection.
        hc.addDataSourceProperty("wrapperPlugins", "iam");
        hc.addDataSourceProperty("iamRegion", cfg.region());

        // RDS rejects IAM auth without TLS. `require` is the minimum;
        // `verify-full` plus sslrootcert pointing at the RDS global CA bundle
        // is the production setting.
        hc.addDataSourceProperty("sslmode", "require");

        hc.setMaximumPoolSize(cfg.maxPoolSize());
        hc.setMinimumIdle(cfg.minIdle());

        // RDS IAM tokens live ~15 min — far shorter than GCP's 1h. Recycle
        // well before that so a connection's underlying token never ages out
        // mid-handout. keepalive stays comfortably under maxLifetime.
        hc.setMaxLifetime(12 * 60 * 1000L);      // 12 min
        hc.setIdleTimeout(5 * 60 * 1000L);       // 5 min
        hc.setConnectionTimeout(10_000L);
        hc.setKeepaliveTime(2 * 60 * 1000L);

        hc.setPoolName("aws-iam-pg-pool");

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
            String host,        // mydb.xxxxx.us-east-1.rds.amazonaws.com
            int port,
            String database,
            String dbUser,
            String region,
            String awsProfile,  // optional; null/blank = default chain
            int maxPoolSize,
            int minIdle
    ) {
        public static DbConfig fromEnv() {
            return new DbConfig(
                    required("RDS_HOST"),
                    intEnv("RDS_PORT", 5432),
                    required("DB_NAME"),
                    required("DB_IAM_USER"),
                    required("AWS_REGION"),
                    System.getenv("AWS_PROFILE"),
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
