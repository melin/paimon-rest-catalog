#!/usr/bin/env python3
"""校验 deploy/kubernetes 清单的内部一致性。

为什么需要这个脚本：K8s 清单里的错误大多不是语法错误，而是**跨文件的引用对不上**——
Service 的 selector 差一个标签、卷名写错、ConfigMap 里引用了 Pod 没有定义的环境变量。
这些在 `kubectl apply` 时都不会报错，只在运行时表现成「Pod 一直 Pending」
或「Spring 启动失败说解析不了占位符」，而报错位置离真正的原因很远。

脚本不做的事：不连接集群。它只检查清单自身是否自洽，因此可以在 CI 里跑，
也可以在没有任何集群的环境里跑。

用法：
    python3 scripts/verify-k8s-manifests.py

优先用 `kubectl kustomize` 渲染（顺带验证 kustomize 组装是否正确）；
取不到 kubectl 时退回直接读目录下的 YAML。
"""
import os
import re
import subprocess
import sys

import yaml

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KDIR = os.path.join(ROOT, "deploy", "kubernetes")

failures = []


def check(label, ok, detail=""):
    if ok:
        print(f"  ✓ {label}")
    else:
        print(f"  ✗ {label}")
        if detail:
            print(f"      {detail}")
        failures.append(label)


def render():
    """返回 (资源列表, 渲染方式说明)。"""
    try:
        out = subprocess.run(
            ["kubectl", "kustomize", KDIR],
            capture_output=True, text=True, timeout=60, cwd=ROOT,
        )
        if out.returncode == 0 and out.stdout.strip():
            docs = [d for d in yaml.safe_load_all(out.stdout) if d]
            return docs, f"kubectl kustomize（{len(docs)} 个资源）"
        return None, f"kubectl kustomize 失败：{out.stderr.strip()[:200]}"
    except FileNotFoundError:
        pass
    if not os.path.isdir(KDIR):
        return None, f"目录不存在：{KDIR}"
    docs = []
    for name in sorted(os.listdir(KDIR)):
        if not name.endswith((".yaml", ".yml")) or name == "kustomization.yaml":
            continue
        with open(os.path.join(KDIR, name), encoding="utf-8") as fp:
            docs += [d for d in yaml.safe_load_all(fp) if d]
    return docs, f"直接读取 YAML（未找到 kubectl，{len(docs)} 个资源）"


docs, how = render()
if docs is None:
    print(f"✗ 无法取得清单：{how}")
    sys.exit(1)
print(f"渲染方式：{how}\n")

by_kind = {}
for d in docs:
    by_kind.setdefault(d.get("kind"), []).append(d)


def named(kind, name):
    for d in by_kind.get(kind, []):
        if d.get("metadata", {}).get("name") == name:
            return d
    return None


WORKLOADS = ("Deployment", "StatefulSet", "DaemonSet")


def pod_spec(w):
    return w["spec"]["template"]["spec"]


def pod_labels(w):
    return w["spec"]["template"]["metadata"].get("labels", {})


def all_workloads():
    out = []
    for kind in WORKLOADS:
        for w in by_kind.get(kind, []):
            out.append((kind, w))
    return out


def containers_of(spec):
    """含 initContainer：它们的 env / volumeMounts 同样要能对上。"""
    return list(spec.get("initContainers", [])) + list(spec.get("containers", []))


def subset(small, big):
    return all(big.get(k) == v for k, v in small.items())


# ---------------------------------------------------------------------------
print("§1 资源基本形状")
# ---------------------------------------------------------------------------
for d in docs:
    kind = d.get("kind", "?")
    name = d.get("metadata", {}).get("name", "?")
    check(f"{kind}/{name} 有 apiVersion 与 kind", bool(d.get("apiVersion")) and kind != "?")
    check(f"{kind}/{name} 有 metadata.name", name != "?")

# 非 Namespace 的资源都必须有 namespace（由 kustomization 统一注入）。
# 漏注会表现为「apply 到了 default 命名空间」，而清单文件本身看不出异常。
mismatched = [
    (d["kind"], d["metadata"]["name"], d["metadata"].get("namespace"))
    for d in docs
    if d.get("kind") != "Namespace"
    and d["metadata"].get("namespace") != by_kind["Namespace"][0]["metadata"]["name"]
]
check("所有资源都被注入到同一个命名空间", not mismatched,
      f"命名空间不一致或缺失：{mismatched}")

