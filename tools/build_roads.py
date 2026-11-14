# 도로 조각 데이터 생성
# 사용법: python tools/osm_extract.py 로 지도 데이터를 뽑은 뒤  python tools/build_roads.py [지역 id ...]   (없으면 tools/regions의 전부)
# 표준 라이브러리와 numpy만 쓴다. 결과는 app/src/main/assets/roads/<지역>.json 과 index.json
#
# 지역 설정(tools/regions/*.json)에는 지점과 연결만 적는다. 나머지는 실제 데이터로 잰다.
#   경로와 거리, 시간  OSRM 공개 서버 (OpenStreetMap 도로망)
#   신호, 터널, 다리, 해안선, 호수, 강, 고개 이름  OpenStreetMap(Geofabrik 파일에서 tools/osm_extract.py가 지역별로 뽑아 둔 것)
#   고도  AWS 지형 타일(Terrarium, SRTM 기반)을 받아서 직접 읽는다
# 응답은 tools/.cache에 저장해서 다시 돌릴 때 서버를 안 부른다.
# 지역을 늘리려면 tools/regions에 설정 파일을 하나 더 두면 된다.
import bisect
import glob
import hashlib
import json
import math
import os
import re
import struct
import sys
import time
import urllib.parse
import urllib.request
import zlib

import numpy as np

from geo import despur, encode, simplify

sys.stdout.reconfigure(encoding='utf-8')
HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, '.cache')
OUT = os.path.join(HERE, '..', 'app', 'src', 'main', 'assets', 'roads')
UA = {'User-Agent': 'roadie-data-build/0.2'}

STEP = 25.0  # 경로를 이 간격으로 쪼개서 잰다(m)
ELE_STEP = 100.0
SIGNAL_NEAR = 30.0
TUNNEL_NEAR = 15.0
COAST_NEAR = 250.0
LAKE_NEAR = 150.0
RIVER_NEAR = 500.0  # 강은 중심선 기준이라 넓게 잡는다
MIN_TUNNEL = 400.0
MIN_SEA = 600.0
MIN_LAKE = 400.0
MIN_RIVER = 800.0
MIN_BRIDGE = 300.0
MIN_PROMINENCE = 80.0
NAME_FIX = {'창혜로': '창해로'}  # 지도에 오타로 올라온 이름


def fetch(key, url, data=None, wait=0.0, raw=False, tries=5):
    os.makedirs(CACHE, exist_ok=True)
    h = hashlib.md5((url + (data or '')).encode()).hexdigest()
    path = os.path.join(CACHE, h + ('.bin' if raw else '.json'))
    if os.path.exists(path):
        with open(path, 'rb') as f:
            b = f.read()
        return b if raw else json.loads(b.decode('utf-8'))
    for attempt in range(tries):
        try:
            req = urllib.request.Request(url, data=data.encode() if data else None, headers=UA)
            with urllib.request.urlopen(req, timeout=180) as r:
                b = r.read()
            if not raw:
                json.loads(b.decode('utf-8'))
            break
        except Exception as e:
            print(f'  {key} 다시 시도 ({str(e)[:80]})')
            time.sleep(4 + attempt * 6)
    else:
        return None
    with open(path, 'wb') as f:
        f.write(b)
    time.sleep(wait)
    return b if raw else json.loads(b.decode('utf-8'))


# 좌표를 미터로. 지역 중심 한 점 기준으로 편다(지역이 수십 km라 충분하다)
ORG = {'lat': 37.5, 'lon': 127.0}


def set_origin(lat, lon):
    ORG['lat'], ORG['lon'] = lat, lon


def xy(lat, lon):
    return ((lon - ORG['lon']) * math.cos(math.radians(ORG['lat'])) * 111320.0, (lat - ORG['lat']) * 110574.0)


def unxy(x, y):
    return (ORG['lat'] + y / 110574.0, ORG['lon'] + x / (math.cos(math.radians(ORG['lat'])) * 111320.0))


def hav(a, b):
    la1, lo1, la2, lo2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    d = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 6371.0 * 2 * math.asin(math.sqrt(d))


