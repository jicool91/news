package ru.gang.newsBot.util;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;

@Slf4j
public class HttpRequestUtil {

    @Getter
    @AllArgsConstructor
    public static class RequestConfig {
        private int maxRetries;
        private int initialTimeoutMs;
        private int maxTimeoutMs;
    }

    public static Document fetchWithRetry(String url, RequestConfig config) throws Exception {
        int retries = 0;
        int currentTimeout = config.initialTimeoutMs;
        Exception lastException = null;

        while (retries <= config.maxRetries) {
            try {
                log.debug("Попытка #{} (таймаут: {}мс): {}", retries + 1, currentTimeout, url);

                Connection connection = Jsoup.connect(url)
                        .userAgent("Mozilla/5.0")
                        .timeout(currentTimeout);

                return connection.get();
            } catch (SocketTimeoutException e) {
                lastException = e;
                log.debug("Таймаут при попытке #{}: {}", retries + 1, e.getMessage());

                retries++;
                if (retries <= config.maxRetries) {
                    int backoffMs = (int) Math.min(currentTimeout * 1.5, config.maxTimeoutMs);
                    log.debug("Повторная попытка через {}мс", backoffMs - currentTimeout);

                    try {
                        TimeUnit.MILLISECONDS.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw ie;
                    }

                    currentTimeout = backoffMs;
                }
            } catch (Exception e) {
                log.error("Неожиданная ошибка при запросе {}: {}", url, e.getMessage());
                throw e;
            }
        }

        log.warn("Все попытки исчерпаны ({}) для URL: {}", config.maxRetries, url);
        throw lastException;
    }
}