# ---------------------------------------------------------------------------
print("\n§2 标签选择器能否选中目标 Pod")
# ---------------------------------------------------------------------------
# selector 与 pod 模板标签对不上时，controller 会创建 Pod 但永不接管，
# 或直接报 `selector does not match template labels`。
for kind, w in all_workloads():
    name = w["metadata"]["name"]
    sel = w["spec"].get("selector", {}).get("matchLabels", {})
    check(f"{kind}/{name} 的 selector 是 pod 标签的子集", subset(sel, pod_labels(w)),
          f"selector={sel} podLabels={pod_labels(w)}")

# Service 要能选中某个工作负载的 Pod，否则 Endpoints 为空——
# 表现为「服务通了但连不上」，且 Service 本身看不出问题。
for svc in by_kind.get("Service", []):
    name = svc["metadata"]["name"]
    sel = svc["spec"].get("selector", {})
    if not sel:
        continue  # headless/ExternalName 之类没有 selector 的情形
    hit = [f"{k}/{w['metadata']['name']}" for k, w in all_workloads() if subset(sel, pod_labels(w))]
    check(f"Service/{name} 的 selector 能选中 Pod", bool(hit),
          f"selector={sel} 未匹配任何工作负载的 pod 标签")

# PDB 的 selector 若不匹配，kubelet 在驱逐时找不到对应预算，
# 会按「没有 PDB」处理——保护静默失效，没有任何报错。
for pdb in by_kind.get("PodDisruptionBudget", []):
    name = pdb["metadata"]["name"]
    sel = pdb["spec"].get("selector", {}).get("matchLabels", {})
    hit = [f"{k}/{w['metadata']['name']}" for k, w in all_workloads() if subset(sel, pod_labels(w))]
    check(f"PodDisruptionBudget/{name} 的 selector 能选中 Pod", bool(hit),
          f"selector={sel} 未匹配任何工作负载的 pod 标签")

# ---------------------------------------------------------------------------
print("\n§3 Service 端口与容器端口")
# ---------------------------------------------------------------------------
for svc in by_kind.get("Service", []):
    name = svc["metadata"]["name"]
    sel = svc["spec"].get("selector", {})
    targets = [w for _, w in all_workloads() if sel and subset(sel, pod_labels(w))]
    if not targets:
        continue
    pod_ports = {}
    for t in targets:
        for c in containers_of(pod_spec(t)):
            for p in c.get("ports", []):
                if p.get("name"):
                    pod_ports[p["name"]] = p.get("containerPort")
    for port in svc["spec"].get("ports", []):
        tp = port.get("targetPort")
        if isinstance(tp, str):
            check(f"Service/{name} 的 targetPort「{tp}」在 Pod 上有同名端口",
                  tp in pod_ports, f"Pod 上的端口名：{sorted(pod_ports)}")

# StatefulSet 的 serviceName 必须指向真实存在的 Service，
# 否则 Pod 拿不到稳定网络标识，且不会有任何报错。
for sts in by_kind.get("StatefulSet", []):
    name = sts["metadata"]["name"]
    svc_name = sts["spec"].get("serviceName")
    check(f"StatefulSet/{name} 的 serviceName「{svc_name}」存在",
          svc_name is not None and named("Service", svc_name) is not None)

# ---------------------------------------------------------------------------
print("\n§4 卷挂载与卷定义能对上")
# ---------------------------------------------------------------------------
for kind, w in all_workloads():
    name = w["metadata"]["name"]
    spec = pod_spec(w)
    vol_names = {v["name"] for v in spec.get("volumes", [])}
    # StatefulSet 的 volumeClaimTemplates 会为每个 Pod 生成同名卷，
    # 它们不出现在 spec.volumes 里，但容器可以正常挂载。
    vol_names |= {t["metadata"]["name"] for t in w["spec"].get("volumeClaimTemplates", [])}
    for c in containers_of(spec):
        mounts = {m["name"] for m in c.get("volumeMounts", [])}
        missing = mounts - vol_names
        check(f"{kind}/{name} 容器 {c['name']} 挂载的卷都有定义", not missing,
              f"缺卷定义：{sorted(missing)}")

