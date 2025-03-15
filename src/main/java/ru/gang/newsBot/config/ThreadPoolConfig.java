package ru.gang.newsBot.config;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
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

    @Value("${thread-pool.io.core-size:10}")
    private int ioPoolCoreSize;

    @Value("${thread-pool.io.max-size:50}")
    private int ioPoolMaxSize;

    @Value("${thread-pool.io.queue-capacity:100}")
    private int ioPoolQueueCapacity;

    @Value("${thread-pool.io.keep-alive-seconds:120}")
    private int ioPoolKeepAliveSeconds;

    @Value("${thread-pool.cpu.core-size:4}")
    private int cpuPoolCoreSize;

    @Value("${thread-pool.cpu.max-size:8}")
    private int cpuPoolMaxSize;

    @Value("${thread-pool.cpu.queue-capacity:50}")
    private int cpuPoolQueueCapacity;

    @Value("${thread-pool.cpu.keep-alive-seconds:60}")
    private int cpuPoolKeepAliveSeconds;

    @Value("${thread-pool.scheduler.size:3}")
    private int schedulerPoolSize;

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

        log.info("Создан пул потоков для IO-операций: core={}, max={}, queueCapacity={}",
                ioPoolCoreSize, ioPoolMaxSize, ioPoolQueueCapacity);

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

        log.info("Создан пул потоков для CPU-операций: core={}, max={}, queueCapacity={}, доступно процессоров: {}",
                actualCoreSize, actualMaxSize, cpuPoolQueueCapacity, availableProcessors);

        threadPoolMonitor().registerPool("cpuTaskExecutor", executor);
        return executor;
    }

    @Bean(name = "schedulerExecutor")
    public ScheduledExecutorService schedulerExecutor() {
        return Executors.newScheduledThreadPool(
                schedulerPoolSize,
                createThreadFactory("scheduler-", true)
        );
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
                        log.error("Необработанное исключение в потоке {}: {}", t.getName(), e.getMessage(), e)
                );
                return thread;
            }
        };
    }

    @Bean(name = "threadPoolMonitor")
    public ThreadPoolMonitor threadPoolMonitor() {
        return new ThreadPoolMonitor();
    }

    @Data
    public static class ThreadPoolMonitor {
        private final Map<String, ThreadPoolTaskExecutor> monitoredPools = new ConcurrentHashMap<>();

        public void registerPool(String name, ThreadPoolTaskExecutor pool) {
            monitoredPools.put(name, pool);
            log.debug("Зарегистрирован пул потоков для мониторинга: {}", name);
        }

        public Map<String, ThreadPoolStats> getStats() {
            Map<String, ThreadPoolStats> stats = new ConcurrentHashMap<>();

            monitoredPools.forEach((name, pool) -> {
                ThreadPoolStats poolStats = new ThreadPoolStats();
                poolStats.setActiveCount(pool.getActiveCount());
                poolStats.setPoolSize(pool.getPoolSize());
                poolStats.setCorePoolSize(pool.getCorePoolSize());
                poolStats.setMaxPoolSize(pool.getMaxPoolSize());
                poolStats.setQueueSize(pool.getThreadPoolExecutor().getQueue().size());
                poolStats.setCompletedTaskCount(pool.getThreadPoolExecutor().getCompletedTaskCount());
                poolStats.setTaskCount(pool.getThreadPoolExecutor().getTaskCount());

                stats.put(name, poolStats);
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