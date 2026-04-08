# 如何监控 MQ？出现问题如何快速定位？

## 一、核心结论

**MQ 监控 = 指标监控 + 日志监控 + 链路追踪**

**快速定位问题 = 明确现象 + 查看指标 + 分析日志 + 追踪链路**

监控核心目标：**提前发现问题、快速定位根因、减少业务影响**

---

## 二、MQ 监控的核心指标

### 2.1 监控指标体系

```
┌─────────────────────────────────────────────────────────┐
│                    MQ 监控指标体系                        │
├─────────────┬─────────────┬─────────────┬───────────────┤
│   生产端    │    服务端   │   消费端    │    系统资源   │
├─────────────┼─────────────┼─────────────┼───────────────┤
│ 发送 TPS    │ 消息堆积量  │ 消费 TPS    │ CPU 使用率     │
│ 发送成功率  │ 队列深度    │ 消费延迟    │ 内存使用率    │
│ 发送耗时    │ Broker 状态 │ 重试次数    │ 磁盘使用率    │
│ 失败次数    │ 副本状态    │ 死信数量    │ 网络 IO       │
└─────────────┴─────────────┴─────────────┴───────────────┘
```

---

### 2.2 关键指标详解

#### （1）消息堆积量（最重要）

**定义**：队列中未被消费的消息数量

**告警阈值**：

| 严重程度 | 阈值 | 处理方式 |
|---------|------|---------|
| 警告 | 1 万条 | 关注，准备扩容 |
| 严重 | 10 万条 | 立即处理 |
| 致命 | 100 万条 | 紧急预案 |

**可能原因**：
- 消费者宕机或挂掉
- 消费速度 < 生产速度
- 消费逻辑有 Bug，消费卡住
- 消费者线程池满

---

#### （2）消费延迟（Latency）

**定义**：消息从生产到消费的时间差

**计算公式**：`消费延迟 = 当前时间 - 消息生产时间`

**告警阈值**：
- 普通业务：> 10 秒告警
- 实时业务：> 1 秒告警
- 延时业务：> 1 分钟告警

---

#### （3）生产/消费 TPS

**定义**：每秒生产/消费的消息数

**作用**：
- 发现流量异常（突增/突降）
- 容量规划参考
- 发现消费者异常（消费 TPS 为 0）

---

#### （4）失败率

**生产失败率** = 生产失败次数 / 生产总次数

**消费失败率** = 消费失败次数 / 消费总次数

**告警阈值**：> 1% 需要关注，> 5% 需要立即处理

---

#### （5）死信队列消息数

**定义**：超过重试次数进入死信队列的消息数

**告警阈值**：> 100 条需要人工介入分析

---

#### （6）Broker 节点状态

- 在线/离线状态
- 主从切换次数
- 副本同步延迟

---

## 三、MQ 监控方案

### 3.1 RabbitMQ 监控

#### 方案 1：Management Plugin（自带）

**访问地址**：`http://mq-server:15672`

**监控内容**：
- Overview：集群概览
- Queues：队列详情（堆积量、消费速率）
- Exchanges：交换机状态
- Channels：连接通道
- Admin：用户权限

**优点**：开箱即用，信息全面

**缺点**：无法历史数据存储，无法告警

---

#### 方案 2：Prometheus + Grafana（推荐）

**架构图**：
```
RabbitMQ ──► rabbitmq_exporter ──► Prometheus ──► Grafana
                                        │
                                        ▼
                                   Alertmanager
```

**部署步骤**：

1. 启用 RabbitMQ Prometheus 插件
```bash
rabbitmq-plugins enable rabbitmq_prometheus
```

2. 配置 Prometheus 抓取
```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'rabbitmq'
    static_configs:
      - targets: ['mq-server:15692']  # Prometheus 端口
```

3. Grafana 导入模板
- 模板 ID：`10991`（RabbitMQ 监控）
- 模板 ID：`11817`（RabbitMQ 深度监控）

---

#### 方案 3：关键监控指标配置

```yaml
# alertmanager 告警配置
groups:
  - name: rabbitmq
    rules:
      # 消息堆积告警
      - alert: RabbitMQQueueHigh
        expr: rabbitmq_queue_messages > 100000
        for: 5m
        annotations:
          summary: "RabbitMQ 队列堆积告警"
          description: "队列 {{ $labels.queue }} 消息数 {{ $value }}"

      # 消费者宕机告警
      - alert: RabbitMQConsumerDown
        expr: rabbitmq_queue_consumers == 0
        for: 1m
        annotations:
          summary: "RabbitMQ 队列无消费者"

      # 连接数告警
      - alert: RabbitMQConnectionsHigh
        expr: rabbitmq_connections > 1000
        for: 5m
        annotations:
          summary: "RabbitMQ 连接数过多"
```

