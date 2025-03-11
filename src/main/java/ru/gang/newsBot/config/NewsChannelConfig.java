package ru.gang.newsBot.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Getter
@Configuration
@ConfigurationProperties(prefix = "news")
public class NewsChannelConfig {
    private Map<String, String> channels;

    public void setChannels(Map<String, String> channels) {
        this.channels = channels;
    }

    // Словарь перевода категорий
    private static final Map<String, String> CATEGORY_TRANSLATIONS = Map.of(
            "Бывший СССР", "former_ussr",
            "Россия", "russia",
            "Мир", "world",
            "Экономика", "economy"
    );

    /**
     * Получает английское название категории по русскому.
     */
    public String getEnglishCategory(String russianCategory) {
        return CATEGORY_TRANSLATIONS.getOrDefault(russianCategory, null);
    }

    /**
     * Получает ID канала по английскому названию категории.
     */
    public String getChannelByEnglishCategory(String englishCategory) {
        return channels.getOrDefault(englishCategory, null);
    }
}
