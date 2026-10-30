package net.nosial.spb.utilities;

import net.nosial.jfederation.records.ScanFile;
import net.nosial.jfederation.records.ServerInformation;
import net.nosial.spb.exceptions.FederationException;
import net.nosial.spb.objects.context.HandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.photo.PhotoSize;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Collects the images of a Telegram message to submit with its content for scanning (OFD 2.1 "Scanned files").
 *
 * <p>Every Federation server accepts files and includes what it finds in them in the result, a server that cannot
 * interpret a file ignores it. Only a server that publishes no file limits ({@code max_scan_files} of 0) ignores every
 * file, so its scans cost no download. Privacy Mode never sends files, it promises to send nothing but the text
 * itself.
 *
 * <p>The images are downloaded and carried in the request rather than named by URL (OFD 2.2 "Scanned file URLs"):
 * the download URL of a Telegram file carries the bot's token, which must never be disclosed to a Federation server.
 */
public final class ScanFiles
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ScanFiles.class);

    /**
     * A file of a message that may be scanned.
     *
     * @param fileId Telegram file identifier used with {@code getFile}
     * @param fileName the file name sent to the server, or {@code null}
     */
    public record Candidate(String fileId, String fileName)
    {
    }

    private ScanFiles()
    {
    }

    /**
     * Returns whether the message carries an image that could be scanned with it: a photo or an image document.
     *
     * @param message Telegram message
     * @return {@code true} when the message carries an image
     */
    public static boolean hasImage(Message message)
    {
        return (message.getPhoto() != null && !message.getPhoto().isEmpty()) || isImage(message.getDocument());
    }

    /**
     * Selects the images of the message within the limits of the server: the largest photo size that fits
     * {@code max_scan_file_size}, and an image document. A file whose size Telegram does not report is selected
     * and checked once it is downloaded.
     *
     * @param message Telegram message
     * @param information the server information of the Federation server
     * @return the files to download, at most {@code max_scan_files}
     */
    public static List<Candidate> candidates(Message message, ServerInformation information)
    {
        if (information.maxScanFiles() <= 0 || information.maxScanFileSize() <= 0)
        {
            return List.of();
        }

        List<Candidate> candidates = new ArrayList<>();
        if (message.getPhoto() != null)
        {
            message.getPhoto().stream()
                    .filter(photo -> photo.getFileId() != null && !photo.getFileId().isBlank())
                    .filter(photo -> photo.getFileSize() == null || photo.getFileSize() <= information.maxScanFileSize())
                    .max(Comparator.comparingLong(ScanFiles::area))
                    .ifPresent(photo -> candidates.add(new Candidate(photo.getFileId(), null)));
        }

        Document document = message.getDocument();
        if (isImage(document)
                && document.getFileId() != null && !document.getFileId().isBlank()
                && (document.getFileSize() == null || document.getFileSize() <= information.maxScanFileSize()))
        {
            candidates.add(new Candidate(document.getFileId(), document.getFileName()));
        }

        return candidates.size() > information.maxScanFiles()
                ? List.copyOf(candidates.subList(0, information.maxScanFiles()))
                : List.copyOf(candidates);
    }

    /**
     * Downloads the images of the message to submit with its content. Failures are logged and skipped, the message
     * is then scanned without the file.
     *
     * @param context the per-update command context
     * @param message Telegram message
     * @return the files to submit with the content, empty when the server ignores files
     */
    public static List<ScanFile> collect(HandlerContext context, Message message)
    {
        if (!context.federation().isAvailable() || !hasImage(message))
        {
            return List.of();
        }

        ServerInformation information;
        try
        {
            information = context.federation().scanInformation();
        }
        catch (FederationException e)
        {
            LOGGER.debug("Unable to read the scanned file limits of the server, scanning without files: {}", e.getMessage());
            return List.of();
        }

        List<ScanFile> files = new ArrayList<>();
        for (Candidate candidate : candidates(message, information))
        {
            File downloaded = null;
            try
            {
                downloaded = context.telegramClient().downloadFile(
                        context.telegramClient().execute(GetFile.builder().fileId(candidate.fileId()).build()));
                long size = Files.size(downloaded.toPath());
                if (size == 0 || size > information.maxScanFileSize())
                {
                    LOGGER.debug("Skipping Telegram file {} of {} bytes for scanning", candidate.fileId(), size);
                    continue;
                }
                files.add(new ScanFile(candidate.fileName(), Files.readAllBytes(downloaded.toPath())));
            }
            catch (Exception e)
            {
                LOGGER.warn("Failed to download Telegram file {} for scanning: {}", candidate.fileId(), e.getMessage());
            }
            finally
            {
                if (downloaded != null)
                {
                    try
                    {
                        Files.deleteIfExists(downloaded.toPath());
                    }
                    catch (Exception e)
                    {
                        LOGGER.debug("Could not delete temporary download {}: {}", downloaded, e.getMessage());
                    }
                }
            }
        }
        return List.copyOf(files);
    }

    private static boolean isImage(Document document)
    {
        return document != null && document.getMimeType() != null
                && document.getMimeType().toLowerCase(Locale.ROOT).startsWith("image/");
    }

    private static long area(PhotoSize photo)
    {
        return (long) (photo.getWidth() != null ? photo.getWidth() : 0) * (photo.getHeight() != null ? photo.getHeight() : 0);
    }
}
