package net.nosial.spb.classes;

import org.telegram.telegrambots.meta.api.objects.message.Message;
import net.nosial.spb.utilities.ReportAttachments;
import net.nosial.spb.objects.ReportAttachment;
import net.nosial.spb.objects.MediaGroupState;
import net.nosial.spb.enums.UpdateType;
import net.nosial.spb.objects.context.HandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Routes incoming updates to the registered handlers across a pool of worker threads.
 *
 * <p>The Telegram polling thread only enqueues work here and never runs a handler itself, so a
 * slow handler delays one update rather than the whole bot. Updates wait in a bounded queue; when
 * the queue is full the surplus is dropped and logged, which keeps a burst from growing the heap
 * without limit.
 *
 * <p>Each update is processed as: every matching {@link net.nosial.spb.enums.DispatchMode#OBSERVE
 * observing} handler runs first, in priority order, then the first matching
 * {@link net.nosial.spb.enums.DispatchMode#ROUTE routed} handler claims the update. A handler that
 * throws is logged and skipped, never retried, so one failure cannot stall dispatch. An inline
 * button press that no handler claims is still acknowledged, so the user's client does not spin.
 *
 * <p><strong>Shutdown.</strong> {@link #close()} stops accepting new updates and lets the queue run
 * out, waiting up to {@link #DEFAULT_DRAIN_TIMEOUT} for in-flight handlers to finish before
 * interrupting them. Callers must stop the poller first, otherwise newly fetched updates are
 * rejected rather than processed.
 */
public final class UpdateDispatcher implements LongPollingUpdateConsumer, AutoCloseable
{
    private static final Logger LOGGER = LoggerFactory.getLogger(UpdateDispatcher.class);

    /** How long {@link #close()} waits for queued and in-flight updates before interrupting. */
    public static final Duration DEFAULT_DRAIN_TIMEOUT = Duration.ofSeconds(30);

    /** Cache key prefix for album state, scoped by chat. */
    private static final String MEDIA_GROUP_CACHE_PREFIX = "media-group:";

    /** Numbers the worker threads, so their names are distinct across dispatchers. */
    private static final AtomicInteger WORKER_COUNTER = new AtomicInteger();

    private final HandlerRegistry registry;
    private final HandlerContext template;
    private final ThreadPoolExecutor executor;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    /**
     * Creates a dispatcher over the given registry.
     *
     * @param registry the handlers to dispatch to
     * @param template the context template every per-update context is derived from
     * @param workerThreads the number of concurrent worker threads (must be &gt;= 1)
     * @param queueCapacity the maximum number of updates waiting to be processed (must be &gt;= 1)
     */
    public UpdateDispatcher(HandlerRegistry registry, HandlerContext template, int workerThreads, int queueCapacity)
    {
        if (workerThreads < 1)
        {
            throw new IllegalArgumentException("workerThreads must be >= 1, got " + workerThreads);
        }

        if (queueCapacity < 1)
        {
            throw new IllegalArgumentException("queueCapacity must be >= 1, got " + queueCapacity);
        }

        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.template = Objects.requireNonNull(template, "template must not be null");
        this.executor = new ThreadPoolExecutor(workerThreads, workerThreads, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity), UpdateDispatcher::newWorkerThread, new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Enqueues a batch of updates received from long polling.
     *
     * <p>Runs on the polling thread and returns as soon as the batch is queued.
     *
     * @param updates the updates to process
     */
    @Override
    public void consume(List<Update> updates)
    {
        if (updates == null)
        {
            return;
        }

        for (Update update : updates)
        {
            submit(update);
        }
    }

    /**
     * Enqueues a single update for processing on a worker thread.
     *
     * @param update the update to process
     * @return {@code true} when the update was queued, {@code false} when it was dropped because
     *         the queue is full or the dispatcher is shutting down
     */
    public boolean submit(Update update)
    {
        if (update == null)
        {
            return false;
        }

        if (!this.accepting.get())
        {
            this.dropped.incrementAndGet();
            LOGGER.debug("Update {} dropped: the dispatcher is shutting down", update.getUpdateId());
            return false;
        }

        try
        {
            this.executor.execute(() -> process(update));
            return true;
        }
        catch (RejectedExecutionException e)
        {
            this.dropped.incrementAndGet();
            if (this.executor.isShutdown())
            {
                LOGGER.debug("Update {} dropped: the dispatcher is shutting down", update.getUpdateId());
            }
            else
            {
                LOGGER.warn("Update {} dropped: the queue is full ({} waiting)",
                        update.getUpdateId(), pendingUpdates());
            }
            return false;
        }
    }

    /**
     * Returns the number of updates waiting for a worker thread.
     *
     * @return the queue depth
     */
    public int pendingUpdates()
    {
        return this.executor.getQueue().size();
    }

    /**
     * Returns how many updates have finished processing since start-up.
     *
     * @return the processed update count
     */
    public long processedUpdates()
    {
        return this.processed.get();
    }

    /**
     * Returns how many updates were discarded because the queue was full or the dispatcher was
     * shutting down.
     *
     * @return the dropped update count
     */
    public long droppedUpdates()
    {
        return this.dropped.get();
    }

    /**
     * Returns whether the dispatcher still accepts updates.
     *
     * @return {@code true} until shutdown begins
     */
    public boolean isAccepting()
    {
        return this.accepting.get();
    }

    /**
     * Stops accepting updates and processes everything already queued.
     *
     * @param timeout how long to wait for the queue to run out and in-flight handlers to finish
     * @return {@code true} when everything drained in time, {@code false} when the remaining work
     *         was interrupted
     */
    public boolean drain(Duration timeout)
    {
        Objects.requireNonNull(timeout, "timeout must not be null");

        if (!this.accepting.compareAndSet(true, false))
        {
            return this.executor.isTerminated();
        }

        int remaining = pendingUpdates();
        if (remaining > 0)
        {
            LOGGER.info("Finishing {} queued update(s) before shutting down", remaining);
        }

        // shutdown() lets the queued tasks run to completion and only refuses new ones, which is
        // exactly the "finish what we have, fetch nothing more" behaviour shutdown wants.
        this.executor.shutdown();

        try
        {
            if (this.executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS))
            {
                return true;
            }

            LOGGER.warn("{} update(s) did not finish within {}s, interrupting them", pendingUpdates() + this.executor.getActiveCount(), timeout.toSeconds());
            this.executor.shutdownNow();
            return false;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            this.executor.shutdownNow();
            return false;
        }
    }

    /**
     * Stops accepting updates and drains the queue, waiting up to {@link #DEFAULT_DRAIN_TIMEOUT}.
     */
    @Override
    public void close()
    {
        drain(DEFAULT_DRAIN_TIMEOUT);
    }

    /**
     * Processes one update on a worker thread.
     *
     * @param update the update to process
     */
    private void process(Update update)
    {
        HandlerContext context = this.template.withUpdate(update);
        trackMediaGroup(context);

        try
        {
            for (Handler observer : this.registry.observersFor(context))
            {
                run(observer, context);
            }

            Optional<Handler> routed = this.registry.routeFor(context);
            if (routed.isPresent())
            {
                run(routed.get(), context);
                return;
            }

            unclaimed(context, update);
        }
        catch (RuntimeException e)
        {
            LOGGER.error("Failed to process update {} ({})", update.getUpdateId(), describe(update), e);
        }
        finally
        {
            this.processed.incrementAndGet();
        }
    }

    /**
     * Creates a worker thread with a name that makes thread dumps and logs readable.
     *
     * @param work the task the thread runs
     * @return the named, non-daemon worker thread
     */
    private static Thread newWorkerThread(Runnable work)
    {
        return new Thread(work, "spb-worker-" + WORKER_COUNTER.incrementAndGet());
    }

    /**
     * Records this update's part of an album, if it is one.
     *
     * <p>Telegram delivers an album as several updates sharing a {@code media_group_id}, each
     * carrying one attachment, so no single update contains the whole thing. Anything that acts on
     * an album — deleting all of it because one part was flagged, attaching all of it to a report —
     * needs the parts seen so far.
     *
     * <p>The dispatcher does this rather than a handler because the dispatcher is what sees every
     * update: a handler that only runs for some of them would miss the rest of the album.
     *
     * @param context the per-update context
     */
    private static void trackMediaGroup(HandlerContext context)
    {
        Message message = context.message();
        if (message != null)
        {
            mediaGroup(context, message);
        }
    }

    /**
     * Retrieves or updates the {@link MediaGroupState} for a given media group in the specified context.
     * The method processes the provided message to associate it with its corresponding media group and
     * collects the relevant attachments for moderation purposes.
     *
     * @param context the per-update context, which provides access to runtime features such as caching
     * @param message the message to process, which contains information about the media group and its author
     * @return the updated {@link MediaGroupState} if the message belongs to a valid media group and meets
     *         the necessary conditions; otherwise, returns {@code null}
     */
    public static MediaGroupState mediaGroup(HandlerContext context, Message message)
    {
        String mediaGroupId = message.getMediaGroupId();
        if (mediaGroupId == null || mediaGroupId.isBlank() || message.getMessageId() == null)
        {
            return null;
        }
        String cacheKey = MEDIA_GROUP_CACHE_PREFIX + message.getChatId() + ":" + mediaGroupId;
        Object cached = context.cache().get(cacheKey,
                ignored -> new MediaGroupState(message.getFrom().getId()));
        if (!(cached instanceof MediaGroupState state) || state.authorId() != message.getFrom().getId())
        {
            return null;
        }
        state.addMessageId(message.getMessageId());
        for (ReportAttachment attachment : ReportAttachments.collect(message))
        {
            state.addAttachment(attachment);
        }
        return state;
    }

    /**
     * Invokes one handler, logging and swallowing whatever it throws.
     *
     * @param handler the handler to invoke
     * @param context the per-update context
     */
    private static void run(Handler handler, HandlerContext context)
    {
        try
        {
            handler.handle(context);
        }
        catch (Exception e)
        {
            LOGGER.warn("Handler {} failed on update {} ({})", handler.name(), context.update().getUpdateId(), describe(context.update()), e);
        }
    }

    /**
     * Deals with an update no routed handler claimed.
     *
     * <p>An unanswered inline button press leaves a loading indicator spinning in the user's
     * client, so it is acknowledged even when nothing handled it.
     *
     * @param context the per-update context
     * @param update the unclaimed update
     */
    private static void unclaimed(HandlerContext context, Update update)
    {
        if (!update.hasCallbackQuery())
        {
            LOGGER.debug("No handler claimed update {} ({})", update.getUpdateId(), describe(update));
            return;
        }

        try
        {
            context.telegramClient().execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(update.getCallbackQuery().getId())
                    .build());
        }
        catch (Exception e)
        {
            LOGGER.debug("Failed to acknowledge unclaimed callback query on update {}", update.getUpdateId(), e);
        }
    }

    /**
     * Returns a short description of an update for diagnostic logging.
     *
     * @param update the update to describe
     * @return the description
     */
    private static String describe(Update update)
    {
        UpdateType type = UpdateType.of(update);
        StringBuilder description = new StringBuilder(type != null ? type.name().toLowerCase() : "unknown");

        if (update.hasCallbackQuery())
        {
            description.append(" data=").append(update.getCallbackQuery().getData());
        }
        else if (update.hasMessage() && update.getMessage().getText() != null)
        {
            String text = update.getMessage().getText();
            description.append(" text=").append(text.length() > 32 ? text.substring(0, 32) + "..." : text);
        }

        return description.toString();
    }
}
