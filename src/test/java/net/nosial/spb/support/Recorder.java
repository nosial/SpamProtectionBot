package net.nosial.spb.support;

import net.nosial.spb.objects.context.HandlerContext;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records which handlers ran, in order.
 *
 * <p>Discovered handlers are instantiated reflectively by the registry, so a test cannot hold a
 * reference to the instance it wants to observe. They report here instead.
 */
public final class Recorder
{
    private static final List<String> INVOCATIONS = new CopyOnWriteArrayList<>();

    private Recorder()
    {
    }

    /**
     * Records that a handler ran for an update.
     *
     * @param handler the handler name
     * @param context the context the handler was given
     */
    public static void record(String handler, HandlerContext context)
    {
        INVOCATIONS.add(handler + ":" + (context.update() != null ? context.update().getUpdateId() : "none"));
    }

    /**
     * Returns the invocations recorded so far, in order.
     *
     * @return the invocations
     */
    public static List<String> invocations()
    {
        return List.copyOf(INVOCATIONS);
    }

    /**
     * Returns the names of the handlers that ran, in order, without the update ids.
     *
     * @return the handler names
     */
    public static List<String> handlers()
    {
        return INVOCATIONS.stream().map(entry -> entry.substring(0, entry.indexOf(':'))).toList();
    }

    /**
     * Discards everything recorded so far.
     */
    public static void reset()
    {
        INVOCATIONS.clear();
    }
}
