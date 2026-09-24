package net.nosial.spb.objects;

/**
 * Cached snapshot of a chat administrator's identity and moderation permissions.
 *
 * <p>Stored alongside the administrator list to drive per-moderator action buttons in report
 * notifications without additional Telegram API calls.
 *
 * @param id the Telegram user id
 * @param isOwner whether the administrator is the chat owner
 * @param canDeleteMessages whether the administrator can delete messages
 * @param canRestrictMembers whether the administrator can restrict or ban members
 */
public record AdminInfo(long id, boolean isOwner, boolean canDeleteMessages, boolean canRestrictMembers)
{
}
