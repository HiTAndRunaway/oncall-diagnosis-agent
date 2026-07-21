package org.example.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务配置
 * 为摘要生成等后台任务提供专用线程池
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    private static final Logger logger = LoggerFactory.getLogger(AsyncConfig.class);

    @Bean("summaryExecutor")
    public Executor summaryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("summary-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy() {
            @Override
            public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
                logger.warn("摘要生成线程池已满，任务被拒绝: {}", e.getActiveCount());
                super.rejectedExecution(r, e);
            }
        });
        executor.initialize();
        return executor;
    }

    /**
     * 混合检索专用线程池
     * 为双路并行召回（dense + sparse）提供线程资源
     */
    /**
     * 记忆提取专用线程池
     * 为异步记忆提取和冲突判断提供线程资源
     */
    @Bean("memoryExecutor")
    public Executor memoryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("memory-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy() {
            @Override
            public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
                logger.warn("记忆提取线程池已满，任务被拒绝: {}", e.getActiveCount());
                super.rejectedExecution(r, e);
            }
        });
        executor.initialize();
        return executor;
    }

    @Bean("searchExecutor")
    public Executor searchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.setKeepAliveSeconds(30);
        executor.setThreadNamePrefix("search-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy() {
            @Override
            public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
                logger.warn("检索线程池已满，降级为调用者线程执行");
                super.rejectedExecution(r, e);
            }
        });
        executor.initialize();
        return executor;
    }
}
