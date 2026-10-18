import re, json, base64, sys, os
B = os.path.dirname(os.path.abspath(__file__))
SRC = sys.argv[1]; OUT = sys.argv[2]
VERSION = "v3.0 (2026-10-18)"
sys.path.insert(0, B)
from t1_mcq import Q as Q1, FLASH as F1

TOPICS = {1:"Definition of Economics",2:"Nature of Economics",3:"Scope of Economics",4:"Methodology of Economics",5:"Microeconomics",6:"Macroeconomics",
7:"Measurements of Utility",8:"Law of Diminishing Marginal Utility",9:"Law of Equi-Marginal Utility",10:"Demand Schedule",11:"Demand Curves (including the Law of Demand)",
12:"Elasticity of Demand",13:"Methods of Measurement of Elasticity of Demand",14:"Elasticity of Supply",15:"Methods of Measurement of Elasticity of Supply",16:"Consumer's Surplus",
17:"Indifference Curve Analysis — IC, MRS, Properties, Budget Line",18:"Consumer's Equilibrium through Indifference Curve Analysis",19:"Production Function",20:"Returns to Factor",
21:"Concepts of Cost of Production",22:"Short-Run Average Cost Curve",23:"Long-Run Average Cost Curve",24:"Theory of Exchange",25:"Forms of Market",
26:"Market Equilibrium under Perfect Competition",27:"Concept of Economic Growth",28:"Concept of Economic Development",29:"Conceptual Overview of National Income",
30:"Concept of State Income",31:"An Overview of the Economy of Uttarakhand"}
MOD = {**{i:1 for i in range(1,7)},**{i:2 for i in range(7,19)},**{i:3 for i in range(19,27)},**{i:4 for i in range(27,32)}}
CH = [(1,"Foundations of Economics",[1,2,3,4]),(2,"Microeconomics and Macroeconomics",[5,6]),(3,"Utility Analysis",[7,8,9]),(4,"Theory of Demand",[10,11]),
(5,"Elasticity of Demand and Measurement",[12,13]),(6,"Elasticity of Supply and Measurement",[14,15]),(7,"Consumer Surplus, Indifference Curves and Consumer Equilibrium",[16,17,18]),
(8,"Production Function and Returns",[19,20]),(9,"Theory of Cost — Concepts, SAC and LAC",[21,22,23]),(10,"Theory of Exchange, Forms of Market and Equilibrium under Perfect Competition",[24,25,26]),
(11,"Economic Growth and Development",[27,28]),(12,"National and State Income",[29,30]),(13,"An Overview of the Economy of Uttarakhand",[31])]
REBUILT = {1}
NOTES = {17:"Syllabus v2.1 mein is topic ka naam badla hai. Purane notes 'Consumer's Equilibrium — Cardinal Approach' ke hain; rework mein ye content T9/T18 mein shift hoga (delete nahi).",
18:"Syllabus v2.1: ab ye topic IC analysis se consumer's equilibrium hai. Purane notes neeche.",
24:"Syllabus v2.1: naam 'Theory of Exchange'. Scope rework ke time research karke decide hoga.",
26:"Syllabus v2.1: 'Market Equilibrium under Perfect Competition' — industry equilibrium aur shifts bhi add honge."}

src = open(SRC, encoding="utf-8").read()
legacy = {int(m.group(1)): m.group(2).strip() for m in re.finditer(r'<script id="payload(\d+)"[^>]*>(.*?)</script>', src, re.S)}
assert len(legacy) == 31

# ---------- SVG helpers ----------
def fig(svg, label): return f'<div class="fig" role="img" aria-label="{label}">{svg}</div>'

