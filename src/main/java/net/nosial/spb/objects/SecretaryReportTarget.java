package net.nosial.spb.objects;

import java.util.List;
import java.util.Map;

/**
 * A first-contact business message the secretary owner was notified about and may still report.
 *
 * <p>Telegram offers no way to fetch a business message again later, so everything a report needs
 * is captured when the notification is sent.
 *
 * @param ownerId the secretary owner, the only user allowed to report it
 * @param contactId the Telegram user id of whoever sent the message
 * @param messageId the business message id
 * @param text the text or caption of the message, or {@code null}
 * @param attachments downloadable files attached to the message
 * @param metadata every property of the message, flattened as Federation metadata
 */
public record SecretaryReportTarget(
        long ownerId,
        long contactId,
        long messageId,
        String text,
        List<ReportAttachment> attachments,
        Map<String, Object> metadata)
{
}
