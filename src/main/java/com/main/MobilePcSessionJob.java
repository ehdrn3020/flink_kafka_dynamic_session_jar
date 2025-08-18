package com.main;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.main.model.Event;
import com.main.model.EnrichedEvent;
import com.main.model.SessionCtx;
import com.main.util.EventParser;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class MobilePcSessionJob {

    // 환경값
    private static final String BOOTSTRAP_SERVERS = "localhost:9092";
    private static final String INPUT_TOPIC  = "log-before";
    private static final String OUTPUT_TOPIC = "log-after";

    // 1분 = 60,000ms
    private static final long ONE_MINUTE_MS = 60_000L;

    // log_name 별 세션 연장 시간
    private static final Map<String, Integer> SESSION_MINUTES_BY_LOGNAME;
    static {
        Map<String, Integer> tmp = new HashMap<>();
        tmp.put("CIN", 180);
        tmp.put("COUT", 30);
        tmp.put("CLICK", 30);
        tmp.put("VISIT", 30);
        SESSION_MINUTES_BY_LOGNAME = Collections.unmodifiableMap(tmp);
    }

    // JSON 파서 (불필드 무시)
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // -------------------- 세션 처리 --------------------
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
                String baseKey;
                if ("PC".equalsIgnoreCase(e.ctype)) {
                    baseKey = e.ip;
                } else {
                    baseKey = e.uuid;
                }
                s.sessionId = baseKey + "_" + e.ts;
                s.startTs   = e.ts;
                s.seq       = 1L;
                s.lastEventTs = e.ts;
                s.expireAt = e.ts; // 일단 초기값
                s.cinOpened = false;
            } else {
                s.lastEventTs = e.ts;
                s.seq++;
            }

            // 만료 시간 계산
            long gapMs;
            switch (e.log_name) {
                case "CIN":
                    gapMs = 180 * ONE_MINUTE_MS;
                    s.cinOpened = true;
                    break;
                case "COUT":
                    gapMs = 30 * ONE_MINUTE_MS;
                    break;
                default:
                    gapMs = s.cinOpened ? 180 * ONE_MINUTE_MS : 30 * ONE_MINUTE_MS;
            }

            s.expireAt = e.ts + gapMs;
            sessionState.update(s);

            // 만료 타이머 등록
            ctx.timerService().registerEventTimeTimer(s.expireAt);

            // 즉시 방출
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

    // -------------------- 직렬화 스키마 --------------------
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

    // -------------------- main --------------------
    public static void main(String[] args) throws Exception {
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        // 체크포인트
        env.enableCheckpointing(30_000);
        env.getCheckpointConfig().setMinPauseBetweenCheckpoints(10_000);
        env.getCheckpointConfig().setCheckpointTimeout(5 * 60_000);
        env.getCheckpointConfig().setExternalizedCheckpointCleanup(
                CheckpointConfig.ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION);

        // 워터마크
        WatermarkStrategy<Event> wm = WatermarkStrategy
                .<Event>forBoundedOutOfOrderness(Duration.ofSeconds(5))
                .withTimestampAssigner((SerializableTimestampAssigner<Event>)
                        (e, ts) -> e.ts == null ? ts : e.ts);

        // Kafka Source
        KafkaSource<String> source = KafkaSource.<String>builder()
                .setBootstrapServers(BOOTSTRAP_SERVERS)
                .setTopics(INPUT_TOPIC)
                .setGroupId("flink-session-consumer")
                .setStartingOffsets(OffsetsInitializer.latest())
                .setValueOnlyDeserializer(new org.apache.flink.api.common.serialization.SimpleStringSchema())
                .build();

        // Event Parser
        DataStream<Event> events = env
                .fromSource(source, WatermarkStrategy.noWatermarks(), "kafka-source")
                .map(EventParser::parse)    // 문자열 → Event
                .filter(e -> e != null)     // null 제거
                .returns(Types.POJO(Event.class))
                .assignTimestampsAndWatermarks(wm);

//        // Json Parser
//        DataStream<Event> events = env
//                .fromSource(source, WatermarkStrategy.noWatermarks(), "kafka-source")
//                .map(json -> {
//                    try {
//                        return MAPPER.readValue(json, Event.class);
//                    } catch (Exception ex) {
//                        System.err.println("Invalid JSON: " + json);
//                        return null;
//                    }
//                })
//                .returns(Types.POJO(Event.class))
//                .assignTimestampsAndWatermarks(wm);

        // 세션 처리
        DataStream<EnrichedEvent> enriched = events
                .keyBy(e -> e.ip)
                .process(new SessionKeyProcess())
                .name("session-key-process");

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

        env.execute("IP/UUID-based Session with CIN rules - Flink 1.18");
    }
}
