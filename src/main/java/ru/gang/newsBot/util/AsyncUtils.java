package ru.gang.newsBot.util;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.gang.newsBot.config.ThreadPoolConfig.ThreadPoolMonitor;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Slf4j
@Component
public class AsyncUtils {

    @Getter private final Executor ioExecutor;
    @Getter private final Executor cpuExecutor;

    @Value("${thread-pool.default-timeout-seconds:30}")
    private int defaultTimeoutSeconds;

    // Явный конструктор с аннотациями @Qualifier для параметров
    public AsyncUtils(
            @Qualifier("ioTaskExecutor") Executor ioExecutor,
            @Qualifier("cpuTaskExecutor") Executor cpuExecutor) {
        this.ioExecutor = ioExecutor;
        this.cpuExecutor = cpuExecutor;
    }

    public <T> CompletableFuture<T> asyncIo(Supplier<T> supplier) {
        return createFuture(supplier, ioExecutor, "IO-операция");
    }

    public <T> CompletableFuture<T> asyncIo(Supplier<T> supplier, String operationName) {
        return createFuture(supplier, ioExecutor, operationName);
    }

    public <T> CompletableFuture<T> asyncCpu(Supplier<T> supplier) {
        return createFuture(supplier, cpuExecutor, "CPU-операция");
    }

    public <T> CompletableFuture<T> asyncCpu(Supplier<T> supplier, String operationName) {
        return createFuture(supplier, cpuExecutor, operationName);
    }

    public CompletableFuture<Void> asyncIoRun(Runnable runnable) {
        return asyncRun(runnable, ioExecutor, "IO-операция");
    }

    public CompletableFuture<Void> asyncIoRun(Runnable runnable, String operationName) {
        return asyncRun(runnable, ioExecutor, operationName);
    }

    public CompletableFuture<Void> asyncCpuRun(Runnable runnable) {
        return asyncRun(runnable, cpuExecutor, "CPU-операция");
    }

    public CompletableFuture<Void> asyncCpuRun(Runnable runnable, String operationName) {
        return asyncRun(runnable, cpuExecutor, operationName);
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

    private <T> CompletableFuture<T> createFuture(Supplier<T> supplier, Executor executor, String operationName) {
        return CompletableFuture.supplyAsync(supplier, executor)
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

    public <T> CompletableFuture<List<T>> allOf(Collection<CompletableFuture<T>> futures) {
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream()
                        .map(CompletableFuture::join)
                        .collect(Collectors.toList()));
    }

    public <T, R> CompletableFuture<List<R>> processCollectionAsync(
            Collection<T> items,
            Function<T, R> processor) {

        List<CompletableFuture<R>> futures = items.stream()
                .map(item -> asyncIo(() -> processor.apply(item)))
                .collect(Collectors.toList());

        return allOf(futures);
    }

    public <T, R> CompletableFuture<List<R>> processCollectionAsyncCpu(
            Collection<T> items,
            Function<T, R> processor) {

        List<CompletableFuture<R>> futures = items.stream()
                .map(item -> asyncCpu(() -> processor.apply(item)))
                .collect(Collectors.toList());

        return allOf(futures);
    }

    public <T> CompletableFuture<T> withRetry(Supplier<T> task, int maxRetries, long delayMs) {
        return withRetry(task, maxRetries, delayMs, "Retry operation");
    }

    public <T> CompletableFuture<T> withRetry(Supplier<T> task, int maxRetries, long delayMs, String operationName) {
        return withRetryInternal(task, 0, maxRetries, delayMs, operationName);
    }

    private <T> CompletableFuture<T> withRetryInternal(
            Supplier<T> task,
            int currentRetry,
            int maxRetries,
            long delayMs,
            String operationName) {

        return asyncIo(task, operationName + " attempt " + (currentRetry + 1))
                .exceptionally(ex -> {
                    if (currentRetry < maxRetries) {
                        log.warn("Ошибка при выполнении {}, попытка {}/{}. Повтор через {} мс",
                                operationName, currentRetry + 1, maxRetries + 1, delayMs);

                        try {
                            Thread.sleep(delayMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Прерывание при ожидании повторной попытки", ie);
                        }

                        try {
                            return withRetryInternal(task, currentRetry + 1, maxRetries, delayMs, operationName).join();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    } else {
                        log.error("Исчерпаны все попытки ({}) для операции {}", maxRetries + 1, operationName);
                        throw new RuntimeException("Исчерпаны все попытки для " + operationName, ex);
                    }
                });
    }

    public <T> CompletableFuture<T> delayedExecution(Supplier<T> task, long delay, TimeUnit timeUnit) {
        CompletableFuture<T> future = new CompletableFuture<>();

        ioExecutor.execute(() -> {
            try {
                timeUnit.sleep(delay);
                future.complete(task.get());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        return future;
    }
}