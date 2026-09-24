package net.nosial.spb.classes;

import net.nosial.spb.enums.DispatchMode;
import net.nosial.spb.enums.UpdateType;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a class as a handler and describes which updates it serves.
 *
 * <p>Handlers are discovered by {@link net.nosial.spb.classes.HandlerRegistry} from this
 * annotation instead of being registered by hand at start-up, so adding a feature means adding one
 * annotated class under {@code net.nosial.spb.handlers} and nothing else. The annotated class must
 * extend {@link Handler}, must not be abstract, and must expose a public
 * no-argument constructor; exactly one instance of it is created and shared by every worker
 * thread, so it must not keep mutable per-update state in fields.
 *
 * <p>An update must match one of the {@link #value() types}. Each of {@link #commands()} and
 * {@link #callbackData()} then constrains only the kind of update it can speak about: the command
 * filter judges command messages and the callback filter judges button presses, and neither has an
 * opinion about the other. A handler that serves {@code /help} and its {@code help:*} buttons
 * declares both and each update is judged by the one that applies; a handler that wants every
 * message of a type plus a callback namespace declares only the callback filter. A handler may
 * narrow the match further at runtime by overriding
 * {@link Handler#accepts}.
 *
 * <p>Example: a handler for {@code /start} and {@code /help} that is consulted before the general
 * command handlers.
 * <pre>{@code
 * @UpdateHandler(value = UpdateType.COMMAND, commands = {"start", "help"}, priority = 100)
 * public final class StartHandler extends Handler { ... }
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface UpdateHandler
{
    /**
     * The kinds of update this handler serves.
     *
     * @return the update types, defaulting to every update
     */
    UpdateType[] value() default {UpdateType.ANY};

    /**
     * The command names this handler claims, without the leading slash.
     *
     * <p>Matching is case-insensitive and accepts the {@code /command@botname} form. An empty
     * array means the handler does not filter by command name.
     *
     * @return the command names
     */
    String[] commands() default {};

    /**
     * The callback data prefixes this handler claims.
     *
     * <p>An inline button press matches when its callback data starts with any of these prefixes,
     * which is how a handler owns a namespace such as {@code "settings:"}. An empty array means
     * the handler does not filter by callback data.
     *
     * @return the callback data prefixes
     */
    String[] callbackData() default {};

    /**
     * How the handler participates in dispatch once its types match.
     *
     * @return the dispatch mode, {@link DispatchMode#ROUTE} by default
     */
    DispatchMode mode() default DispatchMode.ROUTE;

    /**
     * The evaluation order within the handler's dispatch mode; higher runs first.
     *
     * <p>Handlers of equal priority are ordered by class name, so the dispatch order never depends
     * on the order in which the classpath happened to be scanned.
     *
     * @return the priority
     */
    int priority() default 0;

    /**
     * Whether the handler is registered at all.
     *
     * <p>Setting this to {@code false} keeps a half-finished or temporarily disabled handler in
     * the source tree without it receiving updates.
     *
     * @return {@code true} when the handler should be registered
     */
    boolean enabled() default true;
}