---

### 3.2 Kafka 监控

#### 方案 1：Kafka Manager（CMK）

**功能**：
- 集群健康状态
- Topic 管理
- Consumer Lag（消费延迟）监控
- 副本分布

**部署**：
```bash
docker run -d --name kafka-manager \
  -e ZK_HOSTS=zk:2181 \
  -p 9000:9000 \
  hlebalbes/kafka-manager
```

---

#### 方案 2：Prometheus + Grafana

**关键指标**：
```promql
# 消费延迟（Lag）
kafka_consumer_group_lag{group="xxx"}

# 生产速率
rate(kafka_topic_partition_current_offset[1m])

# 消费速率
rate(kafka_consumergroup_current_offset[1m])

# Broker 可用性
kafka_broker_info
```

**Grafana 模板**：`7589`、`9628`

---

#### 方案 3：Burrow（LinkedIn 开源）

**专门监控消费延迟**：
```yaml
# burrow 配置
[consumer]
class-name=Kafka
servers=[zk1:2181,zk2:2181]
group-blacklist=^(console-consumer-?).*$
```

**输出**：消费延迟、消费者状态

---

### 3.3 RocketMQ 监控

#### 方案 1：RocketMQ Console

**访问地址**：`http://mq-console:8080`

**功能**：
- Dashboard：集群概览
- Topic：消息堆积、生产消费 TPS
- Consumer：消费进度、延迟
- Message：消息查询、轨迹

---

#### 方案 2：Prometheus + Grafana

**RocketMQ Exporter 指标**：
```promql
# 消息堆积量
rocketmq_consumer_tps

# 消费延迟
rocketmq_consumer_latency

# 生产 TPS
rocketmq_producer_tps

# Broker 状态
rocketmq_broker_tps
```

**Grafana 模板**：`12396`

---

## 四、出现问题如何快速定位

### 4.1 问题定位流程图

```
发现异常
    │
    ▼
┌─────────────────┐
│ 1. 确认问题范围 │
│ - 单个队列还是  │
│   全部队列？    │
│ - 单个消费者还  │
│   是全部？      │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 2. 查看监控大盘 │
│ - 消息堆积量    │
│ - 消费延迟      │
│ - TPS 变化      │
│ - 失败率        │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 3. 分析可能原因 │
│ - 消费者宕机？  │
│ - 消费逻辑 Bug？│
│ - 资源不足？    │
│ - 网络问题？    │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 4. 查看日志     │
│ - 消费者日志    │
│ - Broker 日志   │
│ - 应用日志      │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 5. 链路追踪     │
│ - 消息轨迹      │
│ - 定位卡点      │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 6. 采取措施     │
│ - 重启消费者    │
│ - 扩容          │
│ - 降级限流      │
│ - 人工消费      │
└─────────────────┘
```

---

### 4.2 常见问题及定位方法

#### 问题 1：消息大量堆积

**现象**：监控显示某队列消息数快速上涨

**定位步骤**：

```bash
# 1. 查看队列状态（RabbitMQ）
rabbitmqctl list_queues name messages consumers

# 2. 查看消费组状态（Kafka）
kafka-consumer-groups.sh --describe --group xxx

# 3. 查看消费者日志
tail -f /var/log/consumer/app.log | grep ERROR

# 4. 查看消费者线程状态
jstack <pid> | grep -A 20 "consumer"
```

**可能原因及解决**：

| 原因 | 现象 | 解决方案 |
|------|------|---------|
| 消费者宕机 | 消费者数量=0 | 重启消费者 |
| 消费卡住 | 线程池满，线程阻塞 | 分析线程栈，修复死锁/Bug |
| 消费能力不足 | 消费 TPS 远低于生产 TPS | 扩容消费者 |
| 网络分区 | Broker 连接断开 | 检查网络，切换节点 |

---

#### 问题 2：消费延迟高

**现象**：消息生产后很久才被消费

**定位步骤**：

