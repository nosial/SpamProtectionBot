package net.nosial.spb.objects;

import net.nosial.spb.classes.Handler;
import net.nosial.spb.classes.UpdateHandler;
import net.nosial.spb.enums.DispatchMode;
import net.nosial.spb.enums.UpdateType;
import net.nosial.spb.exceptions.HandlerException;
import net.nosial.spb.objects.context.HandlerContext;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * A handler instance together with the dispatch rules read from its annotation.
 *
 * <p>Reading the annotation once at start-up keeps the per-update path free of reflection: routing
 * an update is a walk over pre-resolved sets, not an annotation lookup.
 *
 * @param handler the shared handler instance
 * @param types the update types the handler serves
 * @param commands the command names the handler claims, lowercased; empty when it does not filter
 *                 by command
 * @param callbackPrefixes the callback data prefixes the handler claims; empty when it does not
 *                         filter by callback data
 * @param mode how the handler participates in dispatch
 * @param priority the evaluation order within the dispatch mode; higher runs first
 */
public record RegisteredHandler(
        Handler handler,
        Set<UpdateType> types,
        Set<String> commands,
        List<String> callbackPrefixes,
        DispatchMode mode,
        int priority)
{
    /**
     * Reads the {@link UpdateHandler} annotation of the given instance into dispatch rules.
     *
     * @param handler the handler instance
     * @throws HandlerException If the class carries no annotation, or its filters contradict the
     *                          update types, it declares
     */
    public RegisteredHandler(Handler handler)
    {
        this(read(handler));
    }

    /**
     * Copies the dispatch rules read from a handler's annotation.
     *
     * @param resolved the rules read by {@link #read(Handler)}
     */
    private RegisteredHandler(RegisteredHandler resolved)
    {
        this(resolved.handler(), resolved.types(), resolved.commands(), resolved.callbackPrefixes(),
                resolved.mode(), resolved.priority());
    }

    /**
     * Reads a handler's {@link UpdateHandler} annotation into dispatch rules.
     *
     * @param handler the handler instance
     * @return the rules it declared
     * @throws HandlerException If the class carries no annotation, or its filters contradict the
     *                          update types it declares
     */
    private static RegisteredHandler read(Handler handler)
    {
        Objects.requireNonNull(handler, "handler must not be null");

        UpdateHandler annotation = handler.getClass().getAnnotation(UpdateHandler.class);
        if (annotation == null)
        {
            throw new HandlerException(handler.getClass().getName() + " is not annotated with @UpdateHandler");
        }

        Set<UpdateType> types = new LinkedHashSet<>(Arrays.asList(annotation.value()));
        if (types.isEmpty())
        {
            throw new HandlerException(handler.getClass().getName() + " declares no update types");
        }

        Set<String> commands = new LinkedHashSet<>();
        for (String command : annotation.commands())
        {
            String normalized = command.strip().toLowerCase(Locale.ROOT);
            if (normalized.startsWith("/"))
            {
                normalized = normalized.substring(1);
            }

            if (normalized.isEmpty())
            {
                throw new HandlerException(handler.getClass().getName() + " declares an empty command name");
            }

            commands.add(normalized);
        }

        List<String> callbackPrefixes = List.of(annotation.callbackData());
        for (String prefix : callbackPrefixes)
        {
            if (prefix.isEmpty())
            {
                throw new HandlerException(handler.getClass().getName() + " declares an empty callback data prefix");
            }
        }

        if (!commands.isEmpty() && !types.contains(UpdateType.COMMAND) && !types.contains(UpdateType.ANY))
        {
            throw new HandlerException(handler.getClass().getName()
                    + " declares commands but not UpdateType.COMMAND, so it would never match");
        }

        if (!callbackPrefixes.isEmpty() && !types.contains(UpdateType.CALLBACK_QUERY) && !types.contains(UpdateType.ANY))
        {
            throw new HandlerException(handler.getClass().getName()
                    + " declares callback data but not UpdateType.CALLBACK_QUERY, so it would never match");
        }

        return new RegisteredHandler(handler, Set.copyOf(types), Set.copyOf(commands), callbackPrefixes,
                annotation.mode(), annotation.priority());
    }

    /**
     * Returns whether this handler should run for the update the context carries.
     *
     * <p>The declared filters are evaluated first, cheapest first, and the handler's own
     * {@link Handler#accepts(HandlerContext)} hook is consulted last so it only sees updates that
     * already passed the annotation.
     *
     * @param context the per-update context
     * @return {@code true} when the handler should run
     */
    public boolean matches(HandlerContext context)
    {
        if (context == null || context.update() == null)
        {
            return false;
        }

        boolean typeMatched = false;
        for (UpdateType type : this.types)
        {
            if (type.matches(context.update()))
            {
                typeMatched = true;
                break;
            }
        }

        if (!typeMatched)
        {
            return false;
        }

        // Each filter constrains only the kind of update it can speak about: the command filter
        // judges command messages, the callback filter judges button presses, and neither has an
        // opinion about the other. That is what lets one handler serve /report, the plain replies
        // to its prompt, and its report:* buttons from a single declaration.
        if (!this.commands.isEmpty() && UpdateType.COMMAND.matches(context.update())
                && !matchesCommand(context))
        {
            return false;
        }

        if (!this.callbackPrefixes.isEmpty() && UpdateType.CALLBACK_QUERY.matches(context.update())
                && !matchesCallbackData(context))
        {
            return false;
        }

        return this.handler.accepts(context);
    }

    /**
     * Returns whether the update invokes one of the declared commands.
     *
     * @param context the per-update context
     * @return {@code true} when a declared command matched
     */
    private boolean matchesCommand(HandlerContext context)
    {
        if (this.commands.isEmpty())
        {
            return false;
        }

        String command = context.commandName();
        return command != null && this.commands.contains(command);
    }

    /**
     * Returns whether the update is a button press carrying one of the declared prefixes.
     *
     * @param context the per-update context
     * @return {@code true} when a declared prefix matched
     */
    private boolean matchesCallbackData(HandlerContext context)
    {
        if (this.callbackPrefixes.isEmpty())
        {
            return false;
        }

        String data = context.callbackData();
        if (data == null)
        {
            return false;
        }

        for (String prefix : this.callbackPrefixes)
        {
            if (data.startsWith(prefix))
            {
                return true;
            }
        }

        return false;
    }

    /**
     * Returns the handler's name, used in log messages.
     *
     * @return the handler name
     */
    public String name()
    {
        return this.handler.name();
    }
}
