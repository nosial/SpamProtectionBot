package net.nosial.spb.objects;

/**
 * Downloadable Telegram file attached to a message being reported.
 *
 * <p>The record normally holds only the Telegram {@code fileId} reference, which is resolved to
 * actual bytes lazily at report-submission time. When the source message may be deleted before the
 * report is submitted (for example a scanning false positive), the bytes can instead be captured
 * eagerly at scan time and carried in {@code content} so the attachment survives message removal.
 *
 * @param fileId Telegram file identifier used with {@code getFile}
 * @param fileName name supplied to the Federation attachment upload
 * @param content eagerly captured raw file bytes, or {@code null} when the file is resolved lazily
 */
public record ReportAttachment(String fileId, String fileName, byte[] content)
{
    public ReportAttachment(String fileId, String fileName)
    {
        this(fileId, fileName, null);
    }

    public ReportAttachment
    {
        content = content == null ? null : content.clone();
    }

    /**
     * Returns an equivalent attachment carrying eagerly captured bytes.
     *
     * @param attachment the attachment without byte content
     * @param content the raw file bytes, or {@code null} to keep lazy resolution
     * @return the enriched attachment
     */
    public static ReportAttachment withContent(ReportAttachment attachment, byte[] content)
    {
        return new ReportAttachment(attachment.fileId(), attachment.fileName(), content);
    }

    @Override
    public byte[] content()
    {
        return content == null ? null : content.clone();
    }
}