def topic_map(ch, active):
    n, title, ts = next(c for c in CH if c[0] == ch)
    w = 64; gap = 10; x0 = 70; W = x0 + len(ts)*(w+gap) + 60
    s = f'<svg class="sv" viewBox="0 0 {W} 44" aria-hidden="true"><rect class="bx" x="2" y="8" width="58" height="28" rx="8"/><text x="31" y="27" text-anchor="middle" font-size="13" font-weight="700">C{ch}</text>'
    for i, t in enumerate(ts):
        x = x0 + i*(w+gap)
        cls = "bxa" if t == active else "bx"
        s += f'<line class="ln" x1="{x-gap}" y1="22" x2="{x}" y2="22"/><rect class="{cls}" x="{x}" y="8" width="{w}" height="28" rx="8"/><text x="{x+w/2}" y="27" text-anchor="middle" font-size="13" font-weight="{800 if t==active else 500}" class="{"at" if t==active else ""}">T{t}</text>'
    x = x0 + len(ts)*(w+gap)
    s += f'<line class="ln" x1="{x-gap}" y1="22" x2="{x+8}" y2="22"/><text x="{x+12}" y="27" font-size="12" class="mt">C{ch+1} →</text></svg>'
    return f'<div class="tmap" aria-label="Topic {active} chapter {ch} mein">{s}</div>'

def timeline():
    D = [("1776","Adam Smith","Wealth"),("1890","Alfred Marshall","Material welfare"),("1932","Lionel Robbins","Scarcity & choice"),("1948","Paul A. Samuelson","Growth (time)"),("—","J. K. Mehta","Wantlessness")]
    s = '<svg class="sv" viewBox="0 0 320 270"><line class="ln" x1="78" y1="28" x2="78" y2="196"/><line class="ln" x1="78" y1="196" x2="78" y2="240" stroke-dasharray="4 4"/>'
    for i, (y, n, a) in enumerate(D):
        cy = 28 + i*53
        s += (f'<g class="tl" data-i="{i}" tabindex="0" role="button" aria-label="{n}, {a}">'
              f'<rect x="0" y="{cy-22}" width="320" height="46" fill="transparent"/>'
              f'<text x="56" y="{cy+5}" text-anchor="end" font-size="14" font-weight="800" class="at">{y}</text>'
              f'<circle class="dot af" cx="78" cy="{cy}" r="8"/>'
              f'<text x="96" y="{cy-2}" font-size="14.5" font-weight="700">{n}</text><text x="96" y="{cy+16}" font-size="13" class="mt">{a}</text></g>')
    return fig(s + '</svg>', "Timeline: 1776 Smith wealth, 1890 Marshall welfare, 1932 Robbins scarcity, 1948 Samuelson growth, Mehta wantlessness")

def box(x, y, w, h, txt, cls="bx", sub=None):
    s = f'<rect class="{cls}" x="{x}" y="{y}" width="{w}" height="{h}" rx="9"/>'
    if sub:
        s += f'<text x="{x+w/2}" y="{y+h/2-3}" text-anchor="middle" font-size="14" font-weight="700">{txt}</text><text x="{x+w/2}" y="{y+h/2+14}" text-anchor="middle" font-size="12" class="mt">{sub}</text>'
    else:
        s += f'<text x="{x+w/2}" y="{y+h/2+5}" text-anchor="middle" font-size="14" font-weight="700">{txt}</text>'
    return s

ARROW = '<defs><marker id="ah" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto"><path d="M0,0L10,5L0,10z" class="mf"/></marker></defs>'
def arr(x1, y1, x2, y2): return f'<line class="ln" x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" marker-end="url(#ah)"/>'

def flow():
    s = '<svg class="sv" viewBox="0 0 320 250">' + ARROW
    s += box(4, 6, 148, 46, "Unlimited wants", sub="ends anant") + box(168, 6, 148, 46, "Limited resources", sub="alternative uses ke saath")
    s += arr(78, 54, 140, 74) + arr(242, 54, 180, 74)
    s += box(85, 76, 150, 38, "Scarcity", "bxa") + arr(160, 116, 160, 132)
    s += box(85, 134, 150, 38, "Choice") + arr(160, 174, 160, 190)
    s += box(60, 192, 200, 50, "Opportunity cost", "bxa", "next best alternative chhoda")
    return fig(s + '</svg>', "Unlimited wants aur limited resources se scarcity, scarcity se choice, choice se opportunity cost")

def problems():
    s = '<svg class="sv" viewBox="0 0 320 150">' + ARROW
    s += box(95, 4, 130, 40, "Scarcity", "bxa")
    for i, (a, b) in enumerate([("What?", "output mix"), ("How?", "technique"), ("For whom?", "distribution")]):
        x = 4 + i*108
        s += arr(160, 46, x+50, 92) + box(x, 94, 100, 50, a, sub=b)
    return fig(s + '</svg>', "Scarcity se teen central problems: what, how, for whom to produce")

