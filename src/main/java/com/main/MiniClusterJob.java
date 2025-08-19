package com.main;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.common.eventtime.SerializableTimestampAssigner;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.state.StateTtlConfig;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.RestOptions;
import org.apache.flink.configuration.TaskManagerOptions;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.CheckpointConfig;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

import java.time.Duration;

public class MiniClusterJob {
    // ====== 샘플 모델 클래스 ======
    public static class Event {
        public String ip;
        public String uuid;
        public Long ts;
        public String ctype;
        public String log_name;

        public Event() {}
    }

    public static class EnrichedEvent {
        public String session_id;
        public Long start_ts;
        public Long last_ts;
        public Long seq;

        public EnrichedEvent() {}

        static EnrichedEvent of(Event e, SessionCtx ctx) {
            EnrichedEvent ev = new EnrichedEvent();
            ev.session_id = ctx.sessionId;
            ev.start_ts = ctx.startTs;
            ev.last_ts = ctx.lastEventTs;
            ev.seq = ctx.seq;
            return ev;
        }

        @Override
        public String toString() {
            return "EnrichedEvent{session=" + session_id +
                    ", seq=" + seq +
                    ", start=" + start_ts +
                    ", last=" + last_ts + "}";
        }
    }

    public static class SessionCtx {
        public String sessionId;
        public long startTs;
        public long lastEventTs;
        public long expireAt;
        public long seq;
        public boolean cinOpened;

        public SessionCtx() {}
    }

    private static final long ONE_MINUTE_MS = 60_000L;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // ---------- 세션 처리 ----------
    public static class SessionKeyProcess
            extends KeyedProcessFunction<String, Event, EnrichedEvent> {

        private transient ValueState<SessionCtx> sessionState;

        @Override
        public void open(Configuration parameters) {
            ValueStateDescriptor<SessionCtx> desc =
                    new ValueStateDescriptor<>("session-state", Types.POJO(SessionCtx.class));

            StateTtlConfig ttl = StateTtlConfig.newBuilder(Time.hours(3))
                    .setUpdateType(StateTtlConfig.UpdateType.OnCreateAndWrite)
                    .cleanupFullSnapshot()
                    .build();
            desc.enableTimeToLive(ttl);

            sessionState = getRuntimeContext().getState(desc);
        }

        @Override
        public void processElement(Event e, Context ctx, Collector<EnrichedEvent> out) throws Exception {
            if (e == null || e.ip == null || e.ts == null || e.log_name == null) return;

            SessionCtx s = sessionState.value();

            // 신규 세션 or 만료 후 새로 시작
            if (s == null || e.ts > s.expireAt) {
                s = new SessionCtx();
                String baseKey = "PC".equalsIgnoreCase(e.ctype) ? e.ip : e.uuid;
                s.sessionId = baseKey + "_" + e.ts;
                s.startTs   = e.ts;
                s.seq       = 1L;
                s.lastEventTs = e.ts;
                s.expireAt = e.ts;
                s.cinOpened = false;
            } else {
                s.lastEventTs = e.ts;
                s.seq++;
            }

            // 만료 시간 계산
            long gapMs;
            switch (e.log_name) {
                case "CIN":
                    gapMs = 180 * ONE_MINUTE_MS; s.cinOpened = true; break;
                case "COUT":
                    gapMs = 30 * ONE_MINUTE_MS; break;
                default:
                    gapMs = s.cinOpened ? 180 * ONE_MINUTE_MS : 30 * ONE_MINUTE_MS;
            }

            s.expireAt = e.ts + gapMs;
            sessionState.update(s);

            ctx.timerService().registerEventTimeTimer(s.expireAt);
            out.collect(EnrichedEvent.of(e, s));
        }

        @Override
        public void onTimer(long timestamp, OnTimerContext ctx, Collector<EnrichedEvent> out) throws Exception {
            SessionCtx s = sessionState.value();
            if (s != null && timestamp == s.expireAt) {
                sessionState.clear();
            }
        }
    }

    // ---------- MiniCluster 설정 ----------
    static final class LocalMiniCluster {
        private LocalMiniCluster() {}
        static StreamExecutionEnvironment create(int port, int slots) {
            Configuration conf = new Configuration();
            conf.setString(RestOptions.BIND_ADDRESS, "localhost");
            conf.setString(RestOptions.ADDRESS, "localhost");
            conf.setInteger(RestOptions.PORT, port);
            conf.setInteger(TaskManagerOptions.NUM_TASK_SLOTS, slots);
            return StreamExecutionEnvironment.createLocalEnvironmentWithWebUI(conf);
        }
    }

    // ---------- main ----------
    public static void main(String[] args) throws Exception {
        final StreamExecutionEnvironment env = LocalMiniCluster.create(8081, 2);

        env.enableCheckpointing(30_000);
        env.getCheckpointConfig().setMinPauseBetweenCheckpoints(10_000);
        env.getCheckpointConfig().setCheckpointTimeout(5 * 60_000);
        env.getCheckpointConfig().setExternalizedCheckpointCleanup(
                CheckpointConfig.ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION);

        WatermarkStrategy<Event> wm = WatermarkStrategy
                .<Event>forBoundedOutOfOrderness(Duration.ofSeconds(5))
                .withTimestampAssigner((SerializableTimestampAssigner<Event>)
                        (e, ts) -> e.ts == null ? ts : e.ts);

        // ✅ Text Source (샘플 JSON 문자열 3개)
        DataStream<String> raw = env.fromElements(
                "{\"ip\":\"1.1.1.1\",\"uuid\":\"u1\",\"ts\":1692440000000,\"ctype\":\"PC\",\"log_name\":\"CIN\"}",
                "{\"ip\":\"1.1.1.1\",\"uuid\":\"u1\",\"ts\":1692440060000,\"ctype\":\"PC\",\"log_name\":\"CLICK\"}",
                "{\"ip\":\"1.1.1.1\",\"uuid\":\"u1\",\"ts\":1692440120000,\"ctype\":\"PC\",\"log_name\":\"COUT\"}"
        );

        // JSON → Event
        DataStream<Event> events = raw
                .map(json -> {
                    try {
                        return MAPPER.readValue(json, Event.class);
                    } catch (Exception ex) {
                        System.err.println("Invalid JSON: " + json);
                        return null;
                    }
                })
                .filter(e -> e != null)
                .returns(Types.POJO(Event.class))
                .assignTimestampsAndWatermarks(wm);

        // 세션 처리
        DataStream<EnrichedEvent> enriched = events
                .keyBy(e -> e.ip)
                .process(new SessionKeyProcess())
                .name("session-key-process");

        // 결과 출력
        enriched.print().name("debug-print");

        System.out.println("MiniCluster Web UI: http://localhost:8081");
        env.execute("Session Example with Text Source");
    }
}
