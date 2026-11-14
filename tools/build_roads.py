# 도로 조각 데이터 생성
# 사용법: python tools/build_roads.py
# 표준 라이브러리만 쓴다. 결과는 app/src/main/java/com/deeptactics/roadie/RoadData.kt
#
# 지점과 연결(NODES, LINKS)만 사람이 정하고, 나머지는 전부 실제 데이터로 잰다.
#   경로와 거리, 시간  OSRM 공개 서버 (OpenStreetMap 도로망)
#   신호, 터널, 다리, 해안선, 호수, 고개 이름  OpenStreetMap (Overpass API)
#   고도  SRTM 30m (OpenTopoData)
# 응답은 tools/.cache에 저장해서 다시 돌릴 때 서버를 안 부른다.
import bisect
import hashlib
import json
import math
import os
import re
import sys
import time
import urllib.parse
import urllib.request

sys.stdout.reconfigure(encoding='utf-8')
HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, '.cache')
OUT = os.path.join(HERE, '..', 'app', 'src', 'main', 'java', 'com', 'deeptactics', 'roadie', 'RoadData.kt')
UA = {'User-Agent': 'roadie-data-build/0.1'}

HOME = 'home'
NODES = [
    (HOME, '강릉역', 37.7637, 128.8996),
    ('ojuk', '오죽헌', 37.7792, 128.8786),
    ('lake', '경포호', 37.7955, 128.9020),
    ('gbeach', '경포해변', 37.8055, 128.9078),
    ('sacheon', '사천진해변', 37.8410, 128.8770),
    ('yeongok', '연곡', 37.8606, 128.8402),
    ('anmok', '안목해변', 37.7722, 128.9476),
    ('namhang', '남항진', 37.7699, 128.9512),
    ('jdj', '정동진', 37.6912, 129.0341),
    ('simgok', '심곡항', 37.6516, 129.0450),
    ('seongsan', '대관령 옛길 입구', 37.7158, 128.8296),
    ('pass', '대관령 마루', 37.6876, 128.7525),
    # 아래는 지도에서 이름으로 찾은 좌표
    ('anin', '안인진', 37.7277, 128.9889),
    ('jumunjin', '주문진항', 37.8919, 128.8301),
    ('sogeumgang', '소금강', 37.8160, 128.7067),
    ('okgye', '옥계해변', 37.6303, 129.0484),
]
LINKS = [
    (HOME, 'ojuk'), (HOME, 'lake'), ('ojuk', 'lake'), ('lake', 'gbeach'), ('gbeach', 'sacheon'),
    ('sacheon', 'yeongok'), ('yeongok', 'ojuk'), ('gbeach', 'anmok'), (HOME, 'anmok'), ('anmok', 'namhang'),
    ('namhang', 'jdj'), ('jdj', 'simgok'), ('simgok', HOME), ('jdj', HOME), (HOME, 'seongsan'),
    ('seongsan', 'pass'), ('pass', HOME), ('seongsan', 'ojuk'), ('sacheon', 'seongsan'),
    ('namhang', 'anin'), ('anin', 'jdj'), (HOME, 'anin'),
    ('yeongok', 'jumunjin'), ('jumunjin', 'sacheon'),
    ('yeongok', 'sogeumgang'), ('sogeumgang', 'ojuk'),
    ('simgok', 'okgye'), ('okgye', HOME),
]
BBOX = (37.58, 128.68, 37.92, 129.12)

STEP = 25.0  # 경로를 이 간격으로 쪼개서 잰다(m)
ELE_STEP = 100.0  # 고도는 이 간격으로 받는다(m)
SIGNAL_NEAR = 30.0
TUNNEL_NEAR = 15.0
COAST_NEAR = 250.0
LAKE_NEAR = 150.0
MIN_TUNNEL = 400.0
MIN_SEA = 600.0
MIN_LAKE = 400.0
MIN_BRIDGE = 300.0
MIN_PROMINENCE = 80.0


