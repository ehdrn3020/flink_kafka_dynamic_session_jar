package com.main;
import java.io.Serializable;

public class EnrichedEvent implements Serializable {
    public String uid;
    public String access_type;
    public String ip;
    public long ts;
    public String session;  // 새로 만든 세션 키

    public EnrichedEvent() {}

    public EnrichedEvent(String uid, String access_type, String ip, long ts, String session) {
        this.uid = uid;
        this.access_type = access_type;
        this.ip = ip;
        this.ts = ts;
        this.session = session;
    }
}