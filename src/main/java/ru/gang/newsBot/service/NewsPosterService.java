package ru.gang.newsBot.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
        String category = getCategoryByChannelId(channelId);
        String channelLink = CHANNEL_LINKS.getOrDefault(category, "");

        String title = newsTitle != null && !newsTitle.isBlank() ? "**" + newsTitle + "**\n\n" : "";
        String readMore = "🔗 [Читать полностью](" + newsUrl + ")\n";
        String subscribe = "📢 [Подписаться](" + channelLink + ")";

        String footerSpace = readMore + subscribe;
        int reservedSpace = title.length() + footerSpace.length() + 30; // 30 символов для разделителей и запаса
        int availableSpace = MAX_CAPTION_LENGTH - reservedSpace;

        String processedDescription = "";
        if (description != null && !description.isBlank()) {
            if (description.length() <= availableSpace) {
                processedDescription = description + "\n\n";
            } else {
                String[] paragraphs = description.split("\n\n");
                StringBuilder sb = new StringBuilder();

                for (String paragraph : paragraphs) {
                    if (sb.length() + paragraph.length() + 6 <= availableSpace) {
                        if (sb.length() > 0) {
                            sb.append("\n\n");
                        }
                        sb.append(paragraph);
                    } else if (sb.length() == 0) {
                        sb.append(paragraph, 0, Math.min(paragraph.length(), availableSpace - 3));
                        sb.append("...");
                        break;
                    } else {
                        sb.append("...");
                        break;
                    }
                }

                processedDescription = sb.toString() + "\n\n";
            }
        }

        String formattedMessage = title + processedDescription + readMore + subscribe;

        if (formattedMessage.length() > MAX_CAPTION_LENGTH) {
            log.warn("Сообщение превышает лимит после оптимизации: {} символов", formattedMessage.length());
            int excessLength = formattedMessage.length() - MAX_CAPTION_LENGTH + 3;
            processedDescription = processedDescription.substring(0, processedDescription.length() - excessLength) + "...\n\n";
            formattedMessage = title + processedDescription + readMore + subscribe;
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