def fetch(key, url, data=None, wait=0.0):
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, hashlib.md5((url + (data or '')).encode()).hexdigest() + '.json')
    if os.path.exists(path):
        with open(path, encoding='utf-8') as f:
            return json.load(f)
    for attempt in range(4):
        try:
            req = urllib.request.Request(url, data=data.encode() if data else None, headers=UA)
            with urllib.request.urlopen(req, timeout=120) as r:
                res = json.load(r)
            break
        except Exception as e:
            print(f'  {key} 다시 시도 ({e})')
            time.sleep(3 + attempt * 4)
    else:
        raise SystemExit(f'{key} 실패')
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(res, f, ensure_ascii=False)
    time.sleep(wait)
    return res


def overpass(key, body):
    q = f'[out:json][timeout:180][bbox:{BBOX[0]},{BBOX[1]},{BBOX[2]},{BBOX[3]}];{body}'
    return fetch(key, 'https://overpass-api.de/api/interpreter', data=urllib.parse.urlencode({'data': q}), wait=1.0)['elements']


# 좌표를 미터로. 이 지역은 작아서 한 점 기준으로 펴도 충분하다
LAT0 = (BBOX[0] + BBOX[2]) / 2
LON0 = (BBOX[1] + BBOX[3]) / 2
KX = math.cos(math.radians(LAT0)) * 111320.0
KY = 110574.0


def xy(lat, lon):
    return ((lon - LON0) * KX, (lat - LAT0) * KY)