# PPC from NCERT schedule
OX, OY, SX, SY = 46, 266, 52, 20
def P(x, y): return (OX + x*SX, OY - y*SY)
def smooth(pts):
    d = f"M{pts[0][0]:.1f},{pts[0][1]:.1f}"
    for i in range(len(pts)-1):
        p0 = pts[i-1] if i > 0 else pts[i]; p1 = pts[i]; p2 = pts[i+1]; p3 = pts[i+2] if i+2 < len(pts) else p2
        c1 = (p1[0]+(p2[0]-p0[0])/6, p1[1]+(p2[1]-p0[1])/6); c2 = (p2[0]-(p3[0]-p1[0])/6, p2[1]-(p3[1]-p1[1])/6)
        d += f" C{c1[0]:.1f},{c1[1]:.1f} {c2[0]:.1f},{c2[1]:.1f} {p2[0]:.1f},{p2[1]:.1f}"
    return d
SCHED = [(0,10),(1,9),(2,7),(3,4),(4,0)]
def ppc():
    pts = [P(x, y) for x, y in SCHED]; out = [P(x*1.2, y*1.2) for x, y in SCHED]
    s = '<svg class="sv" viewBox="0 0 340 304">'
    for k in range(1, 13, 2): s += f'<line class="gd" x1="{OX}" y1="{OY-k*SY}" x2="{OX+5.3*SX}" y2="{OY-k*SY}"/>'
    s += f'<path class="ax" d="M{OX},{OY-12.4*SY}V{OY}H{OX+5.4*SX}"/>'
    for k in range(0, 13, 2): s += f'<text x="{OX-8}" y="{OY-k*SY+4}" text-anchor="end" font-size="11" class="mt">{k}</text>'
    for k in range(0, 6): s += f'<text x="{OX+k*SX}" y="{OY+16}" text-anchor="middle" font-size="11" class="mt">{k}</text>'
    s += f'<text x="336" y="{OY+32}" text-anchor="end" font-size="12.5" font-weight="700">Corn (X) →</text>'
    s += f'<text x="{OX+6}" y="{OY-12*SY}" font-size="12.5" font-weight="700">Cotton (Y)</text>'
    s += f'<g id="t1-pout" style="display:none"><path class="cv2" d="{smooth(out)}"/><text x="{P(4.25,3.2)[0]}" y="{P(4.25,3.2)[1]}" font-size="12" class="at" font-weight="700">PPC₂</text></g>'
    s += f'<path class="cv" d="{smooth(pts)}"/>'
    for (x, y), (px, py), L in zip(SCHED, pts, "ABCDE"):
        s += f'<circle cx="{px}" cy="{py}" r="3.5" class="af"/><text x="{px+6}" y="{py-6}" font-size="11" class="mt">{L}</text>'
    s += '<circle id="t1-pdot" r="7" class="af" stroke="var(--paper)" stroke-width="2"/><text id="t1-plab" font-size="14" font-weight="800" class="at"></text>'
    s += f'<text x="{P(3.35,2.6)[0]}" y="{P(3.35,2.6)[1]}" font-size="12" class="at" font-weight="700">PPC₁</text></svg>'
    return fig(s, "PPC Corn aur Cotton ke NCERT schedule se: A(0,10), B(1,9), C(2,7), D(3,4), E(4,0); concave to origin")
PPC_PTS = {"on": P(2, 7), "inside": P(1.3, 4.2), "outside": P(2.6, 7.6), "shift": P(2.6, 7.6)}

def mrt():
    s = ('<svg class="sv" viewBox="0 0 240 80"><text x="10" y="47" font-size="22" font-weight="700" font-style="italic">MRT</text>'
         '<text x="70" y="47" font-size="22">=</text>'
         '<line class="ax" x1="100" y1="12" x2="100" y2="70"/><line class="ax" x1="172" y1="12" x2="172" y2="70"/>'
         '<text x="136" y="34" text-anchor="middle" font-size="21"><tspan>Δ</tspan><tspan font-style="italic">Y</tspan></text>'
         '<line class="ax" x1="108" y1="41" x2="164" y2="41"/>'
         '<text x="136" y="64" text-anchor="middle" font-size="21"><tspan>Δ</tspan><tspan font-style="italic">X</tspan></text></svg>')
    return f'<div class="fig" role="img" aria-label="MRT equals absolute value of delta Y divided by delta X" style="max-width:260px">{s}</div>'

