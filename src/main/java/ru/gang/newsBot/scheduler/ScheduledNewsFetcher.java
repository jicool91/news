package ru.gang.newsBot.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.gang.newsBot.bot.NewsBot;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledNewsFetcher {

    private final NewsBot newsBot;

    @Scheduled(fixedRateString = "${news.fetch.interval}")
    public void fetchNewsPeriodically() {
        log.info("Запуск планировщика обновления новостей");
        newsBot.fetchAndPostNews();
        log.info("Плановое обновление новостей завершено");
    }
}