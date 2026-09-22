package net.nosial.spb.classes.interfaces;

import net.nosial.jfederation.records.ReportRecord;
import net.nosial.spb.objects.context.OperatorReportContext;

public interface NotificationSink
{
    /**
     * Sends a notification based on the provided report to a specific Telegram user within the
     * context of an operator-bound session.
     *
     * @param telegramUserId the Telegram user ID of the recipient
     * @param report the report to be sent in the notification
     * @param session the operator-bound context associated with the notification
     * @throws Exception if an error occurs during the sending process
     */
    void send(long telegramUserId, ReportRecord report, OperatorReportContext session) throws Exception;
}
