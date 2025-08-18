package com.main.model;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 세션 정보가 추가된 확장 이벤트
 */
public class EnrichedEvent {
    public String log_name;
    public String ip;
    public Long ts;
    public String ts_str;  // 한국시간 yyyy-MM-dd'T'HH:mm:ss.SSSZ

    public String session_id;
    public long session_start_ts;
    public long session_last_ts;
    public long session_expire_at;
    public long seq_in_session;

    public EnrichedEvent() {}

    public static EnrichedEvent of(Event e, SessionCtx s) {
        EnrichedEvent out = new EnrichedEvent();
        out.log_name = e.log_name;
        out.ip = e.ip;
        out.ts = e.ts;

        // timestamp 변환
        Instant instant = Instant.ofEpochSecond(e.ts);
        ZonedDateTime zdt = instant.atZone(ZoneId.of("Asia/Seoul"));
        out.ts_str = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ").format(zdt);

        out.session_id = s.sessionId;
        out.session_start_ts = s.startTs;
        out.session_last_ts = s.lastEventTs;
        out.session_expire_at = s.expireAt;
        out.seq_in_session = s.seq;
        return out;
    }

    @Override
    public String toString() {
        return "EnrichedEvent{" +
                "log_name='" + log_name + '\'' +
                ", ip='" + ip + '\'' +
                ", ts=" + ts +
                ", ts_str='" + ts_str + '\'' +
                ", session_id='" + session_id + '\'' +
                ", session_start_ts=" + session_start_ts +
                ", session_last_ts=" + session_last_ts +
                ", session_expire_at=" + session_expire_at +
                ", seq_in_session=" + seq_in_session +
                '}';
    }
}