class Grid:
    # 선분을 칸에 나눠 담아 두고 가까운 것만 찾는다
    def __init__(self, cell=200.0):
        self.cell = cell
        self.cells = {}
        self.segs = []

    def add_line(self, pts, tag=None):
        for (x1, y1), (x2, y2) in zip(pts, pts[1:]):
            i = len(self.segs)
            self.segs.append((x1, y1, x2, y2, tag))
            n = max(1, int(math.hypot(x2 - x1, y2 - y1) / (self.cell / 2)))
            for k in range(n + 1):
                x = x1 + (x2 - x1) * k / n
                y = y1 + (y2 - y1) * k / n
                self.cells.setdefault((int(x // self.cell), int(y // self.cell)), set()).add(i)

    def near(self, x, y, r):
        # r 안에 선이 있으면 (거리, tag), 없으면 None
        reach = int(r // self.cell) + 1
        cx, cy = int(x // self.cell), int(y // self.cell)
        best = None
        seen = set()
        for dx in range(-reach, reach + 1):
            for dy in range(-reach, reach + 1):
                for i in self.cells.get((cx + dx, cy + dy), ()):
                    if i in seen:
                        continue
                    seen.add(i)
                    x1, y1, x2, y2, tag = self.segs[i]
                    vx, vy = x2 - x1, y2 - y1
                    L = vx * vx + vy * vy
                    t = 0.0 if L == 0 else max(0.0, min(1.0, ((x - x1) * vx + (y - y1) * vy) / L))
                    d = math.hypot(x - (x1 + t * vx), y - (y1 + t * vy))
                    if d <= r and (best is None or d < best[0]):
                        best = (d, tag)
        return best


def polyline(geom):
    return [xy(p['lat'], p['lon']) for p in geom]


def line_len(pts):
    return sum(math.hypot(b[0] - a[0], b[1] - a[1]) for a, b in zip(pts, pts[1:]))


def load_osm():
    print('OSM 데이터 받는 중')
    signals = [xy(e['lat'], e['lon']) for e in overpass('signals', 'node["highway"="traffic_signals"];out;')]
    tunnel, bridge, coast, lake = Grid(), Grid(), Grid(), Grid()
    for e in overpass('tunnels', 'way["tunnel"~"yes|building_passage"]["highway"];out tags geom;'):
        tunnel.add_line(polyline(e['geometry']), e['tags'].get('name', ''))
    for e in overpass('bridges', 'way["bridge"]["bridge"!="no"]["highway"];out tags geom;'):
        pts = polyline(e['geometry'])
        if line_len(pts) >= 100:
            bridge.add_line(pts, e['tags'].get('name', ''))
    for e in overpass('coast', 'way["natural"="coastline"];out geom;'):
        coast.add_line(polyline(e['geometry']))
    for e in overpass('lakes', '(way["natural"="water"]["name"];relation["natural"="water"]["name"];);out geom;'):
        name = e['tags'].get('name', '')
        if not re.search('호$|호수$|저수지$|호지$', name):
            continue  # 하천저류지 같은 건 호수로 안 친다
        if e['type'] == 'way':
            lake.add_line(polyline(e['geometry']), name)
        else:
            for m in e.get('members', []):
                if m.get('geometry'):
                    lake.add_line(polyline(m['geometry']), name)
    places = []
    for e in overpass('places', '(node["mountain_pass"="yes"]["name"];node["natural"="saddle"]["name"];node["natural"="peak"]["name"];);out body;'):
        t = e['tags']
        kind = 'pass' if t.get('mountain_pass') == 'yes' or t.get('natural') == 'saddle' else 'peak'
        places.append((xy(e['lat'], e['lon']), kind, t['name']))
    print(f'  신호 {len(signals)}, 터널 {len(tunnel.segs)}선분, 다리 {len(bridge.segs)}선분, 해안선 {len(coast.segs)}선분, 호수 {len(lake.segs)}선분, 고개/봉우리 {len(places)}')
    return signals, tunnel, bridge, coast, lake, places


def route(a, b):
    url = (f'https://router.project-osrm.org/route/v1/driving/{a[3]},{a[2]};{b[3]},{b[2]}'
           '?overview=full&geometries=geojson&steps=true')
    r = fetch(f'route {a[1]}→{b[1]}', url, wait=1.0)['routes'][0]
    pts = [xy(lat, lon) for lon, lat in r['geometry']['coordinates']]
    steps, s = [], 0.0
    for st in r['legs'][0]['steps']:
        steps.append((s, s + st['distance'], st.get('name', '')))
        s += st['distance']
    return pts, r['distance'], r['duration'], steps


def resample(pts, step):
    out, total, carry = [(pts[0][0], pts[0][1], 0.0)], 0.0, 0.0
    for (x1, y1), (x2, y2) in zip(pts, pts[1:]):
        L = math.hypot(x2 - x1, y2 - y1)
        d = step - carry
        while d <= L:
            out.append((x1 + (x2 - x1) * d / L, y1 + (y2 - y1) * d / L, total + d))
            d += step
        carry = L - (d - step)
        total += L
    return out, total


def elevations(pts):
    # 100m마다. 한 번에 100곳까지 받는다
    ll = [(LAT0 + y / KY, LON0 + x / KX) for x, y, _ in pts]
    out = []
    for i in range(0, len(ll), 100):
        loc = '|'.join(f'{la:.5f},{lo:.5f}' for la, lo in ll[i:i + 100])
        r = fetch('고도', f'https://api.opentopodata.org/v1/srtm30m?locations={loc}', wait=1.2)
        out += [(x['elevation'] if x['elevation'] is not None else (out[-1] if out else 0.0)) for x in r['results']]
    sm = [sum(out[max(0, i - 1):i + 2]) / len(out[max(0, i - 1):i + 2]) for i in range(len(out))]
    return sm


def runs(mask, ss, min_len, gap=120.0):
    # 참인 구간들을 [시작, 끝](m)로. 짧게 끊긴 틈은 이어 붙인다
    spans = []
    start = None
    for i, on in enumerate(mask):
        if on and start is None:
            start = i
        if (not on or i == len(mask) - 1) and start is not None:
            end = i if on else i - 1
            spans.append([ss[start], ss[end]])
            start = None
    merged = []
    for sp in spans:
        if merged and sp[0] - merged[-1][1] <= gap:
            merged[-1][1] = sp[1]
        else:
            merged.append(sp)
    return [(a, b) for a, b in merged if b - a >= min_len]


def curves(samples):
    # 25m 간격 점들의 방향이 150m 안에 55도 넘게 꺾이면 굽이 하나
    h = [math.atan2(b[1] - a[1], b[0] - a[0]) for a, b in zip(samples, samples[1:])]
    turn = []
    for a, b in zip(h, h[1:]):
        d = (b - a + math.pi) % (2 * math.pi) - math.pi
        turn.append(abs(math.degrees(d)))
    n, i = 0, 0
    while i + 6 <= len(turn):
        if sum(turn[i:i + 6]) >= 55:
            n += 1
            i += 12
        else:
            i += 1
    return n


def clusters(ss, gap=60.0):
    n, last = 0, -1e9
    for s in sorted(ss):
        if s - last > gap:
            n += 1
        last = s
    return n


def name_at(steps, s):
    for a, b, n in steps:
        if a <= s <= b + 1e-6 and n:
            return n
    return ''


def nearest_place(places, x, y, r=2500.0):
    best = None
    for (px, py), kind, name in places:
        d = math.hypot(px - x, py - y)
        if d <= r:
            score = d + (0 if kind == 'pass' else 800)
            if best is None or score < best[0]:
                best = (score, kind, name)
    return best


def clean(name):
    # 괄호 설명과 번길 같은 골목 이름은 장면 이름으로 쓰지 않는다
    name = re.sub(r'\s*\(.*?\)', '', name).strip()
    name = name.replace('창혜로', '창해로')  # 지도에 오타로 올라온 이름
    return '' if re.search(r'\d+번?길$|\d+번길', name) else name


def km(m):
    return round(m / 1000.0, 3)


def build_edge(a, b, osm, nodes):
    signals, tunnel, bridge, coast, lake, places = osm
    pts, dist, dur, steps = route(nodes[a], nodes[b])
    sm, total = resample(pts, STEP)
    xs = [(p[0], p[1]) for p in sm]
    ss = [p[2] for p in sm]
    n = len(sm)

    tun = [tunnel.near(x, y, TUNNEL_NEAR) for x, y in xs]
    brd = [bridge.near(x, y, TUNNEL_NEAR) for x, y in xs]
    cst = [coast.near(x, y, COAST_NEAR) for x, y in xs]
    lak = [lake.near(x, y, LAKE_NEAR) for x, y in xs]
    tun_m = [t is not None for t in tun]
    # 터널 안의 다리처럼 겹치면 터널로 본다
    brd_m = [brd[i] is not None and not tun_m[i] for i in range(n)]
    cst_m = [c is not None and not tun_m[i] for i, c in enumerate(cst)]
    lak_m = [l is not None and not tun_m[i] for i, l in enumerate(lak)]

    sig = []
    for sx, sy in signals:
        for (x, y), s in zip(xs, ss):
            if abs(x - sx) < SIGNAL_NEAR and abs(y - sy) < SIGNAL_NEAR and math.hypot(x - sx, y - sy) <= SIGNAL_NEAR:
                sig.append(s)
                break
    ele_pts = [p for p in sm if True][::int(ELE_STEP / STEP)]
    if ele_pts[-1][2] < total - 1:
        ele_pts.append((pts[-1][0], pts[-1][1], total))
    z = elevations(ele_pts)
    zs = [p[2] for p in ele_pts]
    climb = int(round(z[-1] - z[0]))

    # 장면
    feats = []

    def name_of(tag_grid_hits, s0, s1, default):
        names = [h[1] for h in tag_grid_hits if h and h[1]]
        return max(set(names), key=names.count) if names else default

    def idx(s0, s1):
        i0 = bisect.bisect_left(ss, s0)
        i1 = bisect.bisect_right(ss, s1)
        return i0, i1

    def both(kind, s0, s1, name_f, line_f, name_r=None, line_r=None):
        feats.append(dict(at=s0, kind=kind, name=name_f, line=line_f, len=0, one='fwd'))
        feats.append(dict(at=s1, kind=kind, name=name_r or name_f, line=line_r or line_f, len=0, one='rev'))

    names = {clean(n_) for _, _, n_ in steps if clean(n_)}
    road = max(names, key=lambda n_: sum(b2 - a2 for a2, b2, nn in steps if clean(nn) == n_)) if names else ''

    for s0, s1 in runs(tun_m, ss, MIN_TUNNEL, gap=80.0):
        i0, i1 = idx(s0, s1)
        tn = name_of(tun[i0:i1], s0, s1, '')
        for fwd in (True, False):
            # 나오는 쪽 600m 앞을 본다
            if fwd:
                j0, j1 = idx(s1, s1 + 600)
            else:
                j0, j1 = idx(s0 - 600, s0)
            sea_ahead = any(cst_m[j0:j1])
            lake_ahead = any(lak_m[j0:j1])
            if sea_ahead:
                nm, ln = '터널 끝, 동해', '이 터널 지나면 바다야.'
            elif lake_ahead:
                nm, ln = '터널 끝, 호수', '터널 나가면 호수가 보여.'
            else:
                nm = (tn + ' 출구') if tn else '터널 출구'
                ln = '곧 터널 끝이야. 나가면 분위기가 바뀐다.'
            feats.append(dict(at=s1, kind='TUNNEL_EXIT', name=nm, line=ln, len=s1 - s0, one='fwd' if fwd else 'rev'))
    for s0, s1 in runs(cst_m, ss, MIN_SEA, gap=200.0):
        r = clean(name_at(steps, s0)) or road
        both('SEA', s0, s1, (r + ' 해안도로') if r else '해안도로', '바다 바로 옆으로 붙는다.')
    for s0, s1 in runs(lak_m, ss, MIN_LAKE, gap=200.0):
        i0, i1 = idx(s0, s1)
        ln_ = name_of(lak[i0:i1], s0, s1, '')
        both('LAKE', s0, s1, (ln_ + ' 호숫가') if ln_ else '호숫가', '호수 옆으로 바짝 붙는다.')
    for s0, s1 in runs(brd_m, ss, MIN_BRIDGE, gap=60.0):
        i0, i1 = idx(s0, s1)
        bn = name_of(brd[i0:i1], s0, s1, '')
        bn = clean(bn)
        if bn and not bn.endswith(('교', '다리')):
            bn += ' 다리 위'
        both('BRIDGE', s0, s1, bn or '다리 위', '다리 건널 때 양쪽 다 봐.')

    # 고갯마루: 안쪽에서 솟은 곳, 아니면 오르막이 끝나는 쪽 끝
    summit = None
    for k in range(1, len(z) - 1):
        lo = max(0, k - 10)
        hi = min(len(z), k + 11)
        if z[k] >= max(z[lo:hi]) and min(z[k] - min(z[:k + 1]), z[k] - min(z[k:])) >= MIN_PROMINENCE:
            summit = k
            break
    def pass_name(k):
        sx, sy = ele_pts[k][0], ele_pts[k][1]
        p = nearest_place(places, sx, sy)
        if p is None:
            return '고갯마루', '곧 고갯마루야. 앞이 확 트여.'
        if p[1] == 'pass':
            return p[2] + ' 고갯마루', '곧 고갯마루야. 앞이 확 트여.'
        return p[2] + ' 전망', '저 위에서 한번 내려다보자.'
    if summit is not None:
        nm, ln = pass_name(summit)
        feats.append(dict(at=zs[summit], kind='PASS', name=nm, line=ln, len=0, one='both'))
    elif climb >= 200 and z[-1] >= max(z) - 10:
        nm, ln = pass_name(len(z) - 1)
        feats.append(dict(at=total - 300, kind='PASS', name=nm, line=ln, len=0, one='fwd'))
    elif climb <= -200 and z[0] >= max(z) - 10:
        nm, ln = pass_name(0)
        feats.append(dict(at=300, kind='PASS', name=nm, line=ln, len=0, one='rev'))

    L = total
    kmh = max(25.0, min(90.0, round(dist / dur * 3.6 / 5) * 5))
    nsig = clusters(sig)
    ncurve = curves(xs)
    share = lambda m: sum(STEP for x in m if x) / L
    tags = []
    if share(cst_m) >= 0.18:
        tags.append('SEA')
    if share(lak_m) >= 0.12:
        tags.append('LAKE')
    if abs(climb) >= 150 or ncurve / (L / 1000) >= 1.2:
        tags.append('MOUNTAIN')
    if nsig / (L / 1000) >= 0.8:
        tags.append('CITY')
    if not tags:
        tags.append('MOUNTAIN' if nsig / (L / 1000) < 0.3 else 'CITY')

    return dict(a=a, b=b, road=road or '', km=km(L), kmh=kmh, signals=nsig, curves=ncurve, climb=climb,
                tags=tags, feats=feats, sea=share(cst_m), lake=share(lak_m), report=dict(tunnel=share(tun_m), sea=share(cst_m), lake=share(lak_m), bridge=share(brd_m), dist=dist))


def kt_str(s):
    return '"' + s.replace('\\', '\\\\').replace('"', '\\"').replace('$', '\\$') + '"'


def write_kotlin(nodes, edges):
    lines = ['package com.deeptactics.roadie', '',
             '// tools/build_roads.py가 만든 파일이다. 손으로 고치지 말고 스크립트를 다시 돌린다.',
             '// 경로 OSRM, 도로 정보 OpenStreetMap(© OpenStreetMap contributors, ODbL), 고도 SRTM 30m',
             'object RoadData {', '    val nodes = listOf(']
    for id_, name, lat, lon in nodes:
        lines.append(f'        Node({kt_str(id_)}, {kt_str(name)}, {lat}, {lon}),')
    lines += ['    )', '', '    val edges = listOf(']
    for e in edges:
        tags = ', '.join('Tag.' + t for t in e['tags'])
        tail = f'sea = {e["sea"]:.2f}, lake = {e["lake"]:.2f}'
        if e['feats']:
            lines.append(f'        Edge({kt_str(e["a"])}, {kt_str(e["b"])}, {kt_str(e["road"])}, {e["km"]:.3f}, {float(e["kmh"])}, {e["signals"]}, {e["curves"]}, {e["climb"]}, setOf({tags}),')
            lines.append('            listOf(')
            for f in sorted(e['feats'], key=lambda f: f['at']):
                fl = ''
                if f['one'] == 'fwd':
                    fl = ', oneWay = true'
                elif f['one'] == 'rev':
                    fl = ', reverseOnly = true'
                ln = f', len = {f["len"] / 1000:.3f}' if f['len'] else ''
                lines.append(f'                Feature({f["at"] / 1000:.3f}, SceneKind.{f["kind"]}, {kt_str(f["name"])}, {kt_str(f["line"])}{ln}{fl}),')
            lines.append('            ),')
            lines.append(f'            {tail},')
            lines.append('        ),')
        else:
            lines.append(f'        Edge({kt_str(e["a"])}, {kt_str(e["b"])}, {kt_str(e["road"])}, {e["km"]:.3f}, {float(e["kmh"])}, {e["signals"]}, {e["curves"]}, {e["climb"]}, setOf({tags}), {tail}),')
    lines += ['    )', '}', '']
    with open(OUT, 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(lines))


def main():
    nodes = {n[0]: n for n in NODES}
    osm = load_osm()
    edges = []
    for a, b in LINKS:
        print(f'{nodes[a][1]} → {nodes[b][1]}')
        e = build_edge(a, b, osm, nodes)
        r = e['report']
        print(f'  {e["road"] or "-"} {e["km"]}km {e["kmh"]}km/h 신호{e["signals"]} 굽이{e["curves"]} 고도{e["climb"]:+d}m {e["tags"]}'
              f' 터널{r["tunnel"]:.0%} 해안{r["sea"]:.0%} 호수{r["lake"]:.0%} 다리{r["bridge"]:.0%}')
        for f in sorted(e['feats'], key=lambda f: f['at']):
            print(f'    {f["kind"]:11s} {f["at"] / 1000:6.2f}km {f["one"]:4s} {f["name"]}')
        edges.append(e)
    write_kotlin(list(NODES), edges)
    print('저장:', os.path.normpath(OUT))


if __name__ == '__main__':
    main()
