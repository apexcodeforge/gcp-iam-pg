package com.example.iamdb;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * AWS RDS IAM authentication via the AWS Advanced JDBC Wrapper, no HikariCP.
 * Counterpart to ConnectionPool2 — same "new connection per call" pattern,
 * different cloud. Every getConnection() opens a fresh TCP session; the
 * wrapper's IAM plugin mints a SigV4-signed auth token via the default AWS
 * credentials provider chain (EC2 InstanceProfile, env vars, ~/.aws/credentials).
 */
public final class ConnectionPool3 implements AutoCloseable {

    private final String jdbcUrl;
    private final Properties props;

    public ConnectionPool3(AwsDbConfig cfg) {
        // jdbc:aws-wrapper:postgresql:// tells the wrapper to delegate to the
        // Postgres driver while intercepting auth/failover/etc.
        this.jdbcUrl = "jdbc:aws-wrapper:postgresql://"
                + cfg.host() + ":" + cfg.port() + "/" + cfg.database();

        this.props = new Properties();
        props.setProperty("user", cfg.dbUser());
        // Activates the IAM plugin — generates a 15-min auth token on connect.
        props.setProperty("wrapperPlugins", "iam");
        // Region is usually auto-detected from the RDS endpoint hostname,
        // but set it explicitly when your credentials span multiple regions.
        props.setProperty("iamRegion", cfg.region());
        // RDS refuses IAM auth without TLS. verify-full is the stricter option
        // and needs sslrootcert pointing to the RDS global CA bundle.
        props.setProperty("sslmode", "require");
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, props);
    }

    @Override
    public void close() { }

    public record AwsDbConfig(
            String host,       // RDS endpoint: my-db.xxx.us-east-1.rds.amazonaws.com
            int port,
            String database,
            String dbUser,     // Postgres role with `GRANT rds_iam` applied
            String region      // e.g. "us-east-1"
    ) {
        public static AwsDbConfig fromEnv() {
            return new AwsDbConfig(
                    required("RDS_HOST"),
                    Integer.parseInt(System.getenv().getOrDefault("RDS_PORT", "5432")),
                    required("DB_NAME"),
                    required("DB_IAM_USER"),
                    required("AWS_REGION")
            );
        }

        private static String required(String k) {
            String v = System.getenv(k);
            if (v == null || v.isBlank()) {
                throw new IllegalStateException("Missing env var: " + k);
            }
            return v;
        }
    }
}
