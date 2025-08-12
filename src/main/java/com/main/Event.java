package com.main;
import java.io.Serializable;

public class Event implements Serializable {
    public String uid;          // 사용자 ID
    public String access_type;  // "mobile" or "pc"
    public String ip;           // 클라이언트 IP
    public long ts;             // (선택) epoch millis

    public Event() {}
}