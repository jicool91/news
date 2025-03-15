package ru.gang.newsBot.config;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Configuration
@EnableAsync
@RequiredArgsConstructor
public class ThreadPoolConfig {

    @Value("${thread-pool.io.core-size:10}") private int ioPoolCoreSize;
    @Value("${thread-pool.io.max-size:50}") private int ioPoolMaxSize;
    @Value("${thread-pool.io.queue-capacity:100}") private int ioPoolQueueCapacity;
    @Value("${thread-pool.io.keep-alive-seconds:120}") private int ioPoolKeepAliveSeconds;
    @Value("${thread-pool.io.min-size:5}") private int ioMinSize;
    @Value("${thread-pool.io.absolute-max-size:100}") private int ioAbsoluteMaxSize;

    @Value("${thread-pool.cpu.core-size:4}") private int cpuPoolCoreSize;
    @Value("${thread-pool.cpu.max-size:8}") private int cpuPoolMaxSize;
    @Value("${thread-pool.cpu.queue-capacity:50}") private int cpuPoolQueueCapacity;
    @Value("${thread-pool.cpu.keep-alive-seconds:60}") private int cpuPoolKeepAliveSeconds;
    @Value("${thread-pool.cpu.min-size:2}") private int cpuMinSize;
    @Value("${thread-pool.cpu.absolute-max-size:16}") private int cpuAbsoluteMaxSize;

    @Value("${thread-pool.scheduler.size:3}") private int schedulerPoolSize;
    @Value("${thread-pool.adaptive.enabled:true}") private boolean adaptiveEnabled;
    @Value("${thread-pool.adaptive.io-high-load-threshold:0.7}") private double ioHighLoadThreshold;
    @Value("${thread-pool.adaptive.io-low-load-threshold:0.3}") private double ioLowLoadThreshold;
    @Value("${thread-pool.adaptive.cpu-high-load-threshold:0.8}") private double cpuHighLoadThreshold;
    @Value("${thread-pool.adaptive.cpu-low-load-threshold:0.4}") private double cpuLowLoadThreshold;
    @Value("${thread-pool.adaptive.scale-factor:1.5}") private double scaleFactor;

    @Bean(name = "threadPoolMonitor")
    public ThreadPoolMonitor threadPoolMonitor() {
        return new ThreadPoolMonitor();
    }

    @Bean(name = "ioTaskExecutor")
    public ThreadPoolTaskExecutor ioTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(ioPoolCoreSize);
        executor.setMaxPoolSize(ioPoolMaxSize);
        executor.setQueueCapacity(ioPoolQueueCapacity);
        executor.setKeepAliveSeconds(ioPoolKeepAliveSeconds);
        executor.setThreadNamePrefix("io-thread-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();

        log.info("Создан пул потоков для IO-операций: core={}, max={}", ioPoolCoreSize, ioPoolMaxSize);
        threadPoolMonitor().registerPool("ioTaskExecutor", executor);
        return executor;
    }

