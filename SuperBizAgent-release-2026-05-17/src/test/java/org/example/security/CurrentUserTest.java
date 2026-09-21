package org.example.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link CurrentUser} 单元测试（不依赖网络 / LLM / Spring 容器）
 * <p>
 * 重点覆盖身份读取的边界：已认证、匿名、无上下文，以及
 * <b>不同线程之间不得互相读到对方身份</b>（本次修复的核心诉求）。
 */
class CurrentUserTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        userId, "n/a", AuthorityUtils.createAuthorityList("ROLE_USER")));
    }

    private void authenticateAnonymous() {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser",
                        AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
    }

    // ===== 基本语义 =====

    @Test
    void getId_authenticatedUser_returnsUsername() {
        authenticate("sre-001");

        assertEquals("sre-001", CurrentUser.getId());
        assertTrue(CurrentUser.isAuthenticated());
    }

    @Test
    void getId_anonymousToken_returnsAnonymousPlaceholder() {
        authenticateAnonymous();

        assertEquals(CurrentUser.ANONYMOUS, CurrentUser.getId());
        assertFalse(CurrentUser.isAuthenticated());
    }

    @Test
    void getId_noAuthentication_returnsAnonymousPlaceholder() {
        SecurityContextHolder.clearContext();

        assertEquals(CurrentUser.ANONYMOUS, CurrentUser.getId());
        assertFalse(CurrentUser.isAuthenticated());
    }

    @Test
    void getId_unauthenticatedToken_returnsAnonymousPlaceholder() {
        // isAuthenticated()==false 的令牌（例如未通过认证的凭据）也应视为匿名
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("biz-002", "n/a"));

        assertEquals(CurrentUser.ANONYMOUS, CurrentUser.getId());
        assertFalse(CurrentUser.isAuthenticated());
    }

    @Test
    void getId_neverReturnsNull() {
        SecurityContextHolder.clearContext();
        assertNotNull(CurrentUser.getId());
        assertNotNull(CurrentUser.getId(), "未认证时也必须返回占位值而非 null");
    }

    // ===== getRequiredId =====

    @Test
    void getRequiredId_authenticated_returnsUsername() {
        authenticate("biz-002");

        assertEquals("biz-002", CurrentUser.getRequiredId());
    }

    @Test
    void getRequiredId_notAuthenticated_throws() {
        SecurityContextHolder.clearContext();

        assertThrows(IllegalStateException.class, CurrentUser::getRequiredId);
    }

    // ===== 线程隔离（修复的回归护栏） =====

    @Test
    void getId_isThreadIsolated_noCrossThreadLeak() throws Exception {
        authenticate("user-a");

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // 另一线程未设置任何身份，必须读到 anonymous，绝不能读到 user-a
            Future<String> other = pool.submit(CurrentUser::getId);
            assertEquals(CurrentUser.ANONYMOUS, other.get(5, TimeUnit.SECONDS),
                    "其他线程不得读到本线程的登录身份");

            // 另一线程设置自己的身份后，也不得影响本线程
            Future<String> otherAuthed = pool.submit(() -> {
                authenticate("user-b");
                return CurrentUser.getId();
            });
            assertEquals("user-b", otherAuthed.get(5, TimeUnit.SECONDS));

            assertEquals("user-a", CurrentUser.getId(), "本线程身份不得被其他线程覆盖");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void getId_afterContextCleared_fallsBackToAnonymous() {
        authenticate("sre-001");
        assertEquals("sre-001", CurrentUser.getId());

        SecurityContextHolder.clearContext();

        assertEquals(CurrentUser.ANONYMOUS, CurrentUser.getId(),
                "上下文清理后必须回退为匿名，不得残留上一个身份");
    }
}
