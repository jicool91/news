package ru.gang.newsBot.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import ru.gang.newsBot.config.ThreadPoolConfig.ThreadPoolMonitor;
import ru.gang.newsBot.config.ThreadPoolConfig.ThreadPoolStats;

import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
@Component
public class AdaptiveThreadPoolManager {

    private final ThreadPoolTaskExecutor ioTaskExecutor;
    private final ThreadPoolTaskExecutor cpuTaskExecutor;
    private final ThreadPoolMonitor threadPoolMonitor;

    @Value("${thread-pool.adaptive.enabled:true}")
    private boolean adaptiveEnabled;

    @Value("${thread-pool.adaptive.io-high-load-threshold:0.7}")
    private double ioHighLoadThreshold;

    @Value("${thread-pool.adaptive.io-low-load-threshold:0.3}")
    private double ioLowLoadThreshold;

    @Value("${thread-pool.adaptive.cpu-high-load-threshold:0.8}")
    private double cpuHighLoadThreshold;

    @Value("${thread-pool.adaptive.cpu-low-load-threshold:0.4}")
    private double cpuLowLoadThreshold;

    @Value("${thread-pool.adaptive.scale-factor:1.5}")
    private double scaleFactor;

    @Value("${thread-pool.io.min-size:5}")
    private int ioMinSize;

    @Value("${thread-pool.io.absolute-max-size:100}")
    private int ioAbsoluteMaxSize;

    @Value("${thread-pool.cpu.min-size:2}")
    private int cpuMinSize;

    @Value("${thread-pool.cpu.absolute-max-size:16}")
    private int cpuAbsoluteMaxSize;

    public AdaptiveThreadPoolManager(
            @Qualifier("ioTaskExecutor") ThreadPoolTaskExecutor ioTaskExecutor,
            @Qualifier("cpuTaskExecutor") ThreadPoolTaskExecutor cpuTaskExecutor,
            ThreadPoolMonitor threadPoolMonitor) {
        this.ioTaskExecutor = ioTaskExecutor;
        this.cpuTaskExecutor = cpuTaskExecutor;
        this.threadPoolMonitor = threadPoolMonitor;
    }

    /**
     * Периодически логирует состояние пулов потоков
     */
    @Scheduled(fixedRateString = "${thread-pool.monitoring.log-interval-ms:60000}")
    public void logThreadPoolMetrics() {
        Map<String, ThreadPoolStats> stats = threadPoolMonitor.getStats();

        stats.forEach((name, stat) -> {
            double utilizationRate = calculateUtilizationRate(stat);
            double queueUtilizationRate = calculateQueueUtilizationRate(stat);

            log.info("Пул потоков {}: активные={}, выполнено={}, в очереди={}, размер пула={}, загрузка={:.1f}%, очередь={:.1f}%",
                    name, stat.getActiveCount(), stat.getCompletedTaskCount(),
                    stat.getQueueSize(), stat.getPoolSize(),
                    utilizationRate * 100, queueUtilizationRate * 100);
        });
    }

    /**
     * Адаптивно корректирует размер пулов потоков на основе нагрузки
     */
    @Scheduled(fixedRateString = "${thread-pool.adaptive.adjustment-interval-ms:300000}")
    public void adjustPoolSizes() {
        if (!adaptiveEnabled) {
            return;
        }

        log.debug("Запуск адаптивной корректировки размера пулов потоков");

        adjustIoPoolSize();
        adjustCpuPoolSize();
    }

    /**
     * Корректирует размер пула для IO-операций
     */
    private void adjustIoPoolSize() {
        ThreadPoolStats stats = threadPoolMonitor.getStats().get("ioTaskExecutor");
        if (stats == null) {
            return;
        }

        double utilizationRate = calculateUtilizationRate(stats);
        double queueUtilizationRate = calculateQueueUtilizationRate(stats);

        int currentCoreSize = ioTaskExecutor.getCorePoolSize();
        int currentMaxSize = ioTaskExecutor.getMaxPoolSize();

        // Если высокая загрузка - увеличиваем размер пула
        if (utilizationRate > ioHighLoadThreshold || queueUtilizationRate > 0.5) {
            int newCoreSize = Math.min(
                    (int)(currentCoreSize * scaleFactor),
                    ioAbsoluteMaxSize
            );

            int newMaxSize = Math.min(
                    (int)(currentMaxSize * scaleFactor),
                    ioAbsoluteMaxSize
            );

            if (newCoreSize > currentCoreSize || newMaxSize > currentMaxSize) {
                log.info("Увеличиваем IO пул: core {} -> {}, max {} -> {} (загрузка: {:.1f}%, очередь: {:.1f}%)",
                        currentCoreSize, newCoreSize, currentMaxSize, newMaxSize,
                        utilizationRate * 100, queueUtilizationRate * 100);

                updatePoolSize(ioTaskExecutor, newCoreSize, newMaxSize);
            }
        }
        // Если низкая загрузка - уменьшаем размер пула
        else if (utilizationRate < ioLowLoadThreshold && queueUtilizationRate < 0.1) {
            int newCoreSize = Math.max(
                    (int)(currentCoreSize / scaleFactor),
                    ioMinSize
            );

            int newMaxSize = Math.max(
                    (int)(currentMaxSize / scaleFactor),
                    (int)(newCoreSize * 1.5)
            );

            if (newCoreSize < currentCoreSize && newMaxSize < currentMaxSize) {
                log.info("Уменьшаем IO пул: core {} -> {}, max {} -> {} (загрузка: {:.1f}%, очередь: {:.1f}%)",
                        currentCoreSize, newCoreSize, currentMaxSize, newMaxSize,
                        utilizationRate * 100, queueUtilizationRate * 100);

                updatePoolSize(ioTaskExecutor, newCoreSize, newMaxSize);
            }
        }
    }

