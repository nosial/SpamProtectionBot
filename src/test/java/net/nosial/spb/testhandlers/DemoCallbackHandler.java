package net.nosial.spb.testhandlers;

import net.nosial.spb.classes.Handler;
import net.nosial.spb.classes.UpdateHandler;
import net.nosial.spb.enums.UpdateType;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.support.Recorder;

/** Owns the {@code demo:} callback data namespace. */
@UpdateHandler(value = UpdateType.CALLBACK_QUERY, callbackData = "demo:")
public final class DemoCallbackHandler extends Handler
{
    @Override
    public void handle(HandlerContext context)
    {
        Recorder.record("demo-callback", context);
    }
}
