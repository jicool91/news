package ru.gang.newsBot.scheduler;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.gang.newsBot.bot.NewsBot;

@Component
@RequiredArgsConstructor
public class ScheduledNewsFetcher {

    private final NewsBot newsBot;

    @Scheduled(fixedRateString = "${news.fetch.interval}")
    public void fetchNewsPeriodically() {
        newsBot.fetchAndPostNews();
    }
}
