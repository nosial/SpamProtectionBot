package net.nosial.spb.classes;

import net.nosial.spb.classes.interfaces.TransactionWork;
import net.nosial.spb.classes.interfaces.RowMapper;
import net.nosial.spb.classes.interfaces.StatementBinder;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.nosial.spb.exceptions.DatabaseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * The SQLite access layer shared by every worker thread.
 *
 * <p>This class owns access to the database and nothing else: opening the file, applying the
 * schemas, handing out connections, and running statements safely. It deliberately knows nothing
 * about users, chats, or configurations — those live in the manager layer built on top of it, so
 * the concerns of "how do we talk to SQLite" and "what do we store in it" never mix.
 *
 * <p><strong>Concurrency.</strong> Each caller borrows its own {@link Connection} from a bounded
 * HikariCP pool rather than contending for one shared connection, so reads and writes stay
 * parallel. SQLite allows a single writer at a time; WAL journal mode lets readers run beside that
 * writer, and a busy timeout turns the rare writer collision into a short wait instead of a
 * {@code SQLITE_BUSY} failure. The helper methods below borrow and return a connection per call,
 * which is what makes them safe to call from any number of worker threads at once.
 *
 * <p><strong>Lifecycle.</strong> The constructor creates the database file and any missing parent
 * directories when they do not exist, then applies every {@code sql/*.sql} schema on the
 * classpath. {@link #close()} drains and closes the pool; it must be the last thing shut down so
 * in-flight updates can still reach the database while they finish.
 */
public final class Database implements AutoCloseable
{
    private static final Logger LOGGER = LoggerFactory.getLogger(Database.class);

    /**
     * Maximum number of simultaneous connections.
     *
     * <p>SQLite permits exactly one writer, so a larger pool adds little throughput while costing
     * file handles and WAL churn; this is large enough for every reader to proceed in parallel.
     */
    private static final int MAX_POOL_SIZE = 8;

    /** How long {@link #getConnection()} waits for a free connection before failing. */
    private static final long CONNECTION_TIMEOUT_MILLIS = 30_000;

    /** How long SQLite waits for a busy writer before failing a statement. */
    private static final long BUSY_TIMEOUT_MILLIS = 5_000;

    /** The classpath directory schemas are loaded from. */
    private static final String SCHEMA_DIRECTORY = "sql";

    private final Path path;
    private final boolean created;
    private final HikariDataSource dataSource;

    /**
     * Opens the SQLite database at the given location, creating it when absent, and applies every
     * schema found on the classpath.
     *
     * @param path the database file location
     * @throws DatabaseException If the file cannot be created, the pool cannot be opened, or a
     *                           schema cannot be applied
     */
    public Database(Path path) throws DatabaseException
    {
        Objects.requireNonNull(path, "path must not be null");
        this.path = path.toAbsolutePath().normalize();
        this.created = !Files.exists(this.path);

        Path parent = this.path.getParent();
        if (parent != null && !Files.isDirectory(parent))
        {
            try
            {
                Files.createDirectories(parent);
            }
            catch (IOException e)
            {
                throw new DatabaseException("Failed to create database directory " + parent + ": " + e.getMessage(), e);
            }
        }

        HikariConfig config = new HikariConfig();
        config.setPoolName("spb-sqlite");
        config.setJdbcUrl("jdbc:sqlite:" + this.path);
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(MAX_POOL_SIZE);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(CONNECTION_TIMEOUT_MILLIS);
        config.setAutoCommit(true);

        // Applied to every connection as it is created: WAL lets readers run beside the single
        // writer, NORMAL is the recommended durability level under WAL, the busy timeout turns
        // writer collisions into short waits instead of errors, and the temp store keeps transient
        // sorting in memory. Foreign keys are off by default in SQLite and must be asked for.
        //
        // These go in as connection properties, each executed as its own pragma when the
        // connection opens. A single multi-statement connectionInitSql must NOT be used: the
        // driver executes only its first statement and silently drops the rest.
        Properties sqliteProperties = new Properties();
        sqliteProperties.setProperty("journal_mode", "WAL");
        sqliteProperties.setProperty("synchronous", "NORMAL");
        sqliteProperties.setProperty("busy_timeout", String.valueOf(BUSY_TIMEOUT_MILLIS));
        sqliteProperties.setProperty("temp_store", "MEMORY");
        sqliteProperties.setProperty("foreign_keys", "true");
        // Transactions take the write lock at BEGIN. A deferred transaction that reads and then
        // writes must upgrade its lock mid-flight, and under WAL that upgrade fails with
        // SQLITE_BUSY at once, ignoring the busy timeout, if another writer committed meanwhile.
        sqliteProperties.setProperty("transaction_mode", "IMMEDIATE");
        config.setDataSourceProperties(sqliteProperties);

        try
        {
            // Pool construction fails fast by acquiring a connection, which also creates the
            // database file when it does not exist yet.
            this.dataSource = new HikariDataSource(config);
        }
        catch (RuntimeException e)
        {
            throw new DatabaseException("Failed to open database " + this.path + ": " + e.getMessage(), e);
        }

        try
        {
            applySchemas();
        }
        catch (DatabaseException e)
        {
            this.dataSource.close();
            throw e;
        }

        LOGGER.info("{} SQLite database '{}' with a pool of up to {} connections",
                this.created ? "Created" : "Opened", this.path, MAX_POOL_SIZE);
    }

    /**
     * Returns the absolute location of the database file.
     *
     * @return the database file path
     */
    public Path path()
    {
        return this.path;
    }

    /**
     * Returns whether the database file did not exist and was created by this instance.
     *
     * @return {@code true} when the database was created rather than opened
     */
    public boolean created()
    {
        return this.created;
    }

    /**
     * Returns whether the pool is still open and able to hand out connections.
     *
     * @return {@code true} while the database is usable
     */
    public boolean isOpen()
    {
        return this.dataSource.isRunning();
    }

    /**
     * Borrows a connection from the pool.
     *
     * <p>The caller owns the connection and must close it — use try-with-resources — so it returns
     * to the pool. A single connection must never be shared between threads. Prefer the
     * {@link #query}, {@link #queryOne}, {@link #execute} and {@link #transaction} helpers, which
     * handle this for you.
     *
     * @return a pooled connection the caller must close
     * @throws SQLException If no connection becomes available within the timeout
     */
    public Connection getConnection() throws SQLException
    {
        return this.dataSource.getConnection();
    }

    /**
     * Runs a query and maps every returned row.
     *
     * @param sql the SQL query, with {@code ?} placeholders
     * @param binder binds the placeholders, or {@code null} when there are none
     * @param mapper maps one result row to a value
     * @param <T> the mapped row type
     * @return the mapped rows, in result order, never {@code null}
     * @throws DatabaseException If the query fails
     */
    public <T> List<T> query(String sql, StatementBinder binder, RowMapper<T> mapper) throws DatabaseException
    {
        Objects.requireNonNull(sql, "sql must not be null");
        Objects.requireNonNull(mapper, "mapper must not be null");

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql))
        {
            bind(statement, binder);

            try (ResultSet results = statement.executeQuery())
            {
                List<T> rows = new ArrayList<>();
                while (results.next())
                {
                    rows.add(mapper.map(results));
                }
                return rows;
            }
        }
        catch (SQLException e)
        {
            throw new DatabaseException("Query failed (" + sql + "): " + e.getMessage(), e);
        }
    }

    /**
     * Runs a query expected to match at most one row.
     *
     * <p>Rows beyond the first are ignored, so callers do not have to add {@code LIMIT 1} to
     * lookups by primary key.
     *
     * @param sql the SQL query, with {@code ?} placeholders
     * @param binder binds the placeholders, or {@code null} when there are none
     * @param mapper maps the result row to a value
     * @param <T> the mapped row type
     * @return the mapped row, or {@link Optional#empty()} when nothing matched
     * @throws DatabaseException If the query fails
     */
    public <T> Optional<T> queryOne(String sql, StatementBinder binder, RowMapper<T> mapper) throws DatabaseException
    {
        Objects.requireNonNull(sql, "sql must not be null");
        Objects.requireNonNull(mapper, "mapper must not be null");

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql))
        {
            bind(statement, binder);

            try (ResultSet results = statement.executeQuery())
            {
                return results.next() ? Optional.ofNullable(mapper.map(results)) : Optional.empty();
            }
        }
        catch (SQLException e)
        {
            throw new DatabaseException("Query failed (" + sql + "): " + e.getMessage(), e);
        }
    }

    /**
     * Returns whether the given query matches at least one row.
     *
     * @param sql the SQL query, with {@code ?} placeholders
     * @param binder binds the placeholders, or {@code null} when there are none
     * @return {@code true} when a row matched
     * @throws DatabaseException If the query fails
     */
    public boolean exists(String sql, StatementBinder binder) throws DatabaseException
    {
        return queryOne(sql, binder, results -> Boolean.TRUE).orElse(Boolean.FALSE);
    }

    /**
     * Runs an insert, update, or delete.
     *
     * @param sql the SQL statement, with {@code ?} placeholders
     * @param binder binds the placeholders, or {@code null} when there are none
     * @return the number of affected rows
     * @throws DatabaseException If the statement fails
     */
    public int execute(String sql, StatementBinder binder) throws DatabaseException
    {
        Objects.requireNonNull(sql, "sql must not be null");

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql))
        {
            bind(statement, binder);
            return statement.executeUpdate();
        }
        catch (SQLException e)
        {
            throw new DatabaseException("Statement failed (" + sql + "): " + e.getMessage(), e);
        }
    }

    /**
     * Runs several statements on one connection inside a single transaction.
     *
     * <p>The transaction commits when the unit of work returns and rolls back when it throws, so a
     * multi-table write either lands completely or not at all. The connection handed to the work
     * must not escape it or be used from another thread.
     *
     * @param work the unit of work
     * @param <T> the result type
     * @return whatever the unit of work returned
     * @throws DatabaseException If a statement or the commit failed, in which case the
     *                           transaction was rolled back. An unchecked exception thrown by the
     *                           unit of work also rolls the transaction back, but propagates as
     *                           itself rather than being wrapped.
     */
    public <T> T transaction(TransactionWork<T> work) throws DatabaseException
    {
        Objects.requireNonNull(work, "work must not be null");

        try (Connection connection = getConnection())
        {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);

            try
            {
                T result = work.execute(connection);
                connection.commit();
                return result;
            }
            catch (SQLException | RuntimeException e)
            {
                try
                {
                    connection.rollback();
                }
                catch (SQLException rollbackFailure)
                {
                    e.addSuppressed(rollbackFailure);
                }
                throw e;
            }
            finally
            {
                connection.setAutoCommit(autoCommit);
            }
        }
        catch (SQLException e)
        {
            throw new DatabaseException("Transaction failed: " + e.getMessage(), e);
        }
    }

    /**
     * Returns the names of the tables that exist in the database.
     *
     * <p>Used to verify the database state at start-up and in tests; it is not meant for routine
     * lookups.
     *
     * @return the table names, in alphabetical order
     * @throws DatabaseException If the catalogue cannot be read
     */
    public Set<String> tables() throws DatabaseException
    {
        List<String> names = query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
                null, results -> results.getString(1));
        return new LinkedHashSet<>(names);
    }

    /**
     * Closes the pool and releases every connection.
     *
     * <p>Safe to call more than once. Callers must have stopped using the database first: any
     * connection borrowed after this point fails.
     */
    @Override
    public void close()
    {
        if (this.dataSource.isClosed())
        {
            return;
        }

        this.dataSource.close();
        LOGGER.info("SQLite database '{}' closed", this.path);
    }

    /**
     * Applies the parameters of a statement, tolerating a missing binder.
     *
     * @param statement the statement to bind
     * @param binder the binder, or {@code null}
     * @throws SQLException If a parameter cannot be bound
     */
    private static void bind(PreparedStatement statement, StatementBinder binder) throws SQLException
    {
        if (binder != null)
        {
            binder.bind(statement);
        }
    }

    /**
     * Discovers every {@code sql/*.sql} resource on the classpath and executes it on a single
     * connection, in file name order, before any caller can reach the pool.
     *
     * <p>Schemas must be idempotent ({@code CREATE TABLE IF NOT EXISTS}) so applying them to an
     * existing database is a no-op, and independent of each other so file name order is enough;
     * a schema that must run after another is given a numeric prefix.
     *
     * @throws DatabaseException If a schema cannot be located, read, or executed
     */
    private void applySchemas() throws DatabaseException
    {
        List<URL> schemas = new ArrayList<>();

        try
        {
            Enumeration<URL> directories = classLoader().getResources(SCHEMA_DIRECTORY);
            while (directories.hasMoreElements())
            {
                schemas.addAll(listSchemaFiles(directories.nextElement()));
            }
        }
        catch (IOException e)
        {
            throw new DatabaseException("Failed to locate SQL schemas on the classpath: " + e.getMessage(), e);
        }

        if (schemas.isEmpty())
        {
            throw new DatabaseException("No SQL schemas found on the classpath under '" + SCHEMA_DIRECTORY + "/'");
        }

        schemas.sort(Comparator.comparing(Database::fileName));

        try (Connection connection = getConnection())
        {
            for (URL schema : schemas)
            {
                executeSchema(connection, schema);
            }
        }
        catch (IOException | SQLException e)
        {
            throw new DatabaseException("Failed to apply database schemas: " + e.getMessage(), e);
        }

        LOGGER.debug("Applied {} database schema(s)", schemas.size());
    }

    /**
     * Returns the class loader the schemas are resolved against.
     *
     * @return the context class loader when set, otherwise this class's loader
     */
    private static ClassLoader classLoader()
    {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader != null ? loader : Database.class.getClassLoader();
    }

    /**
     * Returns the file name portion of a URL.
     *
     * @param url the resource URL
     * @return the file name
     */
    private static String fileName(URL url)
    {
        String path = url.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * Lists the {@code .sql} files inside a classpath directory, supporting both an exploded
     * directory ({@code target/classes}) and a directory inside a jar (the shaded distribution).
     *
     * @param directory the classpath directory URL
     * @return the schema file URLs
     * @throws IOException If the directory or jar cannot be read
     */
    private static List<URL> listSchemaFiles(URL directory) throws IOException
    {
        if ("file".equals(directory.getProtocol()))
        {
            try (Stream<Path> files = Files.list(Path.of(directory.toURI())))
            {
                List<URL> schemas = new ArrayList<>();
                for (Path file : files.toList())
                {
                    if (Files.isRegularFile(file) && file.getFileName().toString().endsWith(".sql"))
                    {
                        schemas.add(file.toUri().toURL());
                    }
                }
                return schemas;
            }
            catch (URISyntaxException e)
            {
                throw new IOException("Invalid schema directory URI " + directory, e);
            }
        }

        if ("jar".equals(directory.getProtocol()))
        {
            JarURLConnection connection = (JarURLConnection) directory.openConnection();
            String file = directory.getFile();
            String prefix = file.substring(file.indexOf("!/") + 2);

            try (JarFile jar = connection.getJarFile())
            {
                List<URL> schemas = new ArrayList<>();
                String jarUrl = "jar:" + connection.getJarFileURL() + "!/";

                for (var entry : jar.stream()
                        .filter(entry -> !entry.isDirectory())
                        .filter(entry -> entry.getName().startsWith(prefix) && entry.getName().endsWith(".sql"))
                        .toList())
                {
                    schemas.add(URI.create(jarUrl + entry.getName()).toURL());
                }

                return schemas;
            }
        }

        LOGGER.warn("Skipping schema directory with unsupported protocol '{}' at {}", directory.getProtocol(), directory);
        return List.of();
    }

    /**
     * Executes every statement of a schema file on the given connection.
     *
     * @param connection the connection to execute on
     * @param schema the schema file URL
     * @throws IOException If the schema cannot be read
     * @throws SQLException If a statement fails
     */
    private static void executeSchema(Connection connection, URL schema) throws IOException, SQLException
    {
        String script;
        try (InputStream in = schema.openStream())
        {
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        try (Statement statement = connection.createStatement())
        {
            for (String command : script.split(";"))
            {
                if (!command.isBlank())
                {
                    statement.execute(command);
                }
            }
        }

        LOGGER.debug("Applied schema {}", fileName(schema));
    }
}
