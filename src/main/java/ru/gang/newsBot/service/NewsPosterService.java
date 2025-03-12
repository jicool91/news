package ru.gang.newsBot.service;

import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;

@Service
public class NewsPosterService {

    private static final int MAX_CAPTION_LENGTH = 1024; // Лимит Telegram для подписи

    public SendPhoto buildPhotoMessage(String newsTitle, String newsUrl, String newsSource, String imageUrl, String description, String channelId) {
        String formattedMessage = "📢 *Главная новость дня* 📢\n\n"
                + (newsTitle != null && !newsTitle.isBlank() ? "*" + newsTitle + "*\n\n" : "")
                + (description != null && !description.isBlank() ? description + "\n\n" : "")
                + "🔗 [Читать полностью](" + newsUrl + ")";

        if (formattedMessage.length() > 1024) {
            formattedMessage = formattedMessage.substring(0, 1021) + "...";
        }

        SendPhoto photoMessage = new SendPhoto();
        photoMessage.setChatId(channelId); // <-- Передаем ID канала
        photoMessage.setPhoto(new InputFile(imageUrl));
        photoMessage.setCaption(formattedMessage);
        photoMessage.setParseMode("Markdown");

        return photoMessage;
    }


}
