package net.nosial.spb.classes;

import net.nosial.spb.enums.DispatchMode;
import net.nosial.spb.exceptions.HandlerException;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.objects.RegisteredHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * The set of handlers available to the bot, discovered from their annotations.
 *
 * <p>Handlers are not registered by hand: the registry scans a package for classes carrying
 * {@link UpdateHandler}, instantiates each one, and sorts them into the two dispatch groups. A new
 * feature therefore never touches start-up code — it is one annotated class under
 * {@code net.nosial.spb.handlers}, and the bot picks it up on the next run.
 *
 * <p>Within each group handlers are ordered by descending priority, then by class name so the
 * order never depends on how the classpath happened to be walked. The registry is immutable and
 * safe to share between worker threads once built.
 */
public final class HandlerRegistry
{
    private static final Logger LOGGER = LoggerFactory.getLogger(HandlerRegistry.class);

    /** The package scanned when no other is given. */
    public static final String DEFAULT_PACKAGE = "net.nosial.spb.handlers";

    private List<RegisteredHandler> observers;
    private List<RegisteredHandler> routed;

    /**
     * Creates a registry from already-resolved handlers.
     *
     * @param handlers the registered handlers
     */
    private void sortInto(Collection<RegisteredHandler> handlers)
    {
        Comparator<RegisteredHandler> order = Comparator.comparingInt(RegisteredHandler::priority).reversed()
                .thenComparing(registered -> registered.handler().getClass().getName());
        this.observers = handlers.stream().filter(registered -> registered.mode() == DispatchMode.OBSERVE).sorted(order).toList();
        this.routed = handlers.stream().filter(registered -> registered.mode() == DispatchMode.ROUTE).sorted(order).toList();
    }

    /**
     * Discovers every annotated handler in the default package.
     *
     * @throws HandlerException If a handler cannot be loaded or instantiated
     */
    public HandlerRegistry()
    {
        this(DEFAULT_PACKAGE);
    }

    /**
     * Discovers every annotated handler in the given package and its subpackages.
     *
     * @param packageName the package to scan
     * @throws HandlerException If the package cannot be scanned, or a handler cannot be loaded or
     *                          instantiated
     */
    public HandlerRegistry(String packageName)
    {
        Objects.requireNonNull(packageName, "packageName must not be null");

        List<RegisteredHandler> handlers = new ArrayList<>();
        for (String className : findClasses(packageName))
        {
            Class<?> candidate;
            try
            {
                candidate = Class.forName(className, false, classLoader());
            }
            catch (ClassNotFoundException | LinkageError e)
            {
                LOGGER.debug("Skipping class {}: {}", className, e.getMessage());
                continue;
            }

            if (!candidate.isAnnotationPresent(UpdateHandler.class))
            {
                continue;
            }

            if (!candidate.getAnnotation(UpdateHandler.class).enabled())
            {
                LOGGER.info("Handler {} is disabled and will not receive updates", candidate.getSimpleName());
                continue;
            }

            handlers.add(new RegisteredHandler(instantiate(candidate)));
        }

        sortInto(handlers);
        LOGGER.info("Registered {} handler(s) from '{}': {} observing, {} routed",
                size(), packageName, this.observers.size(), this.routed.size());
    }

    /**
     * Creates a registry from explicitly supplied handler instances.
     *
     * <p>Discovery is how the bot builds its registry; this exists for tests and for the rare case
     * where a handler needs constructor arguments and therefore cannot be discovered.
     *
     * @param handlers the annotated handler instances
     * @throws HandlerException If an instance is not annotated or its filters are contradictory
     */
    public HandlerRegistry(Handler... handlers)
    {
        Objects.requireNonNull(handlers, "handlers must not be null");

        List<RegisteredHandler> registered = new ArrayList<>(handlers.length);
        for (Handler handler : handlers)
        {
            registered.add(new RegisteredHandler(handler));
        }

        sortInto(registered);
    }

    /**
     * Returns the observing handlers that match the update, in the order they must run.
     *
     * @param context the per-update context
     * @return the matching observers, never {@code null}
     */
    public List<Handler> observersFor(HandlerContext context)
    {
        List<Handler> matching = new ArrayList<>();
        for (RegisteredHandler registered : this.observers)
        {
            if (safeMatches(registered, context))
            {
                matching.add(registered.handler());
            }
        }

        return matching;
    }

    /**
     * Returns the routed handler that claims the update.
     *
     * @param context the per-update context
     * @return the first matching routed handler, or {@link Optional#empty()} when none claims it
     */
    public Optional<Handler> routeFor(HandlerContext context)
    {
        for (RegisteredHandler registered : this.routed)
        {
            if (safeMatches(registered, context))
            {
                return Optional.of(registered.handler());
            }
        }

        return Optional.empty();
    }

    /**
     * Returns every registered handler, observers first, each group in dispatch order.
     *
     * @return the registered handlers
     */
    public List<RegisteredHandler> handlers()
    {
        List<RegisteredHandler> all = new ArrayList<>(this.observers.size() + this.routed.size());
        all.addAll(this.observers);
        all.addAll(this.routed);
        return List.copyOf(all);
    }

