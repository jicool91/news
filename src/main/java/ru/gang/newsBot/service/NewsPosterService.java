package ru.gang.newsBot.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import ru.gang.newsBot.config.NewsChannelConfig;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class NewsPosterService {
    private static final Logger log = LoggerFactory.getLogger(NewsPosterService.class);
    private static final int MAX_CAPTION_LENGTH = 1024;

    private final NewsChannelConfig newsChannelConfig;

    private static final Map<String, String> CHANNEL_LINKS = Map.of(
            "former_ussr", "https://t.me/News_Ukraine_project",
            "russia", "https://t.me/News_Russia_project",
            "world", "https://t.me/News_World_project",
            "economy", "https://t.me/News_Economy_project"
    );

    public SendPhoto buildPhotoMessage(String newsTitle, String newsUrl, String newsSource, String imageUrl, String description, String channelId) {
        // Определяем категорию по ID канала
        String category = getCategoryByChannelId(channelId);
        String channelLink = CHANNEL_LINKS.getOrDefault(category, "");

        String title = newsTitle != null && !newsTitle.isBlank() ? "**" + newsTitle + "**\n\n" : "";
        String desc = description != null && !description.isBlank() ? description + "\n\n" : "";
        String readMore = "🔗 [Читать полностью](" + newsUrl + ")\n\n";
        String subscribe = "📢 [Подписаться](" + channelLink + ")";

        String formattedMessage = title + desc + readMore + subscribe;

        if (formattedMessage.length() > MAX_CAPTION_LENGTH) {
            int reserveSpace = readMore.length() + subscribe.length() + title.length() + 5;
            int maxDescriptionLength = MAX_CAPTION_LENGTH - reserveSpace;

            if (description != null && !description.isBlank() && description.length() > maxDescriptionLength) {
                desc = description.substring(0, maxDescriptionLength) + "...\n\n";
            }

            formattedMessage = title + desc + readMore + subscribe;
        }

        SendPhoto photoMessage = new SendPhoto();
        photoMessage.setChatId(channelId);
        photoMessage.setPhoto(new InputFile(imageUrl));
        photoMessage.setCaption(formattedMessage);
        photoMessage.setParseMode("Markdown");

        log.debug("Создано сообщение для канала {}: {} символов", channelId, formattedMessage.length());
        return photoMessage;
    }

    private String getCategoryByChannelId(String channelId) {
        Map<String, String> channels = newsChannelConfig.getChannels();
        for (Map.Entry<String, String> entry : channels.entrySet()) {
            if (entry.getValue().equals(channelId)) {
                return entry.getKey();
            }
        }
        return "";
    }
}