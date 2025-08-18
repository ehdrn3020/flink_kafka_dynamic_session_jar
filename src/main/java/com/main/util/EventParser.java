package com.main.util;

import com.main.model.Event;

import java.util.HashMap;
import java.util.Map;

public class EventParser {

    public static Event parse(String line) {
        if (line == null || line.isEmpty()) return null;

        try {
            // 1단계: \u0004 로 헤더 split
            String[] parts = line.split("\u0004");

            if (parts.length < 8) {
                return null; // 최소한의 필드가 없으면 skip
            }

            Event e = new Event();

            // 기본 필드 매핑
            e.log_name = parts[2]; // 3번째: log_name
            e.ts = Long.parseLong(parts[3]); // 4번째: timestamp (epoch second)
            e.ctype = parts[5];              // 6번째: access_system (PC / mobile / app 등)
            e.ip = parts[6];                 // 7번째: access_ip

            // 2단계: info 파싱 (마지막 필드 기준)
            if (parts.length >= 9) {
                String info = parts[8];
                Map<String, String> kvMap = parseInfo(info);

                // uuid가 존재하면 세팅
                if (kvMap.containsKey("uuid")) {
                    e.uuid = kvMap.get("uuid");
                }
            }

            return e;

        } catch (Exception ex) {
            System.err.println("Invalid record: " + line + " | error=" + ex.getMessage());
            return null;
        }
    }

    private static Map<String, String> parseInfo(String info) {
        Map<String, String> map = new HashMap<>();
        if (info == null) return map;

        // key=value 쌍 구분
        String[] fields = info.split("\u0006&\u0006");
        for (String f : fields) {
            String[] kv = f.split("\u0006=\\u0006", 2);
            if (kv.length == 2) {
                map.put(kv[0], kv[1]);
            }
        }
        return map;
    }
}
