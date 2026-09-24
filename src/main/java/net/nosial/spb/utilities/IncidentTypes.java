package net.nosial.spb.utilities;

import net.nosial.jfederation.enums.IncidentType;

import java.util.Locale;
import java.util.Map;

/**
 * Turns what a user typed into an {@link IncidentType}.
 *
 * <p>Both {@code /report} and {@code /blacklist} take an incident type as a word from the user,
 * and both accept the same shorthands, so the mapping lives here rather than in whichever handler
 * happened to need it first.
 */
public final class IncidentTypes
{
    private static final Map<String, IncidentType> ALIASES = Map.of(
            "illegal", IncidentType.ILLEGAL_CONTENT,
            "service", IncidentType.SERVICE_ABUSE,
            "abuse", IncidentType.SERVICE_ABUSE,
            "fraud", IncidentType.SCAM,
            "malicious", IncidentType.MALWARE,
            "phish", IncidentType.PHISHING);

    /**
     * Parses a single token as an {@link IncidentType}, accepting case-insensitive enum names,
     * hyphenated enum names, and documented short aliases.
     *
     * @param token the token to parse
     * @return the incident type, or {@code null} when the token does not match
     */
    public static IncidentType parse(String token)
    {
        if (token == null || token.isBlank())
        {
            return null;
        }
        String normalized = token.toLowerCase(Locale.ROOT).replace('-', '_');
        for (IncidentType type : IncidentType.values())
        {
            if (type.name().equalsIgnoreCase(normalized))
            {
                return type;
            }
        }
        return ALIASES.get(normalized);
    }
}
