package ru.gang.newsBot.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Function;

@Slf4j
@Component
public class AsyncExceptionHandler {

    public <T> CompletableFuture<T> withExceptionHandling(CompletableFuture<T> future, String operationName) {
        return future.exceptionally(ex -> {
            if (ex.getCause() instanceof TimeoutException) {
                log.error("Таймаут в асинхронной операции {}", operationName);
            } else {
                log.error("Ошибка в асинхронной операции {}: {}", operationName, ex.getMessage(), ex);
            }
            return null;
        });
    }

    public <T> CompletableFuture<T> withRecovery(
            CompletableFuture<T> future,
            String operationName,
            Function<Throwable, T> recoveryFunction) {

        return future.exceptionally(ex -> {
            log.warn("Восстановление после ошибки в операции {}: {}", operationName, ex.getMessage());
            return recoveryFunction.apply(ex);
        });
    }

    public <T> CompletableFuture<T> withRetry(
            CompletableFuture<T> future,
            String operationName,
            Runnable retryOperation,
            int maxRetries) {

        return withRetryInternal(future, operationName, retryOperation, 0, maxRetries);
    }

    private <T> CompletableFuture<T> withRetryInternal(
            CompletableFuture<T> future,
            String operationName,
            Runnable retryOperation,
            int currentRetry,
            int maxRetries) {

        return future.exceptionally(ex -> {
            if (currentRetry < maxRetries) {
                log.warn("Повторная попытка #{} для операции {} после ошибки: {}",
                        currentRetry + 1, operationName, ex.getMessage());

                try {
                    retryOperation.run();
                    return null;
                } catch (Exception retryEx) {
                    log.error("Ошибка при повторной попытке #{} для операции {}: {}",
                            currentRetry + 1, operationName, retryEx.getMessage(), retryEx);
                    return null;
                }
            } else {
                log.error("Исчерпаны все {} попыток для операции {}: {}",
                        maxRetries, operationName, ex.getMessage(), ex);
                return null;
            }
        });
    }

    public <T, U> CompletableFuture<U> withChainedExceptionHandling(
            CompletableFuture<T> future,
            Function<T, U> normalMapper,
            BiFunction<T, Throwable, U> exceptionMapper) {

        return future.handle((result, ex) -> {
            if (ex != null) {
                return exceptionMapper.apply(result, ex);
            } else {
                return normalMapper.apply(result);
            }
        });
    }

    public <T> CompletableFuture<T> withProgressLogging(CompletableFuture<T> future, String operationName) {
        future.thenRun(() -> log.debug("Операция {} успешно завершена", operationName));

        return future.exceptionally(ex -> {
            log.error("Операция {} завершилась с ошибкой: {}", operationName, ex.getMessage(), ex);
            return null;
        });
    }
}