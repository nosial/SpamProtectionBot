package net.nosial.spb.classes;

import net.nosial.spb.enums.DispatchMode;
import net.nosial.spb.enums.UpdateType;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.support.Contexts;
import net.nosial.spb.support.Updates;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for concurrent update dispatch, handler isolation, and graceful shutdown.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class UpdateDispatcherTest
{
    /**
     * Creates a dispatcher over the given handlers.
     *
     * @param workerThreads the worker thread count
     * @param queueCapacity the queue capacity
     * @param handlers the handlers to dispatch to
     * @return the dispatcher
     */
    private static UpdateDispatcher dispatcher(int workerThreads, int queueCapacity, Handler... handlers)
    {
        return new UpdateDispatcher(new HandlerRegistry(handlers), Contexts.template(), workerThreads, queueCapacity);
    }

    @Nested
    @DisplayName("Dispatching updates")
    class Dispatching
    {
        @Test
        @DisplayName("every submitted update reaches a handler")
        void dispatchesEveryUpdate()
        {
            CountingHandler counter = new CountingHandler();

            try (UpdateDispatcher dispatcher = dispatcher(4, 512, counter))
            {
                for (int i = 1; i <= 200; i++)
                {
                    assertTrue(dispatcher.submit(Updates.privateMessage(i, "message " + i)));
                }

                dispatcher.drain(Duration.ofSeconds(10));
            }

            assertEquals(200, counter.count.get());
        }

        @Test
        @DisplayName("a batch from the poller is dispatched in full")
        void dispatchesBatches()
        {
            CountingHandler counter = new CountingHandler();

            try (UpdateDispatcher dispatcher = dispatcher(2, 64, counter))
            {
                dispatcher.consume(List.of(
                        Updates.privateMessage(1, "one"),
                        Updates.privateMessage(2, "two"),
                        Updates.privateMessage(3, "three")));

                dispatcher.drain(Duration.ofSeconds(10));
            }

            assertEquals(3, counter.count.get());
        }

        @Test
        @DisplayName("observers run before the routed handler")
        void observersRunFirst()
        {
            RecordingObserver observer = new RecordingObserver();
            RecordingRouted routed = new RecordingRouted();

            try (UpdateDispatcher dispatcher = dispatcher(1, 16, routed, observer))
            {
                dispatcher.submit(Updates.privateMessage(1, "hello"));
                dispatcher.drain(Duration.ofSeconds(10));
            }

            assertEquals(List.of("observer", "routed"), RecordingObserver.ORDER);
        }

        @Test
        @DisplayName("only the first matching routed handler runs")
        void onlyOneRoutedHandlerRuns()
        {
            CountingHandler first = new CountingHandler();
            SecondCountingHandler second = new SecondCountingHandler();

            try (UpdateDispatcher dispatcher = dispatcher(1, 16, first, second))
            {
                dispatcher.submit(Updates.privateMessage(1, "hello"));
                dispatcher.drain(Duration.ofSeconds(10));
            }

            assertEquals(1, first.count.get() + second.count.get());
        }

        @Test
        @DisplayName("an update no handler claims is still counted as processed")
        void unclaimedUpdatesAreProcessed()
        {
            try (UpdateDispatcher dispatcher = dispatcher(1, 16, new CommandOnlyHandler()))
            {
                dispatcher.submit(Updates.poll(1));
                dispatcher.drain(Duration.ofSeconds(10));

                assertEquals(1, dispatcher.processedUpdates());
            }
        }

        @Test
        @DisplayName("a failing handler does not stop the others")
        void failureIsIsolated()
        {
            CountingHandler counter = new CountingHandler();

            try (UpdateDispatcher dispatcher = dispatcher(1, 16, counter, new FailingObserver()))
            {
                dispatcher.submit(Updates.privateMessage(1, "one"));
                dispatcher.submit(Updates.privateMessage(2, "two"));
                dispatcher.drain(Duration.ofSeconds(10));

                assertEquals(2, counter.count.get());
                assertEquals(2, dispatcher.processedUpdates());
            }
        }

        @Test
        @DisplayName("a null update is ignored")
        void ignoresNullUpdates()
        {
            try (UpdateDispatcher dispatcher = dispatcher(1, 16, new CountingHandler()))
            {
                assertFalse(dispatcher.submit(null));
                dispatcher.consume(null);

                assertEquals(0, dispatcher.processedUpdates());
            }
        }
    }

    @Nested
    @DisplayName("Processing concurrently")
    class Concurrency
    {
        @Test
        @DisplayName("updates are processed on several worker threads at once")
        void usesEveryWorkerThread() throws Exception
        {
            int workers = 4;
            CountDownLatch arrived = new CountDownLatch(workers);
            BlockingHandler handler = new BlockingHandler(arrived);

            try (UpdateDispatcher dispatcher = dispatcher(workers, 64, handler))
            {
                for (int i = 1; i <= workers; i++)
                {
                    dispatcher.submit(Updates.privateMessage(i, "concurrent " + i));
                }

                // Every update blocks until all of them are in flight, so the latch can only reach
                // zero when the dispatcher really is running them in parallel.
                assertTrue(arrived.await(10, TimeUnit.SECONDS),
                        "expected " + workers + " updates in flight at once");
                handler.release();
                dispatcher.drain(Duration.ofSeconds(10));
            }

            assertEquals(workers, handler.threads.size());
        }

        @Test
        @DisplayName("handlers see the update bound to their own context")
        void bindsEachUpdateToItsOwnContext()
        {
            CollectingHandler handler = new CollectingHandler();

            try (UpdateDispatcher dispatcher = dispatcher(4, 256, handler))
            {
                for (int i = 1; i <= 100; i++)
                {
                    dispatcher.submit(Updates.privateMessage(i, "/cmd" + i));
                }

                dispatcher.drain(Duration.ofSeconds(10));
            }

            assertEquals(100, handler.seen.size());
            for (String entry : handler.seen)
            {
                String[] parts = entry.split("/cmd");
                assertEquals(parts[0], parts[1], "update id and payload must come from the same update");
            }
        }
    }

    @Nested
    @DisplayName("Shutting down")
    class Shutdown
    {
        @Test
        @DisplayName("queued updates are finished rather than discarded")
        void drainsQueuedUpdates()
        {
            SlowHandler handler = new SlowHandler();

            UpdateDispatcher dispatcher = dispatcher(2, 256, handler);
            for (int i = 1; i <= 50; i++)
            {
                dispatcher.submit(Updates.privateMessage(i, "queued " + i));
            }

            assertTrue(dispatcher.drain(Duration.ofSeconds(20)));
            assertEquals(50, handler.count.get());
            assertEquals(50, dispatcher.processedUpdates());
        }

        @Test
        @DisplayName("no update is accepted once shutdown has begun")
        void refusesUpdatesAfterShutdown()
        {
            CountingHandler counter = new CountingHandler();
            UpdateDispatcher dispatcher = dispatcher(1, 16, counter);

            dispatcher.close();

            assertFalse(dispatcher.isAccepting());
            assertFalse(dispatcher.submit(Updates.privateMessage(1, "late")));
            assertEquals(1, dispatcher.droppedUpdates());
            assertEquals(0, counter.count.get());
        }

        @Test
        @DisplayName("closing twice is harmless")
        void closeIsIdempotent()
        {
            UpdateDispatcher dispatcher = dispatcher(1, 16, new CountingHandler());

            dispatcher.close();
            dispatcher.close();

            assertFalse(dispatcher.isAccepting());
        }

        @Test
        @DisplayName("updates beyond the queue capacity are dropped, not queued without limit")
        void dropsUpdatesWhenTheQueueIsFull() throws Exception
        {
            CountDownLatch arrived = new CountDownLatch(1);
            BlockingHandler handler = new BlockingHandler(arrived);

            UpdateDispatcher dispatcher = dispatcher(1, 1, handler);
            try
            {
                dispatcher.submit(Updates.privateMessage(1, "in flight"));
                assertTrue(arrived.await(10, TimeUnit.SECONDS));

                dispatcher.submit(Updates.privateMessage(2, "queued"));

                assertFalse(dispatcher.submit(Updates.privateMessage(3, "dropped")));
                assertEquals(1, dispatcher.droppedUpdates());
            }
            finally
            {
                handler.release();
                dispatcher.close();
            }
        }
    }

    @Nested
    @DisplayName("Validating its own configuration")
    class Validation
    {
        @Test
        @DisplayName("a non-positive worker count is rejected")
        void rejectsNonPositiveWorkerThreads()
        {
            assertThrows(IllegalArgumentException.class, () -> dispatcher(0, 16, new CountingHandler()));
        }

        @Test
        @DisplayName("a non-positive queue capacity is rejected")
        void rejectsNonPositiveQueueCapacity()
        {
            assertThrows(IllegalArgumentException.class, () -> dispatcher(1, 0, new CountingHandler()));
        }
    }

    /** Counts the messages it handles. */
    @UpdateHandler(UpdateType.MESSAGE)
    static final class CountingHandler extends Handler
    {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        public void handle(HandlerContext context)
        {
            this.count.incrementAndGet();
        }
    }

    /** A second message handler, to prove only one routed handler runs. */
    @UpdateHandler(value = UpdateType.MESSAGE, priority = -1)
    static final class SecondCountingHandler extends Handler
    {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        public void handle(HandlerContext context)
        {
            this.count.incrementAndGet();
        }
    }

    /** Serves commands only, so other updates go unclaimed. */
    @UpdateHandler(UpdateType.COMMAND)
    static final class CommandOnlyHandler extends Handler
    {
        @Override
        public void handle(HandlerContext context)
        {
        }
    }

    /** Records that it ran before the routed handler. */
    @UpdateHandler(value = UpdateType.ANY, mode = DispatchMode.OBSERVE)
    static final class RecordingObserver extends Handler
    {
        private static final List<String> ORDER = new CopyOnWriteArrayList<>();

        @Override
        public void handle(HandlerContext context)
        {
            ORDER.add("observer");
        }
    }

    /** Records that it ran after the observers. */
    @UpdateHandler(UpdateType.MESSAGE)
    static final class RecordingRouted extends Handler
    {
        @Override
        public void handle(HandlerContext context)
        {
            RecordingObserver.ORDER.add("routed");
        }
    }

    /** Always throws, to prove failures stay contained. */
    @UpdateHandler(value = UpdateType.ANY, mode = DispatchMode.OBSERVE)
    static final class FailingObserver extends Handler
    {
        @Override
        public void handle(HandlerContext context)
        {
            throw new IllegalStateException("deliberate failure");
        }
    }

    /** Blocks every update until released, recording the threads it ran on. */
    @UpdateHandler(UpdateType.MESSAGE)
    static final class BlockingHandler extends Handler
    {
        private final Set<String> threads = ConcurrentHashMap.newKeySet();
        private final CountDownLatch arrived;
        private final CountDownLatch released = new CountDownLatch(1);

        BlockingHandler(CountDownLatch arrived)
        {
            this.arrived = arrived;
        }

        @Override
        public void handle(HandlerContext context)
        {
            this.threads.add(Thread.currentThread().getName());
            this.arrived.countDown();

            try
            {
                this.released.await(20, TimeUnit.SECONDS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        }

        /** Lets every blocked handler return. */
        void release()
        {
            this.released.countDown();
        }
    }

    /** Takes a moment over each update, so a shutdown has something left to drain. */
    @UpdateHandler(UpdateType.MESSAGE)
    static final class SlowHandler extends Handler
    {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        public void handle(HandlerContext context)
        {
            try
            {
                Thread.sleep(5);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return;
            }

            this.count.incrementAndGet();
        }
    }

    /** Records the update id and payload it was given, to prove contexts are not shared. */
    @UpdateHandler(UpdateType.COMMAND)
    static final class CollectingHandler extends Handler
    {
        private final List<String> seen = new CopyOnWriteArrayList<>();

        @Override
        public void handle(HandlerContext context)
        {
            this.seen.add(context.update().getUpdateId() + "/cmd" + context.commandName().substring("cmd".length()));
        }
    }
}
