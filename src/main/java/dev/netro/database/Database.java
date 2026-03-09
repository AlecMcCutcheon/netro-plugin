package dev.netro.database;

import dev.netro.NetroPlugin;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * SQLite database for Netro. Runs schema from bundled schema.sql on initialize.
 */
public class Database {

    private final NetroPlugin plugin;
    private final ReentrantLock lock = new ReentrantLock();
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Netro-db-async");
        t.setDaemon(true);
        return t;
    });
    private Connection connection;

    public Database(NetroPlugin plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        lock.lock();
        try {
            plugin.getDataFolder().mkdirs();
            String path = plugin.getDataFolder().getAbsolutePath() + "/netro.db";
            connection = DriverManager.getConnection("jdbc:sqlite:" + path);
            connection.setAutoCommit(true);

            String schema;
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                    Objects.requireNonNull(plugin.getResource("schema.sql"), "schema.sql"),
                    StandardCharsets.UTF_8))) {
                schema = reader.lines()
                    .filter(line -> !line.trim().startsWith("--"))
                    .collect(Collectors.joining("\n"));
            }

            for (String stmt : schema.split(";")) {
                String s = stmt.trim();
                if (s.isEmpty()) continue;
                try (Statement st = connection.createStatement()) {
                    st.execute(s);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Database init failed: " + e.getMessage());
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }

    public void close() {
        dbExecutor.shutdown();
        lock.lock();
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                connection = null;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Database close: " + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Run a read-only (or mixed) DB job on a background thread, then run the result consumer on the main thread.
     * Use this to avoid blocking the main thread on DB I/O. The callback runs with the same connection/lock as withConnection.
     */
    public <T> void runAsyncRead(ConnectionCallback<T> callback, java.util.function.Consumer<T> onResultOnMainThread) {
        dbExecutor.execute(() -> {
            T result;
            try {
                result = withConnection(callback);
            } catch (Throwable t) {
                plugin.getLogger().warning("Async DB read failed: " + t.getMessage());
                result = null;
            }
            T r = result;
            plugin.getServer().getScheduler().runTask(plugin, () -> onResultOnMainThread.accept(r));
        });
    }

    /**
     * Execute a block with the connection. Caller must not close the connection.
     * Connection access is single-threaded per lock.
     */
    public <T> T withConnection(ConnectionCallback<T> callback) {
        lock.lock();
        try {
            if (connection == null || connection.isClosed())
                throw new IllegalStateException("Database not initialized or closed");
            return callback.run(connection);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }

    @FunctionalInterface
    public interface ConnectionCallback<T> {
        T run(Connection conn) throws SQLException;
    }
}
