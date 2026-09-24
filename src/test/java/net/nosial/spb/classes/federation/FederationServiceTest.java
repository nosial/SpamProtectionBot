package net.nosial.spb.classes.federation;

import net.nosial.jfederation.enums.EntityRelationshipType;
import net.nosial.jfederation.enums.IncidentType;
import net.nosial.jfederation.records.ContentInput;
import net.nosial.spb.classes.FederationService;
import net.nosial.spb.exceptions.FederationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the Federation seam: the unavailable case and the submission request it carries.
 *
 * <p>The live implementation is not exercised here — that would mean standing up a Federation
 * server — but the contract that every handler depends on is: Federation being absent must be an
 * ordinary, well-behaved state rather than a null reference waiting to be dereferenced.
 */
class FederationServiceTest
{
    private final FederationService unavailable = FederationService.unavailable();

    @Nested
    @DisplayName("Running without a Federation server")
    class Unavailable
    {
        @Test
        @DisplayName("it reports itself unavailable instead of pretending")
        void reportsUnavailable()
        {
            assertFalse(FederationServiceTest.this.unavailable.isAvailable());
            assertNull(FederationServiceTest.this.unavailable.endpoint());
            assertNull(FederationServiceTest.this.unavailable.host());
        }

        @Test
        @DisplayName("every operation fails with a checked exception, never a NullPointerException")
        void everyOperationFailsCleanly()
        {
            assertUnavailable(FederationServiceTest.this.unavailable::serverInformation);
            assertUnavailable(FederationServiceTest.this.unavailable::self);
            assertUnavailable(() -> FederationServiceTest.this.unavailable.operatorFor("token"));
            assertUnavailable(() -> FederationServiceTest.this.unavailable.entity("1@telegram.org"));
            assertUnavailable(() -> FederationServiceTest.this.unavailable.queryEntity("1@telegram.org"));
            assertUnavailable(() -> FederationServiceTest.this.unavailable.publishEntity("telegram.org", "1", null));
            assertUnavailable(() -> FederationServiceTest.this.unavailable.linkEntities(null, "a", "b",
                    EntityRelationshipType.ALTERNATIVE));
            assertUnavailable(() -> FederationServiceTest.this.unavailable.scanContent(new ContentInput("hi"), null));
            assertUnavailable(() -> FederationServiceTest.this.unavailable.activeBlacklists("1@telegram.org"));
            assertUnavailable(() -> FederationServiceTest.this.unavailable.blacklistEntity(null, "e", "r",
                    IncidentType.SPAM, 60));
            assertUnavailable(() -> FederationServiceTest.this.unavailable.report("uuid"));
            assertUnavailable(() -> FederationServiceTest.this.unavailable.entityReports("e", 5));
            assertUnavailable(() -> FederationServiceTest.this.unavailable.openAssignedReports("token", 5));
            assertUnavailable(() -> FederationServiceTest.this.unavailable.submitReport(null, "1@telegram.org",
                    new ContentInput("buy now"), IncidentType.SPAM, null));
            assertUnavailable(() -> FederationServiceTest.this.unavailable.closeReport("token", "uuid", null));
            assertUnavailable(() -> FederationServiceTest.this.unavailable.evidence("uuid"));
            assertUnavailable(() -> FederationServiceTest.this.unavailable.evidenceAs("token", "uuid"));
            assertUnavailable(() -> FederationServiceTest.this.unavailable.reportEvidence("uuid", 5));
            assertUnavailable(() -> FederationServiceTest.this.unavailable.evidenceAttachments(null, "uuid"));
            assertUnavailable(() -> FederationServiceTest.this.unavailable.downloadAttachment(null, "uuid", "/tmp"));
            assertUnavailable(() -> FederationServiceTest.this.unavailable.uploadAttachment(null, "uuid", "/tmp/f", "f"));
        }

        @Test
        @DisplayName("the failure says why")
        void failureExplainsItself()
        {
            FederationException e = assertThrows(FederationException.class,
                    FederationServiceTest.this.unavailable::serverInformation);

            assertTrue(e.getMessage().contains("not configured"), e.getMessage());
        }

        @Test
        @DisplayName("closing it is harmless and it is a singleton")
        void closingIsHarmless()
        {
            FederationServiceTest.this.unavailable.close();
            FederationServiceTest.this.unavailable.close();

            assertSame(FederationService.unavailable(), FederationServiceTest.this.unavailable);
        }

        /**
         * Asserts that the given call fails with a {@link FederationException}.
         *
         * @param call the call expected to fail
         */
        private void assertUnavailable(FederationCall call)
        {
            assertThrows(FederationException.class, call::execute);
        }

        /** A call that may fail with a {@link FederationException}. */
        @FunctionalInterface
        private interface FederationCall
        {
            /**
             * Runs the call.
             *
             * @throws FederationException when the server is unavailable
             */
            void execute() throws FederationException;
        }
    }

    @Nested
    @DisplayName("Connected without client permissions")
    class Unauthenticated
    {
        /**
         * A reserved, unroutable port: connecting to it fails immediately rather than timing out,
         * so a test only reaches the network if a code path that should not is exercised.
         */
        private final FederationService anonymous = new FederationService("http://127.0.0.1:1/", null);

        @Test
        @DisplayName("an anonymous client is available but not authenticated")
        void anonymousIsAvailableButNotAuthenticated()
        {
            assertTrue(this.anonymous.isAvailable());
            assertFalse(this.anonymous.isAuthenticated());
        }

        @Test
        @DisplayName("authenticating an anonymous client is a no-op, since there is no token to check")
        void authenticatingAnonymousIsANoOp() throws FederationException
        {
            assertNull(this.anonymous.authenticate());
            assertFalse(this.anonymous.isAuthenticated());
        }

        @Test
        @DisplayName("publishing an entity as the bot is refused without reaching the network")
        void publishEntityRequiresClientPermissions()
        {
            FederationException e = assertThrows(FederationException.class,
                    () -> this.anonymous.publishEntity("telegram.org", "1", null));
            assertTrue(e.getMessage().contains("client permissions"), e.getMessage());
        }

        @Test
        @DisplayName("submitting a report as the bot is refused without reaching the network")
        void submitReportAsBotRequiresClientPermissions()
        {
            FederationException e = assertThrows(FederationException.class,
                    () -> this.anonymous.submitReport(null, "1@telegram.org", new ContentInput("hi"),
                            IncidentType.SPAM, null));
            assertTrue(e.getMessage().contains("client permissions"), e.getMessage());
        }

        @Test
        @DisplayName("uploading an attachment as the bot is refused without reaching the network")
        void uploadAttachmentAsBotRequiresClientPermissions()
        {
            FederationException e = assertThrows(FederationException.class,
                    () -> this.anonymous.uploadAttachment(null, "uuid", "/tmp/f", "f"));
            assertTrue(e.getMessage().contains("client permissions"), e.getMessage());
        }
    }

}
