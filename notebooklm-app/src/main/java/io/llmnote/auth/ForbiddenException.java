package io.llmnote.auth;

/** 归属校验失败;由 ApiExceptionHandler 统一转 404,不暴露资源是否存在。 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