# 卷引用的 ConfigMap / Secret / PVC 必须存在。
# 缺 ConfigMap 会让 Pod 卡在 ContainerCreating，报错只说「找不到 configmap」。
for kind, w in all_workloads():
    name = w["metadata"]["name"]
    for v in pod_spec(w).get("volumes", []):
        if "configMap" in v and v["configMap"]:
            ref = v["configMap"].get("name")
            check(f"{kind}/{name} 卷 {v['name']} 引用的 ConfigMap「{ref}」存在",
                  named("ConfigMap", ref) is not None)
        if "secret" in v and v["secret"]:
            ref = v["secret"].get("secretName")
            check(f"{kind}/{name} 卷 {v['name']} 引用的 Secret「{ref}」存在",
                  named("Secret", ref) is not None)
        if "persistentVolumeClaim" in v and v["persistentVolumeClaim"]:
            ref = v["persistentVolumeClaim"].get("claimName")
            check(f"{kind}/{name} 卷 {v['name']} 引用的 PVC「{ref}」存在",
                  named("PersistentVolumeClaim", ref) is not None)

# ---------------------------------------------------------------------------
print("\n§5 环境变量引用的 Secret key 存在")
# ---------------------------------------------------------------------------
# key 拼错时 Pod 起不来，报 `couldn't find key xxx in Secret`。
secret_keys = {
    s["metadata"]["name"]: set((s.get("stringData") or s.get("data") or {}).keys())
    for s in by_kind.get("Secret", [])
}
for kind, w in all_workloads():
    name = w["metadata"]["name"]
    for c in containers_of(pod_spec(w)):
        for e in c.get("env", []) or []:
            ref = (e.get("valueFrom") or {}).get("secretKeyRef")
            if not ref:
                continue
            sn, sk = ref.get("name"), ref.get("key")
            check(f"{kind}/{name} 容器 {c['name']} 的 {e['name']} ← Secret/{sn}[{sk}]",
                  sn in secret_keys and sk in secret_keys.get(sn, set()),
                  f"Secret 中的 key：{sorted(secret_keys.get(sn, []))}")
        for ef in c.get("envFrom", []) or []:
            ref = ef.get("secretRef") or ef.get("configMapRef")
            if not ref:
                continue
            target = "Secret" if ef.get("secretRef") else "ConfigMap"
            check(f"{kind}/{name} 容器 {c['name']} envFrom {target}「{ref.get('name')}」存在",
                  named(target, ref.get("name")) is not None)

# ---------------------------------------------------------------------------
print("\n§6 配置里的 ${...} 占位符都有对应环境变量")
# ---------------------------------------------------------------------------
# 这是最容易漏的一处：ConfigMap 里写了 ${DB_PASSWORD}，
# 而 Deployment 忘了注入 DB_PASSWORD / 或者名字拼成了 DATABASE_PASSWORD。
# kubectl 不会报错，Spring 启动时才抛 "Could not resolve placeholder"。
PLACEHOLDER = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)\}")


def probe_values(obj):
    """递归取出所有字符串值（只看 value，不看 key，与 Spring 的解析范围一致）。"""
    if isinstance(obj, str):
        yield obj
    elif isinstance(obj, dict):
        for v in obj.values():
            yield from probe_values(v)
    elif isinstance(obj, list):
        for v in obj:
            yield from probe_values(v)


for kind, w in all_workloads():
    name = w["metadata"]["name"]
    spec = pod_spec(w)
    mounted = {
        v["configMap"]["name"]
        for v in spec.get("volumes", [])
        if v.get("configMap") and v["configMap"].get("name")
    }
    needed = set()
    for cm_name in sorted(mounted):
        cm = named("ConfigMap", cm_name)
        if not cm:
            continue
        for value in probe_values(cm.get("data", {})):
            needed |= set(PLACEHOLDER.findall(value))
    if not needed:
        continue
    defined = set()
    for c in containers_of(spec):
        for e in c.get("env", []) or []:
            defined.add(e["name"])
        for ef in c.get("envFrom", []) or []:
            for key in ("configMapRef", "secretRef"):
                if ef.get(key):
                    src = named("ConfigMap" if key == "configMapRef" else "Secret", ef[key]["name"])
                    if src:
                        defined |= set((src.get("data") or src.get("stringData") or {}).keys())
    missing = needed - defined
    check(f"{kind}/{name} 覆盖了挂载配置里的全部占位符",
          not missing, f"缺环境变量：{sorted(missing)}（配置里用到：{sorted(needed)}）")