    /**
     * Returns the number of registered handlers.
     *
     * @return the handler count
     */
    public int size()
    {
        return this.observers.size() + this.routed.size();
    }

    /**
     * Evaluates a handler's filters, treating a failure as a non-match.
     *
     * <p>A handler whose {@link Handler#accepts(HandlerContext)} throws must not be able to stop
     * the remaining handlers from seeing the update, so the failure is logged and the handler is
     * skipped.
     *
     * @param registered the handler to evaluate
     * @param context the per-update context
     * @return {@code true} when the handler should run
     */
    private static boolean safeMatches(RegisteredHandler registered, HandlerContext context)
    {
        try
        {
            return registered.matches(context);
        }
        catch (RuntimeException e)
        {
            LOGGER.warn("Handler {} failed while deciding whether to accept update {}",
                    registered.name(), context.update() != null ? context.update().getUpdateId() : null, e);
            return false;
        }
    }

    /**
     * Instantiates a discovered handler class.
     *
     * @param candidate the annotated class
     * @return the handler instance
     * @throws HandlerException If the class is not a usable handler
     */
    private static Handler instantiate(Class<?> candidate)
    {
        if (!Handler.class.isAssignableFrom(candidate))
        {
            throw new HandlerException(candidate.getName() + " is annotated with @UpdateHandler but does not extend "
                    + Handler.class.getName());
        }

        if (Modifier.isAbstract(candidate.getModifiers()) || candidate.isInterface())
        {
            throw new HandlerException(candidate.getName() + " is annotated with @UpdateHandler but is abstract");
        }

        try
        {
            return (Handler) candidate.getDeclaredConstructor().newInstance();
        }
        catch (NoSuchMethodException e)
        {
            throw new HandlerException(candidate.getName() + " must declare a public no-argument constructor", e);
        }
        catch (ReflectiveOperationException e)
        {
            throw new HandlerException("Failed to instantiate handler " + candidate.getName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Returns the binary names of every class under the given package.
     *
     * @param packageName the package to scan
     * @return the class names, in alphabetical order
     * @throws HandlerException If the package cannot be scanned
     */
    private static Collection<String> findClasses(String packageName)
    {
        String resourcePath = packageName.replace('.', '/');
        TreeSet<String> classNames = new TreeSet<>();

        try
        {
            Enumeration<URL> roots = classLoader().getResources(resourcePath);
            while (roots.hasMoreElements())
            {
                classNames.addAll(listClasses(roots.nextElement(), packageName, resourcePath));
            }
        }
        catch (IOException e)
        {
            throw new HandlerException("Failed to scan package '" + packageName + "': " + e.getMessage(), e);
        }

        if (classNames.isEmpty())
        {
            LOGGER.warn("No classes found under package '{}'", packageName);
        }

        return classNames;
    }

    /**
     * Lists the class names under one classpath root, supporting both an exploded directory
     * ({@code target/classes}) and a directory inside a jar (the shaded distribution).
     *
     * @param root the classpath directory URL
     * @param packageName the package the root corresponds to
     * @param resourcePath the package name as a resource path
     * @return the class names found under the root
     * @throws IOException If the directory or jar cannot be read
     */
    private static List<String> listClasses(URL root, String packageName, String resourcePath) throws IOException
    {
        List<String> classNames = new ArrayList<>();

        if ("file".equals(root.getProtocol()))
        {
            Path directory;
            try
            {
                directory = Path.of(root.toURI());
            }
            catch (URISyntaxException e)
            {
                throw new IOException("Invalid package directory URI " + root, e);
            }

            try (Stream<Path> files = Files.walk(directory))
            {
                for (Path file : files.filter(Files::isRegularFile).toList())
                {
                    String name = file.getFileName().toString();
                    if (!name.endsWith(".class"))
                    {
                        continue;
                    }

                    String relative = directory.relativize(file).toString().replace(java.io.File.separatorChar, '.');
                    classNames.add(packageName + "." + relative.substring(0, relative.length() - ".class".length()));
                }
            }

            return classNames;
        }

        if ("jar".equals(root.getProtocol()))
        {
            JarURLConnection connection = (JarURLConnection) root.openConnection();
            try (JarFile jar = connection.getJarFile())
            {
                for (var entry : jar.stream().filter(entry -> !entry.isDirectory()).toList())
                {
                    String name = entry.getName();
                    if (!name.startsWith(resourcePath + "/") || !name.endsWith(".class"))
                    {
                        continue;
                    }

                    classNames.add(name.substring(0, name.length() - ".class".length()).replace('/', '.'));
                }
            }

            return classNames;
        }

        LOGGER.warn("Skipping classpath root with unsupported protocol '{}' at {}", root.getProtocol(), root);
        return classNames;
    }

    /**
     * Returns the class loader classes are resolved against.
     *
     * @return the context class loader when set, otherwise this class's loader
     */
    private static ClassLoader classLoader()
    {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader != null ? loader : HandlerRegistry.class.getClassLoader();
    }
}
