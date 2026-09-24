package net.nosial.spb.testhandlers;

import net.nosial.spb.classes.Handler;
import net.nosial.spb.classes.UpdateHandler;
import net.nosial.spb.enums.UpdateType;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.support.Recorder;

/** Last in line for any message no other handler claimed. */
@UpdateHandler(value = UpdateType.MESSAGE, priority = -100)
public final class CatchAllMessageHandler extends Handler
{
    @Override
    public void handle(HandlerContext context)
    {
        Recorder.record("catch-all", context);
    }
}
