package ru.gang.newsBot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "news")
public class NewsChannelConfig {
    private Map<String, String> channels;

    private static final Map<String, String> CATEGORY_TRANSLATIONS = Map.of(
            "Бывший СССР", "former_ussr",
            "Россия", "russia",
            "Мир", "world",
            "Экономика", "economy"
    );

    public String getEnglishCategory(String russianCategory) {
        return CATEGORY_TRANSLATIONS.getOrDefault(russianCategory, null);
    }

    public String getChannelByEnglishCategory(String englishCategory) {
        return channels.getOrDefault(englishCategory, null);
    }
}
