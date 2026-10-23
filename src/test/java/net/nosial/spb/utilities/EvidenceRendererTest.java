package net.nosial.spb.utilities;

import net.nosial.jfederation.classes.Json;
import net.nosial.jfederation.records.EvidenceRecord;
import net.nosial.spb.support.Contexts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EvidenceRendererTest
{
    @Test
    void rendersOfd2EvidenceAndEscapesUntrustedContext()
    {
        var evidence = Json.mapper().readValue("""
                {"uuid":"evidence-id","entity":"entity-id","operator":"operator-id",
                 "automated":true,"confidential":true,"content_type":"WEB_PAGE",
                 "context":{"url":"https://example.org/?a=<script>&b=1"},
                 "created":1,"updated":2}
                """, EvidenceRecord.class);
        var languages = Contexts.languages();
        String html = EvidenceRenderer.infoHtml(languages, languages.defaultLanguage(), evidence);
        assertTrue(html.contains("<b>Automated:</b> Yes"), html);
        assertTrue(html.contains("<b>Content type:</b> Web Page"), html);
        assertTrue(html.contains("&lt;script&gt;&amp;b=1"), html);
        assertFalse(html.contains("<script>"), html);
    }
}
