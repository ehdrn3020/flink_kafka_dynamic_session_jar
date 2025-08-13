package com.main;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.kafka.clients.producer.ProducerRecord;


import java.nio.charset.StandardCharsets;

public class MobilePcSessionJob {

    // 환경에 맞게 바꿔주세요
    private static final String BOOTSTRAP_SERVERS = "127.0.0.1:9092";
    private static final String INPUT_TOPIC  = "log-before";
    private static final String OUTPUT_TOPIC = "log-after";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        // 1) Kafka Source: 값은 JSON 문자열
        KafkaSource<String> source = KafkaSource.<String>builder()
                .setBootstrapServers(BOOTSTRAP_SERVERS)
                .setTopics(INPUT_TOPIC)
                .setGroupId("flink-session-consumer")
                .setStartingOffsets(OffsetsInitializer.latest())
                .setValueOnlyDeserializer(new SimpleStringSchema())
                .build();

        DataStream<String> raw = env.fromSource(source, WatermarkStrategy.noWatermarks(), "kafka-source");

//        // 2) JSON -> Event
//        DataStream<Event> events = raw
//                .map(json -> MAPPER.readValue(json, Event.class))
//                .returns(Types.POJO(Event.class));
//
//        // 3) 세션키 생성: mobile -> m_<ip>_<uid>, pc -> p_<ip>_<uid>; 그 외 타입은 드롭
//        DataStream<EnrichedEvent> enriched = events
//                .filter(e -> e != null && e.uid != null && e.ip != null && e.access_type != null)
//                .map(e -> {
//                    String at = e.access_type.toLowerCase();
//                    if ("mobile".equals(at)) {
//                        return new EnrichedEvent(e.uid, e.access_type, e.ip, e.ts, "m_" + e.ip + "_" + e.uid);
//                    } else if ("pc".equals(at)) {
//                        return new EnrichedEvent(e.uid, e.access_type, e.ip, e.ts, "p_" + e.ip + "_" + e.uid);
//                    } else {
//                        return null; // 불필요 타입 제거
//                    }
//                })
//                .filter(x -> x != null)
//                .returns(Types.POJO(EnrichedEvent.class));
//
//        // 4) Kafka Sink: Key=session, Value=전체 JSON
//        KafkaSink<EnrichedEvent> sink = KafkaSink.<EnrichedEvent>builder()
//                .setBootstrapServers(BOOTSTRAP_SERVERS)
//                .setRecordSerializer(new KafkaRecordSerializationSchema<EnrichedEvent>() {
//                    @Override
//                    public ProducerRecord<byte[], byte[]> serialize(EnrichedEvent e, KafkaSinkContext ctx, Long ts) {
//                        try {
//                            byte[] key = e.session.getBytes(StandardCharsets.UTF_8);
//                            byte[] value = MAPPER.writeValueAsBytes(e);
//                            return new ProducerRecord<>(OUTPUT_TOPIC, key, value);
//                        } catch (Exception ex) {
//                            throw new RuntimeException(ex);
//                        }
//                    }
//                })
//                .build();

//        enriched.sinkTo(sink).name("kafka-sink");

        KafkaSink<String> sink = KafkaSink.<String>builder()
            .setBootstrapServers(BOOTSTRAP_SERVERS)
            .setRecordSerializer(new KafkaRecordSerializationSchema<String>() {
                @Override
                public ProducerRecord<byte[], byte[]> serialize(String value, KafkaSinkContext ctx, Long ts) {
                    // Key는 사용하지 않으므로 null, Value는 원문 문자열 그대로 전송
                    return new ProducerRecord<>(OUTPUT_TOPIC, null, value.getBytes(StandardCharsets.UTF_8));
                }
            })
            .build();

        raw.sinkTo(sink).name("kafka-sink (passthrough)");

        env.execute("Mobile/PC Session Key to log-after (simple)");
    }
}
