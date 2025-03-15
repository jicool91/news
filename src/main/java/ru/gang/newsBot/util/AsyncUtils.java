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

    @Getter
    private final Executor ioExecutor;

    @Getter
    private final Executor cpuExecutor;

    private final ThreadPoolMonitor threadPoolMonitor;

    @Value("${thread-pool.default-timeout-seconds:30}")
    private int defaultTimeoutSeconds;

    public AsyncUtils(
            @Qualifier("ioTaskExecutor") Executor ioExecutor,
            @Qualifier("cpuTaskExecutor") Executor cpuExecutor,
            ThreadPoolMonitor threadPoolMonitor) {
        this.ioExecutor = ioExecutor;
        this.cpuExecutor = cpuExecutor;
        this.threadPoolMonitor = threadPoolMonitor;
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
        return CompletableFuture.runAsync(runnable, ioExecutor)
                .orTimeout(defaultTimeoutSeconds, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    log.error("Ошибка при выполнении асинхронной IO-операции: {}", ex.getMessage(), ex);
                    return null;
                });
    }

    public CompletableFuture<Void> asyncIoRun(Runnable runnable, String operationName) {
        return CompletableFuture.runAsync(runnable, ioExecutor)
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

    public CompletableFuture<Void> asyncCpuRun(Runnable runnable) {
        return CompletableFuture.runAsync(runnable, cpuExecutor)
                .orTimeout(defaultTimeoutSeconds, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    log.error("Ошибка при выполнении асинхронной CPU-операции: {}", ex.getMessage(), ex);
                    return null;
                });
    }

    public CompletableFuture<Void> asyncCpuRun(Runnable runnable, String operationName) {
        return CompletableFuture.runAsync(runnable, cpuExecutor)
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

    public <T> CompletableFuture<T> asyncIoWithTimeout(Supplier<T> supplier, String operationName, long timeout, TimeUnit timeUnit) {
        return CompletableFuture.supplyAsync(supplier, ioExecutor)
                .orTimeout(timeout, timeUnit)
                .exceptionally(ex -> {
                    if (ex.getCause() instanceof java.util.concurrent.TimeoutException) {
                        log.error("Таймаут при выполнении {}: превышено {} {}",
                                operationName, timeout, timeUnit.toString().toLowerCase());
                    } else {
                        log.error("Ошибка при выполнении {}: {}", operationName, ex.getMessage(), ex);
                    }
                    return null;
                });
    }

    public <T> CompletableFuture<List<T>> allOf(Collection<CompletableFuture<T>> futures) {
        CompletableFuture<Void> allDone = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0])
        );

        return allDone.thenApply(v ->
                futures.stream()
                        .map(CompletableFuture::join)
                        .collect(Collectors.toList())
        );
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

    public <T, R> CompletableFuture<List<R>> processCollectionInBatches(
            Collection<T> items,
            Function<T, R> processor,
            int batchSize) {

        List<List<T>> batches = partitionList(items, batchSize);

        List<CompletableFuture<List<R>>> batchFutures = batches.stream()
                .map(batch -> processCollectionAsync(batch, processor))
                .collect(Collectors.toList());

        return allOf(batchFutures).thenApply(listOfLists ->
                listOfLists.stream()
                        .flatMap(Collection::stream)
                        .collect(Collectors.toList())
        );
    }

    private <T> List<List<T>> partitionList(Collection<T> items, int batchSize) {
        return items.stream()
                .collect(Collectors.groupingBy(item ->
                        Math.floorDiv(items.stream().toList().indexOf(item), batchSize)))
                .values()
                .stream()
                .collect(Collectors.toList());
    }

    public <T> CompletableFuture<T> withRetry(Supplier<T> task, int maxRetries, long delayMs) {
        return withRetryInternal(task, 0, maxRetries, delayMs, "Retry operation");
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
                        log.warn("Ошибка при выполнении {}, попытка {}/{}. Повтор через {} мс. Ошибка: {}",
                                operationName, currentRetry + 1, maxRetries + 1, delayMs, ex.getMessage());

                        try {
                            Thread.sleep(delayMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Прерывание при ожидании повторной попытки", ie);
                        }

                        return withRetryInternal(task, currentRetry + 1, maxRetries, delayMs, operationName)
                                .join();
                    } else {
                        log.error("Исчерпаны все попытки ({}) для операции {}. Последняя ошибка: {}",
                                maxRetries + 1, operationName, ex.getMessage());
                        throw new RuntimeException("Исчерпаны все попытки для " + operationName, ex);
                    }
                });
    }

    public String getThreadPoolsInfo() {
        StringBuilder info = new StringBuilder("Состояние пулов потоков:\n");

        threadPoolMonitor.getStats().forEach((name, stats) -> {
            info.append(String.format("- %s: активные=%d, размер пула=%d, в очереди=%d, выполнено=%d\n",
                    name, stats.getActiveCount(), stats.getPoolSize(), stats.getQueueSize(), stats.getCompletedTaskCount()));
        });

        return info.toString();
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