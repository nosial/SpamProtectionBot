package net.nosial.spb.utilities;

import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.support.Contexts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests which events let {@link EntityPublisher} send an entity's properties.
 */
class EntityPublisherTest
{
    private static final long GROUP = -1004001234567L;

    @TempDir
    Path directory;

    @Test
    @DisplayName("a private chat never applies privacy mode, even when the default enables it")
    void privateChatIgnoresPrivacy()
    {
        HandlerContext context = Contexts.template(this.directory);
        assertFalse(EntityPublisher.privacyApplies(context, 42L));
    }

    @Test
    @DisplayName("a group applies its own privacy mode")
    void groupFollowsItsPrivacyMode() throws Exception
    {
        HandlerContext context = Contexts.template(this.directory);
        context.managers().chatConfigurations().enableChatConfiguration(GROUP);

        context.managers().chatConfigurations().setPrivacyMode(GROUP, true);
        assertTrue(EntityPublisher.privacyApplies(context, GROUP));

        context.managers().chatConfigurations().setPrivacyMode(GROUP, false);
        assertFalse(EntityPublisher.privacyApplies(context, GROUP));
    }
}