# ---------- assemble ----------
t1 = open(os.path.join(B, "t1.html"), encoding="utf-8").read()
t1 = (t1.replace("{{MAP_C1_T1}}", topic_map(1, 1)).replace("{{TIMELINE_SVG}}", timeline()).replace("{{FLOW_SVG}}", flow())
        .replace("{{PROBLEMS_SVG}}", problems()).replace("{{PPC_SVG}}", ppc()).replace("{{MRT_SVG}}", mrt()))
assert "{{" not in t1

def legacy_article(t, ch):
    note = NOTES.get(t, "")
    return (f'<article class="topic legacy" id="t{t}" data-topic="{t}"><header class="thead"><div class="tkick">Topic {t} · Chapter {ch}</div>'
            f'<h2>{TOPICS[t]}</h2><div class="pending">⏳ Rework pending — neeche aapke purane notes aur MCQs <b>bina badlaav</b> ke hain.'
            f'{(" " + note) if note else ""}</div></header><div class="legacy-frame" data-legacy="{t}"></div></article>')

parts = []
for n, title, ts in CH:
    m = MOD[ts[0]]
    chips = "".join(f'<a href="#t{t}">T{t} {TOPICS[t].split(" —")[0]}</a>' for t in ts)
    body = "".join(t1 if t == 1 else legacy_article(t, n) for t in ts)
    prev = f'<button type="button" data-go="{n-1}">← C{n-1}: {CH[n-2][1]}</button>' if n > 1 else ''
    nxt = f'<button type="button" data-go="{n+1}">C{n+1}: {CH[n][1]} →</button>' if n < 13 else ''
    parts.append(f'<section class="chapter" id="c{n}" data-ch="{n}" hidden><div class="chead"><div class="ck">Chapter {n} · Module M{m}</div><h2>{title}</h2><div class="ct">{chips}</div></div>{body}<div class="cnav">{prev}{nxt}</div></section>')

def jdump(o): return json.dumps(o, ensure_ascii=False).replace("</", "<\\/")
qs = []
for q in Q1:
    assert len(q["o"]) == 4 and 0 <= q["a"] < 4 and len(q["w"]) == 4 and q["w"][q["a"]] == "", q["id"]
    cid = "t1-" + q["c"]
    assert f'id="{cid}"' in t1, cid
    qs.append({"id": q["id"], "c": cid, "tag": q["tag"], "q": q["q"], "o": q["o"], "a": q["a"], "e": q["e"], "w": q["w"]})
ids = [q["id"] for q in qs]; assert len(ids) == len(set(ids))

meta = {"topics": {str(t): {"name": TOPICS[t], "m": MOD[t], "ok": t in REBUILT} for t in TOPICS},
        "chapters": [{"n": n, "title": tt, "topics": ts} for n, tt, ts in CH]}
data = f'<script type="application/json" id="q-1">{jdump(qs)}</script>\n<script type="application/json" id="fc-1">{jdump(F1)}</script>\n'
data += "\n".join(f'<script type="application/octet-stream" id="legacy-{t}">{legacy[t]}</script>' for t in sorted(legacy) if t not in REBUILT)

shell = open(os.path.join(B, "shell.html"), encoding="utf-8").read()
pp = {k: f"{v[0]:.1f},{v[1]:.1f}" for k, v in PPC_PTS.items()}
html = (shell.replace("{{VERSION}}", VERSION).replace("{{CHAPTERS}}", "\n".join(parts)).replace("{{META}}", jdump(meta)).replace("{{DATA}}", data)
        .replace("{{PPC_ON}}", pp["on"]).replace("{{PPC_IN}}", pp["inside"]).replace("{{PPC_OUT}}", pp["outside"]).replace("{{PPC_SH}}", pp["shift"]))
assert "{{" not in html
open(OUT, "w", encoding="utf-8").write(html)
print("ok", len(html), "bytes;", len(qs), "T1 MCQs;", len(F1), "flashcards")
