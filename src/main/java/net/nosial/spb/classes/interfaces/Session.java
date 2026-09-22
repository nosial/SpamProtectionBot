package net.nosial.spb.classes.interfaces;

import net.nosial.spb.classes.sessions.AbstractSessionManager;

public interface Session
{
    /**
     * Returns the opaque session hash.
     *
     * @return the hash
     */
    String hash();
}
