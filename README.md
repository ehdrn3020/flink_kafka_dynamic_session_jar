# flink_kafka_dynamic_session_jar
kafka topic data에 동적인 session 값 적용 flink jar module

### 개요
- Kafka에서 수집한 이벤트 로그(log-before)를 읽어 
사용자 세션을 생성 및 관리
- enriched 이벤트를 다시 Kafka(log-after)에 적재하는 작업을 수행
- 세션 타임아웃 로직은 이벤트 유형에 따라 달라 짐 
- 세션 단위 집계를 가능

### 동작 흐름
Kafka(log-before) --> Flink(SessionKeyProcess) --> Kafka(log-after)

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

### Cluster Deploy
### ssh
```declarative
# aws의 경우 각 ec2 접속시 key 인증
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
./flink/flink-1.18.1/bin/start-cluster.sh
# ./flink/flink-1.18.1/bin/jobmanager.sh start
# ./flink/flink-1.18.1/bin/taskmanager.sh start
```

### RockDB 설정
```declarative
# ① State Backend = RocksDB 
state.backend: rocksdb
# ② 체크포인트 저장소 (HDFS/S3/NFS 등) 
state.checkpoints.dir: hdfs://namenode:9000/flink/checkpoints 
state.savepoints.dir: hdfs://namenode:9000/flink/savepoints 

# ③ 증분 체크포인트(용량/시간 절약) 켜기 
state.backend.incremental: true 

# ④ RocksDB 로컬 디렉터리(고속 디스크 권장, 여러 경로 가능) 
state.backend.rocksdb.localdir: /data/flink/rocksdb 

# ⑤ RocksDB 메모리를 Flink Managed Memory로 관리(권장) 
state.backend.rocksdb.memory.managed: true 

# ⑥ 타이머 저장소(대규모 타이머 많으면 ROCKSDB 권장, 지연 낮추려면 HEAP) 
state.backend.rocksdb.timer-service.factory: ROCKSDB # or HEAP # 

⑦ (선택) 체크포인트 동작 
execution.checkpointing.interval: 45s 
execution.checkpointing.timeout: 5m 
execution.checkpointing.unaligned: true # 셔플/백프레셔 심하면 유리
execution.checkpointing.retained: 1 # checkpoint 보관 개수
```

### hadoop client 설치
```declarative
# flink 의 저장소로 hdfs 설정을 위해 hadoop client 설치

# 설치
wget https://downloads.apache.org/hadoop/common/hadoop-3.3.6/hadoop-3.3.6.tar.gz
sudo tar -xzf hadoop-3.3.6.tar.gz -C /opt
sudo ln -s /opt/hadoop-3.3.6 /opt/hadoop

# 설정 값
echo 'export HADOOP_HOME=/opt/hadoop' >> ~/.bashrc
echo 'export PATH=$PATH:$HADOOP_HOME/bin:$HADOOP_HOME/sbin' >> ~/.bashrc
echo 'export JAVA_HOME=/usr/lib/jvm/java-1.8.0-amazon-corretto.x86_64/jre' >> ~/.bashrc
echo 'export PATH=$JAVA_HOME/bin:$PATH'  >> ~/.bashrc
echo 'export JAVA_HOME=$JAVA_HOME' >> $HADOOP_HOME/etc/hadoop/hadoop-env.sh
source ~/.bashrc

# 유저 권한
echo 'export HDFS_NAMENODE_USER=ec2-user' >> ~/.bashrc
echo 'export HDFS_DATANODE_USER=ec2-user' >> ~/.bashrc
echo 'export HDFS_SECONDARYNAMENODE_USER=ec2-user' >> ~/.bashrc
echo 'export YARN_RESOURCEMANAGER_USER=ec2-user' >> ~/.bashrc
echo 'export YARN_NODEMANAGER_USER=ec2-user'  >> ~/.bashrc

# 설정파일 수정 (마스터 서버에서 복사)
core-site.xml
hdfs-site.xml

# Hadoop이 알려주는 전체 클래스패스를 Flink에 전달
export HADOOP_HOME=/opt/hadoop/hadoop-3.3.1
export HADOOP_CONF_DIR=/opt/hadoop/hadoop-3.3.1/etc/hadoop/
export HADOOP_CLASSPATH=$(/opt/hadoop/hadoop-3.3.1/bin/hadoop classpath --glob)

export HADOOP_CONF_DIR=$HADOOP_HOME/etc/hadoop
export HADOOP_CLASSPATH="$($HADOOP_HOME/bin/hadoop classpath --glob)"

```