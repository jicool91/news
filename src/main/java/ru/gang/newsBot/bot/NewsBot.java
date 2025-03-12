package ru.gang.newsBot.bot;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import ru.gang.newsBot.model.NewsItem;
import ru.gang.newsBot.service.NewsAnalyzerService;
import ru.gang.newsBot.service.NewsPosterService;
import ru.gang.newsBot.service.RssParserService;

import java.io.*;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class NewsBot extends TelegramLongPollingBot {

    private final RssParserService rssParserService;
    private final NewsAnalyzerService newsAnalyzerService;
    private final NewsPosterService newsPosterService;

    @Getter
    private final Set<String> sentNews = new HashSet<>();

    @Value("${telegram.bot.username}")
    private String botUsername;

    @Value("${telegram.bot.token}")
    private String botToken;

    private static final String SENT_NEWS_FILE = "sent_news.txt";

    public NewsBot(DefaultBotOptions options,
                  RssParserService rssParserService,
                  NewsAnalyzerService newsAnalyzerService,
                  NewsPosterService newsPosterService) {
        super(options);
        this.rssParserService = rssParserService;
        this.newsAnalyzerService = newsAnalyzerService;
        this.newsPosterService = newsPosterService;
        System.out.println("✅ Бот успешно запущен и подключен к Telegram API");
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    public void onUpdateReceived(Update update) {
        System.out.println("📥 Получен update: " + update);

        if (update.hasMessage() && update.getMessage().hasText()) {
            String text = update.getMessage().getText();
            Long chatId = update.getMessage().getChatId();

            System.out.println("📩 Получено сообщение: " + text);

            if ("/fetch".equals(text)) {
                System.out.println("🚀 Команда /fetch обработана!");
                fetchAndPostNews();
                sendTextMessage(chatId, "✅ Новости обновлены!");
            }
        }
    }

    private void sendTextMessage(Long chatId, String text) {
        try {
            SendMessage message = new SendMessage(chatId.toString(), text);
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    public void fetchAndPostNews() {
        System.out.println("🔄 Запущено обновление новостей...");

        loadSentNews();

        List<NewsItem> newsList = rssParserService.fetchNewsWithCategory();
        Set<String> sentCategories = new HashSet<>();

        System.out.println("📌 Финальный список отправки новостей:");
        for (NewsItem news : newsList) {
            System.out.println("📜 " + news.getTitle() + " | Категория: " + news.getCategory());
        }

        for (NewsItem news : newsList) {
            if (sentNews.contains(news.getUrl())) {
                System.out.println("⏭ Пропуск: уже отправляли - " + news.getTitle());
                continue;
            }

            if (sentCategories.contains(news.getCategory())) {
                System.out.println("⏭ Пропуск: уже отправлена новость из категории - " + news.getCategory());
                continue;
            }

            System.out.println("✍ Отправка новости: " + news.getTitle());

            String channelId = rssParserService.getCategoryChannel(news.getCategory());
            if (channelId == null) {
                System.out.println("⚠ Не найден канал для категории: " + news.getCategory());
                continue;
            }

            System.out.println("📤 Готовим отправку в канал " + channelId + " для категории " + news.getCategory());
            SendPhoto photoMessage = newsPosterService.buildPhotoMessage(
                    news.getTitle(),
                    news.getUrl(),
                    news.getImageUrl(),
                    news.getDescription(),
                    channelId
            );

            try {
                execute(photoMessage);
                sentNews.add(news.getUrl());
                sentCategories.add(news.getCategory());
                saveSentNews();
                System.out.println("✅ Новость отправлена в канал: " + channelId);
            } catch (TelegramApiException e) {
                System.err.println("❌ Ошибка при отправке фото: " + e.getMessage());
            }
        }
    }

    private void loadSentNews() {
        try (BufferedReader reader = new BufferedReader(new FileReader(SENT_NEWS_FILE))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sentNews.add(line.trim());
            }
        } catch (IOException e) {
            System.out.println("⚠ Файл отправленных новостей не найден. Создаём новый.");
        }
    }

    private void saveSentNews() {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(SENT_NEWS_FILE))) {
            for (String newsUrl : sentNews) {
                writer.write(newsUrl);
                writer.newLine();
            }
        } catch (IOException e) {
            System.err.println("❌ Ошибка при сохранении отправленных новостей: " + e.getMessage());
        }
    }
}
