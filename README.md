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
~/test/flink-1.18.1/bin/flink run -c com.main.MobilePcSessionJob target/flink_kafka_dynamic_session_jar-1.0.jar
```

### Simple Test
```declarative
# topic 생성 ( partition 12 )
# consumer console 실행
/rnd/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic log-after --group flink-session-consumer

# producer console 실행 
/rnd/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic log-before

# Date input
{"uid":"u21","access_type":"mobile","log_name":"CIN","in_time":"100","out_time":"","ip":"192.168.0.5","ts":1734144000000}
{"uid":"u21","access_type":"mobile","log_name":"CLICK","in_time":"150","out_time":"","ip":"192.168.0.5","ts":1734144000500}
{"uid":"k2","access_type":"mobile","log_name":"COUT","in_time":"105","out_time":"","ip":"192.168.0.1","ts":1734144000000}
{"uid":"u22","access_type":"mobile","log_name":"CIN","in_time":"100","out_time":"","ip":"192.168.0.1","ts":1734144000000}
```

### Cluster Test
### ssh
```declarative
export FLINK_SSH_OPTS='-l ec2-user -i ~/.ssh/keypair.pem -o IdentitiesOnly=yes -o StrictHostKeyChecking=no'
```

### JM 서버에서만 설정 파일
#### conf/masters 
```declarative
${master server IP or Hostname}:8081
```

#### conf/workers
```declarative
${Worker server 1 IP or Hostname}
${Worker server 2 IP or Hostname}
${Worker server 3 IP or Hostname}
```

#### conf/flink-conf.yaml
```declarative
# === JM 위치 ===
jobmanager.rpc.address: ${Master server IP or Hostname}
jobmanager.rpc.port: 6123

# === REST/UI ===
rest.bind-address: 0.0.0.0               # 수신 인터페이스
rest.address: ${server IP or Hostname}   # 광고 주소(클러스터에서 접근할 주소)
rest.port: 8081

# === 바인딩 ===
jobmanager.bind-host: 0.0.0.0
taskmanager.bind-host: 0.0.0.0     # 공통(템플릿으로 배포)

# === 리소스/슬롯 ===
jobmanager.memory.process.size: 1600m
taskmanager.numberOfTaskSlots: 2
parallelism.default: 4

# === 장애복구/체크포인트(옵션) ===
# state.checkpoints.dir: s3://... 또는 hdfs://...
# state.checkpoints.num-retained: 3
# state.backend.type: hashmap   # 또는 rocksdb
# jobmanager.execution.failover-strategy: region

# === HA(옵션) ===
# high-availability: zookeeper
# high-availability.zookeeper.quorum: zk01:2181,zk02:2181,zk03:2181
# high-availability.storageDir: hdfs://nn:8020/flink/ha
```


### TM 서버에서만 설정 파일

#### conf/flink-conf.yaml
```declarative
# === JM 위치(동일) ===
jobmanager.rpc.address: ${Master server IP or Hostname}
jobmanager.rpc.port: 6123

# === REST(UI는 JM에 있지만, 바인딩은 무해) ===
rest.bind-address: 0.0.0.0
rest.address: ${server IP or Hostname}   # TM 자신의 광고 주소여도 무해(없어도 됨)

# === 바인딩 ===
jobmanager.bind-host: 0.0.0.0
taskmanager.bind-host: 0.0.0.0

# === [중요] TM 자기 광고 주소(노드별 고유) ===
taskmanager.host: ${server IP or Hostname}

# === 리소스/슬롯 ===
taskmanager.memory.process.size: 1728m
taskmanager.numberOfTaskSlots: 2
parallelism.default: 4

# === 장애복구/체크포인트(옵션) ===
# state.checkpoints.dir: s3://... 또는 hdfs://...
# state.backend.type: hashmap
# jobmanager.execution.failover-strategy: region
```
```declarative
# ./flink/flink-1.18.1/bin/start-cluster.sh

# ./flink/flink-1.18.1/bin/taskmanager.sh start
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