package net.nosial.spb.testhandlers;

import net.nosial.spb.classes.Handler;
import net.nosial.spb.classes.UpdateHandler;
import net.nosial.spb.enums.DispatchMode;
import net.nosial.spb.enums.UpdateType;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.support.Recorder;

/** An observer that sees every update and consumes none. */
@UpdateHandler(value = UpdateType.ANY, mode = DispatchMode.OBSERVE, priority = 10)
public final class ObservingHandler extends Handler
{
    @Override
    public void handle(HandlerContext context)
    {
        Recorder.record("observer-first", context);
    }
}
