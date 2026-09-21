package org.example.agent.tool;

import org.example.config.MemoryProperties;
import org.example.security.CurrentUser;
import org.example.service.MemoryManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;

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
 * {@link ForgetMemoryTool} 身份越权回归测试（不依赖网络 / LLM / Spring 容器）
 * <p>
 * 护栏背景：改造前 {@code forgetMemory} 的 {@code userId} 是一个 LLM 可填的
 * {@code @ToolParam}，模型（或经检索文档注入的指令）可指定任意用户，进而
 * <b>删除他人记忆</b>。改造后身份只能来自认证上下文，且该参数被移除。
 */
class ForgetMemoryToolTest {

    private ForgetMemoryTool tool;
    private MemoryManager memoryManager;
    private MemoryProperties memoryProperties;

    @BeforeEach
    void setUp() {
        memoryManager = mock(MemoryManager.class);
        memoryProperties = new MemoryProperties();   // 默认 requireAuthenticated=true
        tool = new ForgetMemoryTool();
        ReflectionTestUtils.setField(tool, "memoryManager", memoryManager);
        ReflectionTestUtils.setField(tool, "memoryProperties", memoryProperties);
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

    // ===== 工具签名：不得再暴露 userId 参数 =====

    @Test
    void forgetMemory_doesNotExposeUserIdAsToolParameter() throws Exception {
        Method m = ForgetMemoryTool.class.getMethod("forgetMemory", String.class);

        Parameter[] params = m.getParameters();
        assertEquals(1, params.length, "方法应只有一个参数（记忆关键词）");
        assertEquals("target", params[0].getName());
        for (Parameter p : params) {
            assertFalse(p.getName().equalsIgnoreCase("userId"),
                    "userId 不得作为可被模型填充的工具参数");
        }
    }

    @Test
    void toolDescription_statesItOnlyOperatesOnCurrentUser() throws Exception {
        Method m = ForgetMemoryTool.class.getMethod("forgetMemory", String.class);
        String desc = m.getAnnotation(org.springframework.ai.tool.annotation.Tool.class).description();

        // 描述必须正向说明只能作用于当前登录用户，避免模型以为可以指定他人
        assertTrue(desc.contains("当前登录用户"),
                "工具描述应声明只能操作当前登录用户，实际: " + desc);
        assertTrue(desc.contains("不允许指定用户") || desc.contains("无需"),
                "工具描述应说明无需/不允许指定用户，实际: " + desc);
    }

    // ===== 身份来源 =====

    @Test
    void forgetMemory_usesAuthenticatedUserId_notCallerSupplied() {
        authenticate("user-a");
        when(memoryManager.searchSimilarMemories(anyString(), anyString(), anyInt()))
                .thenReturn(List.of());

        tool.forgetMemory("我的偏好");

        verify(memoryManager).searchSimilarMemories(eq("user-a"), eq("我的偏好"), eq(3));
    }

    @Test
    void forgetMemory_deletesOnlyOwnMemories() {
        authenticate("user-a");
        MemoryManager.MemoryResult hit = new MemoryManager.MemoryResult();
        hit.setId("mem-1");
        hit.setScore(0.9);
        when(memoryManager.searchSimilarMemories(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(hit));
        when(memoryManager.deleteMemory(anyString(), anyString())).thenReturn(true);

        String result = tool.forgetMemory("偏好");

        verify(memoryManager).deleteMemory(eq("user-a"), eq("mem-1"));
        assertTrue(result.contains("\"deletedCount\": 1"), "实际: " + result);
    }

    // ===== 未认证时失败安全 =====

    @Test
    void forgetMemory_unauthenticated_isDenied_andTouchesNothing() {
        SecurityContextHolder.clearContext();

        String result = tool.forgetMemory("任意关键词");

        assertTrue(result.contains("\"success\": false"), "实际: " + result);
        assertTrue(result.contains("未认证"), "实际: " + result);
        verify(memoryManager, never()).searchSimilarMemories(anyString(), anyString(), anyInt());
        verify(memoryManager, never()).deleteMemory(anyString(), anyString());
    }

    @Test
    void forgetMemory_anonymousToken_isDenied() {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser",
                        AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        String result = tool.forgetMemory("任意关键词");

        assertTrue(result.contains("\"success\": false"), "实际: " + result);
        verify(memoryManager, never()).deleteMemory(anyString(), anyString());
    }

    /**
     * 用户真的叫 "anonymous" 时也应被当作未认证而拒绝 —— 与占位值冲突是已知取舍，
     * 此处显式锁定该行为，避免无意改变。
     */
    @Test
    void forgetMemory_userNamedAnonymous_isTreatedAsUnauthenticated() {
        authenticate("anonymous");

        String result = tool.forgetMemory("偏好");

        assertTrue(result.contains("\"success\": false"), "实际: " + result);
        verify(memoryManager, never()).deleteMemory(anyString(), anyString());
    }

    /**
     * {@code memory.require-authenticated=false} 时恢复改造前行为：未认证调用操作
     * {@code "anonymous"} 共享桶（仅单用户开发环境可接受）。
     */
    @Test
    void forgetMemory_requireAuthenticatedDisabled_fallsBackToAnonymousBucket() {
        memoryProperties.setRequireAuthenticated(false);
        SecurityContextHolder.clearContext();
        when(memoryManager.searchSimilarMemories(anyString(), anyString(), anyInt()))
                .thenReturn(List.of());

        tool.forgetMemory("偏好");

        verify(memoryManager).searchSimilarMemories(
                eq(CurrentUser.ANONYMOUS), eq("偏好"), eq(3));
    }

    @Test
    void requireAuthenticated_defaultIsTrue() {
        assertTrue(new MemoryProperties().isRequireAuthenticated(),
                "默认必须要求认证（安全默认值）");
    }

    // ===== 下游未命中时的既有行为 =====

    @Test
    void forgetMemory_noMatch_reportsZeroDeleted() {
        authenticate("user-a");
        when(memoryManager.searchSimilarMemories(anyString(), anyString(), anyInt()))
                .thenReturn(List.of());

        String result = tool.forgetMemory("不存在的关键词");

        assertTrue(result.contains("未找到匹配的记忆"), "实际: " + result);
        assertTrue(result.contains("\"deletedCount\": 0"), "实际: " + result);
        verify(memoryManager, never()).deleteMemory(anyString(), anyString());
    }
}
