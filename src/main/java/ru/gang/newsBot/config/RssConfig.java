package ru.gang.newsBot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "news.rss")
public class RssConfig {
    private int timeout = 30000;
    private int maxRetries = 3;
    private int maxTimeout = 60000;
}