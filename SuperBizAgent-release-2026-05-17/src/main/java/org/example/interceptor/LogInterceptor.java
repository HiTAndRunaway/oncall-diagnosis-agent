package org.example.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

@Component
public class LogInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(LogInterceptor.class);
    private static final String START_TIME_ATTR = "logInterceptor_startTime";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        long startTime = System.currentTimeMillis();
        request.setAttribute(START_TIME_ATTR, startTime);

        log.info("═══ 请求开始 ═══");
        log.info("URL    : {} {}", request.getMethod(), request.getRequestURI());
        if (request.getQueryString() != null) {
            log.info("Query  : {}", request.getQueryString());
        }
        log.info("Client : {}", getClientIp(request));
        log.info("Handler: {}", handler);

        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
                           @Nullable ModelAndView modelAndView) throws Exception {
        Long startTime = (Long) request.getAttribute(START_TIME_ATTR);
        long duration = startTime != null ? System.currentTimeMillis() - startTime : -1;

        log.info("═══ 请求完成 ═══");
        log.info("URL     : {} {}", request.getMethod(), request.getRequestURI());
        log.info("Status  : {}", response.getStatus());
        log.info("Duration: {} ms", duration);
        if (modelAndView != null) {
            log.info("View    : {}", modelAndView.getViewName());
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                @Nullable Exception ex) throws Exception {
        if (ex != null) {
            Long startTime = (Long) request.getAttribute(START_TIME_ATTR);
            long duration = startTime != null ? System.currentTimeMillis() - startTime : -1;
            log.error("═══ 请求异常 ═══");
            log.error("URL     : {} {}", request.getMethod(), request.getRequestURI());
            log.error("Duration: {} ms", duration);
            log.error("Error   : {}", ex.getMessage(), ex);
        }

        request.removeAttribute(START_TIME_ATTR);
    }

    /**
     * 获取客户端真实 IP（考虑代理转发）
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 多级代理取第一个 IP
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
