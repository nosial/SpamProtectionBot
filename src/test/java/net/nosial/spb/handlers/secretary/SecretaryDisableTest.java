package net.nosial.spb.handlers.secretary;

import net.nosial.spb.enums.SecretaryContactStatus;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.objects.database.SecretaryContact;
import net.nosial.spb.support.Contexts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretaryDisableTest
{
    private static final long OWNER_A = 101L;
    private static final long OWNER_B = 202L;

    private static void contact(HandlerContext context, String connectionId, long contactId) throws Exception
    {
        context.managers().secretaryContacts().registerSecretaryContact(
                new SecretaryContact(connectionId, contactId, SecretaryContactStatus.ALLOWED, 1L));
    }

    @Test
    void disablingOneOwnerNeverTouchesAnotherOwnersContacts(@TempDir Path directory) throws Exception
    {
        HandlerContext context = Contexts.template(directory);
        context.managers().secretaryConfigurations().setBusinessConnectionId(OWNER_A, "conn-a");
        context.managers().secretaryConfigurations().setBusinessConnectionId(OWNER_B, "conn-b");
        contact(context, "conn-a", 1L);
        contact(context, "conn-b", 2L);

        SecretaryConnectionHandler.applyDisable(context, OWNER_A, "conn-a");
        assertTrue(context.managers().secretaryContacts().getSecretaryContact("conn-a", 1L).isEmpty());
        assertTrue(context.managers().secretaryContacts().getSecretaryContact("conn-b", 2L).isPresent());

        // A repeated disable after owner A's configuration is gone used to delete every contact of
        // every owner; it must now only ever touch the connection named in the update.
        SecretaryConnectionHandler.applyDisable(context, OWNER_A, "conn-a");
        SecretaryConnectionHandler.applyDisable(context, 999L, null);
        assertTrue(context.managers().secretaryContacts().getSecretaryContact("conn-b", 2L).isPresent());
    }

    @Test
    void disablingReleasesTheConnectionsContactClaims(@TempDir Path directory) throws Exception
    {
        HandlerContext context = Contexts.template(directory);
        context.managers().secretaryConfigurations().setBusinessConnectionId(OWNER_A, "conn-a");
        context.cache().put("secretary-contact-claim:conn-a:1", Boolean.FALSE);
        context.cache().put("secretary-contact-claim:conn-b:2", Boolean.FALSE);

        SecretaryConnectionHandler.applyDisable(context, OWNER_A, "conn-a");

        assertNull(context.cache().getIfPresent("secretary-contact-claim:conn-a:1"));
        assertNotNull(context.cache().getIfPresent("secretary-contact-claim:conn-b:2"));
        assertFalse(context.managers().secretaryConfigurations().secretaryConfigurationExists(OWNER_A));
    }
}
