import re, sys, glob, os, collections

LOGDIR = sys.argv[1] if len(sys.argv) > 1 else "logs"
rows = []
for path in sorted(glob.glob(os.path.join(LOGDIR, "*.log"))):
    tag = os.path.basename(path)[:-4]
    if tag in ("campaign",) or tag.startswith("trial"):
        continue
    arch = jit = budget = None
    fp = ""
    jdk = ""
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.rstrip("\n")
            m = re.match(r"BENCH\| tag=(\S+) arch=(\S+) jit=(\S+) ramSticks=(\d+) cpuTier=(\d+)", line)
            if m:
                arch, jit, sticks, cpu = m.group(2), m.group(3), m.group(4), m.group(5)
            m = re.match(r"BENCH\| java.version=(\S+)", line)
            if m: jdk = m.group(1)
            m = re.match(r"BENCH\| callBudgets=(\S+)", line)
            if m: budget = m.group(1)
            m = re.match(r"BENCH\| FINGERPRINT (.*)", line)
            if m: fp = m.group(1)
            m = re.match(r"BENCH\| U label=(\S+) n=(\d+) batch=(\d+) reps=(\d+) java_us min=(\S+) med=(\S+) max=(\S+) mean=(\S+) \| lua_us min=(\S+) med=(\S+) max=(\S+) \| calls_per_s_med=(\S+) freeKB=(\S+)", line)
            if m:
                rows.append(dict(kind="U", tag=tag, jdk=jdk, arch=arch, jit=jit, budget=budget, cpu=cpu, sticks=sticks, label=m.group(1), n=int(m.group(2)), batch=int(m.group(3)), reps=int(m.group(4)),
                                 jmin=float(m.group(5)), jmed=float(m.group(6)), jmax=float(m.group(7)), jmean=float(m.group(8)),
                                 lmin=float(m.group(9)), lmed=float(m.group(10)), lmax=float(m.group(11)), cps=m.group(12), freeKB=int(m.group(13)), fp=fp))
            m = re.match(r"BENCH\| L label=(\S+) n=(\d+) java_wall_ms=(\S+) lua_cpu_ms=(\S+) uptime_ticks=(\d+) calls_per_tick_by_uptime=(\S+) calls_per_s=(\S+) \| (.*)", line)
            if m:
                summ = m.group(8)
                def g(k):
                    mm = re.search(k + r"=(\S+)", summ); return mm.group(1) if mm else ""
                rows.append(dict(kind="L", tag=tag, jdk=jdk, arch=arch, jit=jit, budget=budget, cpu=cpu, sticks=sticks, label=m.group(1), n=int(m.group(2)), wall_ms=float(m.group(3)), cpu_ms=float(m.group(4)),
                                 uticks=int(m.group(5)), cpt_uptime=float(m.group(6)), cps=float(m.group(7)), full=g("full_ticks"), pmin=g("per_full_tick_min"), pmed=g("per_full_tick_med"), pmax=g("per_full_tick_max"), first=g("first_tick"), last=g("last_tick"), seq=g("seq"), fp=fp))
            m = re.match(r"BENCH\| !! FAILED workload=(\S+): (.*)", line)
            if m:
                rows.append(dict(kind="F", tag=tag, jdk=jdk, arch=arch, jit=jit, budget=budget, cpu=cpu, sticks=sticks, label=m.group(1), msg=m.group(2), fp=fp))

def armname(r):
    a = r["arch"]
    if a == "luajit":
        a = "LuaJIT (jit " + r["jit"] + ")"
    else:
        a = "Lua 5." + a[1]
    return a

print("## U rows (us per call, Java-side first->last stamp / (n-1); lua = os.clock cross-check)")
print("| tag | JDK | arch | budget | label | n | batch x reps | java us min / med / max | lua us med | calls/s (med) | free KB after |")
print("|---|---|---|---|---|---|---|---|---|---|---|")
for r in rows:
    if r["kind"] != "U": continue
    print("| %s | %s | %s | %s | %s | %d | %d x %d | %.2f / %.2f / %.2f | %.2f | %s | %d |" % (r["tag"], r["jdk"], armname(r), r["budget"], r["label"], r["n"], r["batch"], r["reps"], r["jmin"], r["jmed"], r["jmax"], r["lmed"], r["cps"], r["freeKB"]))
print()
print("## L rows (limit=256 and the non-direct control)")
print("| tag | JDK | arch | budget | CPU tier | label | n | wall ms | calls/s | per full tick min / med / max | full ticks | first / last tick | seq |")
print("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
for r in rows:
    if r["kind"] != "L": continue
    print("| %s | %s | %s | %s | T%s | %s | %d | %.1f | %.0f | %s / %s / %s | %s | %s / %s | %s |" % (r["tag"], r["jdk"], armname(r), r["budget"], r["cpu"], r["label"], r["n"], r["wall_ms"], r["cps"], r["pmin"], r["pmed"], r["pmax"], r["full"], r["first"], r["last"], r["seq"][:60]))
print()
print("## failures")
for r in rows:
    if r["kind"] == "F":
        print("- %s %s: %s" % (r["tag"], r["label"], r["msg"]))
print()
print("## fingerprints")
seen = set()
for r in rows:
    k = (r["tag"], r["fp"])
    if k in seen: continue
    seen.add(k)
    print("- %s: %s" % (r["tag"], r["fp"]))

# compact pivot: median java us per (arch,jit,budget,jdk) x label for U rows
print()
print("## pivot: median java us/call (U rows)")
labels = []
piv = collections.OrderedDict()
for r in rows:
    if r["kind"] != "U": continue
    if r["label"] not in labels: labels.append(r["label"])
    key = (r["tag"].split("-")[0], r["jdk"], armname(r), r["budget"])
    piv.setdefault(key, {})[r["label"]] = r["jmed"] if r["label"] != "local_call" else r["lmed"]
print("| run | JDK | arch | budget | " + " | ".join(labels) + " |")
print("|---|---|---|---|" + "---|" * len(labels))
for key, d in piv.items():
    print("| %s | %s | %s | %s | " % key + " | ".join(("%.2f" % d[l]) if l in d else "-" for l in labels) + " |")