```bash
# 1. 查看消息时间戳
# RabbitMQ
rabbitmqctl list_messages queue_name

# RocketMQ
./bin/admin.sh queryMsgByOffset -t TopicName -o Offset

# 2. 查看消费者处理耗时
# 日志中查找消费耗时
grep "cost time" /var/log/consumer/app.log | sort -k2 -nr | head
```

**可能原因**：

| 原因 | 解决方案 |
|------|---------|
| 单条消息处理慢 | 优化消费逻辑，异步处理 |
| 消费者并发度低 | 增加消费者线程数 |
| 消息量突增 | 临时扩容 |
| 网络延迟高 | 检查网络，就近部署 |

---

#### 问题 3：消息丢失

**现象**：生产了 1000 条，只消费了 999 条

**定位步骤**：

```bash
# 1. 查看生产端日志
grep "send failed" /var/log/producer/app.log

# 2. 查看 Broker 日志
grep "discarded" /var/log/mq/broker.log

# 3. 查看消息轨迹（RocketMQ）
./bin/admin.sh queryMsgById -i MessageID

# 4. 查看死信队列
rabbitmqctl list_queues name messages | grep dlx
```

**可能原因**：

| 环节 | 原因 | 解决方案 |
|------|------|---------|
| 生产端 | 发送失败未重试 | 开启 Confirm + 重试 |
| Broker | 未持久化就宕机 | 开启持久化 + 多副本 |
| 消费端 | 提前 ACK | 改为业务处理后 ACK |

---

#### 问题 4：重复消费

**现象**：同一条消息被消费多次

**定位步骤**：

```bash
# 1. 查看消费日志
grep "MessageID:xxx" /var/log/consumer/app.log

# 2. 查看 ACK 日志
# 是否有 ACK 超时时重发

# 3. 查看 Broker 日志
# 是否有主从切换导致重复投递
```

**解决方案**：消费端实现幂等性（唯一键、Redis SETNX）

---

#### 问题 5：Broker 宕机

**现象**：监控显示 Broker 离线，生产消费全部失败

**定位步骤**：

```bash
# 1. 检查 Broker 进程
ps -ef | grep broker

# 2. 查看 Broker 日志
tail -f /var/log/mq/broker.log

# 3. 查看系统资源
top -p <broker_pid>
free -h
df -h

# 4. 查看网络
netstat -an | grep 9876
```

**可能原因**：

| 原因 | 解决方案 |
|------|---------|
| OOM | 增加内存，优化堆配置 |
| 磁盘满 | 清理磁盘，增加容量 |
| CPU 100% | 检查是否有死循环 |
| 网络故障 | 切换节点，修复网络 |

---

### 4.3 消息轨迹追踪

#### RocketMQ 消息轨迹

```bash
# 1. 按 MessageID 查询
./bin/admin.sh queryMsgById -i 0A2F4951445F18B4AAC2780E0F8F0000

# 2. 按 Topic 查询
./bin/admin.sh queryMsgByTopic -t TopicName

# 3. 按时间查询
./bin/admin.sh queryMsgByTimestamp -t TopicName -s 1633000000000
```

**输出示例**：
```
MessageID: 0A2F4951445F18B4AAC2780E0F8F0000
Topic:     order_topic
Tag:       create
StoreTime: 2024-01-01 10:00:00
Producer:  192.168.1.100
Consumer:  192.168.1.200 (消费成功)
Retry:     0
```

---

#### Kafka 消息追踪

```bash
# 1. 查看 Offset
kafka-run-class.sh kafka.tools.GetOffsetShell \
  --broker-list localhost:9092 \
  --topic topic_name

# 2. 消费指定 Offset 的消息
kafka-console-consumer.sh --topic topic_name \
  --partition 0 --offset 12345 --max-messages 10
```

---

## 五、生产环境监控实践

### 5.1 监控大盘配置

**Grafana 核心图表**：

```
┌────────────────────────────────────────────────────────┐
│                   MQ 监控大盘                           │
├─────────────────┬──────────────────────────────────────┤
│ 集群概览        │ ● Broker 在线状态 (绿灯)              │
│                 │ ● 总消息数：123,456                   │
│                 │ ● 生产 TPS：1,234                     │
│                 │ ● 消费 TPS：1,200                     │
├─────────────────┼──────────────────────────────────────┤
│ 消息堆积        │ [折线图] 24 小时堆积趋势               │
│                 │ 告警线：10 万                          │
├─────────────────┼──────────────────────────────────────┤
│ 消费延迟        │ [折线图] 各消费组延迟                  │
│                 │ Top10 延迟队列                        │
├─────────────────┼──────────────────────────────────────┤
│ 失败统计        │ [柱状图] 生产/消费失败次数             │
│                 │ 死信队列消息数                        │
├─────────────────┴──────────────────────────────────────┤
│ 系统资源        │ CPU: 45% │ Memory: 62% │ Disk: 78%  │
└────────────────────────────────────────────────────────┘
```

