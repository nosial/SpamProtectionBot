package net.nosial.spb.classes.sessions;

import net.nosial.spb.enums.ConfigurationPage;
import net.nosial.spb.objects.context.ConfigurationContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConfigurationSessionManagerTest
{
    @Test
    void findsTheSessionShowingAVerificationCode()
    {
        ConfigurationSessionManager sessions = new ConfigurationSessionManager();
        ConfigurationContext session = sessions.create(42L, -100L);
        sessions.updatePage(session.hash(), ConfigurationPage.CHANNEL);
        sessions.updateChannelLinkVerificationCode(session.hash(), 123456L);

        assertEquals(session.hash(), sessions.findByChannelLinkVerificationCode(123456L).hash());
        assertNull(sessions.findByChannelLinkVerificationCode(654321L));
    }

    @Test
    void aReplacedOrClearedCodeNoLongerFindsTheSession()
    {
        ConfigurationSessionManager sessions = new ConfigurationSessionManager();
        ConfigurationContext session = sessions.create(42L, -100L);
        sessions.updatePage(session.hash(), ConfigurationPage.CHANNEL);

        sessions.updateChannelLinkVerificationCode(session.hash(), 111L);
        sessions.updateChannelLinkVerificationCode(session.hash(), 222L);
        assertNull(sessions.findByChannelLinkVerificationCode(111L));
        assertEquals(session.hash(), sessions.findByChannelLinkVerificationCode(222L).hash());

        // Leaving the Chat Linking page discards the code.
        sessions.updatePage(session.hash(), ConfigurationPage.MAIN);
        assertNull(sessions.findByChannelLinkVerificationCode(222L));
    }

    @Test
    void anInvalidatedSessionIsNotFound()
    {
        ConfigurationSessionManager sessions = new ConfigurationSessionManager();
        ConfigurationContext session = sessions.create(42L, -100L);
        sessions.updatePage(session.hash(), ConfigurationPage.CHANNEL);
        sessions.updateChannelLinkVerificationCode(session.hash(), 333L);

        sessions.invalidate(session.hash());
        assertNull(sessions.findByChannelLinkVerificationCode(333L));
    }
}
