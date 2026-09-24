package net.nosial.spb.classes.managers;

import net.nosial.spb.classes.Database;
import net.nosial.spb.classes.LanguageManager;
import net.nosial.spb.enums.JoinProtectionBehavior;
import net.nosial.spb.enums.ScanningBehavior;
import net.nosial.spb.enums.SecretaryContactStatus;
import net.nosial.spb.objects.Language;
import net.nosial.spb.support.Configurations;
import net.nosial.spb.exceptions.DatabaseException;
import net.nosial.spb.objects.database.OperatorIdentity;
import net.nosial.spb.objects.database.SecretaryContact;
import net.nosial.spb.objects.database.UserIdentity;
import net.nosial.spb.objects.database.ChatConfiguration;
import net.nosial.spb.objects.database.SecretaryConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the record-keeping layer over the database.
 *
 * <p>Every manager is exercised against a real SQLite file rather than a stand-in, because what is
 * worth checking is exactly what a stand-in would not model: that a value written through the
 * manager's cache is the value a later read finds on disk.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ManagerRegistryTest
{
    @TempDir
    Path directory;

    private static final LanguageManager LANGUAGES = new LanguageManager("en");

    private Database database;
    private ManagerRegistry managers;

    /**
     * Returns the default language, which every fallback resolves to.
     *
     * @return English
     */
    private static Language english()
    {
        return LANGUAGES.defaultLanguage();
    }

    @BeforeEach
    void openDatabase() throws DatabaseException
    {
        this.database = new Database(this.directory.resolve("database.db"));
        this.managers = new ManagerRegistry(this.database, Configurations.minimal(this.directory), LANGUAGES);
    }

    @AfterEach
    void closeDatabase()
    {
        if (this.database != null)
        {
            this.database.close();
        }
    }

    /**
     * Reopens the database, discarding every in-memory cache.
     *
     * @return a registry over the reopened database
     * @throws DatabaseException If the database cannot be reopened
     */
    private ManagerRegistry reopen() throws DatabaseException
    {
        this.database.close();
        this.database = new Database(this.directory.resolve("database.db"));
        this.managers = new ManagerRegistry(this.database, Configurations.minimal(this.directory), LANGUAGES);
        return this.managers;
    }

    @Test
    @DisplayName("every manager is available and distinct")
    void exposesEveryManager()
    {
        assertSame(this.managers.users(), this.managers.users());
        assertTrue(this.managers.operators() != null);
        assertTrue(this.managers.chatConfigurations() != null);
        assertTrue(this.managers.secretaryConfigurations() != null);
        assertTrue(this.managers.secretaryContacts() != null);
        assertTrue(this.managers.languagePreferences() != null);
    }

    @Nested
    @DisplayName("Users")
    class Users
    {
        @Test
        @DisplayName("a user is stored and read back by id and by username")
        void storesAndReadsUsers() throws Exception
        {
            ManagerRegistryTest.this.managers.users().saveUser(new UserIdentity(42L, "tester", "Test", "User"));

            Optional<UserIdentity> byId = ManagerRegistryTest.this.managers.users().getUser(42L);
            assertTrue(byId.isPresent());
            assertEquals("tester", byId.get().username());
            assertEquals("Test", byId.get().firstName());

            assertEquals(Optional.of(42L), ManagerRegistryTest.this.managers.users().getUserIdByUsername("tester"));
            assertEquals(Optional.of(42L), ManagerRegistryTest.this.managers.users().getUserIdByUsername("@TESTER"),
                    "usernames match case-insensitively and with or without the @");
        }

        @Test
        @DisplayName("a user survives a restart")
        void usersArePersistent() throws Exception
        {
            ManagerRegistryTest.this.managers.users().saveUser(new UserIdentity(42L, "tester", "Test", null));

            assertTrue(ManagerRegistryTest.this.reopen().users().getUser(42L).isPresent());
        }

        @Test
        @DisplayName("a renamed user is no longer found under the old username")
        void renamingInvalidatesTheOldUsername() throws Exception
        {
            ManagerRegistryTest.this.managers.users().saveUser(new UserIdentity(42L, "before", "Test", null));
            assertEquals(Optional.of(42L), ManagerRegistryTest.this.managers.users().getUserIdByUsername("before"));

            ManagerRegistryTest.this.managers.users().saveUser(new UserIdentity(42L, "after", "Test", null));

            assertEquals(Optional.of(42L), ManagerRegistryTest.this.managers.users().getUserIdByUsername("after"));
            assertTrue(ManagerRegistryTest.this.managers.users().getUserIdByUsername("before").isEmpty());
        }

        @Test
        @DisplayName("a username that moved to another account follows its new owner")
        void usernamesMoveBetweenAccounts() throws Exception
        {
            UserManager users = ManagerRegistryTest.this.managers.users();
            users.saveUser(new UserIdentity(42L, "handle", "First", null));

            users.saveUser(new UserIdentity(43L, "handle", "Second", null));

            assertEquals(Optional.of(43L), users.getUserIdByUsername("handle"));
            assertNull(users.getUser(42L).orElseThrow().username(), "the previous owner releases the username");
            assertNull(ManagerRegistryTest.this.reopen().users().getUser(42L).orElseThrow().username());
        }

        @Test
        @DisplayName("an unknown user is absent rather than invented")
        void unknownUsersAreAbsent()
        {
            assertTrue(ManagerRegistryTest.this.managers.users().getUser(404L).isEmpty());
            assertFalse(ManagerRegistryTest.this.managers.users().userExists(404L));
        }

        @Test
        @DisplayName("a deleted user is gone")
        void deletesUsers() throws Exception
        {
            ManagerRegistryTest.this.managers.users().saveUser(new UserIdentity(42L, "tester", "Test", null));
            ManagerRegistryTest.this.managers.users().deleteUser(42L);

            assertFalse(ManagerRegistryTest.this.managers.users().userExists(42L));
        }
    }

    @Nested
    @DisplayName("Chat configuration")
    class ChatConfigurations
    {
        @Test
        @DisplayName("an unconfigured chat resolves to defaults without being stored")
        void defaultsAreNotStored()
        {
            ChatConfiguration defaults = ManagerRegistryTest.this.managers.chatConfigurations().resolve(-100L);

            assertFalse(defaults.enabled(), "a chat is off until somebody turns it on");
            assertTrue(defaults.scanningEnabled());
            assertEquals(ScanningBehavior.STRICT, defaults.scanningBehavior(),
                    "a chat that turns protection on gets the strongest protection by default");
            assertEquals(JoinProtectionBehavior.STRICT, defaults.joinProtectionBehavior());
            assertFalse(ManagerRegistryTest.this.managers.chatConfigurations().chatConfigurationExists(-100L));
        }

        @Test
        @DisplayName("an enabled chat starts with exactly the documented defaults")
        void enabledChatTakesSchemaDefaults() throws Exception
        {
            // New rows take their values from the schema's column defaults; the Java defaults
            // describe chats without a row. The two must never drift apart.
            ManagerRegistryTest.this.managers.chatConfigurations().enableChatConfiguration(-100L);

            ChatConfiguration stored = ManagerRegistryTest.this.reopen().chatConfigurations().resolve(-100L);

            ChatConfiguration d = new ChatConfiguration(-100L, false);
            assertEquals(new ChatConfiguration(d.chatId(), true, d.scanningEnabled(), d.scanningBehavior(),
                    d.joinProtectionEnabled(), d.joinProtectionBehavior(), d.joinProtectionNotificationsEnabled(),
                    d.privacyMode(), d.reportingEnabled(), d.moderatorNotificationsEnabled(),
                    d.scanningNotificationsEnabled(), d.reportingNotificationsEnabled(), d.channelLinkId(),
                    d.channelLinkVerificationCode(), d.channelLinkThreadId()), stored);
        }

        @Test
        @DisplayName("each setter changes only its own column")
        void settersChangeOneColumn() throws Exception
        {
            ChatConfigurationManager chats = ManagerRegistryTest.this.managers.chatConfigurations();
            chats.enableChatConfiguration(-100L);
            ChatConfiguration before = chats.resolve(-100L);

            chats.setScanningBehavior(-100L, ScanningBehavior.MODERATE);
            chats.setJoinProtectionBehavior(-100L, JoinProtectionBehavior.PASSIVE);
            chats.setPrivacyMode(-100L, true);
            chats.setReportingEnabled(-100L, false);

            ChatConfiguration after = ManagerRegistryTest.this.reopen().chatConfigurations().resolve(-100L);
            assertEquals(ScanningBehavior.MODERATE, after.scanningBehavior());
            assertEquals(JoinProtectionBehavior.PASSIVE, after.joinProtectionBehavior());
            assertTrue(after.privacyMode());
            assertFalse(after.reportingEnabled());
            assertEquals(before.scanningEnabled(), after.scanningEnabled());
            assertEquals(before.scanningNotificationsEnabled(), after.scanningNotificationsEnabled());
            assertEquals(before.moderatorNotificationsEnabled(), after.moderatorNotificationsEnabled());
        }

        @Test
        @DisplayName("a setter does not create a row for a chat that has none")
        void settersDoNotCreateRows() throws Exception
        {
            ManagerRegistryTest.this.managers.chatConfigurations().setScanningEnabled(-100L, false);

            assertFalse(ManagerRegistryTest.this.managers.chatConfigurations().chatConfigurationExists(-100L));
        }

        @Test
        @DisplayName("a linked chat is found by its link id and consumes its verification code")
        void findsByChannelLink() throws Exception
        {
            ChatConfigurationManager chats = ManagerRegistryTest.this.managers.chatConfigurations();
            chats.enableChatConfiguration(-100L);
            chats.setChannelLinkVerificationCode(-100L, 1234L);
            assertEquals(-100L, chats.getChatConfigurationByChannelLinkVerificationCode(1234L).orElseThrow().chatId());

            chats.linkChannel(-100L, -200L, 7L);

            ChatConfiguration linked = chats.getChatConfigurationByChannelLinkId(-200L).orElseThrow();
            assertEquals(-100L, linked.chatId());
            assertEquals(7L, linked.channelLinkThreadId());
            assertNull(linked.channelLinkVerificationCode());
            assertTrue(chats.getChatConfigurationByChannelLinkVerificationCode(1234L).isEmpty(),
                    "a used code no longer resolves");
        }

        @Test
        @DisplayName("an unlinked or relinked channel no longer resolves to the chat")
        void staleChannelLinksDoNotResolve() throws Exception
        {
            ChatConfigurationManager chats = ManagerRegistryTest.this.managers.chatConfigurations();
            chats.enableChatConfiguration(-100L);
            chats.linkChannel(-100L, -200L, null);
            assertTrue(chats.getChatConfigurationByChannelLinkId(-200L).isPresent());

            chats.linkChannel(-100L, -300L, null);
            assertTrue(chats.getChatConfigurationByChannelLinkId(-200L).isEmpty());
            assertTrue(chats.getChatConfigurationByChannelLinkId(-300L).isPresent());

            chats.unlinkChannel(-100L);
            assertTrue(chats.getChatConfigurationByChannelLinkId(-300L).isEmpty());
        }

        @Test
        @DisplayName("the privacy-mode default comes from the process configuration")
        void appliesPrivacyModeDefault() throws Exception
        {
            ManagerRegistry privacyOn = new ManagerRegistry(ManagerRegistryTest.this.database,
                    Configurations.privacyMode(ManagerRegistryTest.this.directory), LANGUAGES);

            assertTrue(privacyOn.chatConfigurations().defaultConfiguration(-1L).privacyMode());
            assertFalse(ManagerRegistryTest.this.managers.chatConfigurations().defaultConfiguration(-1L).privacyMode());
        }

        @Test
        @DisplayName("the escalation levels are offered in the same order everywhere")
        void behaviourLevelsShareOneOrder()
        {
            // The settings menu lists levels by iterating values(), so two enums declared in
            // different orders would put the same buttons in different places on two pages.
            assertEquals(List.of("PASSIVE", "MODERATE", "STRICT"),
                    Arrays.stream(ScanningBehavior.values()).map(Enum::name).toList());
            assertEquals(List.of("PASSIVE", "MODERATE", "STRICT"),
                    Arrays.stream(JoinProtectionBehavior.values()).map(Enum::name).toList());
        }

        @Test
        @DisplayName("a deleted configuration falls back to defaults again")
        void deletesConfiguration() throws Exception
        {
            ManagerRegistryTest.this.managers.chatConfigurations().enableChatConfiguration(-100L);
            ManagerRegistryTest.this.managers.chatConfigurations().deleteChatConfiguration(-100L);

            assertFalse(ManagerRegistryTest.this.managers.chatConfigurations().resolve(-100L).enabled());
        }
    }

    @Nested
    @DisplayName("Language preferences")
    class LanguagePreferences
    {
        @Test
        @DisplayName("an unset preference falls back to the bot's default language")
        void fallsBackWhenUnset()
        {
            assertEquals(english(), ManagerRegistryTest.this.managers.languagePreferences()
                    .getChatLanguage(-100L));
            assertEquals(english(), ManagerRegistryTest.this.managers.languagePreferences()
                    .getUserLanguage(42L));
        }

        @Test
        @DisplayName("chat and user preferences are stored independently")
        void storesChatAndUserSeparately() throws Exception
        {
            ManagerRegistryTest.this.managers.languagePreferences().setChatLanguage(-100L, LANGUAGES.resolve("zz"));
            ManagerRegistryTest.this.managers.languagePreferences().setUserLanguage(-100L, english());

            ManagerRegistry reopened = ManagerRegistryTest.this.reopen();

            assertEquals(LANGUAGES.resolve("zz"), reopened.languagePreferences()
                    .getChatLanguage(-100L));
            assertEquals(english(), reopened.languagePreferences()
                    .getUserLanguage(-100L));
        }

        @Test
        @DisplayName("a preference can be changed")
        void overwritesPreference() throws Exception
        {
            ManagerRegistryTest.this.managers.languagePreferences().setUserLanguage(42L, LANGUAGES.resolve("zz"));
            ManagerRegistryTest.this.managers.languagePreferences().setUserLanguage(42L, english());

            assertEquals(english(), ManagerRegistryTest.this.managers.languagePreferences()
                    .getUserLanguage(42L));
        }
    }

    @Nested
    @DisplayName("Operators")
    class Operators
    {
        @Test
        @DisplayName("a credential is stored, listed, and removed")
        void storesListsAndRemoves() throws Exception
        {
            ManagerRegistryTest.this.managers.operators().saveOperator(42L,
                    new OperatorIdentity("uuid-1", "token-1"));

            Optional<OperatorIdentity> stored = ManagerRegistryTest.this.managers.operators().getOperator(42L);
            assertTrue(stored.isPresent());
            assertEquals("token-1", stored.get().accessToken());

            Map<Long, OperatorIdentity> all = ManagerRegistryTest.this.managers.operators().listOperators();
            assertEquals(1, all.size());
            assertEquals("uuid-1", all.get(42L).operatorUuid());

            ManagerRegistryTest.this.managers.operators().deleteOperator(42L);
            assertTrue(ManagerRegistryTest.this.managers.operators().getOperator(42L).isEmpty());
        }

        @Test
        @DisplayName("re-authenticating replaces the stored credential")
        void replacesCredential() throws Exception
        {
            ManagerRegistryTest.this.managers.operators().saveOperator(42L, new OperatorIdentity("uuid", "old"));
            ManagerRegistryTest.this.managers.operators().saveOperator(42L, new OperatorIdentity("uuid", "new"));

            assertEquals("new", ManagerRegistryTest.this.reopen().operators().getOperator(42L)
                    .orElseThrow().accessToken());
        }

        @Test
        @DisplayName("an unauthenticated user has no credential")
        void unknownOperatorIsAbsent()
        {
            assertTrue(ManagerRegistryTest.this.managers.operators().getOperator(404L).isEmpty());
        }
    }

    @Nested
    @DisplayName("Secretary mode")
    class Secretary
    {
        @Test
        @DisplayName("a configuration is stored and found by its business connection")
        void storesAndFindsByConnection() throws Exception
        {
            ManagerRegistryTest.this.managers.secretaryConfigurations().setBusinessConnectionId(42L, "conn-1");

            ManagerRegistry reopened = ManagerRegistryTest.this.reopen();

            assertTrue(reopened.secretaryConfigurations().secretaryConfigurationExists(42L));
            assertEquals(Optional.of(42L),
                    reopened.secretaryConfigurations().userIdByBusinessConnection("conn-1"));
        }

        @Test
        @DisplayName("secretary mode defaults to strict, and a created row matches the default")
        void defaultsToStrict() throws Exception
        {
            SecretaryConfigurationManager secretary = ManagerRegistryTest.this.managers.secretaryConfigurations();
            assertEquals(ScanningBehavior.STRICT, secretary.defaultConfiguration(42L).behavior());

            secretary.createSecretaryConfiguration(42L);

            assertEquals(secretary.defaultConfiguration(42L),
                    ManagerRegistryTest.this.reopen().secretaryConfigurations().resolve(42L));
        }

        @Test
        @DisplayName("the behavior is changed on its own and a connection moves with its owner")
        void changesBehaviorAndConnection() throws Exception
        {
            SecretaryConfigurationManager secretary = ManagerRegistryTest.this.managers.secretaryConfigurations();
            secretary.setBusinessConnectionId(42L, "conn-1");
            secretary.setBehavior(42L, ScanningBehavior.PASSIVE);
            assertEquals(Optional.of(42L), secretary.userIdByBusinessConnection("conn-1"));

            secretary.setBusinessConnectionId(42L, "conn-2");

            SecretaryConfiguration stored = ManagerRegistryTest.this.reopen().secretaryConfigurations().resolve(42L);
            assertEquals(ScanningBehavior.PASSIVE, stored.behavior());
            assertEquals("conn-2", stored.businessConnectionId());
            assertTrue(secretary.userIdByBusinessConnection("conn-1").isEmpty(), "the old connection is released");
            assertThrows(IllegalArgumentException.class, () -> secretary.setBehavior(42L, ScanningBehavior.MODERATE));
        }

        @Test
        @DisplayName("a contact starts unknown and can be allowed or denied")
        void tracksContactStatus() throws Exception
        {
            ManagerRegistryTest.this.managers.secretaryContacts().registerSecretaryContact(
                    new SecretaryContact("conn-1", 99L, SecretaryContactStatus.UNKNOWN, 1_700_000_000L));

            assertEquals(SecretaryContactStatus.UNKNOWN, ManagerRegistryTest.this.managers.secretaryContacts()
                    .getSecretaryContact("conn-1", 99L).orElseThrow().status());

            ManagerRegistryTest.this.managers.secretaryContacts()
                    .setSecretaryContactStatus("conn-1", 99L, SecretaryContactStatus.ALLOWED);
            assertEquals(SecretaryContactStatus.ALLOWED, ManagerRegistryTest.this.managers.secretaryContacts()
                    .getSecretaryContact("conn-1", 99L).orElseThrow().status());

            ManagerRegistryTest.this.managers.secretaryContacts()
                    .setSecretaryContactStatus("conn-1", 99L, SecretaryContactStatus.DENIED);
            assertEquals(SecretaryContactStatus.DENIED, ManagerRegistryTest.this.reopen().secretaryContacts()
                    .getSecretaryContact("conn-1", 99L).orElseThrow().status());
        }

        @Test
        @DisplayName("deciding on a contact who has not written yet starts tracking them")
        void decidesOnUntrackedContacts() throws Exception
        {
            ManagerRegistryTest.this.managers.secretaryContacts()
                    .setSecretaryContactStatus("conn-1", 99L, SecretaryContactStatus.DENIED);

            assertEquals(SecretaryContactStatus.DENIED, ManagerRegistryTest.this.reopen().secretaryContacts()
                    .getSecretaryContact("conn-1", 99L).orElseThrow().status());
        }

        @Test
        @DisplayName("disconnecting removes every contact of that connection")
        void deletesContactsOnDisconnect() throws Exception
        {
            ManagerRegistryTest.this.managers.secretaryContacts().registerSecretaryContact(
                    new SecretaryContact("conn-1", 99L, SecretaryContactStatus.ALLOWED, 1L));
            ManagerRegistryTest.this.managers.secretaryContacts().registerSecretaryContact(
                    new SecretaryContact("conn-2", 99L, SecretaryContactStatus.ALLOWED, 1L));

            ManagerRegistryTest.this.managers.secretaryContacts().deleteSecretaryContacts("conn-1");

            assertTrue(ManagerRegistryTest.this.managers.secretaryContacts()
                    .getSecretaryContact("conn-1", 99L).isEmpty());
            assertTrue(ManagerRegistryTest.this.managers.secretaryContacts()
                    .getSecretaryContact("conn-2", 99L).isPresent(), "other connections are untouched");
        }
    }
}
