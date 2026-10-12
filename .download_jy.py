import json, os, sys, time
from concurrent.futures import ThreadPoolExecutor
import urllib.request, urllib.parse

ROOT = os.path.expanduser("~/workspace/jy-engine")
RAW = "https://gitee.com/lwg_6/jy-engine/raw/master/"

tree = json.load(open(os.path.expanduser("~/workspace/jy-engine-tree.json")))["tree"]
blobs = [t["path"] for t in tree if t["type"] == "blob"]

# 可选：跳过 vendored 3rd 与超大二进制
SKIP_PREFIX = ("autolua2/engine/src/main/cpp/3rd/",)
SKIP_BIG = 4 * 1024 * 1024
size_of = {t["path"]: t.get("size", 0) for t in tree if t["type"] == "blob"}

mode = sys.argv[1] if len(sys.argv) > 1 else "core"
if mode == "core":
    todo = [p for p in blobs if not p.startswith(SKIP_PREFIX) and size_of[p] <= SKIP_BIG]
else:
    todo = blobs
print("mode=%s 待下载 %d / %d 文件" % (mode, len(todo), len(blobs)))

def fetch(path):
    dest = os.path.join(ROOT, path)
    if os.path.exists(dest) and os.path.getsize(dest) == size_of.get(path, -1):
        return "skip"
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    url = RAW + urllib.parse.quote(path)
    for attempt in range(3):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(req, timeout=60) as r:
                data = r.read()
            if size_of.get(path) and len(data) != size_of[path]:
                return "size-mismatch:%s(%d!=%d)" % (path, len(data), size_of[path])
            open(dest, "wb").write(data)
            return "ok"
        except Exception as e:
            if attempt == 2:
                return "FAIL:%s:%s" % (path, e)
            time.sleep(1)
    return "FAIL"

ok = fail = skip = other = 0
issues = []
with ThreadPoolExecutor(max_workers=8) as ex:
    for i, res in enumerate(ex.map(fetch, todo)):
        if res == "ok": ok += 1
        elif res == "skip": skip += 1
        elif res == "FAIL" or res.startswith("FAIL"): fail += 1; issues.append(res)
        else: other += 1; issues.append(res)
        if (i+1) % 200 == 0:
            print("  %d/%d  ok=%d fail=%d" % (i+1, len(todo), ok, fail))
print("完成: ok=%d skip=%d fail=%d other=%d" % (ok, skip, fail, other))
for it in issues[:20]:
    print("  ", it)
