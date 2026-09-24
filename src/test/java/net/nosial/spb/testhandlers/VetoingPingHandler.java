package net.nosial.spb.testhandlers;

import net.nosial.spb.classes.Handler;
import net.nosial.spb.classes.UpdateHandler;
import net.nosial.spb.enums.UpdateType;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.support.Recorder;

/**
 * Also claims {@code /ping}, ahead of {@link PingHandler}, but only takes the ones carrying the
 * payload {@code mine} — the rest fall through to the next handler.
 */
@UpdateHandler(value = UpdateType.COMMAND, commands = "ping", priority = 100)
public final class VetoingPingHandler extends Handler
{
    @Override
    public boolean accepts(HandlerContext context)
    {
        return "mine".equals(context.commandPayload());
    }

    @Override
    public void handle(HandlerContext context)
    {
        Recorder.record("veto-ping", context);
    }
}
