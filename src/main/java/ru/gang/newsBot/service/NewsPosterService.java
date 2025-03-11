package ru.gang.newsBot.service;

import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;

@Service
public class NewsPosterService {
    private static final String CHANNEL_ID = "-1002420518377";

    public SendPhoto buildPhotoMessage(String newsTitle,
                                       String newsUrl,
                                       String newsSource,
                                       String imageUrl,
                                       String description) {
        String formattedMessage = "📢 *Главная новость дня* 📢\n\n"
                + (newsTitle != null && !newsTitle.isBlank() ? newsTitle + "\n\n" : "")
                + (description != null && !description.isBlank() ? description + "\n\n" : "")
                + "🏛 *Источник:* " + newsSource;
        SendPhoto photoMessage = new SendPhoto();
        photoMessage.setChatId(CHANNEL_ID);
        photoMessage.setPhoto(new InputFile(imageUrl));
        photoMessage.setCaption(formattedMessage);
        photoMessage.setParseMode("Markdown");
        return photoMessage;
    }
}
