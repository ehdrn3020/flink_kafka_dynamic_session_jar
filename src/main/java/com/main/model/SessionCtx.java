package com.main.model;

/**
 * 세션 상태 (Flink ValueState에 저장)
 */
public class SessionCtx {
    public String sessionId;
    public long startTs;
    public long lastEventTs;
    public long expireAt;
    public long seq;
    public boolean cinOpened; // CIN 발생 여부

    public SessionCtx() {}

    @Override
    public String toString() {
        return "SessionCtx{" +
                "sessionId='" + sessionId + '\'' +
                ", startTs=" + startTs +
                ", lastEventTs=" + lastEventTs +
                ", expireAt=" + expireAt +
                ", seq=" + seq +
                ", cinOpened=" + cinOpened +
                '}';
    }
}
