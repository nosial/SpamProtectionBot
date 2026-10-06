package net.nosial.spb.classes.sessions;

import net.nosial.spb.classes.Cache;
import net.nosial.spb.objects.SecretaryReportTarget;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Remembers the first-contact messages that a secretary notification offers to report.
 *
 * <p>Callback data is limited to 64 bytes, far too little for the message itself, so the Report
 * button carries only an opaque token resolved here. The entries live in memory: once one expires
 * or the bot restarts, the button stops working and the owner is told to forward the message
 * instead.
 */
public final class SecretaryReportStore
{
    private static final int CACHE_MAX_SIZE = 10_000;
    private static final long EXPIRY_DAYS = 7;
    private static final int TOKEN_BYTES = 12;

    private final Cache<String, SecretaryReportTarget> targets;
    private final SecureRandom random;

    public SecretaryReportStore()
    {
        this.targets = Cache.create(CACHE_MAX_SIZE, EXPIRY_DAYS, TimeUnit.DAYS);
        this.random = new SecureRandom();
    }

    /**
     * Stores a reportable message and returns the token that refers to it.
     *
     * @param target the message the owner may report
     * @return the opaque, URL-safe token
     */
    public String store(SecretaryReportTarget target)
    {
        byte[] bytes = new byte[TOKEN_BYTES];
        this.random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        this.targets.put(token, target);
        return token;
    }

    /**
     * Returns the message the token refers to when it belongs to the given owner.
     *
     * @param token the token carried by the Report button
     * @param ownerId the user who pressed the button
     * @return the message, or {@code null} when it expired or belongs to somebody else
     */
    public SecretaryReportTarget find(String token, long ownerId)
    {
        SecretaryReportTarget target = this.targets.getIfPresent(token);
        return target != null && target.ownerId() == ownerId ? target : null;
    }
}
