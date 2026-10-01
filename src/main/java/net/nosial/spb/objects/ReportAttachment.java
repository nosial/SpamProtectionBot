package net.nosial.spb.objects;

/**
 * Downloadable Telegram file attached to a message being reported.
 *
 * <p>The record holds only the Telegram {@code fileId} reference, which is resolved to actual bytes
 * lazily at report-submission time.
 *
 * @param fileId Telegram file identifier used with {@code getFile}
 * @param fileName name supplied to the Federation attachment upload
 */
public record ReportAttachment(String fileId, String fileName)
{
}
