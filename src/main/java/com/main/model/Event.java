package com.main.model;

/**
 * 원본 이벤트 데이터 (Kafka에서 파싱된 후 사용)
 */
public class Event {
    public String log_name;
    public String ip;
    public Long ts;       // epoch second
    public String uuid;
    public String system_type;  // PC / mobile / app 등
    public String uid;

    public Event() {}

    @Override
    public String toString() {
        return "Event{" +
            "uid='" + uid + '\'' +
            "log_name='" + log_name + '\'' +
            ", ip='" + ip + '\'' +
            ", ts=" + ts +
            ", uuid='" + uuid + '\'' +
            ", system_type='" + system_type + '\'' +
        '}';
    }
}
