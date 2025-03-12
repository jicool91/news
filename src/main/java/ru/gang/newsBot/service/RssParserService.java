package ru.gang.newsBot.service;

import lombok.Builder;
import lombok.Data;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
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

    @Data
    @Builder
    private static class NewsItemBasic {
        private String title;
        private String url;
        private String source;
        private String description;
        private String category;
    }

    public Map<String, NewsItem> fetchLatestNewsByCategory() {
        Map<String, NewsItem> categoryNewsMap = new ConcurrentHashMap<>();
        Set<String> targetCategories = new HashSet<>(categoryTranslation.values());

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (String rssUrl : rssUrls) {
            log.info("Асинхронно загружаем RSS: {}", rssUrl);

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    Map<String, NewsItem> newsFromSource = parseRssToLatestByCategory(rssUrl, targetCategories);

                    for (Map.Entry<String, NewsItem> entry : newsFromSource.entrySet()) {
                        categoryNewsMap.put(entry.getKey(), entry.getValue());
                    }

                    log.info("Загружено {} новостей из категорий: {}", newsFromSource.size(),
                            String.join(", ", newsFromSource.keySet()));
                } catch (Exception e) {
                    log.error("Ошибка при обработке RSS {}: {}", rssUrl, e.getMessage(), e);
                }
            });

            futures.add(future);
        }

        CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0])
        );

        try {
            allFutures.get();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Ошибка при ожидании загрузки всех RSS: {}", e.getMessage(), e);
            Thread.currentThread().interrupt();
        }

        return categoryNewsMap;
    }

    private CompletableFuture<String> extractFullDescriptionAsync(String articleUrl) {
        return CompletableFuture.supplyAsync(() -> {
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
        });
    }

    private CompletableFuture<String> extractImageFromArticleAsync(String articleUrl) {
        return CompletableFuture.supplyAsync(() -> {
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
        });
    }

    private Map<String, NewsItem> parseRssToLatestByCategory(String rssUrl, Set<String> targetCategories) throws Exception {
        Map<String, CompletableFuture<NewsItem>> futureCategoryMap = new ConcurrentHashMap<>();
        Map<String, Integer> processingOrder = new HashMap<>();
        int order = 0;

        RequestConfig config = new RequestConfig(
                rssConfig.getMaxRetries(),
                rssConfig.getTimeout(),
                rssConfig.getMaxTimeout());

        Document rssDoc = HttpRequestUtil.fetchWithRetry(rssUrl, config);
        Elements items = rssDoc.select("item");
        log.debug("Найдено элементов <item>: {}", items.size());

        for (Element item : items) {
            String title = item.select("title").text();
            String category = item.select("category").text().trim();

            String normalizedCategory = categoryTranslation.getOrDefault(category, category).toLowerCase();

            if (!targetCategories.contains(normalizedCategory)) {
                log.debug("Пропускаем категорию: {} (нет в списке)", category);
                continue;
            }

            if (processingOrder.containsKey(normalizedCategory) &&
                    processingOrder.get(normalizedCategory) < order) {
                order++;
                continue;
            }

            processingOrder.put(normalizedCategory, order);
            order++;

            String link = item.select("link").text();
            String description = item.select("description").text().trim();
            String source = getSourceName(rssUrl);

            log.debug("Обнаружена новость для категории {}: {}", normalizedCategory, title);

            String imageUrlFromRss = item.select("enclosure[url]").attr("url");

            // Создаем базовый объект с информацией, которая уже доступна
            NewsItemBasic basicNewsItem = NewsItemBasic.builder()
                    .title(title)
                    .url(link)
                    .source(source)
                    .description(description)
                    .category(normalizedCategory)
                    .build();

            // Асинхронно получаем дополнительные данные
            CompletableFuture<String> imageFuture = imageUrlFromRss.isEmpty()
                    ? extractImageFromArticleAsync(link)
                    : CompletableFuture.completedFuture(imageUrlFromRss);

            CompletableFuture<String> descriptionFuture = (description.isEmpty() || description.length() < 100)
                    ? extractFullDescriptionAsync(link)
                    : CompletableFuture.completedFuture(description);

            // Комбинируем результаты асинхронных задач
            CompletableFuture<NewsItem> newsItemFuture = imageFuture
                    .thenCombine(descriptionFuture, (imageUrl, fullDescription) ->
                            NewsItem.builder()
                                    .title(basicNewsItem.getTitle())
                                    .url(basicNewsItem.getUrl())
                                    .source(basicNewsItem.getSource())
                                    .imageUrl(imageUrl)
                                    .description(fullDescription)
                                    .category(basicNewsItem.getCategory())
                                    .build()
                    );

            futureCategoryMap.put(normalizedCategory, newsItemFuture);

            if (futureCategoryMap.keySet().containsAll(targetCategories)) {
                log.info("Найдены новости для всех целевых категорий");
                break;
            }
        }

        // Ожидаем завершения всех асинхронных задач
        Map<String, NewsItem> result = new HashMap<>();
        futureCategoryMap.forEach((category, future) -> {
            try {
                result.put(category, future.get());
            } catch (InterruptedException | ExecutionException e) {
                log.error("Ошибка при получении данных для категории {}: {}", category, e.getMessage(), e);
                Thread.currentThread().interrupt();
            }
        });

        log.info("Успешно обработаны новости для {} категорий из {}",
                result.size(), targetCategories.size());
        return result;
    }

    private String getSourceName(String rssUrl) {
        if (rssUrl.contains("lenta.ru")) return "Lenta.ru";
        return "Другой источник";
    }
}