package ru.gang.newsBot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import ru.gang.newsBot.config.NewsChannelConfig;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class NewsPosterService {
    private static final int MAX_CAPTION_LENGTH = 1024;
    private static final int SHORT_TEXT_THRESHOLD = 900;
    private static final String READ_MORE_TEXT = "...читать полностью";

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

        String title = newsTitle != null && !newsTitle.isBlank() ? "*" + newsTitle + "*\n\n" : "";
        String subscribe = "🔔 [Подписаться](" + channelLink + ")";

        String processedDescription = "";
        boolean isShortText = description == null || description.length() <= SHORT_TEXT_THRESHOLD;

        if (description != null && !description.isBlank()) {
            int reservedSpace = title.length() + subscribe.length() + 40;
            int availableSpace = MAX_CAPTION_LENGTH - reservedSpace;

            if (isShortText && description.length() <= availableSpace) {
                processedDescription = description + "\n\n";
            } else {
                int maxDescriptionLength = Math.min(SHORT_TEXT_THRESHOLD, availableSpace - READ_MORE_TEXT.length() - 10);

                if (description.length() > maxDescriptionLength) {
                    int lastNewLine = description.substring(0, maxDescriptionLength).lastIndexOf("\n\n");
                    int lastSentence = description.substring(0, maxDescriptionLength).lastIndexOf(". ");

                    int cutPoint = Math.max(lastNewLine, lastSentence);

                    if (cutPoint <= 0 || cutPoint < maxDescriptionLength - 100) {
                        int lastSpace = description.substring(0, maxDescriptionLength).lastIndexOf(" ");
                        if (lastSpace > 0) {
                            cutPoint = lastSpace;
                        } else {
                            cutPoint = maxDescriptionLength;
                        }
                    } else {
                        if (cutPoint == lastSentence) {
                            cutPoint += 1;
                        }
                    }

                    String mainText = description.substring(0, cutPoint);
                    processedDescription = mainText + "\n\n[" + READ_MORE_TEXT + "](" + newsUrl + ")\n\n";
                } else {
                    processedDescription = description + "\n\n[" + READ_MORE_TEXT + "](" + newsUrl + ")\n\n";
                }
            }
        }

        String formattedMessage = title + processedDescription + subscribe;

        if (formattedMessage.length() > MAX_CAPTION_LENGTH) {
            log.warn("Сообщение превышает лимит после оптимизации: {} символов", formattedMessage.length());

            int readMoreIndex = processedDescription.indexOf(READ_MORE_TEXT);

            if (readMoreIndex > 0) {
                int excessLength = formattedMessage.length() - MAX_CAPTION_LENGTH;
                int newTextLength = readMoreIndex - excessLength - 10;

                if (newTextLength > 0) {
                    int lastSpace = processedDescription.substring(0, newTextLength).lastIndexOf(" ");
                    if (lastSpace > 0) {
                        newTextLength = lastSpace;
                    }

                    processedDescription = processedDescription.substring(0, newTextLength) +
                            "\n\n[" + READ_MORE_TEXT + "](" + newsUrl + ")\n\n";
                    formattedMessage = title + processedDescription + subscribe;
                }
            } else {
                int excessLength = formattedMessage.length() - MAX_CAPTION_LENGTH + READ_MORE_TEXT.length() + 20;

                if (processedDescription.length() > excessLength) {
                    int cutPosition = processedDescription.length() - excessLength;
                    int lastSpace = processedDescription.substring(0, cutPosition).lastIndexOf(" ");

                    if (lastSpace > 0) {
                        cutPosition = lastSpace;
                    }

                    processedDescription = processedDescription.substring(0, cutPosition) +
                            "\n\n[" + READ_MORE_TEXT + "](" + newsUrl + ")\n\n";
                    formattedMessage = title + processedDescription + subscribe;
                }
            }
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