# flink_kafka_dynamic_session_jar
kafka topic data에 동적인 session 값 적용 flink jar module

```declarative
mvn -U -DskipTests package
~/test/flink-1.15.4/bin/flink run -c com.main.MobileActionSessionJob target/flink_kafka_dynamic_session_jar-1.0.jar
```