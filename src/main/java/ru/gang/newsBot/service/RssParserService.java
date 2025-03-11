package ru.gang.newsBot.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import ru.gang.newsBot.model.NewsItem;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

@Service
public class RssParserService {
    private final List<String> rssUrls = List.of(
            "https://lenta.ru/rss/news",
            "https://meduza.io/rss/all",
            "https://rssexport.rbc.ru/rbcnews/news/20/full.rss",
            "https://tass.ru/rss/v2.xml"
    );

    public List<NewsItem> fetchNewsHeadlinesWithSources() {
        List<NewsItem> newsList = new ArrayList<>();

        for (String rssUrl : rssUrls) {
            System.out.println("🌍 Загружаем RSS: " + rssUrl);
            try {
                List<NewsItem> parsedNews = parseRss(rssUrl);
                newsList.addAll(parsedNews);
                System.out.println("✅ Загружено " + parsedNews.size() + " новостей с " + rssUrl);
            } catch (Exception e) {
                System.out.println("❌ Ошибка при обработке RSS " + rssUrl + ": " + e.getMessage());
            }
        }
        return newsList;
    }

    private List<NewsItem> parseRss(String rssUrl) throws Exception {
        List<NewsItem> newsList = new ArrayList<>();
        try {
            URL url = new URL(rssUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            // Устанавливаем таймауты (5 секунд)
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestMethod("GET");

            Scanner scanner = new Scanner(connection.getInputStream());
            StringBuilder xmlContent = new StringBuilder();
            while (scanner.hasNextLine()) {
                xmlContent.append(scanner.nextLine());
            }
            scanner.close();

            // Очистка проблемных символов
            String cleanedXml = xmlContent.toString().replaceAll("&(?!amp;|lt;|gt;|quot;|apos;)", "&amp;");

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            org.w3c.dom.Document doc = builder.parse(new InputSource(new StringReader(cleanedXml)));

            NodeList items = doc.getElementsByTagName("item");
            for (int i = 0; i < items.getLength(); i++) {
                org.w3c.dom.Node itemNode = items.item(i);

                // Извлекаем title, link, description
                String title = getChildValue(itemNode, "title");
                String link = getChildValue(itemNode, "link");
                String description = getChildValue(itemNode, "description").trim();

                // Если описание отсутствует или слишком короткое – пытаемся загрузить HTML-страницу новости
                if (description.isEmpty() || description.length() < 100) {
                    String fullText = extractFullTextFromArticle(link);
                    if (!fullText.isEmpty()) {
                        description = fullText;
                    }
                }

                // Получаем URL изображения
                String imageUrl = getImageFromItem(doc, i);
                if (imageUrl.isEmpty()) {
                    imageUrl = extractImageFromArticle(link);
                }

                String source = getSourceName(rssUrl);

                newsList.add(new NewsItem(title, link, source, imageUrl, description));
            }
        } catch (Exception e) {
            throw new Exception("Ошибка обработки XML в " + rssUrl + ": " + e.getMessage());
        }
        return newsList;
    }

    public List<NewsItem> fetchNewsWithImages() {
        List<NewsItem> newsList = fetchNewsHeadlinesWithSources();
        newsList.removeIf(news -> news.getImageUrl() == null || news.getImageUrl().isEmpty());
        return newsList;
    }

    public String getNewsSource(String newsUrl) {
        return "Неизвестный источник";
    }

    private String getSourceName(String rssUrl) {
        if (rssUrl.contains("lenta.ru")) return "Lenta.ru";
        if (rssUrl.contains("meduza.io")) return "Meduza";
        if (rssUrl.contains("rbc.ru")) return "РБК";
        if (rssUrl.contains("tass.ru")) return "ТАСС";
        return "Другой источник";
    }

    /**
     * Извлекает URL картинки из тегов <media:content> или <enclosure>
     */
    private String getImageFromItem(org.w3c.dom.Document doc, int index) {
        try {
            NodeList mediaContent = doc.getElementsByTagName("media:content");
            if (mediaContent.getLength() > index) {
                return mediaContent.item(index).getAttributes().getNamedItem("url").getTextContent();
            }
            NodeList enclosure = doc.getElementsByTagName("enclosure");
            if (enclosure.getLength() > index) {
                return enclosure.item(index).getAttributes().getNamedItem("url").getTextContent();
            }
        } catch (Exception ignored) {}
        return "";
    }

    /**
     * Вспомогательный метод: возвращает текст дочернего тега с заданным именем внутри <item>
     */
    private String getChildValue(org.w3c.dom.Node parentNode, String childName) {
        NodeList nodeList = ((org.w3c.dom.Element) parentNode).getElementsByTagName(childName);
        if (nodeList.getLength() > 0) {
            return nodeList.item(0).getTextContent();
        }
        return "";
    }

    /**
     * Загружает HTML-страницу новости по ссылке, ищет контейнер с текстом (например, div.topic-body__content),
     * извлекает все абзацы (<p>) и объединяет их в один текст.
     */
    private String extractFullTextFromArticle(String articleUrl) {
        try {
            // Устанавливаем таймаут 5 секунд
            Document articleDoc = Jsoup.connect(articleUrl).timeout(5000).get();
            Element contentBlock = articleDoc.selectFirst("div.topic-body__content");
            if (contentBlock != null) {
                Elements paragraphs = contentBlock.select("p");
                StringBuilder fullText = new StringBuilder();
                for (Element p : paragraphs) {
                    fullText.append(p.text()).append("\n\n");
                }
                return fullText.toString().trim();
            }
        } catch (Exception e) {
            System.out.println("❌ Ошибка при извлечении полного текста из " + articleUrl + ": " + e.getMessage());
        }
        return "";
    }

    /**
     * Если в RSS отсутствует картинка, пытаемся получить og:image из HTML-страницы новости.
     */
    private String extractImageFromArticle(String articleUrl) {
        try {
            Document articleDoc = Jsoup.connect(articleUrl).timeout(5000).get();
            Element metaOgImage = articleDoc.selectFirst("meta[property=og:image]");
            if (metaOgImage != null) {
                return metaOgImage.attr("content");
            }
        } catch (Exception e) {
            System.out.println("❌ Ошибка при извлечении изображения из " + articleUrl + ": " + e.getMessage());
        }
        return "";
    }
}
