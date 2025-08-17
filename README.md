# flink_kafka_dynamic_session_jar
kafka topic data에 동적인 session 값 적용 flink jar module

### Flink Deploy 
```declarative
# ansible 설치
sudo python3 -m pip install --upgrade pip && \
sudo python3 -m pip install packaging &&
sudo python3 -m pip install ansible==2.9.27

# 설치 확인
ansible --version
```

### Jar File Build
```declarative
cd app
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

### 추후 진행
```declarative
- 병렬 수성
- HA 구성
- state RocksDB로 관리
- 방화벽 포트
# A (JobManager)
ufw allow proto tcp from 10.0.0.0/24 to any port 6123 //jobmanager.rpc.port
ufw allow proto tcp from 10.0.0.0/24 to any port 6124 //blob.server.port A <- B
ufw allow 8081/tcp   # 필요 시 외부 개방 범위 조정

# B (TaskManager)
ufw allow proto tcp from 10.0.0.0/24 to any port 6122 //taskmanager.rpc.port
ufw allow proto tcp from 10.0.0.0/24 to any port 50030:50050 //taskmanager.data.port

# C (TaskManager)
ufw allow proto tcp from 10.0.0.0/24 to any port 6122 //taskmanager.rpc.port
ufw allow proto tcp from 10.0.0.0/24 to any port 50030:50050 //taskmanager.data.port
```