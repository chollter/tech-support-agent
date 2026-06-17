# K8s Infra

本目录提供本机虚拟机 K8s 开发环境的基础依赖。

包含：

- PostgreSQL：应用主库，启动时由 Spring SQL init 初始化表和样例数据。
- Redis：幂等、锁和运行状态缓存。
- Kafka：异步 Agent 运行模式使用；同步 Demo 可以先不启用。

## 启动

```bash
kubectl apply -f k8s/infra/
kubectl get pods
kubectl get svc
```

如果你的集群没有默认 `StorageClass`，本目录已经提供了单机开发可用的 [pv.yaml](E:/CODE/JAVA/tech-support-agent/k8s/infra/pv.yaml)。
它会创建 `hostPath` 类型的 `PersistentVolume`，供 `postgres-data` 和 `redis-data` 两个 PVC 绑定。

如果你执行了第二次 `kubectl apply -f k8s/infra/`，Deployment 可能会触发一次滚动更新。
这时 `kubectl get pods` 短时间内会同时看到新旧两代 Pod，这是正常现象；等新的 Pod 就绪后，旧 Pod 会被自动删除。

如果你看到下面这些状态，通常分别表示：

- `ImagePullBackOff`：节点尝试拉取镜像失败，常见原因是镜像仓库不可达、需要登录，或者镜像地址不存在。
- `ErrImageNeverPull`：Pod 当前配置成了 `imagePullPolicy: Never`，但节点本地并没有这个镜像。
- `Pending`：常见是 PVC 没有绑定成功，比如没有默认 `StorageClass`。

当前清单里的 Redis / Kafka 可以直接指向节点本地已有镜像。
如果你使用本地预加载镜像，建议把 `imagePullPolicy` 设为 `Never`，避免集群再次尝试拉取远端镜像。

如果你的 K8s 没有默认 StorageClass，建议按下面顺序执行：

```bash
kubectl apply -f k8s/infra/pv.yaml
kubectl apply -f k8s/infra/postgres.yaml
kubectl apply -f k8s/infra/redis.yaml
kubectl get pv
kubectl get pvc
```

当前 `pv.yaml` 使用：

- `storageClassName: manual`
- `hostPath: /tmp/k8s/postgres-data`
- `hostPath: /tmp/k8s/redis-data`

适合本机单节点 K8s 开发环境。
如果你在多节点集群里使用，建议改成正式的 `StorageClass` 或者网络存储。

推荐排查命令：

```bash
kubectl describe pod <pod-name>
kubectl describe pvc postgres-data
kubectl describe pvc redis-data
kubectl get events --sort-by=.lastTimestamp
```

## 验证 PostgreSQL

```bash
kubectl exec deploy/postgres -- pg_isready -U postgres
kubectl exec -it deploy/postgres -- psql -U postgres -d tech_support_agent -c "\dt"
```

## 验证 Redis

```bash
kubectl exec deploy/redis -- redis-cli ping
```

预期输出：

```text
PONG
```

## 验证 Kafka

```bash
kubectl exec deploy/kafka -- /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --list
```

如果镜像内脚本路径不同，先进入容器检查：

```bash
kubectl exec -it deploy/kafka -- sh
```

## 应用连接参数

部署应用时使用以下环境变量：

```text
REDIS_HOST=redis
REDIS_PORT=6379
KAFKA_BOOTSTRAP_SERVERS=kafka:9092
PG_HOST=postgres
PG_PORT=5432
PG_DATABASE=tech_support_agent
PG_USER=postgres
PG_PASSWORD=postgres
```
