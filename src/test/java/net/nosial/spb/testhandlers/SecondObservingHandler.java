package net.nosial.spb.testhandlers;

import net.nosial.spb.classes.Handler;
import net.nosial.spb.classes.UpdateHandler;
import net.nosial.spb.enums.DispatchMode;
import net.nosial.spb.enums.UpdateType;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.support.Recorder;

/** A second observer, ordered after {@link ObservingHandler} by its lower priority. */
@UpdateHandler(value = UpdateType.ANY, mode = DispatchMode.OBSERVE, priority = 5)
public final class SecondObservingHandler extends Handler
{
    @Override
    public void handle(HandlerContext context)
    {
        Recorder.record("observer-second", context);
    }
}
