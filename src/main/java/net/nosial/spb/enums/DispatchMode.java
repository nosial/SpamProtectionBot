package net.nosial.spb.enums;

/**
 * How a handler participates in dispatch once its declared update types match.
 *
 * <p>The two modes exist so cross-cutting work (bookkeeping, scanning, membership tracking) can be
 * expressed independently of the handler that ultimately answers the update, instead of every
 * feature handler having to remember to call it.
 */
public enum DispatchMode
{
    /**
     * The handler runs for every matching update before any routing and never consumes it.
     *
     * <p>All observers of an update run, in priority order, regardless of what any of them does.
     * A failing observer is logged and skipped; it never prevents the remaining observers or the
     * routed handler from running.
     */
    OBSERVE,

    /**
     * The handler competes to answer the update, and consumes it when selected.
     *
     * <p>Routed handlers are evaluated in priority order and the first one that matches is the
     * only one invoked, so a specific handler can claim an update ahead of a general one by
     * declaring a higher priority.
     */
    ROUTE
}
