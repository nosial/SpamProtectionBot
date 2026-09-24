package net.nosial.spb.classes.managers;

import net.nosial.spb.classes.Configuration;
import net.nosial.spb.classes.Database;
import net.nosial.spb.classes.LanguageManager;

import java.util.Objects;

/**
 * The managers that own what the bot stores, one per table.
 *
 * <p>{@link Database} answers "how do we reach SQLite"; the managers answer "what do we keep in
 * it". Keeping the two apart means a manager can be reasoned about as plain record-keeping, and
 * the pool, the schemas, and the transaction semantics stay in one place that no manager has to
 * know about. This registry is the seam between them: it is built once over an open database and
 * handed to every handler through the context.
 *
 * <p>Each manager caches its hot lookups in memory, so a chat's configuration is read from SQLite
 * once rather than on every message. All of them are thread-safe.
 */
public final class ManagerRegistry
{
    private final UserManager users;
    private final OperatorManager operators;
    private final ChatConfigurationManager chatConfigurations;
    private final SecretaryConfigurationManager secretaryConfigurations;
    private final SecretaryContactManager secretaryContacts;
    private final LanguagePreferenceManager languagePreferences;

    /**
     * Builds the manager layer over an open database.
     *
     * <p>Takes the objects the managers work with rather than the individual settings they need:
     * the privacy default new chats start with is read off the configuration here, so adding a
     * setting a manager cares about does not widen this signature.
     *
     * @param database the open database
     * @param configuration the validated process configuration
     * @param languages the translations, used to resolve stored language codes
     */
    public ManagerRegistry(Database database, Configuration configuration, LanguageManager languages)
    {
        Objects.requireNonNull(database, "database must not be null");
        Objects.requireNonNull(configuration, "configuration must not be null");
        Objects.requireNonNull(languages, "languages must not be null");

        boolean privacyMode = configuration.isPrivacyMode();

        this.users = new UserManager(database);
        this.operators = new OperatorManager(database);
        this.chatConfigurations = new ChatConfigurationManager(database, privacyMode);
        this.secretaryConfigurations = new SecretaryConfigurationManager(database);
        this.secretaryContacts = new SecretaryContactManager(database);
        this.languagePreferences = new LanguagePreferenceManager(database, languages);
    }

    /**
     * Returns the manager for the {@code users} table.
     *
     * @return the user manager
     */
    public UserManager users()
    {
        return this.users;
    }

    /**
     * Returns the manager for the {@code operators} table.
     *
     * @return the operator manager
     */
    public OperatorManager operators()
    {
        return this.operators;
    }

    /**
     * Returns the manager for the {@code chat_configuration} table.
     *
     * @return the chat configuration manager
     */
    public ChatConfigurationManager chatConfigurations()
    {
        return this.chatConfigurations;
    }

    /**
     * Returns the manager for the {@code secretary_configuration} table.
     *
     * @return the secretary configuration manager
     */
    public SecretaryConfigurationManager secretaryConfigurations()
    {
        return this.secretaryConfigurations;
    }

    /**
     * Returns the manager for the {@code secretary_contacts} table.
     *
     * @return the secretary contact manager
     */
    public SecretaryContactManager secretaryContacts()
    {
        return this.secretaryContacts;
    }

    /**
     * Returns the manager for the {@code language_preferences} table.
     *
     * @return the language preference manager
     */
    public LanguagePreferenceManager languagePreferences()
    {
        return this.languagePreferences;
    }
}
