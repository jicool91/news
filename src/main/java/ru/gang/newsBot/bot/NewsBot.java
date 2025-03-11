package ru.gang.newsBot.bot;

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

import java.util.List;

@Component
public class NewsBot extends TelegramLongPollingBot {

    private final RssParserService rssParserService;
    private final NewsAnalyzerService newsAnalyzerService;
    private final NewsPosterService newsPosterService;

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
        // Укажите имя вашего бота
        return "News_parser_all_bot";
    }

    @Override
    public String getBotToken() {
        // Укажите ваш реальный API-токен
        return "7911138040:AAGVHPinpk116wKKMJ87KBKK6GuPQN28ESA";
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

    /**
     * Вспомогательный метод для отправки обычного текстового сообщения.
     */
    private void sendTextMessage(Long chatId, String text) {
        try {
            SendMessage message = new SendMessage(chatId.toString(), text);
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    /**
     * Метод, который загружает новости и отправляет в канал/чат.
     * Его можно вызывать вручную или по расписанию (через ScheduledNewsFetcher).
     */
    public void fetchAndPostNews() {
        System.out.println("🔄 Запущено обновление новостей...");
        List<NewsItem> newsList = rssParserService.fetchNewsWithImages();
        System.out.println("📰 Загружено " + newsList.size() + " новостей с фото");

        if (!newsList.isEmpty()) {
            NewsItem news = newsList.get(0);
            System.out.println("✍ Отправка новости: " + news.getTitle());

            // Пусть NewsPosterService сформирует сообщение с фото
            SendPhoto photoMessage = newsPosterService.buildPhotoMessage(
                    news.getTitle(),
                    news.getUrl(),
                    news.getSource(),
                    news.getImageUrl(),
                    news.getDescription()
            );

            // А сам бот выполнит отправку
            try {
                execute(photoMessage);
                System.out.println("✅ Новость с фото отправлена!");
            } catch (TelegramApiException e) {
                System.err.println("❌ Ошибка при отправке фото: " + e.getMessage());
            }
        } else {
            System.out.println("⚠ Нет новостей с изображениями!");
        }
    }
}
