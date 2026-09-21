package org.example.security;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前登录用户身份读取工具
 * <p>
 * 统一从 Spring Security 上下文获取用户 ID，供控制器与 Agent 工具共用。
 * <p>
 * <b>为什么不用静态 ThreadLocal / 成员字段缓存身份：</b>
 * Agent 的工具调用可能与请求线程分离（并行工具调用、线程池、@Async），
 * 手工维护的线程绑定状态在那种场景下会读到<b>其他用户</b>的身份，
 * 造成跨用户数据泄露。Spring Security 的 {@code SecurityContextHolder} 由框架
 * 负责写入与清理（{@code SecurityContextHolderFilter}），并可经
 * {@code DelegatingSecurityContextExecutor} 显式传播到异步线程，语义更可靠。
 * <p>
 * <b>使用约束：必须在认证上下文有效的线程内调用。</b>
 * 若将来 Agent 改为在独立线程执行工具调用，需在该线程上传播 SecurityContext
 * （如使用 {@code DelegatingSecurityContextExecutor}），否则本类会返回
 * {@link #ANONYMOUS}，工具将以匿名身份被拒，而不是读到别人的身份 —— 失败安全。
 */
public final class CurrentUser {

    /** 未认证时的占位身份（与改造前各控制器的行为保持一致） */
    public static final String ANONYMOUS = "anonymous";

    private CurrentUser() {
        // 工具类禁止实例化
    }

    /**
     * 获取当前用户 ID
     *
     * @return 已认证返回用户名；未认证或匿名返回 {@link #ANONYMOUS}；从不为 null
     */
    public static String getId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            return auth.getName();
        }
        return ANONYMOUS;
    }

    /**
     * 判断当前是否已认证为某个真实用户
     *
     * @return true 表示存在非匿名的已认证身份
     */
    public static boolean isAuthenticated() {
        return !ANONYMOUS.equals(getId());
    }

    /**
     * 获取当前用户 ID，未认证时抛出异常
     * <p>
     * 用于不能以匿名身份继续执行的场景，避免匿名占位值被当作真实用户 ID 使用。
     *
     * @return 已认证的用户名
     * @throws IllegalStateException 当前没有已认证的真实用户
     */
    public static String getRequiredId() {
        if (!isAuthenticated()) {
            throw new IllegalStateException("当前没有已认证的用户身份");
        }
        return getId();
    }
}
