package net.nosial.spb.objects;

/**
 * Cached snapshot of a chat administrator's identity and permissions.
 *
 * <p>Every administrator of a chat is cached, whatever their rights, so each caller checks the
 * permission it actually needs: {@link #canChangeInfo()} for opening the settings menu,
 * {@link #isModerator()} for moderation, and the individual flags for per-moderator action
 * buttons in report notifications, all without additional Telegram API calls.
 *
 * @param id the Telegram user id
 * @param isOwner whether the administrator is the chat owner
 * @param canDeleteMessages whether the administrator can delete messages
 * @param canRestrictMembers whether the administrator can restrict or ban members
 * @param canChangeInfo whether the administrator can change the chat's information
 */
public record AdminInfo(long id, boolean isOwner, boolean canDeleteMessages, boolean canRestrictMembers,
                        boolean canChangeInfo)
{
    /**
     * Returns whether this administrator can moderate the chat, that is delete messages or
     * restrict members. The owner always can.
     *
     * @return {@code true} for the owner and for administrators with a moderation permission
     */
    public boolean isModerator()
    {
        return this.isOwner || this.canDeleteMessages || this.canRestrictMembers;
    }
}
