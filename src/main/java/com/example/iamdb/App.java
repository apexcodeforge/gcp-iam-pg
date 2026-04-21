package com.example.iamdb;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;

public final class App {

    private static final Duration INTERVAL = Duration.ofMinutes(5);
    private static final Duration TOTAL    = Duration.ofHours(2);

    public static void main(String[] args) throws Exception {
        ConnectionPool.DbConfig cfg = ConnectionPool.DbConfig.fromEnv();

        try (ConnectionPool pool = new ConnectionPool(cfg)) {
            Instant start = Instant.now();
            Instant deadline = start.plus(TOTAL);
            int iteration = 0;

            while (true) {
                iteration++;
                try {
                    runSampleQuery(pool, iteration);
                } catch (Exception e) {
                    System.err.printf("[iter=%d] query failed: %s%n",
                            iteration, e.toString());
                }

                Instant next = start.plus(INTERVAL.multipliedBy(iteration));
                if (!next.isBefore(deadline)) {
                    break;
                }
                long sleepMs = Duration.between(Instant.now(), next).toMillis();
                if (sleepMs > 0) {
                    Thread.sleep(sleepMs);
                }
            }

            System.out.println("done, elapsed=" +
                    Duration.between(start, Instant.now()));
        }
    }

    private static void runSampleQuery(ConnectionPool pool, int iteration) throws Exception {
        long t0 = System.nanoTime();
        try (Connection c = pool.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT current_user, current_database(), now(), " +
                     "pg_backend_pid(), inet_server_addr()")) {
            if (rs.next()) {
                long acquireMs = (System.nanoTime() - t0) / 1_000_000;
                HikariPoolMXBean mx = ((HikariDataSource) pool.dataSource())
                        .getHikariPoolMXBean();
                System.out.printf(
                        "[iter=%2d] %s user=%s db=%s pid=%d server=%s " +
                        "acquire=%dms pool{active=%d,idle=%d,total=%d,waiting=%d}%n",
                        iteration,
                        Instant.now(),
                        rs.getString(1),
                        rs.getString(2),
                        rs.getInt(4),
                        rs.getString(5),
                        acquireMs,
                        mx.getActiveConnections(),
                        mx.getIdleConnections(),
                        mx.getTotalConnections(),
                        mx.getThreadsAwaitingConnection());
            }
        }
    }
}
