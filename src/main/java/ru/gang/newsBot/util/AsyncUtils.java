package ru.gang.newsBot.util;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
@Component
public class AsyncUtils {

    @Getter private final Executor ioExecutor;
    @Getter private final Executor cpuExecutor;

    @Value("${thread-pool.default-timeout-seconds:30}")
    private int defaultTimeoutSeconds;

    public AsyncUtils(
            @Qualifier("ioTaskExecutor") Executor ioExecutor,
            @Qualifier("cpuTaskExecutor") Executor cpuExecutor) {
        this.ioExecutor = ioExecutor;
        this.cpuExecutor = cpuExecutor;
    }

    public <T> CompletableFuture<T> asyncIo(Supplier<T> supplier, String operationName) {
        return CompletableFuture.supplyAsync(supplier, ioExecutor)
                .orTimeout(defaultTimeoutSeconds, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    if (ex.getCause() instanceof java.util.concurrent.TimeoutException) {
                        log.error("Таймаут при выполнении {}: превышено {} секунд", operationName, defaultTimeoutSeconds);
                    } else {
                        log.error("Ошибка при выполнении {}: {}", operationName, ex.getMessage(), ex);
                    }
                    return null;
                });
    }

    public CompletableFuture<Void> asyncIoRun(Runnable runnable) {
        return asyncRun(runnable, ioExecutor, "IO-операция");
    }

    private CompletableFuture<Void> asyncRun(Runnable runnable, Executor executor, String operationName) {
        return CompletableFuture.runAsync(runnable, executor)
                .orTimeout(defaultTimeoutSeconds, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    if (ex.getCause() instanceof java.util.concurrent.TimeoutException) {
                        log.error("Таймаут при выполнении {}: превышено {} секунд", operationName, defaultTimeoutSeconds);
                    } else {
                        log.error("Ошибка при выполнении {}: {}", operationName, ex.getMessage(), ex);
                    }
                    return null;
                });
    }
}