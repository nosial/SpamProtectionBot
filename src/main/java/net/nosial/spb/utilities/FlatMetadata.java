package net.nosial.spb.utilities;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds Federation metadata from whole objects.
 *
 * <p>Federation metadata, for entities and evidence alike, is a flat JSON object of scalar values:
 * it cannot hold nested objects or arrays. An object is therefore represented by all of its
 * properties under dotted paths — a message's author becomes {@code from.id},
 * {@code from.first_name}, and so on, and array elements are addressed by index, as in
 * {@code entities.0.type}. Property names are those of the Telegram Bot API, since objects are
 * serialized the way Telegram sends them; absent properties are left out.
 *
 * <p>The result always satisfies the metadata constraints of the OFD specification and of the
 * reference server, whichever is stricter: keys of at most 64 characters, strings of at most 1000
 * bytes (longer ones are cut at a character boundary, and empty ones are dropped since the server
 * refuses them), and an encoded size within {@link #MAX_ENCODED_BYTES} however the server
 * re-encodes it. When an object does not
 * fit, the explicitly supplied fields are kept first, then its properties from the shallowest
 * down, so {@code from.id} survives where {@code reply_to_message.from.language_code} does not;
 * {@link #TRUNCATED_KEY} is then set so a reader knows the record is partial.
 *
 * <p>The maps returned are unmodifiable and keep the object's property order. All methods are
 * stateless and thread-safe.
 */
public final class FlatMetadata
{
    private static final Logger LOGGER = LoggerFactory.getLogger(FlatMetadata.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Set to {@code true} when properties or string values had to be cut to fit. */
    public static final String TRUNCATED_KEY = "_truncated";

    /**
     * The encoded size the metadata is kept within: the reference server's 8000 bytes, the stricter
     * of it and the specification's 8192. Sizes are measured by {@link #encodedLength(Object)} as
     * the most any JSON encoder could produce, so no server's re-encoding can exceed this.
     */
    static final int MAX_ENCODED_BYTES = 8_000;
    static final int MAX_KEY_LENGTH = 64;
    static final int MAX_STRING_BYTES = 1_000;

    /**
     * Returns every property of an object as flat metadata.
     *
     * @param object a Jackson-serializable object such as a Telegram {@code Message} or
     *               {@code User}, or {@code null}
     * @return the metadata, empty when {@code object} is {@code null} or cannot be serialized
     */
    public static Map<String, Object> of(Object object)
    {
        return of(Map.of(), object);
    }

    /**
     * Returns the given fields followed by every property of an object, as flat metadata.
     *
     * <p>The fields take precedence: they are kept before any property when space runs short, and
     * a property with the same key as a field is left out.
     *
     * @param fields scalar fields to include first; their values must be strings, numbers, booleans
     *               or {@code null} (which is dropped)
     * @param object a Jackson-serializable object, or {@code null} for none
     * @return the metadata, never {@code null}
     */
    public static Map<String, Object> of(Map<String, ?> fields, Object object)
    {
        boolean[] truncated = {false};
        Map<String, Object> properties = new LinkedHashMap<>();
        if (object != null)
        {
            try
            {
                flatten(null, MAPPER.valueToTree(object), properties, truncated);
            }
            catch (IllegalArgumentException e)
            {
                LOGGER.warn("Could not serialize {} for metadata: {}", object.getClass().getSimpleName(), e.getMessage());
            }
        }
        return combine(fields, properties, truncated);
    }

    /**
     * Returns the given fields followed by metadata that is already flat, such as a snapshot taken
     * earlier with {@link #of(Object)}, re-fitted to the size budget.
     *
     * <p>As with {@link #of(Map, Object)}, the fields take precedence over existing keys.
     *
     * @param fields scalar fields to include first
     * @param metadata flat metadata, or {@code null} for none
     * @return the metadata, never {@code null}
     */
    public static Map<String, Object> withFields(Map<String, ?> fields, Map<String, ?> metadata)
    {
        boolean[] truncated = {false};
        Map<String, Object> properties = new LinkedHashMap<>();
        if (metadata != null)
        {
            for (Map.Entry<String, ?> entry : metadata.entrySet())
            {
                if (TRUNCATED_KEY.equals(entry.getKey()))
                {
                    truncated[0] |= Boolean.TRUE.equals(entry.getValue());
                    continue;
                }
                Object value = scalar(entry.getValue(), truncated);
                if (value != null)
                {
                    properties.put(entry.getKey(), value);
                }
            }
        }
        return combine(fields, properties, truncated);
    }

    /**
     * Combines a set of scalar fields and property maps into a single flattened metadata map.
     * Fields take precedence over properties; if a key exists in both maps, only the field's value
     * is included. Properties not present in the fields are added subsequently.
     *
     * @param fields a map containing scalar field values; their values must be strings, numbers,
     *               booleans, or {@code null} (null values are excluded)
     * @param properties a map of additional properties to include, which may contain overlapping
     *                   keys with the fields
     * @param truncated a boolean array that records whether any value was truncated during
     *                  normalization or size fitting; the first element will be set to {@code true}
     *                  if truncation occurs
     * @return a flattened metadata map combining the fields and properties, ensuring no duplicate
     *         keys while fitting the size constraints
     */
    private static Map<String, Object> combine(Map<String, ?> fields, Map<String, Object> properties, boolean[] truncated)
    {
        List<Entry> entries = new ArrayList<>();
        int order = 0;
        for (Map.Entry<String, ?> field : fields.entrySet())
        {
            Object value = scalar(field.getValue(), truncated);
            if (value != null)
            {
                entries.add(new Entry(field.getKey(), value, -1, order++));
            }
        }
        for (Map.Entry<String, Object> property : properties.entrySet())
        {
            if (!fields.containsKey(property.getKey()))
            {
                entries.add(new Entry(property.getKey(), property.getValue(), depth(property.getKey()), order++));
            }
        }
        return fit(entries, truncated[0]);
    }

    /**
     * Calculates the depth of a key based on the number of dot-separated segments it contains.
     *
     * @param key the input string representing a hierarchical key
     * @return the depth of the key, where the root level is 0 and each dot ('.') indicates a deeper segment
     */
    private static int depth(String key)
    {
        int depth = 0;
        for (int i = 0; i < key.length(); i++)
        {
            if (key.charAt(i) == '.')
            {
                depth++;
            }
        }
        return depth;
    }

    /**
     * Flattens a hierarchical JSON node structure into a flat map structure. Each nested key in
     * the JSON is represented as a single key in the resulting map, with keys composed of the
     * original hierarchy path delimited by dots.
     *
     * @param path the current hierarchy path, used to construct keys for nested fields; can be
     *             {@code null} for top-level nodes
     * @param node the JSON node to flatten; expected to be of type {@link JsonNode}
     * @param out  the output map to store the flattened key-value pairs
     * @param truncated an array capturing whether any value was truncated during processing;
     *                  the first element will be set to {@code true} if a truncation occurs
     */
    private static void flatten(String path, JsonNode node, Map<String, Object> out, boolean[] truncated)
    {
        if (node == null || node.isNull() || node.isMissingNode())
        {
            return;
        }

        if (node.isObject())
        {
            for (Map.Entry<String, JsonNode> field : node.properties())
            {
                String key = path == null ? field.getKey() : path + "." + field.getKey();
                flatten(key, field.getValue(), out, truncated);
            }
            return;
        }

        if (node.isArray())
        {
            for (int i = 0; i < node.size(); i++)
            {
                String key = path == null ? String.valueOf(i) : path + "." + i;
                flatten(key, node.get(i), out, truncated);
            }
            return;
        }

        if (path == null)
        {
            // A bare scalar has no property name to file it under.
            return;
        }

        Object value;
        if (node.isBoolean())
        {
            value = node.booleanValue();
        }
        else if (node.isIntegralNumber())
        {
            value = node.canConvertToLong() ? node.longValue() : node.asText();
        }
        else if (node.isNumber())
        {
            value = node.doubleValue();
        }
        else
        {
            value = node.asText();
        }

        value = scalar(value, truncated);
        if (value != null)
        {
            out.putIfAbsent(path, value);
        }
    }

    /**
     * Normalizes a value into its scalar representation based on type constraints, ensuring compatibility
     * with the metadata system. Supports numbers, strings, booleans, and enums while removing undesired
     * or unsupported input types. Handles truncation and surrogate replacements for certain types as needed.
     *
     * @param value the input object to normalize; may be a scalar type like Boolean, Number, CharSequence,
     *              or an Enum, or {@code null}
     * @param truncated a boolean array used to flag if any truncation occurred during the process;
     *                  the first element will be modified to {@code true} if truncation happens
     * @return the scalar representation of the input value, or {@code null} if the input is unsupported,
     *         invalid, or truncated to an empty string
     */
    private static Object scalar(Object value, boolean[] truncated)
    {
        if (value == null)
        {
            return null;
        }
        if (value instanceof Boolean || value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte)
        {
            return value;
        }
        if (value instanceof Double || value instanceof Float)
        {
            double number = ((Number) value).doubleValue();
            return Double.isFinite(number) ? value : null;
        }
        if (value instanceof Number || value instanceof CharSequence || value instanceof Enum<?>)
        {
            String text = replaceLoneSurrogates(value.toString());
            if (text.isEmpty())
            {
                return null;
            }
            String cut = truncateUtf8(text, MAX_STRING_BYTES);
            if (cut.length() != text.length())
            {
                truncated[0] = true;
            }
            return cut.isEmpty() ? null : cut;
        }
        LOGGER.debug("Dropping metadata value of unsupported type {}", value.getClass().getName());
        return null;
    }

    /**
     * Fits the given list of entries into a size-constrained metadata map. Entries that do
     * not meet key constraints or that exceed the size budget are excluded. If truncation is
     * required during the operation, a reserved key indicates this in the returned map.
     *
     * @param entries a list of {@code Entry} objects, each containing a key-value pair and
     *                associated metadata such as depth and order
     * @param alreadyTruncated a flag indicating if the entries were previously truncated; if
     *                         {@code true}, truncation metadata will be carried forward
     * @return an unmodifiable map of metadata containing the valid entries that fit within
     *         the specified constraints; also includes a truncation marker if necessary
     */
    private static Map<String, Object> fit(List<Entry> entries, boolean alreadyTruncated)
    {
        List<Entry> candidates = new ArrayList<>(entries.size());
        boolean truncated = alreadyTruncated;
        for (Entry entry : entries)
        {
            if (entry.key == null || entry.key.isEmpty()
                    || entry.key.codePointCount(0, entry.key.length()) > MAX_KEY_LENGTH
                    || entry.key.getBytes(StandardCharsets.UTF_8).length > MAX_KEY_LENGTH)
            {
                truncated = true;
                continue;
            }
            candidates.add(entry);
        }

        List<Entry> byPriority = new ArrayList<>(candidates);
        byPriority.sort(Comparator.comparingInt((Entry e) -> e.depth).thenComparingInt(e -> e.order));

        // Room for the braces, and for the truncation marker should it be needed.
        int budget = MAX_ENCODED_BYTES - 2 - (encodedSize(TRUNCATED_KEY, Boolean.TRUE) + 1);
        int used = 0;
        List<Entry> kept = new ArrayList<>();
        for (Entry entry : byPriority)
        {
            int size = encodedSize(entry.key, entry.value) + (kept.isEmpty() ? 0 : 1);
            if (used + size > budget)
            {
                truncated = true;
                continue;
            }
            used += size;
            kept.add(entry);
        }

        kept.sort(Comparator.comparingInt((Entry e) -> e.order));
        Map<String, Object> metadata = new LinkedHashMap<>();
        for (Entry entry : kept)
        {
            metadata.putIfAbsent(entry.key, entry.value);
        }
        if (truncated)
        {
            metadata.put(TRUNCATED_KEY, Boolean.TRUE);
        }
        return Collections.unmodifiableMap(metadata);
    }

    /**
     * Calculates the total encoded size of a key-value pair when serialized as JSON.
     * This includes the encoded length of the key, one additional byte for the key-value separator,
     * and the encoded length of the value.
     *
     * @param key the key of the pair, represented as a string
     * @param value the value associated with the key, which can be of any object type
     * @return the total encoded size of the key-value pair in bytes
     */
    private static int encodedSize(String key, Object value)
    {
        return encodedLength(key) + 1 + encodedLength(value);
    }

    /**
     * Calculates the encoded length of the given value when serialized as JSON.
     * If the value is a string, it evaluates the length based on character escaping
     * and encoding rules. For other objects, it calculates the size using Jackson's
     * serialization mechanism.
     *
     * @param value the value to evaluate, which can be a string or any object
     * @return the calculated byte length of the encoded value; if serialization fails,
     *         returns a default large value (Integer.MAX_VALUE / 4)
     */
    static int encodedLength(Object value)
    {
        if ((value instanceof String text))
        {
            int length = 2;
            for (int i = 0; i < text.length(); i++)
            {
                char c = text.charAt(i);
                if (c == '"' || c == '\\' || c == '\b' || c == '\f' || c == '\n' || c == '\r' || c == '\t')
                {
                    length += 2;
                }
                else if (c < 0x20 || c >= 0x80)
                {
                    length += 6;
                }
                else
                {
                    length += 1;
                }
            }

            return length;
        }

        try
        {
            return MAPPER.writeValueAsBytes(value).length;
        }
        catch (JsonProcessingException e)
        {
            return Integer.MAX_VALUE / 4;
        }
    }

    /**
     * Replaces any lone surrogate characters in the input text with the Unicode replacement character (U+FFFD).
     * A lone surrogate is an unpaired high surrogate or low surrogate.
     * If the input string does not contain any lone surrogates, the original string is returned unchanged.
     *
     * @param text the input string which may contain lone surrogate characters
     * @return a new string with lone surrogates replaced by U+FFFD, or the original input string if no replacements are needed
     */
    static String replaceLoneSurrogates(String text)
    {
        StringBuilder result = null;
        for (int i = 0; i < text.length(); i++)
        {
            char c = text.charAt(i);
            boolean paired = Character.isHighSurrogate(c) && i + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(i + 1));
            if (paired)
            {
                if (result != null)
                {
                    result.append(c).append(text.charAt(i + 1));
                }
                i++;
                continue;
            }
            if (Character.isSurrogate(c))
            {
                if (result == null)
                {
                    result = new StringBuilder(text.length()).append(text, 0, i);
                }
                result.append('\uFFFD');
                continue;
            }
            if (result != null)
            {
                result.append(c);
            }
        }
        return result != null ? result.toString() : text;
    }

    /**
     * Truncates the given text to fit within the specified maximum number of bytes
     * when encoded in UTF-8. The truncation ensures that the resulting string does not
     * break character boundaries or include incomplete multi-byte characters.
     *
     * @param text the input string to be truncated
     * @param maxBytes the maximum number of bytes allowed for the resulting string
     * @return the truncated string, guaranteed to fit within the specified byte limit
     *         while preserving valid UTF-8 character boundaries
     */
    static String truncateUtf8(String text, int maxBytes)
    {
        int bytes = 0;
        int i = 0;
        while (i < text.length())
        {
            int codePoint = text.codePointAt(i);
            int width = codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
            if (bytes + width > maxBytes)
            {
                break;
            }
            bytes += width;
            i += Character.charCount(codePoint);
        }
        return text.substring(0, i);
    }

    /**
     * One flattened property.
     *
     * @param key the dotted path
     * @param value the scalar value
     * @param depth how deeply nested the property is; {@code -1} for an explicit field
     * @param order its position in the original property order
     */
    private record Entry(String key, Object value, int depth, int order)
    {
    }
}
