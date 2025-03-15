package ru.gang.newsBot.bot;

import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
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
import ru.gang.newsBot.util.AsyncUtils;

import java.io.*;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
public class NewsBot extends TelegramLongPollingBot {

    private final RssParserService rssParserService;
    private final NewsAnalyzerService newsAnalyzerService;
    private final NewsPosterService newsPosterService;
    private final AsyncUtils asyncUtils;

    @Getter
    private final Set<String> sentNews = new HashSet<>();

    @Value("${telegram.bot.username}") private String botUsername;
    @Value("${telegram.bot.token}") private String botToken;

    private static final String SENT_NEWS_FILE = "sent_news.txt";

    public NewsBot(DefaultBotOptions options,
                   RssParserService rssParserService,
                   NewsAnalyzerService newsAnalyzerService,
                   NewsPosterService newsPosterService,
                   AsyncUtils asyncUtils) {
        super(options);
        this.rssParserService = rssParserService;
        this.newsAnalyzerService = newsAnalyzerService;
        this.newsPosterService = newsPosterService;
        this.asyncUtils = asyncUtils;
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
        if (update.hasMessage() && update.getMessage().hasText()) {
            String text = update.getMessage().getText();
            Long chatId = update.getMessage().getChatId();

            if ("/fetch".equals(text)) {
                log.info("Команда /fetch обработана!");
                fetchAndPostNews();
                sendTextMessage(chatId, "✅ Новости обновлены!");
            }
        }
    }

    @SneakyThrows
    private void sendTextMessage(Long chatId, String text) {
        try {
            execute(new SendMessage(chatId.toString(), text));
        } catch (TelegramApiException e) {
            log.error("Ошибка при отправке текстового сообщения", e);
        }
    }

    public void fetchAndPostNews() {
        log.info("Запущено обновление новостей...");
        asyncUtils.asyncIoRun(this::loadSentNews);

        Map<String, NewsItem> categoryNewsMap = rssParserService.fetchLatestNewsByCategory();
        log.info("Финальный список отправки новостей: {} категорий", categoryNewsMap.size());

        Set<CompletableFuture<Void>> sendFutures = new HashSet<>();

        categoryNewsMap.forEach((category, news) -> {
            if (sentNews.contains(news.getUrl())) {
                log.debug("Пропуск: уже отправляли - {}", news.getTitle());
                return;
            }

            String channelId = rssParserService.getCategoryChannel(news.getCategory());
            if (channelId == null) {
                log.warn("Не найден канал для категории: {}", news.getCategory());
                return;
            }

            String description = news.getDescription().trim();
            if (description.isEmpty()) {
                description = "Описание недоступно. Подробнее по ссылке ниже.";
            }

            SendPhoto photoMessage = newsPosterService.buildPhotoMessage(
                    news.getTitle(), news.getUrl(), news.getSource(),
                    news.getImageUrl(), news.getDescription(), channelId
            );

            CompletableFuture<Void> sendFuture = asyncUtils.asyncIo(() -> {
                try {
                    execute(photoMessage);
                    sentNews.add(news.getUrl());
                    saveSentNews();
                    return true;
                } catch (TelegramApiException e) {
                    log.error("Ошибка при отправке фото: {}", e.getMessage(), e);
                    return false;
                }
            }, "Отправка новости " + news.getTitle()).thenAccept(success -> {
                if (success) {
                    log.debug("Новость '{}' успешно отправлена", news.getTitle());
                }
            });

            sendFutures.add(sendFuture);
        });

        if (!sendFutures.isEmpty()) {
            CompletableFuture.allOf(sendFutures.toArray(new CompletableFuture[0]))
                    .thenRun(() -> log.info("Все новости обработаны, отправлено {} из {} новостей",
                            sendFutures.size(), categoryNewsMap.size()));
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
        asyncUtils.asyncIoRun(() -> {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(SENT_NEWS_FILE))) {
                for (String newsUrl : sentNews) {
                    writer.write(newsUrl);
                    writer.newLine();
                }
            } catch (IOException e) {
                log.error("Ошибка при сохранении отправленных новостей", e);
            }
        });
    }
}