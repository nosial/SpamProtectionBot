package net.nosial.spb.classes.notifications;

import net.nosial.spb.classes.LanguageManager;
import net.nosial.spb.classes.managers.UserManager;
import net.nosial.spb.objects.Language;
import net.nosial.spb.objects.database.UserIdentity;
import net.nosial.spb.utilities.HtmlEscape;
import org.telegram.telegrambots.meta.api.objects.User;

/**
 * Content-building utilities for moderation notifications.
 *
 * <p>Provides reusable methods for rendering user mentions, chat references and bounded,
 * HTML-escaped detail text. Delivery is handled by {@link NotificationSender}.
 *
 * <p>All methods in this class are stateless and thread-safe.
 */
public final class NotificationFormatter
{
    /** Maximum number of code points of untrusted detail text embedded in a notification. */
    public static final int MAX_DETAIL_LENGTH = 1_500;

    private static final int MAX_NAME_LENGTH = 256;
    private static volatile LanguageManager languageManager;

    /**
     * Sets the LanguageManager used to localize notification text. Must be called once during
     * startup, before any notification is rendered.
     *
     * @param languageManager the LanguageManager instance to use
     * @throws IllegalArgumentException if {@code languageManager} is {@code null}
     */
    public static void setLanguageManager(LanguageManager languageManager)
    {
        if (languageManager == null)
        {
            throw new IllegalArgumentException("languageManager must not be null");
        }

        NotificationFormatter.languageManager = languageManager;
    }

    /**
     * Retrieves the current instance of the LanguageManager.
     *
     * @return the singleton instance of LanguageManager being used, or {@code null} if not set
     */
    public static LanguageManager languageManager()
    {
        return languageManager;
    }

    /**
     * Renders an inline Telegram mention for a user.
     *
     * <p>When the user has a username the visible text is {@code @username}, which Telegram renders
     * as a clickable link to the user's profile. When no username is set the display name
     * ({@code Firstname} or {@code Firstname Lastname}) is wrapped in a {@code tg://user?id=}
     * deep-link so tapping it still navigates to the profile.
     *
     * @param user Telegram user associated with the notification
     * @return HTML-safe inline mention, or {@code Unknown user} when unavailable
     */
    public static String userMention(User user, Language lang)
    {
        if (user == null)
        {
            return languageManager.get(lang, "chat_notification", "unknown_user");
        }

        return userMention(user.getId(), user.getUserName(), user.getFirstName(), user.getLastName(), lang);
    }

    /**
     * Renders an inline Telegram mention for a known user id without a profile snapshot.
     *
     * @param userId Telegram user id
     * @return HTML-safe inline mention
     */
    public static String userMention(long userId, Language lang)
    {
        return userMention(userId, null, null, null, lang);
    }

    /**
     * Renders an inline Telegram mention for a user by resolving their identity from the database.
     *
     * <p>Renders like {@link #userMention(User, Language)}, falling back to the bare user id when
     * the identity is not found or the manager is {@code null}.
     *
     * @param userId Telegram user id
     * @param users the user identity manager, or {@code null}
     * @return HTML-safe inline mention
     */
    public static String userMention(long userId, UserManager users, Language lang)
    {
        UserIdentity identity = users != null ? users.getUser(userId).orElse(null) : null;
        if (identity == null)
        {
            return userMention(userId, lang);
        }
        return userMention(userId, identity.username(), identity.firstName(), identity.lastName(), lang);
    }

    /**
     * Renders a protected-chat label without relying on a public username.
     *
     * @param chatName cached chat title, or {@code null}
     * @param chatId Telegram chat id
     * @return HTML-safe chat reference
     */
    public static String chatReference(String chatName, long chatId, Language lang)
    {
        String name = chatName == null || chatName.isBlank() ? languageManager.get(lang, "chat_notification", "chat_id_fallback", chatId) : chatName;
        return escapeAndTruncate(name, MAX_NAME_LENGTH, lang) + " (<code>" + chatId + "</code>)";
    }

    /**
     * Escapes user-provided notification detail and bounds its rendered size.
     *
     * @param value untrusted detail
     * @param maximumLength maximum number of Unicode code points before escaping
     * @return HTML-safe detail
     */
    public static String escapeAndTruncate(String value, int maximumLength, Language lang)
    {
        if (value == null || value.isBlank())
        {
            return languageManager.get(lang, "chat_notification", "no_text");
        }
        int codePoints = value.codePointCount(0, value.length());
        int end = value.offsetByCodePoints(0, Math.min(codePoints, maximumLength));
        String suffix = codePoints > maximumLength ? "…" : "";
        return HtmlEscape.escape(value.substring(0, end)) + suffix;
    }

    /**
     * Renders {@code @username} when one is set, otherwise the display name wrapped in a
     * {@code tg://user?id=} deep-link, falling back to the user id when no name is known.
     */
    private static String userMention(long userId, String username, String firstName, String lastName,
                                      Language lang)
    {
        if (username != null && !username.isBlank())
        {
            return "@" + escapeAndTruncate(username, MAX_NAME_LENGTH, lang);
        }

        StringBuilder name = new StringBuilder();
        if (firstName != null && !firstName.isBlank())
        {
            name.append(firstName);
        }
        if (lastName != null && !lastName.isBlank())
        {
            if (!name.isEmpty())
            {
                name.append(' ');
            }
            name.append(lastName);
        }
        if (name.isEmpty())
        {
            name.append(languageManager.get(lang, "chat_notification", "user_id_fallback", userId));
        }

        return "<a href=\"tg://user?id=" + userId + "\">"
                + escapeAndTruncate(name.toString(), MAX_NAME_LENGTH, languageManager.defaultLanguage())
                + "</a> (<code>" + userId + "</code>)";
    }
}
