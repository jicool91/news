package ru.gang.newsBot.service;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;
import ru.gang.newsBot.config.NewsChannelConfig;
import ru.gang.newsBot.model.NewsItem;

import java.util.*;

@Service
public class RssParserService {

    private final NewsChannelConfig newsChannelConfig;

    private final Map<String, String> categoryToChannel;

    private static final Map<String, String> categoryTranslation = Map.of(
            "Бывший СССР", "former_ussr",
            "Россия", "russia",
            "Мир", "world",
            "Экономика", "economy"
    );

    public RssParserService(NewsChannelConfig newsChannelConfig, NewsChannelConfig config) {
        this.newsChannelConfig = newsChannelConfig;
        this.categoryToChannel = config.getChannels();
        System.out.println("📌 Загруженные категории: " + categoryToChannel);
    }

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
            System.out.println("🌍 Загружаем RSS: " + rssUrl);
            try {
                List<NewsItem> parsedNews = parseRss(rssUrl);
                newsList.addAll(parsedNews);
                System.out.println("✅ Загружено " + parsedNews.size() + " новостей с " + rssUrl);
            } catch (Exception e) {
                System.err.println("❌ Ошибка при обработке RSS " + rssUrl + ": " + e.getMessage());
            }
        }
        return newsList;
    }

    private List<NewsItem> parseRss(String rssUrl) throws Exception {
        List<NewsItem> newsList = new ArrayList<>();

        // Подключаемся к RSS-ленте
        Connection connection = Jsoup.connect(rssUrl)
                .userAgent("Mozilla/5.0")
                .header("Accept", "application/rss+xml")
                .timeout(10000);

        // Загружаем XML
        Document rssDoc = connection.get();

        // Выводим весь XML для проверки
        System.out.println("🔍 Содержимое RSS: \n" + rssDoc);

        // Ищем элементы <item>
        Elements items = rssDoc.select("item");
        System.out.println("🔍 Найдено элементов <item>: " + items.size());

        int count = 0;
        for (Element item : items) {
            if (count >= maxItems) break;

            // Читаем данные новости
            String title = item.select("title").text();
            String link = item.select("link").text();
            String description = item.select("description").text().trim();
            String category = item.select("category").text().trim();

            System.out.println("📌 Обнаружена новость: " + title);
            System.out.println("🏷 Категория (оригинал): " + category);

            // Перевод категории через словарь
            String normalizedCategory = categoryTranslation.getOrDefault(category, category).toLowerCase();
            System.out.println("✅ Категория переведена: " + category + " -> " + normalizedCategory);

            // Логируем доступные категории из пропертей
            System.out.println("📌 Доступные категории: " + categoryToChannel.keySet());

            if (!categoryToChannel.containsKey(normalizedCategory)) {
                System.out.println("⏭ Пропускаем категорию: " + category + " (нет в списке)");
                continue;
            }

            // Получаем изображение
            String imageUrl = item.select("enclosure[url]").attr("url");
            if (imageUrl.isEmpty()) {
                imageUrl = extractImageFromArticle(link);
            }

            // Определяем источник
            String source = getSourceName(rssUrl);

            // Добавляем новость
            newsList.add(new NewsItem(title, link, source, imageUrl, description, normalizedCategory));
            count++;
        }
        return newsList;
    }


    private String getSourceName(String rssUrl) {
        if (rssUrl.contains("lenta.ru")) return "Lenta.ru";
        return "Другой источник";
    }

    private String extractImageFromArticle(String articleUrl) {
        try {
            Document articleDoc = Jsoup.connect(articleUrl)
                    .userAgent("Mozilla/5.0")
                    .timeout(10000)
                    .get();
            Element metaOgImage = articleDoc.selectFirst("meta[property=og:image]");
            return metaOgImage != null ? metaOgImage.attr("content") : "";
        } catch (Exception e) {
            return "";
        }
    }
}
