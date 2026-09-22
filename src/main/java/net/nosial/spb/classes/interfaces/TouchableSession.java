package net.nosial.spb.classes.interfaces;

/**
 * A session whose expiry is refreshed on every access.
 *
 * <p>Sessions implementing this interface are re-inserted into the cache after each lookup,
 * pushing the write-expiry forward. This is appropriate for interactive sessions (e.g. report
 * dialogs, configuration menus) where the session should remain alive as long as the user is
 * actively engaged.
 */
public interface TouchableSession extends Session
{
    /**
     * Returns a copy of this session with the last-used timestamp refreshed to the current time.
     *
     * @return the touched session
     */
    Session touch();
}
