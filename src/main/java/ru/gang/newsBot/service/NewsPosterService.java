package ru.gang.newsBot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;

@Service
public class NewsPosterService {
    private static final Logger log = LoggerFactory.getLogger(NewsPosterService.class);
    private static final int MAX_CAPTION_LENGTH = 1024;

    public SendPhoto buildPhotoMessage(String newsTitle, String newsUrl, String newsSource, String imageUrl, String description, String channelId) {
        String formattedMessage = "📢 *Главная новость дня* 📢\n\n"
                + (newsTitle != null && !newsTitle.isBlank() ? "*" + newsTitle + "*\n\n" : "")
                + (description != null && !description.isBlank() ? description + "\n\n" : "")
                + "🔗 [Читать полностью](" + newsUrl + ")";

        if (formattedMessage.length() > MAX_CAPTION_LENGTH) {
            log.debug("Сообщение превышает лимит Telegram ({}), обрезаем", MAX_CAPTION_LENGTH);
            formattedMessage = formattedMessage.substring(0, MAX_CAPTION_LENGTH - 3) + "...";
        }

        SendPhoto photoMessage = new SendPhoto();
        photoMessage.setChatId(channelId);
        photoMessage.setPhoto(new InputFile(imageUrl));
        photoMessage.setCaption(formattedMessage);
        photoMessage.setParseMode("Markdown");

        log.debug("Создано сообщение для канала {}: {} символов", channelId, formattedMessage.length());
        return photoMessage;
    }
}