---

### 5.2 告警通知配置

**告警级别**：
| 级别 | 通知方式 | 响应时间 |
|------|---------|---------|
| P0（致命） | 电话 + 短信 + 企微 | 5 分钟 |
| P1（严重） | 短信 + 企微 | 15 分钟 |
| P2（警告） | 企微 | 1 小时 |
| P3（提示） | 邮件 | 24 小时 |

**告警规则示例**：
```yaml
# Alertmanager 配置
route:
  receiver: 'wechat'
  group_by: ['alertname']

receivers:
  - name: 'wechat'
    wechat_configs:
      - corp_id: 'xxx'
        agent_id: 'xxx'
        to_user: '@all'

  - name: 'phone'
    webhook_configs:
      - url: 'http://alert-center/phone'
```

---

### 5.3 日志收集

**ELK 日志收集**：
```yaml
# Filebeat 配置
filebeat.inputs:
  - type: log
    paths:
      - /var/log/mq/*.log
    fields:
      service: mq

output.elasticsearch:
  hosts: ["es:9200"]
  index: "mq-logs-%{+YYYY.MM.dd}"
```

**关键日志**：
- Broker 日志：`/var/log/mq/broker.log`
- 生产者日志：`/var/log/producer/*.log`
- 消费者日志：`/var/log/consumer/*.log`

---

## 六、面试答题话术（直接背）

**面试官问：如何监控 MQ？出现问题如何快速定位？**

答：我从监控指标、监控方案、问题定位三方面说：

**第一，核心监控指标：**
1. **消息堆积量**：最核心指标，超过 10 万条告警
2. **消费延迟**：消息从生产到消费的时间，超过 10 秒告警
3. **生产/消费 TPS**：发现流量异常
4. **失败率**：生产/消费失败率超过 1% 关注，5% 告警
5. **死信队列**：超过 100 条人工介入
6. **Broker 状态**：在线/离线、主从切换

**第二，监控方案：**
- **RabbitMQ**：Management Plugin + Prometheus + Grafana
- **Kafka**：Kafka Manager + Burrow + Prometheus
- **RocketMQ**：RocketMQ Console + Prometheus

**第三，问题定位流程：**
1. **确认问题范围**：单个队列还是全部，单个消费者还是全部
2. **查看监控大盘**：堆积量、延迟、TPS、失败率
3. **分析可能原因**：消费者宕机、消费卡住、资源不足
4. **查看日志**：消费者日志、Broker 日志
5. **消息轨迹**：RocketMQ 按 MessageID 查询
6. **采取措施**：重启、扩容、降级、人工消费

**常见问题处理**：
- **消息堆积**：重启消费者、扩容、临时增加队列
- **消费延迟**：优化消费逻辑、增加并发度
- **消息丢失**：查轨迹、查日志、确认 ACK 时机
- **重复消费**：消费端幂等处理

**总结**：监控要覆盖全链路，定位问题要从现象到本质，先恢复业务再排查根因。

---

## 七、扩展问题

### Q1：消息积压了上百万条，怎么处理？

**紧急处理**：
1. 先修复消费者 Bug 或重启消费者
2. 临时扩容消费者，10 倍并发度
3. 如果还是消费不过来，新建 Topic，10 倍队列数，写程序批量转发
4. 非核心业务降级，优先处理核心消息

**长期优化**：
1. 优化消费逻辑，提升单条处理速度
2. 增加消费者常驻实例
3. 消息队列增加分区/队列数

---

### Q2：如何保证监控的高可用？

1. **监控数据多副本**：Prometheus 联邦集群
2. **告警多渠道**：企微 + 短信 + 电话
3. **告警分级**：不同级别不同通知方式
4. **监控独立部署**：不依赖业务集群

---

### Q3：你们生产环境 MQ 监控告警阈值怎么设的？

```yaml
消息堆积：> 10 万警告，> 50 万严重
消费延迟：> 10 秒警告，> 60 秒严重
失败率：> 1% 警告，> 5% 严重
TPS 下降：环比下降 50% 警告
Broker 宕机：立即电话告警
```
