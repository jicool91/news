package ru.gang.newsBot.scheduler;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.gang.newsBot.bot.NewsBot;

@Component
@RequiredArgsConstructor
public class ScheduledNewsFetcher {
    private static final Logger log = LoggerFactory.getLogger(ScheduledNewsFetcher.class);

    private final NewsBot newsBot;

    @Scheduled(fixedRateString = "${news.fetch.interval}")
    public void fetchNewsPeriodically() {
        log.info("Запуск планировщика обновления новостей");
        newsBot.fetchAndPostNews();
        log.info("Плановое обновление новостей завершено");
    }
}
