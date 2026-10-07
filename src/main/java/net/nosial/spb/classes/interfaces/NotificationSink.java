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

    /**
     * Tells an operator that their stored credential was rejected by the Federation server and
     * has been removed.
     *
     * @param telegramUserId the Telegram user ID of the recipient
     * @throws Exception if an error occurs during the sending process
     */
    void sendCredentialRevoked(long telegramUserId) throws Exception;
}
