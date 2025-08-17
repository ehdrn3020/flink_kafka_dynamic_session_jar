# flink_kafka_dynamic_session_jar
kafka topic data에 동적인 session 값 적용 flink jar module

### Jar File Build
```declarative
mvn -U -DskipTests package
~/test/flink-1.15.4/bin/flink run -c com.main.MobilePcSessionJob target/flink_kafka_dynamic_session_jar-1.0.jar
```

### Simple Test
```declarative
# consumer console 실행
/rnd/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic log-after --group flink-session-consumer

# producer console 실행 
/rnd/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic log-before

# Date input
{"uid":"u21","access_type":"mobile","log_name":"CIN","in_time":"100","out_time":"","ip":"192.168.0.5","ts":1734144000000}
{"uid":"u21","access_type":"mobile","log_name":"CLICK","in_time":"150","out_time":"","ip":"192.168.0.5","ts":1734144000500}
{"uid":"u21","access_type":"mobile","log_name":"COUT","in_time":"150","out_time":"","ip":"192.168.0.5","ts":1734144001500}
{"uid":"u22","access_type":"mobile","log_name":"CIN","in_time":"100","out_time":"","ip":"192.168.0.1","ts":1734144000000}
```