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
import ru.gang.newsBot.util.AsyncUtils;
import ru.gang.newsBot.util.HttpRequestUtil;
import ru.gang.newsBot.util.HttpRequestUtil.RequestConfig;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RssParserService {
    private final NewsChannelConfig newsChannelConfig;
    private final RssConfig rssConfig;
    private final AsyncUtils asyncUtils;

    private static final int MAX_IMAGE_RETRY_ATTEMPTS = 5;
    private static final int IMAGE_RETRY_DELAY_MS = 5000;
    private static final Pattern DEFAULT_IMAGE_PATTERN = Pattern.compile(".*/assets/webpack/images/lenta_og\\.[a-f0-9]+\\.png$");
    private static final Pattern VALID_IMAGE_PATTERN = Pattern.compile(".*/images/\\d+/\\d+/\\d+/\\d+/.*\\.jpg$");

    private final List<String> rssUrls = List.of("https://lenta.ru/rss/news");

    @Data
    @Builder
    private static class NewsItemBasic {
        private String title;
        private String url;
        private String source;
        private String description;
        private String category;
    }

    public String getCategoryChannel(String category) {
        return newsChannelConfig.getChannelByEnglishCategory(category);
    }

    public Map<String, NewsItem> fetchLatestNewsByCategory() {
        Map<String, NewsItem> categoryNewsMap = new ConcurrentHashMap<>();
        Set<String> targetCategories = new HashSet<>(NewsChannelConfig.CATEGORY_TRANSLATIONS.values());

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (String rssUrl : rssUrls) {
            log.info("Асинхронно загружаем RSS: {}", rssUrl);

            CompletableFuture<Void> future = asyncUtils.asyncIoRun(() -> {
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
        return asyncUtils.asyncIo(() -> {
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
        }, "Извлечение описания статьи " + articleUrl);
    }

    private CompletableFuture<String> extractImageFromArticleAsync(String articleUrl) {
        return asyncUtils.asyncIo(() -> {
            try {
                return extractImageWithRetries(articleUrl, 0);
            } catch (Exception e) {
                log.error("Ошибка при извлечении изображения из статьи {}", articleUrl, e);
                return "";
            }
        }, "Извлечение изображения из статьи " + articleUrl);
    }

    private String extractImageWithRetries(String articleUrl, int attemptCount) throws Exception {
        if (attemptCount >= MAX_IMAGE_RETRY_ATTEMPTS) {
            log.warn("Достигнуто максимальное количество попыток получения изображения для {}", articleUrl);
            return "";
        }

        RequestConfig config = new RequestConfig(
                rssConfig.getMaxRetries(),
                rssConfig.getTimeout(),
                rssConfig.getMaxTimeout());

        Document articleDoc = HttpRequestUtil.fetchWithRetry(articleUrl, config);
        Element metaOgImage = articleDoc.selectFirst("meta[property=og:image]");

        if (metaOgImage == null) {
            log.debug("Мета-тег с изображением не найден для {}", articleUrl);
            return "";
        }

        String imageUrl = metaOgImage.attr("content");

        if (imageUrl != null && !imageUrl.isEmpty()) {
            if (DEFAULT_IMAGE_PATTERN.matcher(imageUrl).matches()) {
                log.debug("Обнаружено стандартное изображение для {}, попытка: {}", articleUrl, attemptCount + 1);

                TimeUnit.MILLISECONDS.sleep(IMAGE_RETRY_DELAY_MS);
                return extractImageWithRetries(articleUrl, attemptCount + 1);
            } else if (VALID_IMAGE_PATTERN.matcher(imageUrl).matches()) {
                log.debug("Найдено валидное изображение для {} на попытке {}: {}", articleUrl, attemptCount + 1, imageUrl);
                return imageUrl;
            } else {
                log.debug("Найдено изображение для {}, но оно не соответствует ожидаемому формату: {}", articleUrl, imageUrl);

                if (attemptCount < 2) {
                    TimeUnit.MILLISECONDS.sleep(IMAGE_RETRY_DELAY_MS);
                    return extractImageWithRetries(articleUrl, attemptCount + 1);
                }
                return imageUrl;
            }
        }

        log.debug("Изображение не найдено для {}", articleUrl);
        return "";
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

            String normalizedCategory = newsChannelConfig.getEnglishCategory(category);
            if (normalizedCategory == null) {
                normalizedCategory = category.toLowerCase();
            }

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

            if (!imageUrlFromRss.isEmpty() && DEFAULT_IMAGE_PATTERN.matcher(imageUrlFromRss).matches()) {
                log.debug("Стандартное изображение в RSS, будем загружать из статьи: {}", imageUrlFromRss);
                imageUrlFromRss = "";
            }

            NewsItemBasic basicNewsItem = NewsItemBasic.builder()
                    .title(title)
                    .url(link)
                    .source(source)
                    .description(description)
                    .category(normalizedCategory)
                    .build();

            CompletableFuture<String> imageFuture = imageUrlFromRss.isEmpty()
                    ? extractImageFromArticleAsync(link)
                    : CompletableFuture.completedFuture(imageUrlFromRss);

            CompletableFuture<String> descriptionFuture = (description.isEmpty() || description.length() < 100)
                    ? extractFullDescriptionAsync(link)
                    : CompletableFuture.completedFuture(description);

            CompletableFuture<NewsItem> newsItemFuture = imageFuture
                    .thenCombineAsync(descriptionFuture, (imageUrl, fullDescription) ->
                                    NewsItem.builder()
                                            .title(basicNewsItem.getTitle())
                                            .url(basicNewsItem.getUrl())
                                            .source(basicNewsItem.getSource())
                                            .imageUrl(imageUrl)
                                            .description(fullDescription)
                                            .category(basicNewsItem.getCategory())
                                            .build()
                            , asyncUtils.getCpuExecutor());

            futureCategoryMap.put(normalizedCategory, newsItemFuture);

            if (futureCategoryMap.keySet().containsAll(targetCategories)) {
                log.info("Найдены новости для всех целевых категорий");
                break;
            }
        }

        Map<String, NewsItem> result = new HashMap<>();

        List<CompletableFuture<?>> allFutures = new ArrayList<>(futureCategoryMap.values());
        try {
            CompletableFuture.allOf(allFutures.toArray(new CompletableFuture[0])).get();

            futureCategoryMap.forEach((category, future) -> {
                try {
                    NewsItem newsItem = future.getNow(null);
                    if (newsItem != null) {
                        result.put(category, newsItem);
                    }
                } catch (Exception e) {
                    log.error("Ошибка при получении данных для категории {}: {}", category, e.getMessage(), e);
                }
            });
        } catch (InterruptedException | ExecutionException e) {
            log.error("Ошибка при ожидании всех задач: {}", e.getMessage(), e);
            Thread.currentThread().interrupt();
        }

        log.info("Успешно обработаны новости для {} категорий из {}",
                result.size(), targetCategories.size());
        return result;
    }

    private String getSourceName(String rssUrl) {
        if (rssUrl.contains("lenta.ru")) return "Lenta.ru";
        return "Другой источник";
    }
}