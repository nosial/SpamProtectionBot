package net.nosial.spb.utilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostCanonicalizerTest
{
    @Test
    void tldListIsBundled()
    {
        assertTrue(HostCanonicalizer.tldsLoaded());
    }

    @Test
    void parsesAnIanaFormattedTldList()
    {
        StringBuilder body = new StringBuilder("# Version 2026092400, Last Updated Thu Sep 24 07:07:01 2026 UTC\n");
        for (int i = 0; i < HostCanonicalizer.MIN_TLD_COUNT; i++)
        {
            body.append("TLD").append(i).append("\r\n");
        }
        body.append("XN--P1AI\n\n");

        java.util.Set<String> tlds = HostCanonicalizer.parseTldList(body.toString());
        assertEquals(HostCanonicalizer.MIN_TLD_COUNT + 1, tlds.size());
        assertTrue(tlds.contains("tld0") && tlds.contains("xn--p1ai"));
    }

    @Test
    void refusesResponsesThatAreNotATldList()
    {
        String valid = "COM\n".repeat(1) + String.join("\n", java.util.stream.IntStream.range(0, 1_200)
                .mapToObj(i -> "T" + i).toList());
        assertTrue(HostCanonicalizer.parseTldList(valid) != null);

        // Too short: a truncated download.
        assertEquals(null, HostCanonicalizer.parseTldList("# Version 1\nCOM\nNET\nORG\n"));
        // Anything that is not a bare label: an HTML error page or proxy notice.
        assertEquals(null, HostCanonicalizer.parseTldList("<html><body>Service Unavailable</body></html>\n" + valid));
        assertEquals(null, HostCanonicalizer.parseTldList(valid + "\nexample.com"));
        assertEquals(null, HostCanonicalizer.parseTldList(""));
        assertEquals(null, HostCanonicalizer.parseTldList(null));
    }

    @ParameterizedTest
    @CsvSource({
            "https://example.co.uk/path, example.co.uk",
            "HTTPS://WWW.Evil.COM/Login, www.evil.com",
            "http://evil.com., evil.com",
            "http://evil.com:8080/x?y#z, evil.com",
            "http://user:pass@evil.com/, evil.com",
            // Browsers open the host after the last '@', not the one that looks legitimate.
            "http://google.com@evil.com/, evil.com",
            "http://google.com:x@evil.com:443, evil.com",
            // A backslash ends the authority of a special URL, as in browsers.
            "http://evil.com\\@google.com/, evil.com",
            "http:evil.com, evil.com",
            "https:////evil.com, evil.com",
            "evil.com/path, evil.com",
            "evil.com:8080/path, evil.com",
            "wss://chat.evil.com, chat.evil.com",
            "ftp://files.evil.com, files.evil.com",
            "custom://files.evil.com/, files.evil.com",
            "http://%65vil.com/, evil.com",
            "http://пример.рф/, xn--e1afmkfd.xn--p1ai",
            "http://xn--e1afmkfd.xn--p1ai/, xn--e1afmkfd.xn--p1ai",
            "http://evil。com/, evil.com",
            "' http://evil.com/ ', evil.com",
            "setup.py, setup.py",
            "http://8.8.8.8/, 8.8.8.8",
            "http://8.8.8.8:53, 8.8.8.8",
            // WHATWG IPv4 forms a browser accepts.
            "http://0x08080808/, 8.8.8.8",
            "http://134744072/, 8.8.8.8",
            "http://010.010.010.010/, 8.8.8.8",
            "http://8.8.2056/, 8.8.8.8",
            "http://8.526344/, 8.8.8.8",
            "http://[2001:4860:4860::8888]/, 2001:4860:4860::8888",
            "http://[2001:4860:4860:0:0:0:0:8888]:443/, 2001:4860:4860::8888",
            "http://[::ffff:8.8.8.8]/, 8.8.8.8",
            "http://[::FFFF:0808:0808]/, 8.8.8.8",
    })
    void urlsYieldTheirCanonicalHost(String url, String expected)
    {
        assertEquals(Optional.of(expected), HostCanonicalizer.fromUrl(url));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            " ", "http://", "http:///", "https://@", "http://:80", "http://[]", "http://[::1",
            "http://[2001:4860::8888]x", "http://evil.com:99999", "http://evil.com:8o",
            "mailto:someone@evil.com", "tel:+123456789", "javascript:alert('evil.com')",
            "data:text/html,evil.com", "tg://resolve?domain=evil", "file:///etc/passwd",
            "http://localhost/", "http://localhost:8080", "localhost:8080", "http://intranet/",
            "http://evil.local/", "http://evil.invalid/", "http://evil.test/", "http://router.lan",
            "http://example.com/", "http://www.example.org/", "http://evil.notarealtld/",
            "http://evil..com/", "http://.evil.com/", "http://evil.com../", "http://-evil.com/",
            "http://evil-.com/", "http://ev_il.com/", "http://evil com/", "http://evil%2/",
            "http://evil%zz.com/", "http://%ff.com/", "http://xn--.com/", "http://xn--zz.com/",
            "http://127.0.0.1/", "http://0x7f.1/", "http://2130706433/", "http://10.0.0.1/",
            "http://192.168.1.1/", "http://172.16.0.1/", "http://169.254.169.254/",
            "http://100.64.0.1/", "http://0.0.0.0/", "http://255.255.255.255/", "http://224.0.0.1/",
            "http://192.0.2.1/", "http://198.51.100.1/", "http://203.0.113.1/",
            "http://256.1.1.1/", "http://1.2.3.4.5/", "http://1.2.3.08/", "http://0x100000000/",
            "http://99999999999999999999999999/", "http://1.2.3.4a/",
            "http://[::1]/", "http://[::]/", "http://[fe80::1]/", "http://[fc00::1]/",
            "http://[ff02::1]/", "http://[2001:db8::1]/", "http://[::ffff:127.0.0.1]/",
            "http://[fe80::1%25eth0]/", "http://[1:2:3:4:5:6:7:8:9]/", "http://[1::2::3]/",
            "http://[12345::1]/", "http://[2001:4860:::8888]/",
    })
    void unacceptableUrlsYieldNothing(String url)
    {
        assertEquals(Optional.empty(), HostCanonicalizer.fromUrl(url));
    }

    @Test
    void oversizedNamesYieldNothing()
    {
        assertEquals(Optional.empty(), HostCanonicalizer.fromUrl("http://" + "a".repeat(64) + ".com/"));
        assertEquals(Optional.empty(), HostCanonicalizer.fromText("a.".repeat(127) + "com"));
        assertEquals(Optional.of("a".repeat(63) + ".com"), HostCanonicalizer.fromText("a".repeat(63) + ".com"));
    }

    @ParameterizedTest
    @CsvSource({
            "evil.com, evil.com",
            "Evil.COM, evil.com",
            "evil.com., evil.com",
            "sub.evil.co.uk, sub.evil.co.uk",
            "xn--e1afmkfd.xn--p1ai, xn--e1afmkfd.xn--p1ai",
            "8.8.8.8, 8.8.8.8",
            "2001:4860:4860::8888, 2001:4860:4860::8888",
            "2001:4860:4860:0000:0000:0000:0000:8888, 2001:4860:4860::8888",
            "[2001:4860:4860::8888], 2001:4860:4860::8888",
            "2001:DB9:0:0:1:0:0:1, 2001:db9::1:0:0:1",
            "2001:4860:0:1:0:0:0:1, 2001:4860:0:1::1",
            "2001:4860:1:1:1:1:0:1, 2001:4860:1:1:1:1:0:1",
            "::ffff:8.8.8.8, 8.8.8.8",
    })
    void wordsYieldTheirCanonicalHost(String word, String expected)
    {
        assertEquals(Optional.of(expected), HostCanonicalizer.fromText(word));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "e.g", "i.e", "Mr.Smith", "3.14", "1.2.3", "v1.2.3.4", "1.2.3.4.5",
            // Leading zeros are ambiguous (octal to some parsers), so plain text does not guess.
            "010.8.8.8", "8.8.8.08", "0x8.8.8.8",
            "setup.py", "notes.md", "Main.java", "archive.zip", "run.sh", "lib.rs",
            "readme.txt", "config.yml", "example.com", "evil.localhost",
            "12:30:45", "aa:bb:cc:dd:ee:ff", "a::b", "::", "::1", "fe80::1", "2001:db8::1",
    })
    void unacceptableWordsYieldNothing(String word)
    {
        assertEquals(Optional.empty(), HostCanonicalizer.fromText(word));
    }

    @Test
    void ipv6FormattingFollowsRfc5952()
    {
        // The first of two equally long zero runs is compressed.
        assertEquals("2001:4860:0:0:1::", HostCanonicalizer.formatIpv6(new int[]{0x2001, 0x4860, 0, 0, 1, 0, 0, 0}));
        assertEquals("2001:4860::1:0:0:1", HostCanonicalizer.formatIpv6(new int[]{0x2001, 0x4860, 0, 0, 1, 0, 0, 1}));
        // A single zero group is never compressed.
        assertEquals("2001:4860:0:1:1:1:1:1", HostCanonicalizer.formatIpv6(new int[]{0x2001, 0x4860, 0, 1, 1, 1, 1, 1}));
    }
}
