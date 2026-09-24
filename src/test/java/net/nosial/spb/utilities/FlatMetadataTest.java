package net.nosial.spb.utilities;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.nosial.spb.support.Updates;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlatMetadataTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Message message(String extraFields)
    {
        return Updates.fromJson("""
                {"update_id": 1, "message": {"message_id": 7, "date": 1700000000,
                 "chat": {"id": -100, "type": "supergroup", "title": "Group"},
                 "from": {"id": 42, "is_bot": false, "first_name": "Ann", "language_code": "en"},
                 %s}}
                """.formatted(extraFields)).getMessage();
    }

    /** Asserts every constraint the OFD specification and the reference server place on metadata. */
    static void assertValidMetadata(Map<String, Object> metadata) throws Exception
    {
        for (Map.Entry<String, Object> entry : metadata.entrySet())
        {
            assertTrue(!entry.getKey().isEmpty() && entry.getKey().getBytes(StandardCharsets.UTF_8).length <= 64,
                    entry.getKey());
            Object value = entry.getValue();
            assertTrue(value == null || value instanceof String || value instanceof Number || value instanceof Boolean,
                    entry.getKey() + " is " + value.getClass());
            if (value instanceof String text)
            {
                assertFalse(text.isEmpty(), entry.getKey());
                assertTrue(text.getBytes(StandardCharsets.UTF_8).length <= 1000, entry.getKey());
            }
        }
        assertTrue(MAPPER.writeValueAsBytes(metadata).length <= 8000);
        // Also within budget when every non-ASCII character is escaped, as ASCII-only encoders do.
        assertTrue(MAPPER.writer().with(com.fasterxml.jackson.core.json.JsonWriteFeature.ESCAPE_NON_ASCII)
                .writeValueAsBytes(metadata).length <= 8000);
    }

    @Test
    void flattensNestedObjectsAndArraysUnderDottedPaths() throws Exception
    {
        Map<String, Object> metadata = FlatMetadata.of(message("""
                "text": "/start hi", "entities": [{"type": "bot_command", "offset": 0, "length": 6}],
                "reply_to_message": {"message_id": 6, "date": 1700000000, "chat": {"id": -100, "type": "supergroup"},
                  "from": {"id": 43, "is_bot": true, "first_name": "Bot"}, "text": "x"}"""));

        assertEquals(7L, metadata.get("message_id"));
        assertEquals(42L, metadata.get("from.id"));
        assertEquals(false, metadata.get("from.is_bot"));
        assertEquals("en", metadata.get("from.language_code"));
        assertEquals(-100L, metadata.get("chat.id"));
        assertEquals("Group", metadata.get("chat.title"));
        assertEquals("bot_command", metadata.get("entities.0.type"));
        assertEquals(6L, metadata.get("entities.0.length"));
        assertEquals(43L, metadata.get("reply_to_message.from.id"));
        assertEquals("x", metadata.get("reply_to_message.text"));
        // Absent properties are left out, not sent as null.
        assertFalse(metadata.containsKey("caption"));
        assertFalse(metadata.containsKey("from.username"));
        assertFalse(metadata.containsKey(FlatMetadata.TRUNCATED_KEY));
        assertValidMetadata(metadata);
    }

    @Test
    void keepsPropertyOrderAndIsUnmodifiable()
    {
        Map<String, Object> metadata = FlatMetadata.of(message("\"text\": \"hi\""));
        assertEquals(List.of("message_id", "from.id", "from.first_name", "from.is_bot", "from.language_code",
                "date", "chat.id", "chat.type", "chat.title", "text"), List.copyOf(metadata.keySet()));
        assertThrows(UnsupportedOperationException.class, () -> metadata.put("x", 1));
    }

    @Test
    void explicitFieldsComeFirstAndWinOverProperties()
    {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("reporter_id", 99L);
        fields.put("message_id", 1234L);
        fields.put("absent", null);
        Map<String, Object> metadata = FlatMetadata.of(fields, message("\"text\": \"hi\""));

        assertEquals(List.of("reporter_id", "message_id"), List.copyOf(metadata.keySet()).subList(0, 2));
        assertEquals(1234L, metadata.get("message_id"));
        assertFalse(metadata.containsKey("absent"));
    }

    @Test
    void withFieldsRefitsASnapshot()
    {
        Map<String, Object> snapshot = FlatMetadata.of(message("\"text\": \"hi\""));
        Map<String, Object> metadata = FlatMetadata.withFields(Map.of("reporter_id", 99L), snapshot);

        assertEquals("reporter_id", metadata.keySet().iterator().next());
        assertEquals(42L, metadata.get("from.id"));
        assertEquals(snapshot.size() + 1, metadata.size());
        assertEquals(Map.of("a", 1L), FlatMetadata.withFields(Map.of("a", 1L), null));
    }

    @Test
    void longStringsAreCutAtACharacterBoundary() throws Exception
    {
        // 999 ASCII bytes then a 4-byte emoji: the emoji does not fit and must not be split.
        String text = "a".repeat(999) + "😀" + "tail";
        Map<String, Object> metadata = FlatMetadata.of(message("\"text\": " + MAPPER.writeValueAsString(text)));

        assertEquals("a".repeat(999), metadata.get("text"));
        assertEquals(true, metadata.get(FlatMetadata.TRUNCATED_KEY));
        assertValidMetadata(metadata);

        assertEquals("é".repeat(500), FlatMetadata.truncateUtf8("é".repeat(600), 1000));
        assertEquals("ab", FlatMetadata.truncateUtf8("ab€", 4));
    }

    @Test
    void emptyStringsAndOverlongKeysAreDropped() throws Exception
    {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("empty", "");
        fields.put("k".repeat(65), 1L);
        fields.put("k".repeat(64), 2L);
        Map<String, Object> metadata = FlatMetadata.of(fields, null);

        assertFalse(metadata.containsKey("empty"));
        assertEquals(2L, metadata.get("k".repeat(64)));
        assertEquals(true, metadata.get(FlatMetadata.TRUNCATED_KEY));
        assertValidMetadata(metadata);
    }

    @Test
    void oversizedObjectsKeepTheShallowestPropertiesWithinBudget() throws Exception
    {
        StringBuilder entities = new StringBuilder();
        for (int i = 0; i < 300; i++)
        {
            entities.append(i == 0 ? "" : ",").append("{\"type\": \"text_link\", \"offset\": ").append(i)
                    .append(", \"length\": 1, \"url\": \"https://example.com/").append("p".repeat(60)).append(i).append("\"}");
        }
        String longText = MAPPER.writeValueAsString("x".repeat(4000));
        Map<String, Object> metadata = FlatMetadata.of(message(
                "\"text\": " + longText + ", \"entities\": [" + entities + "], "
                        + "\"reply_to_message\": {\"message_id\": 6, \"date\": 1700000000, "
                        + "\"chat\": {\"id\": -100, \"type\": \"supergroup\"}, \"text\": " + longText + "}"));

        assertValidMetadata(metadata);
        assertEquals(true, metadata.get(FlatMetadata.TRUNCATED_KEY));
        assertEquals(7L, metadata.get("message_id"));
        assertEquals(42L, metadata.get("from.id"));
        assertEquals(-100L, metadata.get("chat.id"));
        assertEquals(1000, ((String) metadata.get("text")).length());
        assertTrue(metadata.containsKey("reply_to_message.message_id"));
        // Deep entries are what gives way.
        assertNull(metadata.get("entities.299.url"));
    }

    @Test
    void escapeHeavyTextStaysWithinBudgetForAnyEncoder() throws Exception
    {
        String heavy = MAPPER.writeValueAsString("\u2028".repeat(300) + "\uD83D\uDE00".repeat(100) + "\"\\\u0001");
        StringBuilder entities = new StringBuilder();
        for (int i = 0; i < 50; i++)
        {
            entities.append(i == 0 ? "" : ",").append("{\"type\": \"text_link\", \"offset\": 0, \"length\": 1, \"url\": ")
                    .append(heavy).append("}");
        }
        Map<String, Object> metadata = FlatMetadata.of(message("\"text\": " + heavy + ", \"entities\": [" + entities + "]"));
        assertValidMetadata(metadata);
        assertEquals(true, metadata.get(FlatMetadata.TRUNCATED_KEY));
        assertEquals(42L, metadata.get("from.id"));
    }

    @Test
    void loneSurrogatesAreReplaced() throws Exception
    {
        Map<String, Object> metadata = FlatMetadata.of(message("\"text\": \"a\\ud800b\\udc00\\ud83d\\ude00\""));
        assertEquals("a\uFFFDb\uFFFD\uD83D\uDE00", metadata.get("text"));
        assertValidMetadata(metadata);
    }

    @Test
    void userEntitiesKeepTheirTelegramPropertyNames()
    {
        Map<String, Object> metadata = FlatMetadata.of(message("\"text\": \"hi\"").getFrom());
        assertEquals(Map.of("id", 42L, "first_name", "Ann", "is_bot", false, "language_code", "en"), metadata);
    }

    @Test
    void nothingToFlatten()
    {
        assertEquals(Map.of(), FlatMetadata.of(null));
        assertEquals(Map.of(), FlatMetadata.of("a bare string has no property names"));
        assertEquals(Map.of(), FlatMetadata.of(List.of()));
    }
}