    /**
     * Корректирует размер пула для CPU-операций
     */
    private void adjustCpuPoolSize() {
        ThreadPoolStats stats = threadPoolMonitor.getStats().get("cpuTaskExecutor");
        if (stats == null) {
            return;
        }

        double utilizationRate = calculateUtilizationRate(stats);
        double queueUtilizationRate = calculateQueueUtilizationRate(stats);

        int currentCoreSize = cpuTaskExecutor.getCorePoolSize();
        int currentMaxSize = cpuTaskExecutor.getMaxPoolSize();
        int availableProcessors = Runtime.getRuntime().availableProcessors();

        // Для CPU пула учитываем количество доступных процессоров
        int absoluteMaxSize = Math.min(cpuAbsoluteMaxSize, availableProcessors * 2);

        // Если высокая загрузка - увеличиваем размер пула
        if (utilizationRate > cpuHighLoadThreshold || queueUtilizationRate > 0.5) {
            int newCoreSize = Math.min(
                    (int)(currentCoreSize * scaleFactor),
                    absoluteMaxSize
            );

            int newMaxSize = Math.min(
                    (int)(currentMaxSize * scaleFactor),
                    absoluteMaxSize
            );

            if (newCoreSize > currentCoreSize || newMaxSize > currentMaxSize) {
                log.info("Увеличиваем CPU пул: core {} -> {}, max {} -> {} (загрузка: {:.1f}%, очередь: {:.1f}%)",
                        currentCoreSize, newCoreSize, currentMaxSize, newMaxSize,
                        utilizationRate * 100, queueUtilizationRate * 100);

                updatePoolSize(cpuTaskExecutor, newCoreSize, newMaxSize);
            }
        }
        // Если низкая загрузка - уменьшаем размер пула
        else if (utilizationRate < cpuLowLoadThreshold && queueUtilizationRate < 0.1) {
            int newCoreSize = Math.max(
                    (int)(currentCoreSize / scaleFactor),
                    cpuMinSize
            );

            int newMaxSize = Math.max(
                    (int)(currentMaxSize / scaleFactor),
                    (int)(newCoreSize * 1.5)
            );

            if (newCoreSize < currentCoreSize && newMaxSize < currentMaxSize) {
                log.info("Уменьшаем CPU пул: core {} -> {}, max {} -> {} (загрузка: {:.1f}%, очередь: {:.1f}%)",
                        currentCoreSize, newCoreSize, currentMaxSize, newMaxSize,
                        utilizationRate * 100, queueUtilizationRate * 100);

                updatePoolSize(cpuTaskExecutor, newCoreSize, newMaxSize);
            }
        }
    }

    /**
     * Обновляет размер пула потоков
     */
    private void updatePoolSize(ThreadPoolTaskExecutor executor, int coreSize, int maxSize) {
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
    }

    /**
     * Рассчитывает коэффициент использования пула потоков
     */
    private double calculateUtilizationRate(ThreadPoolStats stats) {
        if (stats.getPoolSize() == 0) {
            return 0.0;
        }
        return (double) stats.getActiveCount() / stats.getPoolSize();
    }

    /**
     * Рассчитывает коэффициент заполнения очереди
     */
    private double calculateQueueUtilizationRate(ThreadPoolStats stats) {
        int queueCapacity;
        if (stats.getQueueSize() == 0) {
            return 0.0;
        }

        if (stats.getQueueSize() > 0) {
            ThreadPoolExecutor executor = null;
            if ("ioTaskExecutor".equals(getExecutorName(stats))) {
                executor = ioTaskExecutor.getThreadPoolExecutor();
            } else if ("cpuTaskExecutor".equals(getExecutorName(stats))) {
                executor = cpuTaskExecutor.getThreadPoolExecutor();
            }

            if (executor != null) {
                queueCapacity = executor.getQueue().remainingCapacity() + stats.getQueueSize();
                return (double) stats.getQueueSize() / queueCapacity;
            }
        }

        return 0.0;
    }

    /**
     * Определяет имя пула потоков по статистике
     */
    private String getExecutorName(ThreadPoolStats stats) {
        Map<String, ThreadPoolStats> allStats = threadPoolMonitor.getStats();
        for (Map.Entry<String, ThreadPoolStats> entry : allStats.entrySet()) {
            if (stats == entry.getValue()) {
                return entry.getKey();
            }
        }
        return "unknown";
    }
}