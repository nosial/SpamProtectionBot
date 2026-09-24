package net.nosial.spb.testhandlers;

import net.nosial.spb.classes.Handler;
import net.nosial.spb.classes.UpdateHandler;
import net.nosial.spb.enums.DispatchMode;
import net.nosial.spb.enums.UpdateType;
import net.nosial.spb.objects.context.HandlerContext;

/** An observer that always fails, proving one bad handler cannot stop the rest. */
@UpdateHandler(value = UpdateType.ANY, mode = DispatchMode.OBSERVE, priority = 1000)
public final class FailingObservingHandler extends Handler
{
    @Override
    public void handle(HandlerContext context)
    {
        throw new IllegalStateException("this observer always fails");
    }
}
