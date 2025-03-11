package ru.gang.newsBot.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.gang.newsBot.bot.NewsBot;

/**
 * Отдельный сервис, который вызывает метод обновления новостей
 * по расписанию, чтобы не проксировать сам бот.
 */
@Service
public class ScheduledNewsFetcher {

    private final NewsBot newsBot;

    public ScheduledNewsFetcher(NewsBot newsBot) {
        this.newsBot = newsBot;
    }

    /**
     * Раз в 10 минут вызываем fetchAndPostNews() у бота.
     */
    @Scheduled(fixedRate = 600000)
    public void fetchNews() {
        newsBot.fetchAndPostNews();
    }
}
