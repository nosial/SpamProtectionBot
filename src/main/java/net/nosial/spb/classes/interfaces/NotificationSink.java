package net.nosial.spb.classes.interfaces;

import net.nosial.jfederation.records.ReportRecord;

public interface NotificationSink
{
    /**
     * Sends a notification based on the provided report to a specific Telegram user.
     *
     * @param telegramUserId the Telegram user ID of the recipient
     * @param report the report to be sent in the notification
     * @throws Exception if an error occurs during the sending process
     */
    void send(long telegramUserId, ReportRecord report) throws Exception;
}
