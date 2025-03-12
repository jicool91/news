package ru.gang.newsBot.bot;

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;
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
    private static final Logger log = LoggerFactory.getLogger(NewsBot.class);

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
        log.info("Бот успешно запущен и подключен к Telegram API");
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
        log.debug("Получен update: {}", update);

        if (update.hasMessage() && update.getMessage().hasText()) {
            String text = update.getMessage().getText();
            Long chatId = update.getMessage().getChatId();

            log.info("Получено сообщение: {}", text);

            if ("/fetch".equals(text)) {
                log.info("Команда /fetch обработана!");
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
            log.error("Ошибка при отправке текстового сообщения", e);
        }
    }

    public void fetchAndPostNews() {
        log.info("Запущено обновление новостей...");

        loadSentNews();

        List<NewsItem> newsList = rssParserService.fetchNewsWithCategory();
        Set<String> sentCategories = new HashSet<>();

        log.info("Финальный список отправки новостей: {} элементов", newsList.size());
        for (NewsItem news : newsList) {
            log.debug("Новость: {} | Категория: {}", news.getTitle(), news.getCategory());
        }

        for (NewsItem news : newsList) {
            if (sentNews.contains(news.getUrl())) {
                log.debug("Пропуск: уже отправляли - {}", news.getTitle());
                continue;
            }

            if (sentCategories.contains(news.getCategory())) {
                log.debug("Пропуск: уже отправлена новость из категории - {}", news.getCategory());
                continue;
            }

            log.info("Отправка новости: {}", news.getTitle());

            String channelId = rssParserService.getCategoryChannel(news.getCategory());
            if (channelId == null) {
                log.warn("Не найден канал для категории: {}", news.getCategory());
                continue;
            }

            log.debug("Готовим отправку в канал {} для категории {}", channelId, news.getCategory());

            // Проверяем описание новости
            String description = news.getDescription().trim();
            if (description.isEmpty()) {
                log.debug("Описание отсутствует, подставляем заглушку.");
                description = "Описание недоступно. Подробнее по ссылке ниже.";
            }

            // Формируем текст сообщения
            String caption = "**" + news.getTitle() + "**\n\n" + description;

            // Учитываем лимит в 1024 символа
            if (caption.length() > 950) {
                caption = caption.substring(0, 950) + "...";
            }

            caption += "\n\n[Читать полностью](" + news.getUrl() + ")";

            // Создаем сообщение с фото
            SendPhoto photoMessage = newsPosterService.buildPhotoMessage(
                    news.getTitle(),
                    news.getUrl(),
                    news.getSource(),
                    news.getImageUrl(),
                    news.getDescription(),
                    channelId
            );

            try {
                execute(photoMessage);
                sentNews.add(news.getUrl());
                sentCategories.add(news.getCategory());
                saveSentNews();
                log.info("Новость успешно отправлена в канал: {}", channelId);
            } catch (TelegramApiException e) {
                log.error("Ошибка при отправке фото: {}", e.getMessage(), e);
            }
        }
    }

    private void loadSentNews() {
        try (BufferedReader reader = new BufferedReader(new FileReader(SENT_NEWS_FILE))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sentNews.add(line.trim());
            }
            log.debug("Загружено {} отправленных ранее новостей", sentNews.size());
        } catch (IOException e) {
            log.info("Файл отправленных новостей не найден. Создаём новый.");
        }
    }

    private void saveSentNews() {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(SENT_NEWS_FILE))) {
            for (String newsUrl : sentNews) {
                writer.write(newsUrl);
                writer.newLine();
            }
            log.debug("Сохранено {} отправленных новостей", sentNews.size());
        } catch (IOException e) {
            log.error("Ошибка при сохранении отправленных новостей", e);
        }
    }
}