package net.nosial.spb.classes;

import net.nosial.jfederation.records.EntityQueryResult;
import net.nosial.spb.objects.Language;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds links into a Federation Web Application instance, so a message displaying a Federation
 * record can offer a button opening that same record in the browser.
 *
 * <p>The web application is optional: when {@code federation.web_application_endpoint} is not
 * configured, every method here yields nothing and callers send their messages unchanged. Callers
 * therefore never branch on whether links are enabled themselves; they pass whatever
 * {@link #button} returns to {@link #attach}, which skips a {@code null} button.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class FederationWebLinks
{
    /** Links that are never produced, used when no web application is configured. */
    public static final FederationWebLinks DISABLED = new FederationWebLinks(null);

    /**
     * A Federation record type the web application has a detail page for.
     */
    public enum Record
    {
        REPORT("reports"),
        EVIDENCE("evidence"),
        ENTITY("entities"),
        BLACKLIST("blacklist"),
        OPERATOR("operators");

        private final String path;

        Record(String path)
        {
            this.path = path;
        }
    }

    private final String baseUrl;

    /**
     * Creates the links for the given web application endpoint.
     *
     * @param endpoint the web application base URL, or {@code null} to disable links
     */
    public FederationWebLinks(String endpoint)
    {
        this.baseUrl = endpoint == null || endpoint.isBlank() ? null
                : (endpoint.endsWith("/") ? endpoint : endpoint + "/");
    }

    /**
     * Creates the links configured for this process.
     *
     * @param configuration the process configuration
     * @return the configured links, or {@link #DISABLED} when no web application is configured
     */
    public static FederationWebLinks from(Configuration configuration)
    {
        String endpoint = configuration.getFederationWebApplicationEndpoint();
        return endpoint == null ? DISABLED : new FederationWebLinks(endpoint);
    }

    /**
     * Returns whether a web application is configured.
     *
     * @return {@code true} when links are produced
     */
    public boolean isEnabled()
    {
        return this.baseUrl != null;
    }

    /**
     * Returns the URL of a record's detail page in the web application.
     *
     * @param record the record type
     * @param id the record UUID (or, for an entity, any identifier the web application resolves)
     * @return the detail page URL, or {@code null} when links are disabled or the id is blank
     */
    public String url(Record record, String id)
    {
        if (this.baseUrl == null || id == null || id.isBlank())
        {
            return null;
        }

        return this.baseUrl + record.path + "/" + URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * Returns a button opening a record's detail page in the web application.
     *
     * @param lm the translations
     * @param lang the language of the message the button is attached to
     * @param record the record type
     * @param id the record UUID
     * @return the button, or {@code null} when links are disabled or the id is blank
     */
    public InlineKeyboardButton button(LanguageManager lm, Language lang, Record record, String id)
    {
        String url = url(record, id);
        if (url == null)
        {
            return null;
        }

        String label = switch (record)
        {
            case REPORT -> lm.get(lang, "buttons", "view_report");
            case EVIDENCE -> lm.get(lang, "buttons", "view_evidence");
            case ENTITY -> lm.get(lang, "buttons", "view_entity");
            case BLACKLIST -> lm.get(lang, "buttons", "view_blacklist");
            case OPERATOR -> lm.get(lang, "buttons", "view_operator");
        };
        return InlineKeyboardButton.builder().text(label).url(url).build();
    }

    /**
     * Returns a button opening the entity an entity query resolved to.
     *
     * @param lm the translations
     * @param lang the language of the message the button is attached to
     * @param query the entity query, or {@code null} when the entity was not queried
     * @return the button, or {@code null} when links are disabled or the query resolved no entity
     */
    public InlineKeyboardButton entityButton(LanguageManager lm, Language lang, EntityQueryResult query)
    {
        return query == null || query.entityRecord() == null ? null
                : button(lm, lang, Record.ENTITY, query.entityRecord().uuid());
    }

    /**
     * Returns the keyboard with the given link buttons appended as one extra row, after any rows it
     * already has. {@code null} buttons are skipped, so the result of {@link #button} can be passed
     * straight in.
     *
     * @param markup the existing keyboard, or {@code null} for none
     * @param buttons the link buttons to append
     * @return the extended keyboard; the original {@code markup} (possibly {@code null}) when there
     *         is no button to append
     */
    public static InlineKeyboardMarkup attach(InlineKeyboardMarkup markup, InlineKeyboardButton... buttons)
    {
        List<InlineKeyboardButton> present = new ArrayList<>();
        for (InlineKeyboardButton button : buttons)
        {
            if (button != null)
            {
                present.add(button);
            }
        }
        if (present.isEmpty())
        {
            return markup;
        }

        List<InlineKeyboardRow> rows = new ArrayList<>();
        if (markup != null && markup.getKeyboard() != null)
        {
            rows.addAll(markup.getKeyboard());
        }
        rows.add(new InlineKeyboardRow(present));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    /**
     * Returns a replacement keyboard followed by the link buttons of the keyboard it replaces, so
     * editing a message's actions does not drop its link to the web application.
     *
     * @param replacement the new keyboard, or {@code null} for none
     * @param previous the keyboard being replaced, or {@code null} for none
     * @return the replacement keyboard with the previous link rows appended
     */
    public static InlineKeyboardMarkup keepLinks(InlineKeyboardMarkup replacement, InlineKeyboardMarkup previous)
    {
        List<InlineKeyboardRow> links = linksOnly(previous).getKeyboard();
        if (links.isEmpty())
        {
            return replacement;
        }

        List<InlineKeyboardRow> rows = new ArrayList<>();
        if (replacement != null && replacement.getKeyboard() != null)
        {
            rows.addAll(replacement.getKeyboard());
        }
        rows.addAll(links);
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    /**
     * Returns the keyboard left once every button that is not a link is removed, so a message whose
     * actions have been used up keeps its link to the web application.
     *
     * @param markup the current keyboard, or {@code null} for none
     * @return the keyboard holding only link buttons, empty when there are none
     */
    public static InlineKeyboardMarkup linksOnly(InlineKeyboardMarkup markup)
    {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        if (markup != null && markup.getKeyboard() != null)
        {
            for (InlineKeyboardRow row : markup.getKeyboard())
            {
                List<InlineKeyboardButton> links = row.stream().filter(Objects::nonNull).filter(b -> b.getUrl() != null).toList();
                if (!links.isEmpty())
                {
                    rows.add(new InlineKeyboardRow(links));
                }
            }
        }
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }
}
