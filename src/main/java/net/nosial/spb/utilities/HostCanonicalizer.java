package net.nosial.spb.utilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.net.IDN;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns hosts found in untrusted text into canonical Federation entity hosts.
 *
 * <p>The OFD specification requires a host entity to already be in canonical form, and a server
 * hashes the host exactly as sent, so {@code Example.COM} and {@code example.com} would otherwise
 * become two different entities. A canonical host here is one of:
 *
 * <ul>
 *   <li>a domain name of lowercase ASCII labels (internationalized names converted to punycode),
 *       at least two labels long, without a trailing dot, under a delegated top-level domain;</li>
 *   <li>a public IPv4 address as four decimal octets without leading zeros;</li>
 *   <li>a public IPv6 address in its RFC 5952 compressed lowercase form. An IPv4-mapped IPv6
 *       address is reported as the IPv4 address it maps to, since both reach the same host.</li>
 * </ul>
 *
 * <p>Private, loopback, link-local, documentation and otherwise non-routable addresses, and the
 * RFC 2606 {@code example} domains, are rejected: they identify no one in particular, so
 * publishing them would only add noise.
 *
 * <p>Two entry points reflect how much the input can be trusted to be a host at all.
 * {@link #fromUrl(String)} takes something already known to be a link and parses its host the way
 * a browser would (the WHATWG URL rules), including the hexadecimal and octal IPv4 forms a browser
 * accepts, so an obfuscated link such as {@code http://0x7f.1/} resolves to the address it really
 * opens. {@link #fromText(String)} takes a word picked out of free text and accepts only the strict
 * forms, since there a dotted word is as likely to be a file name or a version number as a host.
 *
 * <p>Nothing here performs a DNS lookup. All methods are stateless and thread-safe.
 */
public final class HostCanonicalizer
{
    private static final Logger LOGGER = LoggerFactory.getLogger(HostCanonicalizer.class);

    private static final String TLD_RESOURCE = "/tlds.txt";

    /** IANA's list of delegated top-level domains, fetched at startup to replace the bundled copy. */
    static final URI TLD_LIST_URL = URI.create("https://data.iana.org/TLD/tlds-alpha-by-domain.txt");

    /**
     * The fewest entries a fetched list must have to be used. The root zone holds well over a
     * thousand TLDs, so anything shorter is a truncated or wrong response, never a real list.
     */
    static final int MIN_TLD_COUNT = 1_000;

    private static final Duration TLD_FETCH_TIMEOUT = Duration.ofSeconds(15);
    private static final Pattern TLD_LINE = Pattern.compile("^[A-Za-z0-9-]{1,63}$");
    private static final int MAX_DOMAIN_LENGTH = 253;
    private static final int MAX_LABEL_LENGTH = 63;
    private static final int MAX_PORT = 65_535;

    /** Schemes whose URLs always carry a host and treat {@code \} like {@code /} (WHATWG). */
    private static final Set<String> SPECIAL_SCHEMES = Set.of("http", "https", "ws", "wss", "ftp");

    /** Special-use names under a TLD that is otherwise accepted (RFC 2606). */
    private static final Set<String> RESERVED_DOMAINS = Set.of("example.com", "example.net", "example.org");

    /**
     * Delegated TLDs that are more often file extensions than domains when they appear in plain
     * text ({@code setup.py}, {@code notes.md}, {@code Main.java}). Such a word is only accepted as
     * a domain when it was marked up as a link.
     */
    private static final Set<String> FILE_EXTENSION_TLDS = Set.of("java", "md", "mov", "pl", "pm", "ps", "py", "rs", "sh", "zip");

    private static final Pattern SCHEME = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*):");
    private static final Pattern PORT_THEN_PATH = Pattern.compile("^[0-9]+(?:[/?#].*)?$", Pattern.DOTALL);

    /**
     * The delegated TLDs in use: the bundled snapshot at first, replaced by IANA's current list
     * once {@link #refreshTlds()} succeeds. Held in memory only and swapped as a whole, so readers
     * always see one complete list.
     */
    private static volatile Set<String> tlds = loadBundledTlds();

    /**
     * Returns the canonical host of a link.
     *
     * <p>Accepts a full URL ({@code https://user@Example.com:8443/path}) or a scheme-less link as
     * Telegram detects them ({@code example.com/path}). Links whose scheme has no host, such as
     * {@code mailto:} or {@code tg:} deep links, yield nothing.
     *
     * @param url the link, possibly malformed or {@code null}
     * @return the canonical host, or empty when the link has no acceptable host
     */
    public static Optional<String> fromUrl(String url)
    {
        String host = urlHost(url);
        return host != null ? canonicalHost(host, true, false) : Optional.empty();
    }

    /**
     * Returns the canonical host of a word taken from free text: a domain name, a dotted-decimal
     * IPv4 address, or an IPv6 address, optionally in brackets. Leading-zero IPv4 octets are
     * rejected rather than guessed at, since {@code 010} means 8 to some parsers and 10 to others.
     *
     * @param candidate the word, possibly malformed or {@code null}
     * @return the canonical host, or empty when the word is not an acceptable host
     */
    public static Optional<String> fromText(String candidate)
    {
        if (candidate == null || candidate.isEmpty())
        {
            return Optional.empty();
        }
        return canonicalHost(candidate, false, true);
    }

    /**
     * Returns whether the bundled list of delegated TLDs was loaded. Without it no domain is
     * accepted, although IP addresses still are.
     *
     * @return {@code true} when TLD validation is available
     */
    public static boolean tldsLoaded()
    {
        return !tlds.isEmpty();
    }

    /**
     * Extracts the raw host of a link, following the WHATWG URL rules closely enough to agree with
     * a browser on which host a link opens: user information is skipped up to the last {@code @},
     * a backslash ends the authority of a special URL, and the port is dropped.
     *
     * @param url the link
     * @return the percent-decoded host (bracketed for IPv6), or {@code null} when there is none
     */
    static String urlHost(String url)
    {
        if (url == null)
        {
            return null;
        }

        String input = stripControlCharacters(url);
        if (input.isEmpty())
        {
            return null;
        }

        String rest;
        boolean special;
        Matcher scheme = SCHEME.matcher(input);
        if (scheme.find())
        {
            String name = scheme.group(1).toLowerCase(Locale.ROOT);
            String afterScheme = input.substring(scheme.end());
            if (SPECIAL_SCHEMES.contains(name))
            {
                special = true;
                rest = stripLeadingSlashes(afterScheme);
            }
            else if (afterScheme.startsWith("//"))
            {
                special = false;
                rest = afterScheme.substring(2);
            }
            else if (PORT_THEN_PATH.matcher(afterScheme).matches())
            {
                // "example.com:8080/path" — a scheme-less link with a port, not a scheme.
                special = true;
                rest = input;
            }
            else
            {
                // mailto:, tel:, javascript:, data: and the like have no host.
                return null;
            }
        }
        else
        {
            // A link Telegram detected without a scheme; browsers open these as http.
            special = true;
            rest = input;
        }

        int end = rest.length();
        for (int i = 0; i < rest.length(); i++)
        {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#' || (special && c == '\\'))
            {
                end = i;
                break;
            }
        }

        String authority = rest.substring(0, end);
        String hostAndPort = authority.substring(authority.lastIndexOf('@') + 1);
        if (hostAndPort.isEmpty())
        {
            return null;
        }

        String host;
        String port;
        if (hostAndPort.charAt(0) == '[')
        {
            int close = hostAndPort.indexOf(']');
            if (close < 0)
            {
                return null;
            }
            host = hostAndPort.substring(0, close + 1);
            String after = hostAndPort.substring(close + 1);
            if (!after.isEmpty() && after.charAt(0) != ':')
            {
                return null;
            }
            port = after.isEmpty() ? "" : after.substring(1);
        }
        else
        {
            int colon = hostAndPort.indexOf(':');
            host = colon < 0 ? hostAndPort : hostAndPort.substring(0, colon);
            port = colon < 0 ? "" : hostAndPort.substring(colon + 1);
            host = percentDecode(host);
        }

        if (host == null || host.isEmpty() || !validPort(port))
        {
            return null;
        }
        return host;
    }

    /**
     * Canonicalizes a raw host.
     *
     * @param raw the host, bracketed when it is an IPv6 literal from a URL
     * @param lenientIpv4 whether to accept the WHATWG IPv4 forms (hex, octal, fewer than four parts)
     * @param fromPlainText whether the host was picked out of plain text rather than a link
     * @return the canonical host, or empty when it is not acceptable
     */
    private static Optional<String> canonicalHost(String raw, boolean lenientIpv4, boolean fromPlainText)
    {
        if (raw.startsWith("["))
        {
            if (!raw.endsWith("]") || raw.length() < 3)
            {
                return Optional.empty();
            }
            return canonicalIpv6(raw.substring(1, raw.length() - 1));
        }
        if (raw.indexOf(':') >= 0)
        {
            return canonicalIpv6(raw);
        }

        String ascii = toAscii(raw);
        if (ascii == null)
        {
            return Optional.empty();
        }
        if (ascii.endsWith("."))
        {
            ascii = ascii.substring(0, ascii.length() - 1);
        }
        if (ascii.isEmpty() || ascii.endsWith("."))
        {
            return Optional.empty();
        }

        if (endsInNumber(ascii))
        {
            long address = lenientIpv4 ? parseWhatwgIpv4(ascii) : parseStrictIpv4(ascii);
            return address >= 0 && isPublicIpv4(address) ? Optional.of(formatIpv4(address)) : Optional.empty();
        }
        return canonicalDomain(ascii, fromPlainText);
    }

    /**
     * Validates a lowercase ASCII domain name.
     *
     * @param domain the domain, without a trailing dot
     * @param fromPlainText whether file-extension TLDs must be refused
     * @return the domain, or empty when it is not acceptable
     */
    private static Optional<String> canonicalDomain(String domain, boolean fromPlainText)
    {
        if (domain.length() > MAX_DOMAIN_LENGTH)
        {
            return Optional.empty();
        }

        String[] labels = domain.split("\\.", -1);
        if (labels.length < 2)
        {
            return Optional.empty();
        }
        for (String label : labels)
        {
            if (!validLabel(label))
            {
                return Optional.empty();
            }
        }

        String tld = labels[labels.length - 1];
        if (!tlds.contains(tld) && !tld.equals("onion"))
        {
            return Optional.empty();
        }
        if (fromPlainText && FILE_EXTENSION_TLDS.contains(tld))
        {
            return Optional.empty();
        }
        for (String reserved : RESERVED_DOMAINS)
        {
            if (domain.equals(reserved) || domain.endsWith("." + reserved))
            {
                return Optional.empty();
            }
        }
        return Optional.of(domain);
    }

    /**
     * Validates if the given label adheres to the constraints for a valid label.
     * A valid label must have a length greater than 0 and less than or equal to the
     * maximum allowed length, must not start or end with a hyphen ('-'), and can
     * only contain lowercase letters, digits, and hyphens. Additionally, if the
     * label uses the "xn--" prefix for Punycode, it verifies the accuracy of the
     * encoding and decoding process.
     *
     * @param label the string to validate as a label
     * @return true if the input satisfies the label validation rules, false otherwise
     */
    private static boolean validLabel(String label)
    {
        int length = label.length();
        if (length == 0 || length > MAX_LABEL_LENGTH || label.charAt(0) == '-' || label.charAt(length - 1) == '-')
        {
            return false;
        }
        for (int i = 0; i < length; i++)
        {
            char c = label.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-'))
            {
                return false;
            }
        }
        if (label.startsWith("xn--"))
        {
            try
            {
                String unicode = IDN.toUnicode(label);
                return !unicode.equals(label) && IDN.toASCII(unicode).toLowerCase(Locale.ROOT).equals(label);
            }
            catch (IllegalArgumentException e)
            {
                return false;
            }
        }
        return true;
    }

    /**
     * Converts the given hostname to its ASCII-compatible encoding (ACE).
     * This method uses the {@link IDN} class to perform the conversion and ensures
     * that the resulting string contains only valid ASCII characters.
     *
     * If the input hostname cannot be converted or contains invalid ASCII characters,
     * this method will return null.
     *
     * @param host the hostname to be converted to ASCII. It may include internationalized domain names (IDNs).
     * @return the ASCII-compatible encoding of the hostname, or null if conversion fails or non-ASCII
     *         characters are present in the result.
     */
    private static String toAscii(String host)
    {
        String ascii;
        try
        {
            ascii = IDN.toASCII(host).toLowerCase(Locale.ROOT);
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
        for (int i = 0; i < ascii.length(); i++)
        {
            if (ascii.charAt(i) >= 0x80)
            {
                return null;
            }
        }
        return ascii;
    }

    /**
     * Determines whether the given host string ends with a numeric value.
     * A numeric value can be a sequence of decimal digits or a hexadecimal number
     * prefixed with "0x" or "0X".
     *
     * @param host the string to check; typically a hostname or an IP address.
     * @return {@code true} if the last portion of the string (after the last period)
     *         consists solely of numeric characters or a valid hexadecimal number;
     *         {@code false} otherwise.
     */
    private static boolean endsInNumber(String host)
    {
        String last = host.substring(host.lastIndexOf('.') + 1);
        if (last.isEmpty())
        {
            return false;
        }

        if (last.chars().allMatch(c -> c >= '0' && c <= '9'))
        {
            return true;
        }

        return (last.startsWith("0x") || last.startsWith("0X")) && last.substring(2).chars().allMatch(c -> Character.digit(c, 16) >= 0);
    }

    /**
     * Parses a strictly formatted IPv4 address string and converts it into its numeric representation.
     * The method expects the format "x.x.x.x", where x is a valid decimal number between 0 and 255.
     * Leading zeros in octets are not allowed, and all parts must strictly conform to the IPv4 format.
     *
     * @param host The string representation of an IPv4 address to parse.
     *             Must strictly follow the decimal dot-separated IPv4 address format.
     * @return A long value representing the numeric IPv4 address if the format is valid,
     *         or -1 if the input does not strictly conform to the IPv4 address format.
     */
    static long parseStrictIpv4(String host)
    {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4)
        {
            return -1;
        }
        long address = 0;
        for (String part : parts)
        {
            if (part.isEmpty() || part.length() > 3 || (part.length() > 1 && part.charAt(0) == '0'))
            {
                return -1;
            }
            for (int i = 0; i < part.length(); i++)
            {
                if (part.charAt(i) < '0' || part.charAt(i) > '9')
                {
                    return -1;
                }
            }
            int octet = Integer.parseInt(part);
            if (octet > 255)
            {
                return -1;
            }
            address = (address << 8) | octet;
        }
        return address;
    }

    /**
     * Parses a given host string as a WHATWG-compliant IPv4 address representation.
     * The method validates the format and range of each segment and computes the corresponding
     * long-encoded IPv4 address.
     *
     * @param host the string representation of the potential IPv4 address. It should consist
     *             of up to 4 numerical components separated by dots.
     * @return the computed long-encoded IPv4 address if the input is a valid IPv4 address,
     *         or -1 if the input is invalid or falls outside the acceptable range.
     */
    static long parseWhatwgIpv4(String host)
    {
        String[] parts = host.split("\\.", -1);
        if (parts.length > 4)
        {
            return -1;
        }

        List<BigInteger> numbers = new ArrayList<>(parts.length);
        for (String part : parts)
        {
            BigInteger number = parseIpv4Number(part);
            if (number == null)
            {
                return -1;
            }
            numbers.add(number);
        }

        BigInteger byteLimit = BigInteger.valueOf(255);
        for (int i = 0; i < numbers.size() - 1; i++)
        {
            if (numbers.get(i).compareTo(byteLimit) > 0)
            {
                return -1;
            }
        }
        BigInteger last = numbers.get(numbers.size() - 1);
        if (last.compareTo(BigInteger.valueOf(256).pow(5 - numbers.size())) >= 0)
        {
            return -1;
        }

        long address = last.longValueExact();
        for (int i = 0; i < numbers.size() - 1; i++)
        {
            address += numbers.get(i).longValueExact() << (8 * (3 - i));
        }
        return address;
    }

    /**
     * Parses a string representing a numerical value in various notations (decimal, hexadecimal, or octal)
     * into a {@link BigInteger}. The method supports interpreting the input as follows:
     * - Decimal notation if the string contains only numeric digits.
     * - Hexadecimal notation if the string starts with "0x" or "0X".
     * - Octal notation if the string starts with a leading zero.
     *
     * @param part the string to parse, which may represent a number in decimal, hexadecimal (starting with "0x" or "0X"),
     *             or octal (leading zero) format. An empty string or a string containing invalid characters for its radix
     *             will return {@code null}.
     * @return the parsed {@link BigInteger}, or {@code null} if the input is empty, contains invalid characters,
     *         or is excessively long for processing. Returns {@link BigInteger#ZERO} if the input is valid
     *         but contains no significant digits.
     */
    private static BigInteger parseIpv4Number(String part)
    {
        if (part.isEmpty())
        {
            return null;
        }

        int radix = 10;
        String digits = part;
        if (part.length() >= 2 && (part.startsWith("0x") || part.startsWith("0X")))
        {
            radix = 16;
            digits = part.substring(2);
        }
        else if (part.length() >= 2 && part.charAt(0) == '0')
        {
            radix = 8;
            digits = part.substring(1);
        }

        if (digits.isEmpty())
        {
            return BigInteger.ZERO;
        }
        // Anything this long is far beyond 32 bits in every radix; refuse before allocating.
        if (digits.length() > 40)
        {
            return null;
        }
        for (int i = 0; i < digits.length(); i++)
        {
            if (Character.digit(digits.charAt(i), radix) < 0)
            {
                return null;
            }
        }
        return new BigInteger(digits, radix);
    }

    /**
     * Determines whether the given IPv4 address, represented as a 32-bit integer, is a public IPv4 address.
     * A public IPv4 address is an address that is not part of a reserved or private range.
     *
     * @param address The 32-bit integer representation of the IPv4 address.
     *                The most significant byte is the first part of the address,
     *                e.g., for 192.168.0.1, the integer representation stores 192 in the highest 8 bits.
     * @return {@code true} if the address is a public IPv4 address, {@code false} otherwise.
     */
    static boolean isPublicIpv4(long address)
    {
        int a = (int) (address >>> 24) & 0xFF;
        int b = (int) (address >>> 16) & 0xFF;
        int c = (int) (address >>> 8) & 0xFF;

        return !(a == 0                                   // "this network"
                || a == 10                                // private
                || (a == 100 && (b & 0xC0) == 64)         // shared address space (CGNAT)
                || a == 127                               // loopback
                || (a == 169 && b == 254)                 // link-local
                || (a == 172 && (b & 0xF0) == 16)         // private
                || (a == 192 && b == 0 && c == 0)         // IETF protocol assignments
                || (a == 192 && b == 0 && c == 2)         // documentation (TEST-NET-1)
                || (a == 192 && b == 88 && c == 99)       // deprecated 6to4 relay anycast
                || (a == 192 && b == 168)                 // private
                || (a == 198 && (b & 0xFE) == 18)         // benchmarking
                || (a == 198 && b == 51 && c == 100)      // documentation (TEST-NET-2)
                || (a == 203 && b == 0 && c == 113)       // documentation (TEST-NET-3)
                || a >= 224);                             // multicast, reserved, broadcast
    }

    /**
     * Converts a 32-bit long representation of an IPv4 address into its standard dotted-decimal string format.
     *
     * @param address the 32-bit long representation of the IPv4 address
     * @return the IPv4 address in dotted-decimal string format
     */
    private static String formatIpv4(long address)
    {
        return ((address >>> 24) & 0xFF) + "." + ((address >>> 16) & 0xFF) + "." + ((address >>> 8) & 0xFF) + "." + (address & 0xFF);
    }

    /**
     * Converts the provided IPv6 address text into its canonical form if it is a valid public IPv6 or
     * an IPv4-mapped address and is public.
     *
     * @param text the string representation of the IPv6 address to be processed
     * @return an {@code Optional} containing the canonical representation of the IPv6 address if it
     *         is valid and public, or an empty {@code Optional} if the address is invalid or not public
     */
    private static Optional<String> canonicalIpv6(String text)
    {
        int[] groups = parseIpv6(text);
        if (groups == null)
        {
            return Optional.empty();
        }

        boolean mapped = groups[0] == 0 && groups[1] == 0 && groups[2] == 0 && groups[3] == 0
                && groups[4] == 0 && groups[5] == 0xFFFF;
        if (mapped)
        {
            long address = ((long) groups[6] << 16) | groups[7];
            return isPublicIpv4(address) ? Optional.of(formatIpv4(address)) : Optional.empty();
        }
        return isPublicIpv6(groups) ? Optional.of(formatIpv6(groups)) : Optional.empty();
    }

    /**
     * Parses an IPv6 address in any RFC 4291 text form: eight groups, one {@code ::} standing for
     * one or more zero groups, and an optional dotted-decimal IPv4 tail. Zone identifiers are
     * refused; they only mean something on the machine that wrote them.
     *
     * @return the eight 16-bit groups, or {@code null} when the text is not an address
     */
    static int[] parseIpv6(String text)
    {
        if (text.isEmpty() || text.indexOf('%') >= 0)
        {
            return null;
        }

        int doubleColon = text.indexOf("::");
        if (doubleColon >= 0 && text.indexOf("::", doubleColon + 1) >= 0)
        {
            return null;
        }

        List<Integer> head;
        List<Integer> tail;
        if (doubleColon < 0)
        {
            head = parseIpv6Groups(text, true);
            tail = List.of();
        }
        else
        {
            head = parseIpv6Groups(text.substring(0, doubleColon), false);
            tail = parseIpv6Groups(text.substring(doubleColon + 2), true);
        }
        if (head == null || tail == null)
        {
            return null;
        }

        int total = head.size() + tail.size();
        if ((doubleColon < 0 && total != 8) || (doubleColon >= 0 && total > 7))
        {
            return null;
        }

        int[] groups = new int[8];
        for (int i = 0; i < head.size(); i++)
        {
            groups[i] = head.get(i);
        }
        for (int i = 0; i < tail.size(); i++)
        {
            groups[8 - tail.size() + i] = tail.get(i);
        }
        return groups;
    }

    private static List<Integer> parseIpv6Groups(String text, boolean allowIpv4Tail)
    {
        List<Integer> groups = new ArrayList<>();
        if (text.isEmpty())
        {
            return groups;
        }

        String[] parts = text.split(":", -1);
        for (int i = 0; i < parts.length; i++)
        {
            String part = parts[i];
            if (i == parts.length - 1 && allowIpv4Tail && part.indexOf('.') >= 0)
            {
                long ipv4 = parseStrictIpv4(part);
                if (ipv4 < 0)
                {
                    return null;
                }
                groups.add((int) (ipv4 >>> 16));
                groups.add((int) (ipv4 & 0xFFFF));
                continue;
            }
            if (part.isEmpty() || part.length() > 4)
            {
                return null;
            }
            int value = 0;
            for (int j = 0; j < part.length(); j++)
            {
                int digit = Character.digit(part.charAt(j), 16);
                if (digit < 0 || part.charAt(j) > 0x7F)
                {
                    return null;
                }
                value = (value << 4) | digit;
            }
            groups.add(value);
        }
        return groups.size() <= 8 ? groups : null;
    }

    /**
     * Returns whether an IPv6 address is global unicast (2000::/3, the only range IANA allocates
     * from) and not one of its documentation, benchmarking or ORCHID blocks.
     */
    static boolean isPublicIpv6(int[] groups)
    {
        int first = groups[0];
        int second = groups[1];
        if ((first & 0xE000) != 0x2000)
        {
            return false;
        }
        return !((first == 0x2001 && second == 0x0DB8)                     // documentation
                || (first == 0x2001 && second == 0x0002 && groups[2] == 0)  // benchmarking
                || (first == 0x2001 && (second & 0xFFF0) == 0x0010)         // ORCHID
                || (first == 0x2001 && (second & 0xFFF0) == 0x0020)         // ORCHIDv2
                || (first == 0x3FFF && (second & 0xF000) == 0));            // documentation
    }

    /**
     * Formats an IPv6 address as RFC 5952 recommends: lowercase hex without leading zeros, the
     * first longest run of two or more zero groups replaced by {@code ::}.
     */
    static String formatIpv6(int[] groups)
    {
        int bestStart = -1;
        int bestLength = 1;
        for (int i = 0; i < 8; )
        {
            if (groups[i] != 0)
            {
                i++;
                continue;
            }
            int start = i;
            while (i < 8 && groups[i] == 0)
            {
                i++;
            }
            if (i - start > bestLength)
            {
                bestStart = start;
                bestLength = i - start;
            }
        }

        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 8; i++)
        {
            if (i == bestStart)
            {
                text.append("::");
                i += bestLength - 1;
                continue;
            }
            if (!text.isEmpty() && text.charAt(text.length() - 1) != ':')
            {
                text.append(':');
            }
            text.append(Integer.toHexString(groups[i]));
        }
        return text.toString();
    }

    /**
     * Removes control characters (such as tab, newline, and carriage return)
     * and trims leading and trailing whitespace characters from the given input string.
     *
     * @param input The input string from which control characters and excess whitespace
     *              will be stripped. Must not be null.
     * @return A new string with control characters removed and leading/trailing
     *         whitespace trimmed. Returns an empty string if the input is empty
     *         after processing.
     */
    private static String stripControlCharacters(String input)
    {
        int start = 0;
        int end = input.length();
        while (start < end && input.charAt(start) <= 0x20)
        {
            start++;
        }
        while (end > start && input.charAt(end - 1) <= 0x20)
        {
            end--;
        }

        StringBuilder result = new StringBuilder(end - start);
        for (int i = start; i < end; i++)
        {
            char c = input.charAt(i);
            if (c != '\t' && c != '\n' && c != '\r')
            {
                result.append(c);
            }
        }
        return result.toString();
    }

    /**
     * Removes all leading forward slashes '/' and backslashes '\' from the given string.
     *
     * @param input the string from which leading slashes are to be removed
     * @return a new string with all leading slashes removed
     */
    private static String stripLeadingSlashes(String input)
    {
        int i = 0;
        while (i < input.length() && (input.charAt(i) == '/' || input.charAt(i) == '\\'))
        {
            i++;
        }
        return input.substring(i);
    }

    /**
     * Validates if the given port string represents a valid port number.
     *
     * @param port the string representation of the port to validate.
     *             An empty string is considered valid.
     * @return true if the port is valid (non-empty numeric string within the valid range of port numbers);
     *         false otherwise.
     */
    private static boolean validPort(String port)
    {
        if (port.isEmpty())
        {
            return true;
        }
        if (port.length() > 5 || !port.chars().allMatch(c -> c >= '0' && c <= '9'))
        {
            return false;
        }
        return Integer.parseInt(port) <= MAX_PORT;
    }

    /**
     * Decodes {@code %XX} escapes in a URL host as UTF-8, as browsers do before interpreting it.
     *
     * @return the decoded host, or {@code null} for a malformed escape or invalid UTF-8
     */
    private static String percentDecode(String host)
    {
        if (host.indexOf('%') < 0)
        {
            return host;
        }

        byte[] literal = host.getBytes(StandardCharsets.UTF_8);
        ByteBuffer bytes = ByteBuffer.allocate(literal.length);
        for (int i = 0; i < literal.length; i++)
        {
            if (literal[i] != '%')
            {
                bytes.put(literal[i]);
                continue;
            }
            if (i + 2 >= literal.length)
            {
                return null;
            }
            int high = Character.digit(literal[i + 1], 16);
            int low = Character.digit(literal[i + 2], 16);
            if (high < 0 || low < 0)
            {
                return null;
            }
            bytes.put((byte) ((high << 4) | low));
            i += 2;
        }
        bytes.flip();

        try
        {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(bytes)
                    .toString();
        }
        catch (CharacterCodingException e)
        {
            return null;
        }
    }

    /**
     * Fetches IANA's current list of delegated TLDs and uses it in place of the bundled snapshot,
     * which goes stale as new TLDs are delegated.
     *
     * <p>The list is kept in memory only; nothing is written to disk. A response is used only when
     * it parses as a TLD list of plausible size, so an outage, a proxy error page or a truncated
     * download leaves the current list in place rather than emptying it. Blocks for at most about
     * {@code 2 × 15} seconds (connect, then response); call it off the startup path.
     *
     * @return {@code true} when the fetched list was installed
     */
    public static boolean refreshTlds()
    {
        try
        {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(TLD_FETCH_TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(TLD_LIST_URL).timeout(TLD_FETCH_TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.US_ASCII));
            if (response.statusCode() != 200)
            {
                LOGGER.warn("Could not fetch the TLD list from {} (HTTP {}); keeping the {} TLDs already loaded",
                        TLD_LIST_URL, response.statusCode(), tlds.size());
                return false;
            }

            Set<String> fetched = parseTldList(response.body());
            if (fetched == null)
            {
                LOGGER.warn("The TLD list from {} was not a valid list; keeping the {} TLDs already loaded", TLD_LIST_URL, tlds.size());
                return false;
            }

            int previous = tlds.size();
            tlds = fetched;
            LOGGER.info("Loaded {} TLDs from {} (bundled list had {})", fetched.size(), TLD_LIST_URL, previous);
            return true;
        }
        catch (IOException e)
        {
            LOGGER.warn("Could not fetch the TLD list from {}: {}; keeping the {} TLDs already loaded", TLD_LIST_URL, e.getMessage(), tlds.size());
            return false;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Parses a list in IANA's format: {@code #} comment lines, then one TLD per line.
     *
     * @param body the list text
     * @return the lowercased TLDs, or {@code null} when any line is not a TLD label or the list is
     *         implausibly short
     */
    static Set<String> parseTldList(String body)
    {
        if (body == null)
        {
            return null;
        }

        Set<String> parsed = new HashSet<>();
        for (String line : body.split("\\R"))
        {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#"))
            {
                continue;
            }
            if (!TLD_LINE.matcher(line).matches())
            {
                return null;
            }
            parsed.add(line.toLowerCase(Locale.ROOT));
        }
        return parsed.size() >= MIN_TLD_COUNT ? Set.copyOf(parsed) : null;
    }

    /**
     * Loads the bundled IANA list of delegated top-level domains, lowercased.
     *
     * @return the TLDs, or an empty set when the resource is missing or unreadable
     */
    private static Set<String> loadBundledTlds()
    {
        Set<String> tlds = new HashSet<>();
        try (InputStream in = HostCanonicalizer.class.getResourceAsStream(TLD_RESOURCE))
        {
            if (in == null)
            {
                LOGGER.error("TLD list {} is missing from the classpath; no domain will be accepted", TLD_RESOURCE);
                return Set.of();
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII));
            String line;
            while ((line = reader.readLine()) != null)
            {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#"))
                {
                    tlds.add(line.toLowerCase(Locale.ROOT));
                }
            }
        }
        catch (IOException e)
        {
            LOGGER.error("Failed to read TLD list {}; no domain will be accepted", TLD_RESOURCE, e);
            return Set.of();
        }
        return Set.copyOf(tlds);
    }
}
