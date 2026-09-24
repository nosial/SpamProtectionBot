package net.nosial.spb.objects.database;

import net.nosial.spb.classes.managers.UserManager;

/**
 * A lightweight snapshot of a Telegram user identity.
 *
 * <p>Used by {@link UserManager} to map Telegram user ids to
 * their current username and back again.
 *
 * @param userId the Telegram user id
 * @param username the Telegram username without the leading '@', or {@code null} when private
 * @param firstName the user's first name, or {@code null}
 * @param lastName the user's last name, or {@code null}
 */
public record UserIdentity(long userId, String username, String firstName, String lastName)
{
}