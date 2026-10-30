package net.nosial.spb.utilities;

import net.nosial.jfederation.classes.Json;
import net.nosial.jfederation.records.ServerInformation;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.photo.PhotoSize;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanFilesTest
{
    private static ServerInformation limits(int maxFiles, long maxFileSize)
    {
        return Json.readValue("{\"max_scan_files\": " + maxFiles + ", \"max_scan_file_size\": " + maxFileSize + "}",
                ServerInformation.class);
    }

    private static PhotoSize photo(String fileId, int side, Integer fileSize)
    {
        return PhotoSize.builder().fileId(fileId).fileUniqueId(fileId).width(side).height(side).fileSize(fileSize).build();
    }

    private static Document document(String fileId, String mimeType, Long fileSize)
    {
        return Document.builder().fileId(fileId).fileUniqueId(fileId).fileName(fileId + ".png").mimeType(mimeType)
                .fileSize(fileSize).build();
    }

    @Test
    void selectsTheLargestPhotoWithinTheSizeLimit()
    {
        Message message = new Message();
        message.setPhoto(List.of(photo("small", 90, 1_000), photo("medium", 320, 20_000), photo("large", 1280, 200_000)));

        assertEquals(List.of(new ScanFiles.Candidate("medium", null)), ScanFiles.candidates(message, limits(4, 100_000)));
        assertEquals(List.of(new ScanFiles.Candidate("large", null)), ScanFiles.candidates(message, limits(4, 10_485_760)));
    }

    @Test
    void selectsImageDocumentsOnly()
    {
        Message image = new Message();
        image.setDocument(document("qr", "image/png", 5_000L));
        assertTrue(ScanFiles.hasImage(image));
        assertEquals(List.of(new ScanFiles.Candidate("qr", "qr.png")), ScanFiles.candidates(image, limits(4, 10_485_760)));

        Message archive = new Message();
        archive.setDocument(document("archive", "application/zip", 5_000L));
        assertFalse(ScanFiles.hasImage(archive));
        assertEquals(List.of(), ScanFiles.candidates(archive, limits(4, 10_485_760)));
    }

    @Test
    void selectsNothingFromAServerThatIgnoresFiles()
    {
        Message message = new Message();
        message.setPhoto(List.of(photo("photo", 320, 20_000)));

        assertEquals(List.of(), ScanFiles.candidates(message, limits(0, 0)));
        assertEquals(List.of(), ScanFiles.candidates(message, Json.readValue("{}", ServerInformation.class)));
    }

    @Test
    void keepsWithinTheFileLimit()
    {
        Message message = new Message();
        message.setPhoto(List.of(photo("photo", 320, 20_000)));
        message.setDocument(document("qr", "image/png", 5_000L));

        assertEquals(List.of(new ScanFiles.Candidate("photo", null)), ScanFiles.candidates(message, limits(1, 10_485_760)));
        assertEquals(2, ScanFiles.candidates(message, limits(4, 10_485_760)).size());
    }
}
