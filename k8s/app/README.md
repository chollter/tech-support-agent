# K8s App

本目录提供 OpsMind 应用在本机虚拟机 K8s 环境中的最小部署清单。

## 前置条件

先启动基础依赖：

```bash
kubectl apply -f k8s/infra/
kubectl get pods
```

如果 GHCR 镜像是私有的，先创建镜像拉取凭据，并在 `deployment.yaml` 中加入 `imagePullSecrets`：

```bash
kubectl create secret docker-registry ghcr-secret \
  --docker-server=ghcr.io \
  --docker-username=chollter \
  --docker-password=<github_pat> \
  --docker-email=<your_email>
```

## 部署应用

```bash
kubectl apply -f k8s/app/
kubectl rollout status deployment/tech-support-agent --timeout=180s
kubectl get pods
kubectl get svc tech-support-agent
```

## 访问

`service.yaml` 使用 `NodePort` 暴露端口：

```text
http://<虚拟机IP>:30020/
```

## 常用排查

```bash
kubectl describe pod -l app=tech-support-agent
kubectl logs deploy/tech-support-agent
kubectl get endpoints tech-support-agent
```
