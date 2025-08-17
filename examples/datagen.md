# First Example with Datagen

## flink install
```aiignore
# install
wget https://archive.apache.org/dist/flink/flink-1.15.4/flink-1.15.4-bin-scala_2.12.tgz

# tar
tar -xzf flink-*.tgz
```

## jar 다운로드
```aiignore
# Maven Central에서 관련 jar 파일 다운로드 
# - https://central.sonatype.com/
# /lib 경로에 설치
wget https://repo1.maven.org/maven2/org/apache/flink/flink-connector-datagen/1.17.1/flink-connector-datagen-1.17.1.jar
```

## 실행
```aiignore
# flink 실행
./bin/start-cluster.sh
(./bin/stop-cluster.sh)

# 실행 확인
ps aux | grep flink

# flink-sql 실행
./bin/sql-client.sh
```

## sql-client 예제
```aiignore
CREATE TABLE Orders (
  order_id BIGINT,
  price    DOUBLE,
  order_time TIMESTAMP(3),
  WATERMARK FOR order_time AS order_time - INTERVAL '5' SECOND
) WITH (
  'connector' = 'datagen',
  'rows-per-second' = '5',
  'fields.order_id.kind' = 'sequence',
  'fields.order_id.start' = '1',
  'fields.order_id.end' = '1000000',
  'fields.price.min' = '1',
  'fields.price.max' = '1000'
);

CREATE TABLE PrintSink (
  order_id BIGINT,
  price    DOUBLE,
  order_time TIMESTAMP(3)
) WITH (
  'connector' = 'print'
);

# 데이터 생성
SELECT * FROM Orders;
```

## source / sink table
```aiignore
소스(Source) 테이블
정의
- 외부 시스템(파일, DB, 메시지 큐, HTTP API 등)로부터 데이터를 읽어오는 역할을 하는 테이블

특징
- SELECT 가능
- INSERT 불가 (외부에서만 데이터 공급)
- Flink SQL에서는 "connector" 속성이 해당 소스 타입을 지정
- Orders는 datagen 커넥터를 사용한 소스 테이블


싱크(Sink) 테이블
정의
- Flink 처리 결과를 외부 시스템(파일, DB, 메시지 큐, 콘솔 출력 등)에 내보내는 역할을 하는 테이블

특징
- INSERT 가능
- SELECT 불가 (print, jdbc, filesystem 등 대부분이 그럼)
- 결과는 테이블 자체에서 보이는 게 아니라 외부 대상(파일, 콘솔, 로그)에 기록됨
- PrintSink는 싱크 전용
- INSERT INTO PrintSink SELECT * FROM Orders; 가능
- SELECT * FROM PrintSink; ❌ (불가)
```

## tm 확인
```aiignore
http://{public ip}:8081/#/overview
```
