package ru.gang.newsBot.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;

@Service
@RequiredArgsConstructor
public class NewsPosterService {

    private static final String CHANNEL_ID = "-1002420518377";
    private static final int MAX_CAPTION_LENGTH = 1024;

    public SendPhoto buildPhotoMessage(String title, String url, String imageUrl, String description, String channelId) {
        SendPhoto photoMessage = new SendPhoto();
        photoMessage.setChatId(channelId);
        photoMessage.setPhoto(new InputFile(imageUrl));

        String caption = title + "\n\n" + description;
        if (caption.length() > 900) {
            caption = caption.substring(0, 900) + "... Читать полностью: " + url;
        }

        photoMessage.setCaption(caption);
        return photoMessage;
    }
}