    @Bean(name = "cpuTaskExecutor")
    public ThreadPoolTaskExecutor cpuTaskExecutor() {
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        int actualCoreSize = Math.min(availableProcessors, cpuPoolCoreSize);
        int actualMaxSize = Math.min(availableProcessors * 2, cpuPoolMaxSize);

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(actualCoreSize);
        executor.setMaxPoolSize(actualMaxSize);
        executor.setQueueCapacity(cpuPoolQueueCapacity);
        executor.setKeepAliveSeconds(cpuPoolKeepAliveSeconds);
        executor.setThreadNamePrefix("cpu-thread-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();

        log.info("Создан пул потоков для CPU-операций: core={}, max={}", actualCoreSize, actualMaxSize);
        threadPoolMonitor().registerPool("cpuTaskExecutor", executor);
        return executor;
    }

    @Bean(name = "schedulerExecutor")
    public ScheduledExecutorService schedulerExecutor() {
        return Executors.newScheduledThreadPool(schedulerPoolSize, createThreadFactory("scheduler-", true));
    }

    private ThreadFactory createThreadFactory(String namePrefix, boolean daemon) {
        return new ThreadFactory() {
            private final AtomicLong threadCounter = new AtomicLong(0);

            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r);
                thread.setName(namePrefix + threadCounter.incrementAndGet());
                thread.setDaemon(daemon);
                thread.setUncaughtExceptionHandler((t, e) ->
                        log.error("Необработанное исключение в потоке {}: {}", t.getName(), e.getMessage(), e));
                return thread;
            }
        };
    }

    @Scheduled(fixedRateString = "${thread-pool.monitoring.log-interval-ms:60000}")
    public void logThreadPoolMetrics() {
        Map<String, ThreadPoolStats> stats = threadPoolMonitor().getStats();

        stats.forEach((name, stat) -> {
            double utilizationRate = calculateUtilizationRate(stat);
            double queueUtilizationRate = calculateQueueUtilizationRate(stat);

            log.info("Пул потоков {}: активные={}, размер={}, загрузка={:.1f}%, очередь={:.1f}%",
                    name, stat.getActiveCount(), stat.getPoolSize(),
                    utilizationRate * 100, queueUtilizationRate * 100);
        });
    }

    @Scheduled(fixedRateString = "${thread-pool.adaptive.adjustment-interval-ms:300000}")
    public void adjustPoolSizes() {
        if (!adaptiveEnabled) return;

        adjustIoPoolSize();
        adjustCpuPoolSize();
    }

    private void adjustIoPoolSize() {
        ThreadPoolStats stats = threadPoolMonitor().getStats().get("ioTaskExecutor");
        if (stats == null) return;

        double utilizationRate = calculateUtilizationRate(stats);
        double queueUtilizationRate = calculateQueueUtilizationRate(stats);

        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) threadPoolMonitor().getMonitoredPools().get("ioTaskExecutor");
        int currentCoreSize = executor.getCorePoolSize();
        int currentMaxSize = executor.getMaxPoolSize();

        if (utilizationRate > ioHighLoadThreshold || queueUtilizationRate > 0.5) {
            int newCoreSize = Math.min((int)(currentCoreSize * scaleFactor), ioAbsoluteMaxSize);
            int newMaxSize = Math.min((int)(currentMaxSize * scaleFactor), ioAbsoluteMaxSize);

            if (newCoreSize > currentCoreSize || newMaxSize > currentMaxSize) {
                log.info("Увеличиваем IO пул: core {} -> {}, max {} -> {}",
                        currentCoreSize, newCoreSize, currentMaxSize, newMaxSize);
                updatePoolSize(executor, newCoreSize, newMaxSize);
            }
        } else if (utilizationRate < ioLowLoadThreshold && queueUtilizationRate < 0.1) {
            int newCoreSize = Math.max((int)(currentCoreSize / scaleFactor), ioMinSize);
            int newMaxSize = Math.max((int)(currentMaxSize / scaleFactor), (int)(newCoreSize * 1.5));

            if (newCoreSize < currentCoreSize && newMaxSize < currentMaxSize) {
                log.info("Уменьшаем IO пул: core {} -> {}, max {} -> {}",
                        currentCoreSize, newCoreSize, currentMaxSize, newMaxSize);
                updatePoolSize(executor, newCoreSize, newMaxSize);
            }
        }
    }

    private void adjustCpuPoolSize() {
        ThreadPoolStats stats = threadPoolMonitor().getStats().get("cpuTaskExecutor");
        if (stats == null) return;

        double utilizationRate = calculateUtilizationRate(stats);
        double queueUtilizationRate = calculateQueueUtilizationRate(stats);

        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) threadPoolMonitor().getMonitoredPools().get("cpuTaskExecutor");
        int currentCoreSize = executor.getCorePoolSize();
        int currentMaxSize = executor.getMaxPoolSize();
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        int absoluteMaxSize = Math.min(cpuAbsoluteMaxSize, availableProcessors * 2);

        if (utilizationRate > cpuHighLoadThreshold || queueUtilizationRate > 0.5) {
            int newCoreSize = Math.min((int)(currentCoreSize * scaleFactor), absoluteMaxSize);
            int newMaxSize = Math.min((int)(currentMaxSize * scaleFactor), absoluteMaxSize);

            if (newCoreSize > currentCoreSize || newMaxSize > currentMaxSize) {
                log.info("Увеличиваем CPU пул: core {} -> {}, max {} -> {}",
                        currentCoreSize, newCoreSize, currentMaxSize, newMaxSize);
                updatePoolSize(executor, newCoreSize, newMaxSize);
            }
        } else if (utilizationRate < cpuLowLoadThreshold && queueUtilizationRate < 0.1) {
            int newCoreSize = Math.max((int)(currentCoreSize / scaleFactor), cpuMinSize);
            int newMaxSize = Math.max((int)(currentMaxSize / scaleFactor), (int)(newCoreSize * 1.5));

            if (newCoreSize < currentCoreSize && newMaxSize < currentMaxSize) {
                log.info("Уменьшаем CPU пул: core {} -> {}, max {} -> {}",
                        currentCoreSize, newCoreSize, currentMaxSize, newMaxSize);
                updatePoolSize(executor, newCoreSize, newMaxSize);
            }
        }
    }

    private void updatePoolSize(ThreadPoolTaskExecutor executor, int coreSize, int maxSize) {
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
    }

    private double calculateUtilizationRate(ThreadPoolStats stats) {
        return stats.getPoolSize() == 0 ? 0.0 : (double) stats.getActiveCount() / stats.getPoolSize();
    }

    private double calculateQueueUtilizationRate(ThreadPoolStats stats) {
        if (stats.getQueueSize() == 0) return 0.0;

        ThreadPoolExecutor executor = null;
        if ("ioTaskExecutor".equals(getExecutorName(stats))) {
            executor = ((ThreadPoolTaskExecutor)threadPoolMonitor().getMonitoredPools().get("ioTaskExecutor")).getThreadPoolExecutor();
        } else if ("cpuTaskExecutor".equals(getExecutorName(stats))) {
            executor = ((ThreadPoolTaskExecutor)threadPoolMonitor().getMonitoredPools().get("cpuTaskExecutor")).getThreadPoolExecutor();
        }

        if (executor != null) {
            int queueCapacity = executor.getQueue().remainingCapacity() + stats.getQueueSize();
            return (double) stats.getQueueSize() / queueCapacity;
        }
        return 0.0;
    }

    private String getExecutorName(ThreadPoolStats stats) {
        Map<String, ThreadPoolStats> allStats = threadPoolMonitor().getStats();
        for (Map.Entry<String, ThreadPoolStats> entry : allStats.entrySet()) {
            if (stats == entry.getValue()) return entry.getKey();
        }
        return "unknown";
    }

    @Data
    public static class ThreadPoolMonitor {
        private final Map<String, Executor> monitoredPools = new ConcurrentHashMap<>();

        public void registerPool(String name, Executor pool) {
            monitoredPools.put(name, pool);
        }

        public Map<String, ThreadPoolStats> getStats() {
            Map<String, ThreadPoolStats> stats = new ConcurrentHashMap<>();

            monitoredPools.forEach((name, executor) -> {
                if (executor instanceof ThreadPoolTaskExecutor pool) {
                    ThreadPoolStats poolStats = new ThreadPoolStats();
                    poolStats.setActiveCount(pool.getActiveCount());
                    poolStats.setPoolSize(pool.getPoolSize());
                    poolStats.setCorePoolSize(pool.getCorePoolSize());
                    poolStats.setMaxPoolSize(pool.getMaxPoolSize());
                    poolStats.setQueueSize(pool.getThreadPoolExecutor().getQueue().size());
                    poolStats.setCompletedTaskCount(pool.getThreadPoolExecutor().getCompletedTaskCount());
                    poolStats.setTaskCount(pool.getThreadPoolExecutor().getTaskCount());
                    stats.put(name, poolStats);
                }
            });
            return stats;
        }
    }

    @Data
    public static class ThreadPoolStats {
        private int activeCount;
        private int poolSize;
        private int corePoolSize;
        private int maxPoolSize;
        private int queueSize;
        private long completedTaskCount;
        private long taskCount;
    }
}