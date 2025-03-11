package ru.gang.newsBot.scheduler;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.gang.newsBot.bot.NewsBot;

@Component
public class ScheduledNewsFetcher {

    private final NewsBot newsBot;

    public ScheduledNewsFetcher(NewsBot newsBot) {
        this.newsBot = newsBot;
    }

    @Scheduled(fixedRateString = "${news.fetch.interval}")
    public void fetchNewsPeriodically() {
        newsBot.fetchAndPostNews();
    }
}
