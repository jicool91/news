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
import java.util.stream.Collectors;

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

    public Map<String, NewsItem> fetchLatestNewsByCategory() {
        Map<String, NewsItem> categoryNewsMap = new HashMap<>();
        Set<String> targetCategories = new HashSet<>(categoryTranslation.values());

        for (String rssUrl : rssUrls) {
            log.info("Загружаем RSS: {}", rssUrl);
            try {
                Map<String, NewsItem> newsFromSource = parseRssToLatestByCategory(rssUrl, targetCategories);

                // Добавляем или заменяем новости по каждой категории
                for (Map.Entry<String, NewsItem> entry : newsFromSource.entrySet()) {
                    categoryNewsMap.put(entry.getKey(), entry.getValue());
                }

                log.info("Загружено по {} новостей из категорий: {}", newsFromSource.size(),
                        String.join(", ", newsFromSource.keySet()));
            } catch (Exception e) {
                log.error("Ошибка при обработке RSS {}: {}", rssUrl, e.getMessage(), e);
            }
        }

        return categoryNewsMap;
    }

    private String extractFullDescription(String articleUrl) {
        try {
            RequestConfig config = new RequestConfig(
                    rssConfig.getMaxRetries(),
                    rssConfig.getTimeout(),
                    rssConfig.getMaxTimeout());

            Document articleDoc = HttpRequestUtil.fetchWithRetry(articleUrl, config);

            String fullText = "";

            if (articleUrl.contains("lenta.ru")) {
                Elements paragraphs = articleDoc.select(".topic-body__content p");
                fullText = paragraphs.stream()
                        .map(Element::text)
                        .filter(text -> !text.isEmpty())
                        .collect(Collectors.joining("\n\n"));
            }

            if (fullText.isEmpty()) {
                Elements paragraphs = articleDoc.select("article p, .article p, .news-text p, .entry-content p, .post-content p, .content p");
                fullText = paragraphs.stream()
                        .map(Element::text)
                        .filter(text -> !text.isEmpty())
                        .collect(Collectors.joining("\n\n"));
            }

            if (fullText.isEmpty()) {
                Element descriptionElement = articleDoc.selectFirst("meta[name=description]");
                fullText = descriptionElement != null ? descriptionElement.attr("content") : "";
            }

            log.debug("Извлечено полное описание ({}): {} символов", articleUrl, fullText.length());
            return fullText;
        } catch (Exception e) {
            log.error("Ошибка при извлечении полного описания: {}", e.getMessage(), e);
            return "";
        }
    }

    private Map<String, NewsItem> parseRssToLatestByCategory(String rssUrl, Set<String> targetCategories) throws Exception {
        Map<String, NewsItem> categoryNewsMap = new HashMap<>();
        Map<String, Integer> processingOrder = new HashMap<>();
        int order = 0;

        RequestConfig config = new RequestConfig(
                rssConfig.getMaxRetries(),
                rssConfig.getTimeout(),
                rssConfig.getMaxTimeout());

        Document rssDoc = HttpRequestUtil.fetchWithRetry(rssUrl, config);
        Elements items = rssDoc.select("item");
        log.debug("Найдено элементов <item>: {}", items.size());

        // Перебираем все новости в поисках последних новостей по каждой категории
        for (Element item : items) {
            String title = item.select("title").text();
            String category = item.select("category").text().trim();

            String normalizedCategory = categoryTranslation.getOrDefault(category, category).toLowerCase();

            // Проверяем, является ли эта категория целевой
            if (!targetCategories.contains(normalizedCategory)) {
                log.debug("Пропускаем категорию: {} (нет в списке)", category);
                continue;
            }

            // Если мы уже нашли новость для этой категории и она была раньше в RSS (больший порядок),
            // пропускаем эту новость, так как она старее
            if (processingOrder.containsKey(normalizedCategory) &&
                    processingOrder.get(normalizedCategory) < order) {
                order++;
                continue;
            }

            // Сохраняем порядок для этой категории
            processingOrder.put(normalizedCategory, order);
            order++;

            String link = item.select("link").text();
            String description = item.select("description").text().trim();

            log.debug("Обнаружена новость для категории {}: {}", normalizedCategory, title);

            String imageUrl = item.select("enclosure[url]").attr("url");
            if (imageUrl.isEmpty()) {
                imageUrl = extractImageFromArticle(link);
            }

            String source = getSourceName(rssUrl);

            if (description.isEmpty() || description.length() < 100) {
                description = extractFullDescription(link);
            }

            NewsItem newsItem = NewsItem.builder()
                    .title(title)
                    .url(link)
                    .source(source)
                    .imageUrl(imageUrl)
                    .description(description)
                    .category(normalizedCategory)
                    .build();

            categoryNewsMap.put(normalizedCategory, newsItem);

            // Если у нас есть новости для всех целевых категорий, можно выходить из цикла
            if (categoryNewsMap.keySet().containsAll(targetCategories)) {
                log.info("Найдены новости для всех целевых категорий");
                break;
            }
        }

        log.info("Успешно обработаны новости для {} категорий из {}",
                categoryNewsMap.size(), targetCategories.size());
        return categoryNewsMap;
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