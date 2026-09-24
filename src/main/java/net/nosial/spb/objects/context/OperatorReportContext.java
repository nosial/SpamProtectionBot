package net.nosial.spb.objects.context;

import net.nosial.spb.classes.interfaces.Session;
import net.nosial.spb.objects.database.OperatorIdentity;

/**
 * A memory-only, operator-bound action session for a report notification.
 *
 * <p>The access token snapshot is intentionally retained only in process memory for the session's
 * eight-hour lifetime. It lets a callback close the report as the operator who received it without
 * mutating the process-wide Federation client.
 *
 * @param hash opaque callback-session identifier
 * @param operatorTelegramUserId Telegram user permitted to act on this notification
 * @param operatorIdentity Federation credentials snapshot for the operator
 * @param reportUuid Federation report identifier to close
 * @param createdAt epoch milliseconds when the notification action session was created
 */
public record OperatorReportContext(
        String hash,
        long operatorTelegramUserId,
        OperatorIdentity operatorIdentity,
        String reportUuid,
        long createdAt)
        implements Session
{
}
