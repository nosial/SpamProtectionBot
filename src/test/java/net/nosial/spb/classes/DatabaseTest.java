package net.nosial.spb.classes;

import net.nosial.spb.exceptions.DatabaseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for opening the database, applying its schemas, and reaching it concurrently.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class DatabaseTest
{
    /** The tables the shipped schemas are expected to create. */
    private static final Set<String> EXPECTED_TABLES = Set.of("chat_configuration", "language_preferences",
            "operators", "secretary_configuration", "secretary_contacts", "users");

    @TempDir
    Path directory;

    /**
     * Opens a database in the temporary directory.
     *
     * @return the open database
     * @throws DatabaseException If the database cannot be opened
     */
    private Database open() throws DatabaseException
    {
        return new Database(this.directory.resolve("database.db"));
    }

    /**
     * Inserts a user row.
     *
     * @param database the open database
     * @param id the user id
     * @param username the username
     * @throws DatabaseException If the insert fails
     */
    private static void insertUser(Database database, long id, String username) throws DatabaseException
    {
        database.execute("INSERT INTO users (id, username, first_name, last_name) VALUES (?, ?, ?, ?)", statement ->
        {
            statement.setLong(1, id);
            statement.setString(2, username);
            statement.setString(3, "First");
            statement.setString(4, "Last");
        });
    }

    @Nested
    @DisplayName("Opening the database")
    class Opening
    {
        @Test
        @DisplayName("a missing database file is created with every schema")
        void createsDatabaseAndSchemas() throws Exception
        {
            Path path = DatabaseTest.this.directory.resolve("database.db");

            try (Database database = new Database(path))
            {
                assertTrue(database.created());
                assertTrue(Files.exists(path));
                assertTrue(database.tables().containsAll(EXPECTED_TABLES), database.tables().toString());
            }
        }

        @Test
        @DisplayName("missing parent directories are created")
        void createsParentDirectories() throws Exception
        {
            Path path = DatabaseTest.this.directory.resolve("nested/state/database.db");

            try (Database database = new Database(path))
            {
                assertTrue(Files.exists(path));
                assertEquals(path.toAbsolutePath().normalize(), database.path());
            }
        }

        @Test
        @DisplayName("reopening an existing database keeps its data")
        void reopenIsNonDestructive() throws Exception
        {
            Path path = DatabaseTest.this.directory.resolve("database.db");

            try (Database database = new Database(path))
            {
                insertUser(database, 1, "first");
            }

            try (Database database = new Database(path))
            {
                assertFalse(database.created());
                assertTrue(database.exists("SELECT 1 FROM users WHERE id = ?", statement -> statement.setLong(1, 1)));
            }
        }

        @Test
        @DisplayName("applying the schemas twice is harmless")
        void schemasAreIdempotent() throws Exception
        {
            Path path = DatabaseTest.this.directory.resolve("database.db");

            try (Database first = new Database(path))
            {
                assertTrue(first.tables().containsAll(EXPECTED_TABLES));
            }

            try (Database second = new Database(path))
            {
                assertTrue(second.tables().containsAll(EXPECTED_TABLES));
            }
        }

        @Test
        @DisplayName("a relative path is resolved to an absolute one")
        void resolvesRelativePaths() throws Exception
        {
            try (Database database = open())
            {
                assertTrue(database.path().isAbsolute());
            }
        }

        @Test
        @DisplayName("a null path is rejected")
        void rejectsNullPath()
        {
            assertThrows(NullPointerException.class, () -> new Database(null));
        }
    }

    @Nested
    @DisplayName("Running statements")
    class Statements
    {
        @Test
        @DisplayName("rows written are read back")
        void writesAndReads() throws Exception
        {
            try (Database database = open())
            {
                insertUser(database, 7, "seven");

                Optional<String> username = database.queryOne("SELECT username FROM users WHERE id = ?",
                        statement -> statement.setLong(1, 7), results -> results.getString("username"));

                assertTrue(username.isPresent());
                assertEquals("seven", username.get());
            }
        }

        @Test
        @DisplayName("a query with no match returns nothing")
        void emptyQueryReturnsEmpty() throws Exception
        {
            try (Database database = open())
            {
                assertTrue(database.queryOne("SELECT username FROM users WHERE id = ?",
                        statement -> statement.setLong(1, 404), results -> results.getString(1)).isEmpty());
                assertFalse(database.exists("SELECT 1 FROM users WHERE id = ?",
                        statement -> statement.setLong(1, 404)));
            }
        }

        @Test
        @DisplayName("every matching row is mapped, in query order")
        void mapsEveryRow() throws Exception
        {
            try (Database database = open())
            {
                insertUser(database, 1, "alpha");
                insertUser(database, 2, "beta");
                insertUser(database, 3, "gamma");

                List<String> usernames = database.query("SELECT username FROM users ORDER BY id", null,
                        results -> results.getString(1));

                assertEquals(List.of("alpha", "beta", "gamma"), usernames);
            }
        }

        @Test
        @DisplayName("an update reports how many rows it changed")
        void reportsAffectedRows() throws Exception
        {
            try (Database database = open())
            {
                insertUser(database, 1, "alpha");
                insertUser(database, 2, "beta");

                int changed = database.execute("UPDATE users SET first_name = ?", statement ->
                        statement.setString(1, "Renamed"));

                assertEquals(2, changed);
            }
        }

        @Test
        @DisplayName("a failing statement raises a DatabaseException naming the SQL")
        void wrapsFailures() throws Exception
        {
            try (Database database = open())
            {
                DatabaseException e = assertThrows(DatabaseException.class,
                        () -> database.query("SELECT * FROM no_such_table", null, results -> results.getString(1)));

                assertTrue(e.getMessage().contains("no_such_table"), e.getMessage());
            }
        }

        @Test
        @DisplayName("a borrowed connection works and returns to the pool")
        void handsOutConnections() throws Exception
        {
            try (Database database = open())
            {
                for (int i = 0; i < 32; i++)
                {
                    try (var connection = database.getConnection();
                         Statement statement = connection.createStatement())
                    {
                        assertTrue(statement.execute("SELECT 1"));
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("Running transactions")
    class Transactions
    {
        @Test
        @DisplayName("a transaction commits when the work returns")
        void commitsOnSuccess() throws Exception
        {
            try (Database database = open())
            {
                int written = database.transaction(connection ->
                {
                    try (var statement = connection.prepareStatement(
                            "INSERT INTO users (id, username) VALUES (?, ?)"))
                    {
                        statement.setLong(1, 1);
                        statement.setString(2, "alpha");
                        statement.executeUpdate();
                    }

                    try (var statement = connection.prepareStatement(
                            "INSERT INTO users (id, username) VALUES (?, ?)"))
                    {
                        statement.setLong(1, 2);
                        statement.setString(2, "beta");
                        statement.executeUpdate();
                    }

                    return 2;
                });

                assertEquals(2, written);
                assertEquals(List.of("alpha", "beta"),
                        database.query("SELECT username FROM users ORDER BY id", null, r -> r.getString(1)));
            }
        }

        @Test
        @DisplayName("a transaction rolls back completely when the work fails")
        void rollsBackOnFailure() throws Exception
        {
            try (Database database = open())
            {
                assertThrows(DatabaseException.class, () -> database.transaction(connection ->
                {
                    try (var statement = connection.prepareStatement(
                            "INSERT INTO users (id, username) VALUES (?, ?)"))
                    {
                        statement.setLong(1, 1);
                        statement.setString(2, "alpha");
                        statement.executeUpdate();
                    }

                    // A duplicate primary key: the first insert must not survive this.
                    try (var statement = connection.prepareStatement(
                            "INSERT INTO users (id, username) VALUES (?, ?)"))
                    {
                        statement.setLong(1, 1);
                        statement.setString(2, "duplicate");
                        statement.executeUpdate();
                    }

                    return null;
                }));

                assertEquals(List.of(), database.query("SELECT username FROM users", null, r -> r.getString(1)));
            }
        }

        @Test
        @DisplayName("a runtime failure inside a transaction also rolls it back")
        void rollsBackOnRuntimeFailure() throws Exception
        {
            try (Database database = open())
            {
                assertThrows(IllegalStateException.class, () -> database.transaction(connection ->
                {
                    try (var statement = connection.prepareStatement(
                            "INSERT INTO users (id, username) VALUES (?, ?)"))
                    {
                        statement.setLong(1, 1);
                        statement.setString(2, "alpha");
                        statement.executeUpdate();
                    }

                    throw new IllegalStateException("handler changed its mind");
                }));

                assertEquals(List.of(), database.query("SELECT username FROM users", null, r -> r.getString(1)));
            }
        }
    }

    @Nested
    @DisplayName("Serving concurrent callers")
    class Concurrency
    {
        @Test
        @DisplayName("many threads write at once without losing a row")
        void concurrentWritesAllLand() throws Exception
        {
            int threads = 8;
            int perThread = 25;

            try (Database database = open())
            {
                ExecutorService executor = Executors.newFixedThreadPool(threads);
                CountDownLatch start = new CountDownLatch(1);
                AtomicInteger failures = new AtomicInteger();

                for (int t = 0; t < threads; t++)
                {
                    int worker = t;
                    executor.execute(() ->
                    {
                        try
                        {
                            start.await();
                            for (int i = 0; i < perThread; i++)
                            {
                                insertUser(database, worker * 1000L + i, "user-" + worker + "-" + i);
                            }
                        }
                        catch (Exception e)
                        {
                            failures.incrementAndGet();
                        }
                    });
                }

                start.countDown();
                executor.shutdown();
                assertTrue(executor.awaitTermination(45, TimeUnit.SECONDS));

                assertEquals(0, failures.get(), "no writer should have failed");
                assertEquals(threads * perThread, database.queryOne("SELECT COUNT(*) FROM users", null,
                        results -> results.getInt(1)).orElseThrow());
            }
        }

        @Test
        @DisplayName("readers run beside a writer")
        void readersRunBesideWriters() throws Exception
        {
            try (Database database = open())
            {
                insertUser(database, 1, "alpha");

                ExecutorService executor = Executors.newFixedThreadPool(4);
                CountDownLatch start = new CountDownLatch(1);
                AtomicInteger reads = new AtomicInteger();
                AtomicInteger failures = new AtomicInteger();

                for (int t = 0; t < 4; t++)
                {
                    int worker = t;
                    executor.execute(() ->
                    {
                        try
                        {
                            start.await();
                            for (int i = 0; i < 50; i++)
                            {
                                if (worker == 0)
                                {
                                    insertUser(database, 100L + i, "writer-" + i);
                                }
                                else if (database.exists("SELECT 1 FROM users WHERE id = ?",
                                        statement -> statement.setLong(1, 1)))
                                {
                                    reads.incrementAndGet();
                                }
                            }
                        }
                        catch (Exception e)
                        {
                            failures.incrementAndGet();
                        }
                    });
                }

                start.countDown();
                executor.shutdown();
                assertTrue(executor.awaitTermination(45, TimeUnit.SECONDS));

                assertEquals(0, failures.get());
                assertEquals(150, reads.get());
            }
        }
    }

    @Nested
    @DisplayName("Closing the database")
    class Closing
    {
        @Test
        @DisplayName("the pool is closed and reports it")
        void closesThePool() throws Exception
        {
            Database database = open();
            assertTrue(database.isOpen());

            database.close();

            assertFalse(database.isOpen());
        }

        @Test
        @DisplayName("closing twice is harmless")
        void closeIsIdempotent() throws Exception
        {
            Database database = open();

            database.close();
            database.close();

            assertFalse(database.isOpen());
        }

        @Test
        @DisplayName("statements after close fail rather than corrupt anything")
        void refusesWorkAfterClose() throws Exception
        {
            Database database = open();
            database.close();

            assertThrows(SQLException.class, database::getConnection);
        }
    }
}
