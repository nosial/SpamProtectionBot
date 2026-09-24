package net.nosial.spb.utilities;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import net.nosial.spb.support.Updates;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostExtractorTest
{
    private static List<String> extract(String text)
    {
        return HostExtractor.extract(text, null, HostExtractor.MAX_HOSTS_PER_MESSAGE);
    }

    private static MessageEntity entity(String type, int offset, int length)
    {
        return MessageEntity.builder().type(type).offset(offset).length(length).build();
    }

    @Test
    void findsBareDomainsAndAddresses()
    {
        assertEquals(List.of("evil.com", "8.8.8.8", "2001:4860:4860::8888"),
                extract("Visit evil.com or 8.8.8.8, also 2001:4860:4860::8888."));
    }

    @Test
    void findsUrlsWrittenInProse()
    {
        assertEquals(List.of("evil.com", "phish.net", "en.wikipedia.org"),
                extract("See (https://evil.com/login). Or \"http://phish.net\"! "
                        + "https://en.wikipedia.org/wiki/Foo_(bar)"));
    }

    @Test
    void doesNotPickApartUrlPaths()
    {
        assertEquals(List.of("evil.com"), extract("https://evil.com/files/readme.md/other.org?x=1"));
        assertEquals(List.of("evil.com"), extract("evil.com/downloads/other.org"));
    }

    @Test
    void skipsEmailsAndMentions()
    {
        assertEquals(List.of(), extract("mail john.doe@evil.com or ping @evil_bot"));
    }

    @Test
    void skipsWordsThatOnlyLookLikeHosts()
    {
        assertEquals(List.of(), extract("e.g. version 1.2.3.4.5, pi is 3.14, see setup.py and Main.java "
                + "at 12:30:45, mac aa:bb:cc:dd:ee:ff, std::vector, example.com, 192.168.1.1, 127.0.0.1"));
    }

    @Test
    void skipsHostsGluedToOtherWords()
    {
        assertEquals(List.of(), extract("пример.com x_evil.com evil.com_x /path/evil.com"));
    }

    @Test
    void deduplicatesCanonicalForms()
    {
        assertEquals(List.of("evil.com"), extract("evil.com EVIL.COM https://Evil.com/ evil.com."));
        assertEquals(List.of("8.8.8.8"), extract("8.8.8.8 ::ffff:8.8.8.8 http://0x08080808/"));
    }

    @Test
    void usesTelegramLinkEntities()
    {
        String text = "Click here, or пример.рф";
        List<MessageEntity> entities = List.of(
                MessageEntity.builder().type("text_link").offset(0).length(10).url("https://hidden.evil.com/x").build(),
                entity("url", 15, 9));
        assertEquals(List.of("hidden.evil.com", "xn--e1afmkfd.xn--p1ai"),
                HostExtractor.extract(text, entities, HostExtractor.MAX_HOSTS_PER_MESSAGE));
    }

    @Test
    void acceptsFileLikeDomainsOnlyWhenTelegramLinkedThem()
    {
        String text = "get setup.zip";
        assertEquals(List.of(), extract(text));
        assertEquals(List.of("setup.zip"),
                HostExtractor.extract(text, List.of(entity("url", 4, 9)), HostExtractor.MAX_HOSTS_PER_MESSAGE));
    }

    @Test
    void emailEntitiesAreNotMinedForDomains()
    {
        String text = "write to a@evil.com";
        assertEquals(List.of(), HostExtractor.extract(text, List.of(entity("email", 9, 10)),
                HostExtractor.MAX_HOSTS_PER_MESSAGE));
    }

    @Test
    void toleratesMalformedEntities()
    {
        String text = "evil.com";
        List<MessageEntity> entities = new ArrayList<>();
        entities.add(null);
        entities.add(entity("url", -1, 3));
        entities.add(entity("url", 5, 100));
        entities.add(entity("url", Integer.MAX_VALUE, Integer.MAX_VALUE));
        entities.add(entity("url", 0, 0));
        entities.add(MessageEntity.builder().type("text_link").offset(0).length(4).build());
        entities.add(MessageEntity.builder().type("text_link").offset(0).length(4).url("not a url").build());
        assertEquals(List.of("evil.com"), HostExtractor.extract(text, entities, HostExtractor.MAX_HOSTS_PER_MESSAGE));
    }

    @Test
    void toleratesEmptyInput()
    {
        assertEquals(List.of(), HostExtractor.extract(null, null, 10));
        assertEquals(List.of(), HostExtractor.extract("", List.of(), 10));
        assertEquals(List.of(), HostExtractor.extract("evil.com", null, 0));
        assertEquals(List.of(), HostExtractor.extract((Message) null));
    }

    @Test
    void capsTheNumberOfHosts()
    {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 100; i++)
        {
            text.append("host").append(i).append(".com ");
        }
        List<String> hosts = extract(text.toString());
        assertEquals(HostExtractor.MAX_HOSTS_PER_MESSAGE, hosts.size());
        assertEquals("host0.com", hosts.get(0));
    }

    @Test
    void pathologicalInputFinishesQuickly()
    {
        String[] inputs = {
                "a.".repeat(50_000),
                "http://" + "a".repeat(100_000),
                ":".repeat(100_000),
                "1.".repeat(50_000),
                "a:".repeat(50_000),
                "http://".repeat(20_000),
                "(".repeat(50_000) + "http://evil.com" + ")".repeat(50_000),
        };
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () ->
        {
            for (String input : inputs)
            {
                assertTrue(extract(input).size() <= HostExtractor.MAX_HOSTS_PER_MESSAGE);
            }
        });
    }

    @Test
    void randomInputNeverThrowsAndOnlyYieldsCanonicalHosts()
    {
        String alphabet = "aeXz09.-:/\\@[]%_ ()\"'.:0x7f8пр。，\t\n#?=+" + "😀";
        String[] seeds = {"http://", "https://", "evil.com", "8.8.8.8", "2001:4860::8888", "xn--", "::ffff:"};
        java.util.Random random = new java.util.Random(20260924L);
        for (int round = 0; round < 20_000; round++)
        {
            StringBuilder text = new StringBuilder();
            int pieces = random.nextInt(12);
            for (int i = 0; i < pieces; i++)
            {
                if (random.nextInt(3) == 0)
                {
                    text.append(seeds[random.nextInt(seeds.length)]);
                }
                else
                {
                    text.append(alphabet.charAt(random.nextInt(alphabet.length())));
                }
            }

            String input = text.toString();
            List<MessageEntity> entities = List.of(
                    entity("url", random.nextInt(input.length() + 2), random.nextInt(input.length() + 2)),
                    MessageEntity.builder().type("text_link").offset(0).length(1).url(input).build());
            for (String host : HostExtractor.extract(input, entities, HostExtractor.MAX_HOSTS_PER_MESSAGE))
            {
                String asUrl = host.indexOf(':') >= 0 ? "http://[" + host + "]/" : "http://" + host + "/";
                assertEquals(java.util.Optional.of(host), HostCanonicalizer.fromUrl(asUrl),
                        () -> "not a fixed point: " + host + " from " + input);
                assertEquals(host, host.toLowerCase(java.util.Locale.ROOT));
                assertTrue(host.chars().allMatch(c -> c > 0x20 && c < 0x7F), host);
            }
        }
    }

    @Test
    void readsCaptionWhenThereIsNoText()
    {
        Message message = Updates.fromJson("""
                {"update_id": 1, "message": {"message_id": 7, "date": 1700000000,
                 "chat": {"id": -100, "type": "supergroup"},
                 "from": {"id": 42, "is_bot": false, "first_name": "A"},
                 "photo": [{"file_id": "f", "file_unique_id": "u", "width": 1, "height": 1}],
                 "caption": "free prizes at evil.com",
                 "caption_entities": [{"type": "url", "offset": 15, "length": 8}]}}
                """).getMessage();
        assertEquals(List.of("evil.com"), HostExtractor.extract(message));
    }
}
