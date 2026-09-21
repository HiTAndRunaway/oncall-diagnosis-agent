package org.example.agent.tool;

import org.example.security.CurrentUser;
import org.example.service.MemorySearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RecallMemoryTool} 身份隔离测试（不依赖网络 / LLM / Spring 容器）
 * <p>
 * 回归护栏：改造前用户身份由上游写入静态 {@code ThreadLocal}，在并发请求、
 * 线程池复用、流式线程切换下会读到<b>其他用户</b>的身份，造成跨用户记忆泄露。
 * 改造后身份在调用时从 Spring Security 上下文读取，本测试锁定该行为。
 */
class RecallMemoryToolTest {

    private RecallMemoryTool tool;
    private MemorySearchService memorySearchService;

    @BeforeEach
    void setUp() {
        memorySearchService = mock(MemorySearchService.class);
        tool = new RecallMemoryTool();
        ReflectionTestUtils.setField(tool, "memorySearchService", memorySearchService);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        userId, "n/a", AuthorityUtils.createAuthorityList("ROLE_USER")));
    }

    // ===== 身份来源 =====

    @Test
    void recallMemory_usesCurrentSecurityContextUserId() {
        authenticate("user-a");
        when(memorySearchService.search(anyString(), anyString(), anyInt())).thenReturn("[]");

        tool.recallMemory("我的偏好", 3);

        verify(memorySearchService).search(eq("user-a"), eq("我的偏好"), eq(3));
    }

    @Test
    void recallMemory_identityIsReReadPerCall_notCached() {
        when(memorySearchService.search(anyString(), anyString(), anyInt())).thenReturn("[]");

        authenticate("user-a");
        tool.recallMemory("q1", 3);

        // 同一实例、同一线程，身份必须随上下文变化重新读取
        authenticate("user-b");
        tool.recallMemory("q2", 3);

        verify(memorySearchService).search(eq("user-a"), eq("q1"), eq(3));
        verify(memorySearchService).search(eq("user-b"), eq("q2"), eq(3));
    }

    // ===== 未认证时的失败安全 =====

    @Test
    void recallMemory_unauthenticated_returnsErrorAndDoesNotSearch() {
        SecurityContextHolder.clearContext();

        String result = tool.recallMemory("任意查询", 3);

        assertTrue(result.contains("error"), "未认证时应返回错误 JSON，实际: " + result);
        assertTrue(result.contains("[]"), "未认证时结果列表应为空");
        verify(memorySearchService, never()).search(anyString(), anyString(), anyInt());
    }

    @Test
    void recallMemory_anonymousToken_returnsErrorAndDoesNotSearch() {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser",
                        AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        String result = tool.recallMemory("任意查询", 3);

        assertTrue(result.contains("error"));
        verify(memorySearchService, never()).search(anyString(), anyString(), anyInt());
    }

    // ===== 并发串号回归护栏 =====

    @Test
    void recallMemory_concurrentUsers_neverSeeEachOthersIdentity() throws Exception {
        final int threads = 16;
        final int iterations = 200;

        when(memorySearchService.search(anyString(), anyString(), anyInt()))
                .thenAnswer(inv -> inv.getArgument(0));

        Set<String> violations = ConcurrentHashMap.newKeySet();
        AtomicInteger seq = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads * iterations);

        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < iterations; i++) {
                            try {
                                int id = seq.incrementAndGet();
                                String userId = "user-" + id;
                                authenticate(userId);
                                String result = tool.recallMemory("query-" + id, 3);
                                if (!userId.equals(result)) {
                                    violations.add("期望 " + userId + "，实际 " + result);
                                }
                            } finally {
                                done.countDown();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "并发测试超时");
        } finally {
            pool.shutdownNow();
        }

        assertTrue(violations.isEmpty(),
                "检测到跨用户身份串号（共 " + violations.size() + " 例）: " + violations);
    }

    /**
     * 线程池复用场景：本线程未设置身份时，绝不能读到其他线程留下的身份。
     * <p>
     * 这一条正是改造前静态 {@code ThreadLocal} 的失效模式 —— 若将来有人重新引入
     * 手工身份缓存，本用例会失败。
     */
    @Test
    void recallMemory_doesNotLeakIdentityAcrossThreads() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // 先在线程池线程上以 user-a 身份调用一次，让该线程留下痕迹
            pool.submit(() -> {
                authenticate("user-a");
                return tool.recallMemory("q-a", 3);
            }).get(5, TimeUnit.SECONDS);

            // 再次提交任务：该 worker 线程未设置身份，不得复用 user-a
            String result = pool.submit(() -> {
                SecurityContextHolder.clearContext();
                return tool.recallMemory("q-none", 3);
            }).get(5, TimeUnit.SECONDS);

            assertTrue(result.contains("error"),
                    "未认证调用必须被拒绝，不得复用上一次调用留下的身份。实际: " + result);

            // 本测试线程的身份也不得被 worker 线程影响
            assertFalse(CurrentUser.isAuthenticated());
            verify(memorySearchService, never()).search(eq("user-a"), eq("q-none"), anyInt());
        } finally {
            pool.shutdownNow();
        }
    }

    // ===== 既有行为保持不变 =====

    @Test
    void recallMemory_topKDefaultIsThree() {
        authenticate("user-a");
        when(memorySearchService.search(anyString(), anyString(), anyInt())).thenReturn("[]");

        tool.recallMemory("查询", null);

        verify(memorySearchService).search(eq("user-a"), eq("查询"), eq(3));
    }

    @Test
    void recallMemory_topKIsCappedAtTen() {
        authenticate("user-a");
        when(memorySearchService.search(anyString(), anyString(), anyInt())).thenReturn("[]");

        tool.recallMemory("查询", 999);

        verify(memorySearchService).search(eq("user-a"), eq("查询"), eq(10));
    }

    @Test
    void recallMemory_returnsDownstreamResultUnchanged() {
        authenticate("user-a");
        String payload = "[{\"content\":\"用户偏好中文\"}]";
        when(memorySearchService.search(any(), any(), anyInt())).thenReturn(payload);

        assertEquals(payload, tool.recallMemory("偏好", 1));
    }
}
