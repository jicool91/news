package ru.gang.newsBot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;
import ru.gang.newsBot.config.NewsChannelConfig;
import ru.gang.newsBot.config.RssConfig;
import ru.gang.newsBot.model.NewsItem;
import ru.gang.newsBot.util.HttpRequestUtil;
import ru.gang.newsBot.util.HttpRequestUtil.RequestConfig;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class RssParserService {
    private final NewsChannelConfig newsChannelConfig;
    private final RssConfig rssConfig;

    private static final Map<String, String> categoryTranslation = Map.of(
            "Бывший СССР", "former_ussr",
            "Россия", "russia",
            "Мир", "world",
            "Экономика", "economy"
    );

    public String getCategoryChannel(String category) {
        return newsChannelConfig.getChannelByEnglishCategory(category);
    }

    private final List<String> rssUrls = List.of(
            "https://lenta.ru/rss/news"
    );

    private final int maxItems = 5;

    public List<NewsItem> fetchNewsWithCategory() {
        List<NewsItem> newsList = new ArrayList<>();
        for (String rssUrl : rssUrls) {
            log.info("Загружаем RSS: {}", rssUrl);
            try {
                List<NewsItem> parsedNews = parseRss(rssUrl);
                newsList.addAll(parsedNews);
                log.info("Загружено {} новостей с {}", parsedNews.size(), rssUrl);
            } catch (Exception e) {
                log.error("Ошибка при обработке RSS {}: {}", rssUrl, e.getMessage(), e);
            }
        }
        return newsList;
    }

    private String extractFullDescription(String articleUrl) {
        try {
            RequestConfig config = new RequestConfig(
                    rssConfig.getMaxRetries(), 
                    rssConfig.getTimeout(), 
                    rssConfig.getMaxTimeout());
            
            Document articleDoc = HttpRequestUtil.fetchWithRetry(articleUrl, config);
            Element descriptionElement = articleDoc.selectFirst("meta[name=description]");
            String description = descriptionElement != null ? descriptionElement.attr("content") : "";
            log.debug("Извлечено полное описание ({}): {}", articleUrl, description);
            return description;
        } catch (Exception e) {
            log.error("Ошибка при извлечении полного описания: {}", e.getMessage(), e);
            return "";
        }
    }

    private List<NewsItem> parseRss(String rssUrl) throws Exception {
        List<NewsItem> newsList = new ArrayList<>();

        RequestConfig config = new RequestConfig(
                rssConfig.getMaxRetries(), 
                rssConfig.getTimeout(), 
                rssConfig.getMaxTimeout());
        
        Document rssDoc = HttpRequestUtil.fetchWithRetry(rssUrl, config);
        Elements items = rssDoc.select("item");
        log.debug("Найдено элементов <item>: {}", items.size());

        int count = 0;
        for (Element item : items) {
            if (count >= maxItems) break;

            String title = item.select("title").text();
            String link = item.select("link").text();
            String description = item.select("description").text().trim();
            String category = item.select("category").text().trim();

            log.debug("Обнаружена новость: {}", title);
            log.debug("Категория (оригинал): {}", category);

            String normalizedCategory = categoryTranslation.getOrDefault(category, category).toLowerCase();
            log.debug("Категория переведена: {} -> {}", category, normalizedCategory);

            if (!newsChannelConfig.getChannels().containsKey(normalizedCategory)) {
                log.debug("Пропускаем категорию: {} (нет в списке)", category);
                continue;
            }

            String imageUrl = item.select("enclosure[url]").attr("url");
            if (imageUrl.isEmpty()) {
                imageUrl = extractImageFromArticle(link);
            }

            String source = getSourceName(rssUrl);

            if (description.isEmpty()) {
                description = extractFullDescription(link);
            }

            newsList.add(NewsItem.builder()
                    .title(title)
                    .url(link)
                    .source(source)
                    .imageUrl(imageUrl)
                    .description(description)
                    .category(normalizedCategory)
                    .build());
            count++;
        }
        
        log.info("Успешно обработано {} новостей из источника {}", newsList.size(), rssUrl);
        return newsList;
    }

    private String getSourceName(String rssUrl) {
        if (rssUrl.contains("lenta.ru")) return "Lenta.ru";
        return "Другой источник";
    }

    private String extractImageFromArticle(String articleUrl) {
        try {
            RequestConfig config = new RequestConfig(
                    rssConfig.getMaxRetries(), 
                    rssConfig.getTimeout(), 
                    rssConfig.getMaxTimeout());
            
            Document articleDoc = HttpRequestUtil.fetchWithRetry(articleUrl, config);
            Element metaOgImage = articleDoc.selectFirst("meta[property=og:image]");
            String imageUrl = metaOgImage != null ? metaOgImage.attr("content") : "";
            log.debug("Извлечена картинка для {}: {}", articleUrl, imageUrl);
            return imageUrl;
        } catch (Exception e) {
            log.error("Ошибка при извлечении изображения из статьи {}", articleUrl, e);
            return "";
        }
    }
}