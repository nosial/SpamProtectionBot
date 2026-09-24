package net.nosial.spb.testhandlers;

import net.nosial.spb.classes.Handler;
import net.nosial.spb.classes.UpdateHandler;
import net.nosial.spb.enums.UpdateType;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.support.Recorder;

/** Claims {@code /ping} and {@code /PING}, exercising case-insensitive command matching. */
@UpdateHandler(value = UpdateType.COMMAND, commands = {"ping", "PING"})
public final class PingHandler extends Handler
{
    @Override
    public void handle(HandlerContext context)
    {
        Recorder.record("ping", context);
    }
}
