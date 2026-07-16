package org.example.service;

import org.example.config.AgenticRagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Agentic RAG 护栏执行器
 * <p>
 * 职责：
 * <ol>
 *   <li>维护每次对话的检索轮次计数（ThreadLocal）</li>
 *   <li>提供超时检测（整个检索阶段）</li>
 *   <li>向工具提供 _meta 信息（当前轮次 / 剩余轮次）</li>
 * </ol>
 * <p>
 * 注意：Guard 不拦截工具调用（不在代码层面阻止 Agent），
 * 而是通过 _meta 字段 + System Prompt 指令引导 Agent 行为。
 */
@Component
public class AgenticRagGuard {

    private static final Logger logger = LoggerFactory.getLogger(AgenticRagGuard.class);

    private final AgenticRagProperties properties;

    /** 当前对话线程的检索轮次计数 */
    private final ThreadLocal<Integer> roundCounter = ThreadLocal.withInitial(() -> 0);

    /** 当前对话线程的检索阶段开始时间 */
    private final ThreadLocal<Long> startTime = ThreadLocal.withInitial(System::currentTimeMillis);

    public AgenticRagGuard(AgenticRagProperties properties) {
        this.properties = properties;
    }

    /**
     * 新一轮对话开始时重置计数器
     * 应在 ChatService 创建新对话时调用
     */
    public void reset() {
        roundCounter.set(0);
        startTime.set(System.currentTimeMillis());
        logger.debug("AgenticRagGuard 已重置");
    }

    /**
     * 检索前调用，返回当前轮次信息并自动递增计数器
     *
     * @return 包含 round / maxRounds / remainingRounds 的 RoundInfo
     */
    public RoundInfo beforeSearch() {
        int current = roundCounter.get() + 1;
        roundCounter.set(current);
        int remaining = Math.max(0, properties.getMaxSearchRounds() - current);
        logger.debug("AgenticRagGuard beforeSearch: round={}, maxRounds={}, remaining={}",
                current, properties.getMaxSearchRounds(), remaining);
        return new RoundInfo(current, properties.getMaxSearchRounds(), remaining);
    }

    /**
     * 判断是否应该强制停止检索
     * 条件：达到最大轮次 或 超过总超时
     */
    public boolean shouldForceStop() {
        if (roundCounter.get() >= properties.getMaxSearchRounds()) {
            logger.debug("AgenticRagGuard shouldForceStop: 达到最大轮次 {}", properties.getMaxSearchRounds());
            return true;
        }
        long elapsed = System.currentTimeMillis() - startTime.get();
        if (elapsed > properties.getTimeoutSeconds() * 1000) {
            logger.debug("AgenticRagGuard shouldForceStop: 超时 {}ms", elapsed);
            return true;
        }
        return false;
    }

    /**
     * 获取当前轮次信息（不递增计数器）
     */
    public RoundInfo currentRound() {
        int current = roundCounter.get();
        int remaining = Math.max(0, properties.getMaxSearchRounds() - current);
        return new RoundInfo(current, properties.getMaxSearchRounds(), remaining);
    }

    /**
     * 轮次信息记录
     */
    public record RoundInfo(int round, int maxRounds, int remainingRounds) {
    }
}