class Grid:
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
        reach = int(r // self.cell) + 1
        cx, cy = int(x // self.cell), int(y // self.cell)
        best, seen = None, set()
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


# 고도 ------------------------------------------------------------------------
def png_pixels(data):
    assert data[:8] == b'\x89PNG\r\n\x1a\n'
    pos, idat, w, h, ct, il = 8, b'', 0, 0, 2, 0
    while pos < len(data):
        ln = struct.unpack('>I', data[pos:pos + 4])[0]
        typ, body = data[pos + 4:pos + 8], data[pos + 8:pos + 8 + ln]
        pos += 12 + ln
        if typ == b'IHDR':
            w, h, bd, ct, _, _, il = struct.unpack('>IIBBBBB', body)
            assert bd == 8 and il == 0
        elif typ == b'IDAT':
            idat += body
    bpp = 3 if ct == 2 else 4
    raw = zlib.decompress(idat)
    stride = w * bpp
    rows, prev, i = [], [0] * stride, 0
    for _ in range(h):
        f = raw[i]
        line = list(raw[i + 1:i + 1 + stride])
        i += stride + 1
        if f == 1:
            for x in range(bpp, stride):
                line[x] = (line[x] + line[x - bpp]) & 255
        elif f == 2:
            line = [(a + b) & 255 for a, b in zip(line, prev)]
        elif f == 3:
            for x in range(stride):
                left = line[x - bpp] if x >= bpp else 0
                line[x] = (line[x] + ((left + prev[x]) >> 1)) & 255
        elif f == 4:
            for x in range(stride):
                a = line[x - bpp] if x >= bpp else 0
                b = prev[x]
                c = prev[x - bpp] if x >= bpp else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if pa <= pb and pa <= pc else (b if pb <= pc else c)
                line[x] = (line[x] + pr) & 255
        rows.append(line)
        prev = line
    return np.array(rows, dtype=np.int32).reshape(h, w, bpp)


TILES = {}
Z = 12


def elevation(lat, lon):
    n = 2 ** Z
    fx = (lon + 180) / 360 * n
    fy = (1 - math.log(math.tan(math.radians(lat)) + 1 / math.cos(math.radians(lat))) / math.pi) / 2 * n
    tx, ty = int(fx), int(fy)
    if (tx, ty) not in TILES:
        data = fetch('고도 타일', f'https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{Z}/{tx}/{ty}.png', raw=True)
        TILES[(tx, ty)] = png_pixels(data) if data else None
    t = TILES[(tx, ty)]
    if t is None:
        return 0.0
    px, py = min(255, int((fx - tx) * 256)), min(255, int((fy - ty) * 256))
    r, g, b = t[py, px, :3]
    return float(r * 256 + g + b / 256 - 32768)


# 지도 데이터 ---------------------------------------------------------------------
class Osm:
    pass


def load_extract(rid):
    # tools/osm_extract.py가 뽑아 둔 지역 데이터를 격자에 올린다
    path = os.path.join(CACHE, f'osm_{rid}.json')
    if not os.path.exists(path):
        raise SystemExit(f'{path} 가 없다. 먼저 python tools/osm_extract.py 를 돌려라')
    d = json.load(open(path, encoding='utf-8'))
    osm = Osm()
    osm.sig_grid = Grid(100.0)
    for lat, lon in d['signals']:
        x, y = xy(lat, lon)
        osm.sig_grid.add_line([(x, y), (x + 0.01, y)])
    osm.tunnel, osm.bridge, osm.coast, osm.lake, osm.river = Grid(), Grid(), Grid(), Grid(), Grid()
    for kind, grid in (('tunnel', osm.tunnel), ('bridge', osm.bridge), ('coast', osm.coast), ('lake', osm.lake), ('river', osm.river)):
        for name, pts in d[kind]:
            p = [xy(a, c) for a, c in pts]
            if len(p) < 2:
                continue
            # 고가도로는 빼고 이름이 다리인 것만 장면 후보로 둔다
            if kind == 'bridge' and not (re.search(r'(대교|교|다리)$', name) and line_len(p) >= 100):
                continue
            grid.add_line(p, name)
    places = [(xy(la, lo), kind, name) for la, lo, kind, name in d['places']]
    return osm, places, d['found']


def route(a, b):
    url = (f'https://router.project-osrm.org/route/v1/driving/{a["lon"]},{a["lat"]};{b["lon"]},{b["lat"]}'
           '?overview=full&geometries=geojson&steps=true')
    r = fetch(f'route {a["name"]}→{b["name"]}', url, wait=1.0)
    if r is None or not r.get('routes'):
        return None
    r = r['routes'][0]
    ll = [(lat, lon) for lon, lat in r['geometry']['coordinates']]
    steps, s = [], 0.0
    for st in r['legs'][0]['steps']:
        steps.append((s, s + st['distance'], st.get('name', '')))
        s += st['distance']
    return ll, r['distance'], r['duration'], steps


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


def runs(mask, ss, min_len, gap=120.0):
    spans, start = [], None
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
    h = [math.atan2(b[1] - a[1], b[0] - a[0]) for a, b in zip(samples, samples[1:])]
    turn = [abs(math.degrees((b - a + math.pi) % (2 * math.pi) - math.pi)) for a, b in zip(h, h[1:])]
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


def clean(name):
    name = re.sub(r'\s*\(.*?\)', '', name).strip()
    for a, b in NAME_FIX.items():
        name = name.replace(a, b)
    return '' if re.search(r'\d+번?길$|\d+번길', name) else name


def nearest_place(places, x, y, r=2500.0):
    best = None
    for (px, py), kind, name in places:
        d = math.hypot(px - x, py - y)
        if d <= r:
            score = d + (0 if kind == 'pass' else 800)
            if best is None or score < best[0]:
                best = (score, kind, name)
    return best


def name_at(steps, s):
    for a, b, n in steps:
        if a <= s <= b + 1e-6 and n:
            return n
    return ''


def build_edge(a, b, ids, osm, places):
    r = route(a, b)
    if r is None:
        return None
    ll, dist, dur, steps = r
    pts = [xy(la, lo) for la, lo in ll]
    sm, total = resample(pts, STEP)
    xs = [(p[0], p[1]) for p in sm]
    ss = [p[2] for p in sm]
    n = len(sm)

    tun = [osm.tunnel.near(x, y, TUNNEL_NEAR) for x, y in xs]
    brd = [osm.bridge.near(x, y, TUNNEL_NEAR) for x, y in xs]
    cst = [osm.coast.near(x, y, COAST_NEAR) for x, y in xs]
    lak = [osm.lake.near(x, y, LAKE_NEAR) for x, y in xs]
    riv = [osm.river.near(x, y, RIVER_NEAR) for x, y in xs]
    tun_m = [t is not None for t in tun]
    brd_m = [brd[i] is not None and not tun_m[i] for i in range(n)]
    cst_m = [c is not None and not tun_m[i] for i, c in enumerate(cst)]
    lak_m = [l is not None and not tun_m[i] for i, l in enumerate(lak)]
    riv_m = [v is not None and not tun_m[i] for i, v in enumerate(riv)]

    sig = [s_ for (x, y), s_ in zip(xs, ss) if osm.sig_grid.near(x, y, SIGNAL_NEAR)]

    ele_pts = sm[::int(ELE_STEP / STEP)]
    if ele_pts[-1][2] < total - 1:
        ele_pts.append((pts[-1][0], pts[-1][1], total))
    zraw = []
    for x, y, _ in ele_pts:
        la, lo = unxy(x, y)
        zraw.append(elevation(la, lo))
    z = [sum(zraw[max(0, i - 1):i + 2]) / len(zraw[max(0, i - 1):i + 2]) for i in range(len(zraw))]
    zs = [p[2] for p in ele_pts]
    climb = int(round(z[-1] - z[0]))

    feats = []

    def idx(s0, s1):
        return bisect.bisect_left(ss, s0), bisect.bisect_right(ss, s1)

    def name_of(hits):
        names = [h[1] for h in hits if h and h[1]]
        return max(set(names), key=names.count) if names else ''

    def both(kind, s0, s1, nm, ln):
        feats.append(dict(at=s0, kind=kind, name=nm, line=ln, len=0, one='fwd'))
        feats.append(dict(at=s1, kind=kind, name=nm, line=ln, len=0, one='rev'))

    names = {clean(n_) for _, _, n_ in steps if clean(n_)}
    road = max(names, key=lambda n_: sum(b2 - a2 for a2, b2, nn in steps if clean(nn) == n_)) if names else ''

    for s0, s1 in runs(tun_m, ss, MIN_TUNNEL, gap=80.0):
        i0, i1 = idx(s0, s1)
        tn = name_of(tun[i0:i1])
        for fwd in (True, False):
            j0, j1 = idx(s1, s1 + 600) if fwd else idx(s0 - 600, s0)
            if any(cst_m[j0:j1]):
                nm, ln = '터널 끝, 바다', '이 터널 지나면 바다야.'
            elif any(riv_m[j0:j1]):
                nm, ln = '터널 끝, 강', '터널 나가면 강이 보여.'
            elif any(lak_m[j0:j1]):
                nm, ln = '터널 끝, 호수', '터널 나가면 호수가 보여.'
            else:
                nm, ln = ((tn + ' 출구') if tn else '터널 출구'), '곧 터널 끝이야. 나가면 분위기가 바뀐다.'
            feats.append(dict(at=s1, kind='TUNNEL_EXIT', name=nm, line=ln, len=s1 - s0, one='fwd' if fwd else 'rev'))
    for s0, s1 in runs(cst_m, ss, MIN_SEA, gap=200.0):
        r_ = clean(name_at(steps, s0)) or road
        both('SEA', s0, s1, (r_ + ' 해안도로') if r_ else '해안도로', '바다 바로 옆으로 붙는다.')
    for s0, s1 in runs(lak_m, ss, MIN_LAKE, gap=200.0):
        i0, i1 = idx(s0, s1)
        ln_ = name_of(lak[i0:i1])
        both('LAKE', s0, s1, (ln_ + ' 호숫가') if ln_ else '호숫가', '호수 옆으로 바짝 붙는다.')
    for s0, s1 in runs(riv_m, ss, MIN_RIVER, gap=250.0):
        i0, i1 = idx(s0, s1)
        rn = name_of(riv[i0:i1])
        both('RIVER', s0, s1, (rn + ' 강변') if rn else '강변', '강물 바로 옆으로 달린다.')
    for s0, s1 in runs(brd_m, ss, MIN_BRIDGE, gap=60.0):
        i0, i1 = idx(s0, s1)
        bn = clean(name_of(brd[i0:i1]))
        if bn and not bn.endswith(('교', '다리')):
            bn += ' 다리 위'
        both('BRIDGE', s0, s1, bn or '다리 위', '다리 건널 때 양쪽 다 봐.')

    summit = None
    for k in range(1, len(z) - 1):
        lo = max(0, k - 10)
        hi = min(len(z), k + 11)
        if z[k] >= max(z[lo:hi]) and min(z[k] - min(z[:k + 1]), z[k] - min(z[k:])) >= MIN_PROMINENCE:
            summit = k
            break

    def pass_name(k):
        p = nearest_place(places, ele_pts[k][0], ele_pts[k][1])
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
    if share(riv_m) >= 0.25:
        tags.append('RIVER')
    if abs(climb) >= 150 or ncurve / (L / 1000) >= 1.2:
        tags.append('MOUNTAIN')
    if nsig / (L / 1000) >= 0.8:
        tags.append('CITY')
    if not tags:
        tags.append('MOUNTAIN' if nsig / (L / 1000) < 0.3 else 'CITY')

    # 지도에 그리고 장면 좌표를 낼 도로 모양. 골목으로 갔다 돌아오는 작은 고리와 8m 안쪽 굴곡은 버린다
    g = encode(simplify(despur(ll, xy), 8.0, xy))
    return dict(a=ids[a['id']], b=ids[b['id']], road=road, km=round(L / 1000, 3), kmh=float(kmh), signals=nsig, curves=ncurve,
                climb=climb, tags=tags, feats=feats, sea=round(share(cst_m), 2), lake=round(share(lak_m), 2), river=round(share(riv_m), 2), g=g)


# 지점과 연결 -------------------------------------------------------------------
def resolve_nodes(cfg, found):
    out = []
    for n in cfg['nodes']:
        lat, lon = n['lat'], n['lon']
        if n.get('find'):
            best = None
            for la, lo in found.get(n['find'], []):
                d = hav((lat, lon), (la, lo))
                if d <= 3.0 and (best is None or d < best[0]):
                    best = (d, la, lo)
            if best:
                print(f'  {n["name"]}: 지도에서 찾음 ({best[0] * 1000:.0f}m 차이)')
                lat, lon = round(best[1], 5), round(best[2], 5)
            else:
                print(f'  {n["name"]}: 지도에서 못 찾아 기본 좌표 사용')
        out.append(dict(id=n['id'], name=n['name'], lat=lat, lon=lon))
    return out


def make_links(cfg, nodes):
    spec = cfg['links']
    if 'explicit' in spec:
        return [tuple(x) for x in spec['explicit']]
    k, cap, hard = spec['knn'], spec['maxKm'], spec.get('hardMaxKm', spec['maxKm'])
    links = set()
    for i, a in enumerate(nodes):
        d = sorted(((hav((a['lat'], a['lon']), (b['lat'], b['lon'])), j) for j, b in enumerate(nodes) if j != i))
        chosen = [j for dist, j in d[:k] if dist <= cap]
        if len(chosen) < 2:
            chosen = [j for dist, j in d[:2] if dist <= hard]
        for j in chosen:
            links.add((min(i, j), max(i, j)))
    # 끊어진 덩어리가 있으면 가장 가까운 쌍으로 잇는다
    while True:
        comp = list(range(len(nodes)))

        def find(x):
            while comp[x] != x:
                comp[x] = comp[comp[x]]
                x = comp[x]
            return x
        for a, b in links:
            comp[find(a)] = find(b)
        if len({find(i) for i in range(len(nodes))}) == 1:
            break
        best = None
        for i in range(len(nodes)):
            for j in range(i + 1, len(nodes)):
                if find(i) != find(j):
                    d = hav((nodes[i]['lat'], nodes[i]['lon']), (nodes[j]['lat'], nodes[j]['lon']))
                    if best is None or d < best[0]:
                        best = (d, i, j)
        links.add((best[1], best[2]))
    return [(nodes[a]['id'], nodes[b]['id']) for a, b in sorted(links)]


def bbox_of(nodes):
    la = [n[0] for n in nodes]
    lo = [n[1] for n in nodes]
    return [min(la) - 0.2, min(lo) - 0.2, max(la) + 0.2, max(lo) + 0.2]


def build_region(path):
    cfg = json.load(open(path, encoding='utf-8'))
    print(f'== {cfg["name"]} ({cfg["id"]})')
    raw = json.load(open(os.path.join(CACHE, f'osm_{cfg["id"]}.json'), encoding='utf-8'))
    nodes = resolve_nodes(cfg, raw['found'])
    set_origin(sum(n['lat'] for n in nodes) / len(nodes), sum(n['lon'] for n in nodes) / len(nodes))
    osm, places, _ = load_extract(cfg['id'])
    ids = {n['id']: f'{cfg["prefix"]}_{n["id"]}' for n in nodes}
    byid = {n['id']: n for n in nodes}
    links = make_links(cfg, nodes)
    print(f'지점 {len(nodes)}곳, 연결 {len(links)}개')
    edges = []
    for a, b in links:
        t0 = time.time()
        e = build_edge(byid[a], byid[b], ids, osm, places)
        if e is None:
            print(f'  {byid[a]["name"]} → {byid[b]["name"]}: 경로 없음, 건너뜀')
            continue
        straight = hav((byid[a]['lat'], byid[a]['lon']), (byid[b]['lat'], byid[b]['lon']))
        if e['km'] > straight * 3 + 2:
            print(f'  {byid[a]["name"]} → {byid[b]["name"]}: 너무 돌아가서({e["km"]}km) 건너뜀')
            continue
        edges.append(e)
        print(f'{byid[a]["name"]} → {byid[b]["name"]}  {e["road"] or "-"} {e["km"]}km {e["kmh"]}km/h 신호{e["signals"]} 굽이{e["curves"]} 고도{e["climb"]:+d}m {e["tags"]}'
              f' 장면 {sorted({f["kind"] for f in e["feats"]})} ({time.time() - t0:.0f}s)')
    out = dict(region=cfg['id'], name=cfg['name'],
               nodes=[[ids[n['id']], n['name'], n['lat'], n['lon']] for n in nodes],
               edges=[dict(a=e['a'], b=e['b'], road=e['road'], km=e['km'], kmh=e['kmh'], sig=e['signals'], cur=e['curves'], climb=e['climb'],
                           tags=e['tags'], sea=e['sea'], lake=e['lake'], river=e['river'], g=e['g'],
                           f=[[round(f['at'] / 1000, 3), f['kind'], f['name'], f['line'], round(f['len'] / 1000, 3),
                               {'fwd': 'f', 'rev': 'r'}.get(f['one'], '')] for f in sorted(e['feats'], key=lambda f: f['at'])]) for e in edges])
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, cfg['id'] + '.json'), 'w', encoding='utf-8') as f:
        json.dump(out, f, ensure_ascii=False, separators=(',', ':'))
    return dict(id=cfg['id'], name=cfg['name'], file=cfg['id'] + '.json', bbox=bbox_of([(n['lat'], n['lon']) for n in nodes]))


def main():
    only = set(sys.argv[1:])
    index = []
    for p in sorted(glob.glob(os.path.join(HERE, 'regions', '*.json'))):
        rid = os.path.splitext(os.path.basename(p))[0]
        done = os.path.join(OUT, rid + '.json')
        if only and rid not in only:
            if os.path.exists(done):  # 이미 만든 지역은 색인에만 넣는다
                d = json.load(open(done, encoding='utf-8'))
                index.append(dict(id=rid, name=d['name'], file=rid + '.json', bbox=bbox_of([(n[2], n[3]) for n in d['nodes']])))
            continue
        index.append(build_region(p))
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, 'index.json'), 'w', encoding='utf-8') as f:
        json.dump(index, f, ensure_ascii=False, separators=(',', ':'))
    print('저장:', os.path.normpath(OUT))


if __name__ == '__main__':
    main()
