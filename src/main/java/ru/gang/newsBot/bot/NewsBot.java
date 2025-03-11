package ru.gang.newsBot.bot;

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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class NewsBot extends TelegramLongPollingBot {

    private final RssParserService rssParserService;
    private final NewsAnalyzerService newsAnalyzerService;
    private final NewsPosterService newsPosterService;

    private final Set<String> sentNews = new HashSet<>();

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

    @Value("${telegram.bot.username}")
    @Override
    public String getBotUsername() {
        // Укажите имя вашего бота
        return "News_parser_all_bot";
    }

    @Value("${telegram.bot.token}")
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
        List<NewsItem> newsList = rssParserService.fetchNewsWithCategory();

        System.out.println("📌 Финальный список отправки новостей:");
        for (NewsItem item : newsList) {  // Изменяем имя переменной в цикле
            System.out.println("📜 " + item.getTitle() + " | Категория: " + item.getCategory());
        }

        for (NewsItem news : newsList) {
            if (sentNews.contains(news.getUrl())) {
                System.out.println("⏭ Пропуск: уже отправлена - " + news.getTitle());
                continue;
            }

            System.out.println("✍ Отправка новости: " + news.getTitle());

            // Получаем канал для категории через RssParserService
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
                sentNews.add(news.getUrl()); // Запоминаем отправленную новость
                System.out.println("✅ Новость отправлена в канал: " + channelId);
            } catch (TelegramApiException e) {
                System.err.println("❌ Ошибка при отправке фото: " + e.getMessage());
            }
        }
    }

}
