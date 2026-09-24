package net.nosial.spb.objects.database;

import net.nosial.spb.enums.SecretaryContactStatus;

/**
 * A person who wrote a message to a secretary-enabled business connection.
 *
 * @param businessConnectionId the business connection the contact wrote to
 * @param id the Telegram user id of the contact (references users.id)
 * @param status the tracking status of the contact
 * @param firstSeenAt the unix time (seconds) the contact was first observed
 */
public record SecretaryContact(
        String businessConnectionId,
        long id,
        SecretaryContactStatus status,
        long firstSeenAt)
{
}