# ---------------------------------------------------------------------------
print("\n§7 探针命令与容器端口一致")
# ---------------------------------------------------------------------------
# 探针打错端口时容器永远不 Ready，而日志完全正常。
for kind, w in all_workloads():
    name = w["metadata"]["name"]
    spec = pod_spec(w)
    cports = {
        p.get("containerPort")
        for c in containers_of(spec)
        for p in c.get("ports", [])
    }
    for c in containers_of(spec):
        for probe_name in ("startupProbe", "livenessProbe", "readinessProbe"):
            probe = c.get(probe_name)
            if not probe or "exec" not in probe:
                continue
            cmd = " ".join(probe["exec"].get("command", []))
            for port in re.findall(r"127\.0\.0\.1:(\d+)", cmd):
                check(f"{kind}/{name} 容器 {c['name']} 的 {probe_name} 端口 {port} 有对应 containerPort",
                      int(port) in cports, f"容器端口：{sorted(x for x in cports if x)}")

# ---------------------------------------------------------------------------
print("\n§8 探针地址与 Dockerfile 的 HEALTHCHECK 一致")
# ---------------------------------------------------------------------------
# 两处都指向同一个公开端点。它们漂移时不会有任何报错，
# 但会让「容器健康检查通过」与「K8s 认为就绪」失去一致性。
dockerfile = os.path.join(ROOT, "Dockerfile")
if os.path.exists(dockerfile):
    text = open(dockerfile, encoding="utf-8").read()
    hc = re.search(r"HEALTHCHECK[^\n]*\n[^\n]*http://127\.0\.0\.1:(\d+)(/[^\s\"']*)?", text)
    if hc:
        hc_port, hc_path = hc.group(1), (hc.group(2) or "").rstrip()
        probe_paths = set()
        for _, w in all_workloads():
            for c in containers_of(pod_spec(w)):
                for pn in ("startupProbe", "livenessProbe", "readinessProbe"):
                    p = c.get(pn)
                    if p and "exec" in p:
                        # 命令是 `case "$code" in 200|401|403)` 这类多行 shell，
                        # 路径后面紧跟 `)`，因此不能简单地匹配到行尾。
                        probe_paths |= set(re.findall(r"127\.0\.0\.1:\d+(/[^\s)\"']*)", " ".join(p["exec"]["command"])))
        check(f"K8s 探针路径与 Dockerfile HEALTHCHECK 一致（{hc_path}）",
              probe_paths == {hc_path}, f"K8s 探针路径：{sorted(probe_paths)}")
        # 只要有**任意一个**容器监听了健康检查用的端口即可。
        # 不能要求每个容器都监听——initContainer 通常不声明任何端口。
        cport_ok = any(
            p.get("containerPort") == int(hc_port)
            for _, w in all_workloads()
            for c in pod_spec(w).get("containers", [])
            for p in c.get("ports", [])
        )
        check(f"容器端口与 Dockerfile HEALTHCHECK 端口一致（{hc_port}）", cport_ok)
    else:
        check("Dockerfile 中能找到 HEALTHCHECK", False, "未匹配到 HEALTHCHECK 行")

# ---------------------------------------------------------------------------
print("\n§9 清单里的镜像与 Dockerfile 的构建命令一致")
# ---------------------------------------------------------------------------
# 改了镜像 tag 却忘了同步清单，会在集群里表现成 ImagePullBackOff，
# 而 `docker images` 里明明有这个镜像。
if os.path.exists(dockerfile):
    text = open(dockerfile, encoding="utf-8").read()
    built = set(re.findall(r"docker build -t (\S+)", text))
    used = set()
    for _, w in all_workloads():
        for c in containers_of(pod_spec(w)):
            used.add(c["image"])
    # 方向是「Dockerfile 构建了什么 → 清单有没有用上」。
    # 反过来的方向做不了：`mysql:8.0` 这类公共镜像名里同样没有斜杠，
    # 靠字符串区分不出「本地构建」与「公共镜像」，那样只会产生噪音。
    # 这个方向能抓住真正的问题：改了镜像 tag 却忘了同步清单，
    # 结果集群里 ImagePullBackOff，而 `docker images` 里明明躺着这个镜像。
    check("Dockerfile 构建的镜像都被清单引用",
          built <= used, f"Dockerfile 构建：{sorted(built)}；清单用到：{sorted(used)}")

# ---------------------------------------------------------------------------
print()
if failures:
    print(f"校验未通过：{len(failures)} 项不一致")
    for f in failures:
        print(f"  - {f}")
    sys.exit(1)
print("校验通过：清单内部引用自洽。")
