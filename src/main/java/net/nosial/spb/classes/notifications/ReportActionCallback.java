package net.nosial.spb.classes.notifications;

import net.nosial.spb.objects.AdminInfo;

import java.util.function.Predicate;

/**
 * A moderation button on a report notification: the reported message and the action to take.
 *
 * <p>Everything the action needs travels in the callback data, as
 * {@code rpa:<chat_id>:<message_id>:<author_id>:<action>}, so the buttons keep working for as long
 * as the reported message exists and survive bot restarts. Even with the largest ids Telegram
 * issues the data stays under Telegram's 64-byte callback data limit.
 *
 * @param chatId the protected chat the reported message lives in
 * @param messageId the reported message
 * @param authorId the author of the reported message
 * @param action the moderation action to take
 */
public record ReportActionCallback(long chatId, int messageId, long authorId, Action action)
{
    /** Namespace of the current, stateless moderation buttons. */
    public static final String PREFIX = "rpa";

    /**
     * Namespace of the buttons sent before they became stateless, which referenced state that has
     * since expired. They are still claimed, so pressing one gets an answer.
     */
    public static final String LEGACY_PREFIX = "report_action";

    /**
     * The moderation actions offered on a report notification, with the administrator right each
     * one needs. The same right is required of the moderator pressing the button and of the bot,
     * which performs the action.
     */
    public enum Action
    {
        DELETE("delete", AdminInfo::canDeleteMessages),
        MUTE("mute", AdminInfo::canRestrictMembers),
        BAN("ban", AdminInfo::canRestrictMembers);

        private final String code;
        private final Predicate<AdminInfo> permission;

        Action(String code, Predicate<AdminInfo> permission)
        {
            this.code = code;
            this.permission = permission;
        }

        /**
         * Returns whether the given administrator holds the right this action needs.
         *
         * @param admin the administrator, or {@code null} when unknown
         * @return {@code true} when the administrator may perform this action
         */
        public boolean permits(AdminInfo admin)
        {
            return admin != null && this.permission.test(admin);
        }

        /**
         * Resolves an action from its callback code.
         *
         * @param code the code carried in the callback data
         * @return the action, or {@code null} when the code is unknown
         */
        static Action fromCode(String code)
        {
            for (Action action : values())
            {
                if (action.code.equals(code))
                {
                    return action;
                }
            }
            return null;
        }
    }

    /**
     * Builds the callback data for this button.
     *
     * @return the callback data
     */
    public String data()
    {
        return PREFIX + ":" + this.chatId + ":" + this.messageId + ":" + this.authorId + ":" + this.action.code;
    }

    /**
     * Parses callback data produced by {@link #data()}.
     *
     * @param data the callback data
     * @return the parsed button, or {@code null} when the data is not a current moderation button
     */
    public static ReportActionCallback parse(String data)
    {
        if (data == null)
        {
            return null;
        }
        String[] parts = data.split(":");
        if (parts.length != 5 || !PREFIX.equals(parts[0]))
        {
            return null;
        }

        Action action = Action.fromCode(parts[4]);
        if (action == null)
        {
            return null;
        }
        try
        {
            return new ReportActionCallback(Long.parseLong(parts[1]), Integer.parseInt(parts[2]), Long.parseLong(parts[3]), action);
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }
}
