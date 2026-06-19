package com.nomad.client;

/**
 * Nomad API 调用异常。
 */
public class NomadException extends Exception {

    private final int httpCode;

    public NomadException(String message, int httpCode) {
        super(message);
        this.httpCode = httpCode;
    }

    public NomadException(String message, int httpCode, Throwable cause) {
        super(message, cause);
        this.httpCode = httpCode;
    }

    /**
     * @return HTTP 状态码，网络异常时为 -1
     */
    public int getHttpCode() {
        return httpCode;
    }
}
