package io.llmnote.log;

import io.llmnote.auth.Principal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 访问日志拦截器:GET 也记;OPTIONS/HEAD 跳过(基础设施);
 * /api/auth/* 跳过(由 AuthController 显式记,避免重复)。
 */
@ExtendWith(MockitoExtension.class)
class AccessLogInterceptorTest {

    @Mock AccessLogService accessLogService;
    @Mock HttpServletRequest req;
    @Mock HttpServletResponse resp;

    AccessLogInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new AccessLogInterceptor(accessLogService);
    }

    @Test
    void get_isLogged() {
        when(req.getMethod()).thenReturn("GET");
        when(req.getRequestURI()).thenReturn("/api/forum/posts/1");
        when(resp.getStatus()).thenReturn(200);

        interceptor.afterCompletion(req, resp, null, null);

        verify(accessLogService).logRequest(req, null, "GET_POSTS", 200);
    }

    @Test
    void post_isLogged() {
        when(req.getMethod()).thenReturn("POST");
        when(req.getRequestURI()).thenReturn("/api/notes");
        when(resp.getStatus()).thenReturn(201);

        interceptor.afterCompletion(req, resp, null, null);

        verify(accessLogService).logRequest(req, null, "POST_NOTES", 201);
    }

    @Test
    void putAndDeleteAndPatch_areLogged() {
        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        for (String method : new String[]{"PUT", "DELETE", "PATCH"}) {
            when(req.getMethod()).thenReturn(method);
            when(req.getRequestURI()).thenReturn("/api/notes/42");
            when(resp.getStatus()).thenReturn(200);

            interceptor.afterCompletion(req, resp, null, null);
        }

        verify(accessLogService, org.mockito.Mockito.times(3)).logRequest(
                org.mockito.ArgumentMatchers.eq(req),
                org.mockito.ArgumentMatchers.isNull(),
                action.capture(),
                org.mockito.ArgumentMatchers.eq(200));
        // 3 个 action 各自以 method 起头
        assertThat(action.getAllValues()).containsExactly("PUT_NOTES", "DELETE_NOTES", "PATCH_NOTES");
    }

    @Test
    void options_andHead_areSkipped() {
        for (String method : new String[]{"OPTIONS", "HEAD", "TRACE"}) {
            when(req.getMethod()).thenReturn(method);
            interceptor.afterCompletion(req, resp, null, null);
        }
        verify(accessLogService, never()).logRequest(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void authPath_isSkipped() {
        for (String uri : new String[]{"/api/auth/login", "/api/auth/register"}) {
            when(req.getMethod()).thenReturn("POST");
            when(req.getRequestURI()).thenReturn(uri);
        }
        // 调用 2 次,2 次都应该跳过
        interceptor.afterCompletion(req, resp, null, null);
        interceptor.afterCompletion(req, resp, null, null);

        verify(accessLogService, never()).logRequest(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void get_withTrailingId_extractsResourceName() {
        when(req.getMethod()).thenReturn("GET");
        when(req.getRequestURI()).thenReturn("/api/forum/posts/313");
        when(resp.getStatus()).thenReturn(200);

        interceptor.afterCompletion(req, resp, null, null);

        // 跳过数字段,取 POSTS
        verify(accessLogService).logRequest(req, null, "GET_POSTS", 200);
    }

    @Test
    void get_passesPrincipalWhenHolderHasOne() throws Exception {
        Principal p = Principal.ofUser(9L);
        io.llmnote.auth.CurrentPrincipalHolder.set(p);
        try {
            when(req.getMethod()).thenReturn("GET");
            when(req.getRequestURI()).thenReturn("/api/notes");
            when(resp.getStatus()).thenReturn(200);

            interceptor.afterCompletion(req, resp, null, null);

            verify(accessLogService).logRequest(req, p, "GET_NOTES", 200);
        } finally {
            io.llmnote.auth.CurrentPrincipalHolder.clear();
        }
    }
}
