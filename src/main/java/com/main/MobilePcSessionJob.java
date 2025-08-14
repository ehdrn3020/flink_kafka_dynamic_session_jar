package com.main;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.common.eventtime.SerializableTimestampAssigner;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.serialization.SerializationSchema;
import org.apache.flink.api.common.state.StateTtlConfig;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.CheckpointConfig;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Collections;

public class MobilePcSessionJob {

    // 환경값
    private static final String BOOTSTRAP_SERVERS = "localhost:9092";
    private static final String INPUT_TOPIC  = "log-before";
    private static final String OUTPUT_TOPIC = "log-after";

    // 처리 대상 log_name
    private static final Set<String> ALLOWED_LOG_NAMES;
    static {
        Set<String> tmp = new HashSet<>();
        tmp.add("CIN");
        tmp.add("COUT");
        tmp.add("CLICK");
        tmp.add("VISIT");
        ALLOWED_LOG_NAMES = Collections.unmodifiableSet(tmp);
    }

    // log_name 별 세션 연장 분수 (재배포로 변경 반영)
    private static final Map<String, Integer> SESSION_MINUTES_BY_LOGNAME;
    static {
        Map<String, Integer> tmp = new HashMap<>();
        tmp.put("CIN",   180);
        tmp.put("COUT",   30);
        tmp.put("CLICK",  30);
        tmp.put("VISIT",  30);
        SESSION_MINUTES_BY_LOGNAME = Collections.unmodifiableMap(tmp);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // 입력 이벤트 POJO (필요 필드만)
    public static class Event {
        public String log_name;
        public String ip;
        public Long   ts;

        // (있다면 다른 필드도 추가 가능: user_id, access_system 등)
        public Event() {}
    }

    // 세션 상태
    public static class SessionCtx {
        public String sessionId;    // ip_timestamp
        public long   startTs;
        public long   lastEventTs;
        public long   expireAt;
        public long   seq;

        public SessionCtx() {}
    }

    // === 출력 이벤트(Enriched) ===
    public static class EnrichedEvent {
        public String log_name;
        public String ip;
        public Long   ts;

        public String session_id;
        public long   session_start_ts;
        public long   session_last_ts;
        public long   session_expire_at;
        public long   seq_in_session;

        public EnrichedEvent() {}

        public static EnrichedEvent of(Event e, SessionCtx s) {
            EnrichedEvent out = new EnrichedEvent();
            out.log_name = e.log_name;
            out.ip = e.ip;
            out.ts = e.ts;
            out.session_id = s.sessionId;
            out.session_start_ts = s.startTs;
            out.session_last_ts = s.lastEventTs;
            out.session_expire_at = s.expireAt;
            out.seq_in_session = s.seq;
            return out;
        }
    }

    // === 핵심: 세션 처리 ===
    public static class SessionKeyProcess
            extends KeyedProcessFunction<String, Event, EnrichedEvent> {

        private transient ValueState<SessionCtx> sessionState;

        @Override
        public void open(Configuration parameters) {
            ValueStateDescriptor<SessionCtx> desc =
                    new ValueStateDescriptor<>("session-state", Types.POJO(SessionCtx.class));

            // TTL(보조 안전장치). 타이머로도 지우지만 오래된 상태 자동정리.
            StateTtlConfig ttl = StateTtlConfig.newBuilder(Time.hours(4))
                    .setUpdateType(StateTtlConfig.UpdateType.OnCreateAndWrite)
                    .cleanupFullSnapshot()
                    .build();
            desc.enableTimeToLive(ttl);

            sessionState = getRuntimeContext().getState(desc);
        }

        @Override
        public void processElement(Event e, Context ctx, Collector<EnrichedEvent> out) throws Exception {
            if (e == null || e.ip == null || e.ts == null || e.log_name == null) return;
            if (!ALLOWED_LOG_NAMES.contains(e.log_name)) return;

            // log_name → 연장 분수
            int minutes = SESSION_MINUTES_BY_LOGNAME.getOrDefault(e.log_name, 30);
            long gapMs  = minutes * 60_000L;

            SessionCtx s = sessionState.value();

            // 신규 or 만료 이후면 새 세션 시작
            if (s == null || e.ts > s.expireAt) {
                s = new SessionCtx();
                s.sessionId = e.ip + "_" + e.ts; // ip_timestamp
                s.startTs   = e.ts;
                s.seq       = 0L;
            }

            // 갱신/연장
            s.lastEventTs = e.ts;
            s.expireAt    = e.ts + gapMs;
            s.seq++;

            sessionState.update(s);

            // 만료 타이머 등록 (이벤트 시간)
            ctx.timerService().registerEventTimeTimer(s.expireAt);

            // 즉시 Enriched 이벤트 방출
            out.collect(EnrichedEvent.of(e, s));
        }

        @Override
        public void onTimer(long timestamp, OnTimerContext ctx, Collector<EnrichedEvent> out) throws Exception {
            SessionCtx s = sessionState.value();
            if (s != null && timestamp == s.expireAt) {
                // (선택) 세션 요약을 내보내고 싶다면 여기서 emit 가능
                sessionState.clear();
            }
        }
    }

    // === JSON 직렬화 스키마 ===
    public static class JsonValueSchema<T> implements SerializationSchema<T> {
        @Override
        public byte[] serialize(T element) {
            try {
                return MAPPER.writeValueAsBytes(element);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static class SessionKeySchema implements SerializationSchema<EnrichedEvent> {
        @Override
        public byte[] serialize(EnrichedEvent e) {
            return e.session_id.getBytes(StandardCharsets.UTF_8);
        }
    }

    public static void main(String[] args) throws Exception {
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        // 체크포인트(권장)
        env.enableCheckpointing(30_000);
        env.getCheckpointConfig().setMinPauseBetweenCheckpoints(10_000);
        env.getCheckpointConfig().setCheckpointTimeout(5 * 60_000);
        env.getCheckpointConfig().setExternalizedCheckpointCleanup(
                CheckpointConfig.ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION);

        // 이벤트 시간 워터마크 (지연 5초 예시)
        WatermarkStrategy<Event> wm = WatermarkStrategy
                .<Event>forBoundedOutOfOrderness(Duration.ofSeconds(5))
                .withTimestampAssigner((SerializableTimestampAssigner<Event>)
                        (e, ts) -> e.ts == null ? ts : e.ts);

        // Kafka Source (값=JSON 문자열)
        KafkaSource<String> source = KafkaSource.<String>builder()
                .setBootstrapServers(BOOTSTRAP_SERVERS)
                .setTopics(INPUT_TOPIC)
                .setGroupId("flink-session-consumer")
                .setStartingOffsets(OffsetsInitializer.latest())
                .setValueOnlyDeserializer(new org.apache.flink.api.common.serialization.SimpleStringSchema())
                .build();

        DataStream<Event> events = env
                .fromSource(source, WatermarkStrategy.noWatermarks(), "kafka-source")
                .map(json -> MAPPER.readValue(json, Event.class))
                .returns(Types.POJO(Event.class))
                .assignTimestampsAndWatermarks(wm);

        // keyBy(IP) → 세션 처리
        DataStream<EnrichedEvent> enriched = events
                .keyBy(e -> e.ip)
                .process(new SessionKeyProcess())
                .name("session-key-process");

        // Kafka Sink (Flink 1.15.4 시그니처: serialize(T, KafkaSinkContext, Long)을 내부에서 처리해줌)
        KafkaRecordSerializationSchema<EnrichedEvent> recordSchema =
                KafkaRecordSerializationSchema.<EnrichedEvent>builder()
                        .setTopic(OUTPUT_TOPIC)
                        .setKeySerializationSchema(new SessionKeySchema())
                        .setValueSerializationSchema(new JsonValueSchema<>())
                        .build();

        KafkaSink<EnrichedEvent> sink = KafkaSink.<EnrichedEvent>builder()
                .setBootstrapServers(BOOTSTRAP_SERVERS)
                .setRecordSerializer(recordSchema)
                .build();

        enriched.sinkTo(sink).name("kafka-sink");
        enriched.print().name("debug-print");

        env.execute("IP-based Session (CIN=180m, others=30m) - Flink 1.15.4");
    }
}
