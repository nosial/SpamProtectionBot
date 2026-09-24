package net.nosial.spb.utilities;

import net.nosial.spb.classes.UpdateDispatcher;
import net.nosial.spb.objects.context.HandlerContext;
import net.nosial.spb.objects.MediaGroupState;
import net.nosial.spb.objects.ReportAttachment;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Collects every primary downloadable file carried by a Telegram message. */
public final class ReportAttachments
{
    /**
     * Returns the message's unique, downloadable file attachments in stable presentation order.
     * Thumbnails are deliberately excluded; Telegram exposes them as derivatives of a primary file.
     *
     * @param message target message of a report
     * @return immutable attachment descriptors
     */
    public static List<ReportAttachment> collect(Message message)
    {
        Map<String, ReportAttachment> attachments = new LinkedHashMap<>();
        if (message.getDocument() != null)
        {
            add(attachments, message.getDocument().getFileId(), message.getDocument().getFileName());
        }
        if (message.getAudio() != null)
        {
            add(attachments, message.getAudio().getFileId(), message.getAudio().getFileName());
        }
        if (message.getVideo() != null)
        {
            add(attachments, message.getVideo().getFileId(), message.getVideo().getFileName());
        }
        if (message.getAnimation() != null)
        {
            add(attachments, message.getAnimation().getFileId(), message.getAnimation().getFileName());
        }
        if (message.getVoice() != null)
        {
            add(attachments, message.getVoice().getFileId(), null);
        }
        if (message.getVideoNote() != null)
        {
            add(attachments, message.getVideoNote().getFileId(), null);
        }
        if (message.getSticker() != null)
        {
            add(attachments, message.getSticker().getFileId(), null);
        }
        if (message.getPhoto() != null && !message.getPhoto().isEmpty())
        {
            message.getPhoto().stream()
                    .filter(photo -> photo.getFileId() != null && !photo.getFileId().isBlank())
                    .max(Comparator.comparingInt(photo -> photo.getFileSize() != null ? photo.getFileSize() : 0))
                    .ifPresent(largest -> add(attachments, largest.getFileId(), null));
        }
        return List.copyOf(new ArrayList<>(attachments.values()));
    }

    /**
     * Adds a {@link ReportAttachment} to the given map of attachments if the provided file ID is
     * valid and not already present in the map.
     *
     * @param attachments the map of file ID to {@link ReportAttachment} where the new attachment
     *                    will be added
     * @param fileId the unique identifier of the file to be added; if null or blank, the method
     *               will do nothing
     * @param fileName the name of the file associated with the file ID; used as metadata for
     *                 the attachment
     */
    private static void add(Map<String, ReportAttachment> attachments, String fileId, String fileName)
    {
        if (fileId == null || fileId.isBlank())
        {
            return;
        }
        attachments.putIfAbsent(fileId, new ReportAttachment(fileId, fileName));
    }

    /**
     * Collects all downloadable file attachments associated with the given message. If the message
     * belongs to a media group with attachments, those group attachments are returned. Otherwise,
     * attachments are collected directly from the message.
     *
     * @param context the handler context used to determine media group associations
     * @param message the message being evaluated for attachments
     * @return an immutable list of attachments for the given message, either from its media group or as
     *         individual message-level attachments
     */
    public static List<ReportAttachment> forReport(HandlerContext context, Message message)
    {
        MediaGroupState mediaGroup = UpdateDispatcher.mediaGroup(context, message);
        if (mediaGroup == null || mediaGroup.attachments().isEmpty())
        {
            return ReportAttachments.collect(message);
        }
        return List.copyOf(mediaGroup.attachments().values());
    }
}
