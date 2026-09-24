package net.nosial.spb.testhandlers;

import net.nosial.spb.classes.Handler;
import net.nosial.spb.classes.UpdateHandler;
import net.nosial.spb.enums.UpdateType;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.support.Recorder;

/** Would outrank every other handler, but is switched off and must never run. */
@UpdateHandler(value = UpdateType.ANY, priority = 10_000, enabled = false)
public final class DisabledHandler extends Handler
{
    @Override
    public void handle(HandlerContext context)
    {
        Recorder.record("disabled", context);
    }
}
