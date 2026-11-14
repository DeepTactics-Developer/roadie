# 앱 지도 화면의 배경(바다, 호수와 강, 녹지, 큰 도로)을 지역별로 뽑는다
# 사용법: python tools/basemap.py [지역 ...]   (osm_extract.py가 받아 둔 지도 파일을 쓴다)
# pyosmium, shapely가 필요하다: pip install osmium shapely
# 결과는 app/src/main/assets/roads/<지역>.map.json
#
# 지도 데이터 © OpenStreetMap contributors (ODbL). 앱 화면에 출처를 표시한다
import glob
import json
import math
import os
import sys
import time

import osmium
from shapely.geometry import LineString, Point, Polygon, box
from shapely.ops import linemerge, polygonize, unary_union
from shapely.strtree import STRtree

from geo import encode, simplify

sys.stdout.reconfigure(encoding='utf-8')
HERE = os.path.dirname(os.path.abspath(__file__))
PBF = os.path.join(HERE, '.cache', 'south-korea.osm.pbf')
OUT = os.path.join(HERE, '..', 'app', 'src', 'main', 'assets', 'roads')
MARGIN = 0.2  # index.json의 지역 범위와 같게

ROADS = {'motorway': 0, 'trunk': 0, 'primary': 1, 'secondary': 2, 'tertiary': 3}
TOL = {'road': 15.0, 'water': 20.0, 'green': 40.0, 'sea': 30.0, 'river': 20.0}
MIN_AREA = {'water': 20000.0, 'green': 150000.0}  # m², 이보다 작은 건 버린다


def specs():
    out = {}
    for p in sorted(glob.glob(os.path.join(HERE, 'regions', '*.json'))):
        cfg = json.load(open(p, encoding='utf-8'))
        la = [n['lat'] for n in cfg['nodes']]
        lo = [n['lon'] for n in cfg['nodes']]
        out[cfg['id']] = (min(la) - MARGIN, min(lo) - MARGIN, max(la) + MARGIN, max(lo) + MARGIN)
    return out


def make_xy(lat0):
    k = math.cos(math.radians(lat0)) * 111320.0
    return lambda lat, lon: (lon * k, lat * 110540.0)


def area_m2(ring, xy):
    q = [xy(*p) for p in ring]
    return abs(sum(x1 * y2 - x2 * y1 for (x1, y1), (x2, y2) in zip(q, q[1:] + q[:1]))) / 2


def kind_of(t):
    if t.get('natural') == 'water' or t.get('landuse') == 'reservoir' or t.get('waterway') == 'riverbank':
        return 'water'
    if t.get('leisure') == 'park' or t.get('landuse') == 'forest' or t.get('natural') == 'wood':
        return 'green'
    return None


def overlaps(b, pts):
    la = [p[0] for p in pts]
    lo = [p[1] for p in pts]
    return not (max(la) < b[0] or min(la) > b[2] or max(lo) < b[1] or min(lo) > b[3])


def collect(regions):
    out = {rid: dict(roads=[], water=[], green=[], river=[], coast=[]) for rid in regions}
    keys = osmium.filter.KeyFilter('highway', 'natural', 'waterway', 'landuse', 'leisure')
    fp = osmium.FileProcessor(PBF).with_locations().with_areas().with_filter(keys)
    t0 = time.time()
    for o in fp:
        t = o.tags
        if isinstance(o, osmium.osm.Way):
            hw = t.get('highway')
            if hw in ROADS:
                kind = 'roads'
            elif t.get('natural') == 'coastline':
                kind = 'coast'
            elif t.get('waterway') == 'river':
                kind = 'river'
            else:
                continue
            pts = [(n.lat, n.lon) for n in o.nodes if n.location.valid()]
            if len(pts) < 2:
                continue
            for rid, b in regions.items():
                if overlaps(b, pts):
                    out[rid][kind].append((ROADS.get(hw, 0), pts))
        elif isinstance(o, osmium.osm.Area):
            kind = kind_of(t)
            if kind is None:
                continue
            for outer in o.outer_rings():
                ring = [(n.lat, n.lon) for n in outer]
                if len(ring) < 4:
                    continue
                holes = [[(n.lat, n.lon) for n in inner] for inner in o.inner_rings(outer)]
                for rid, b in regions.items():
                    if overlaps(b, ring):
                        out[rid][kind].append((ring, holes))
    print(f'  훑기 {time.time() - t0:.0f}s')
    return out


def clip_poly(ring, holes, bb):
    p = Polygon([(lo, la) for la, lo in ring], [[(lo, la) for la, lo in h] for h in holes if len(h) >= 4])
    if not p.is_valid:
        p = p.buffer(0)
    p = p.intersection(bb)
    polys = [p] if p.geom_type == 'Polygon' else [g for g in getattr(p, 'geoms', []) if g.geom_type == 'Polygon']
    return [([(y, x) for x, y in g.exterior.coords], [[(y, x) for x, y in i.coords] for i in g.interiors]) for g in polys if not g.is_empty]


def clip_line(pts, bb):
    g = LineString([(lo, la) for la, lo in pts]).intersection(bb)
    lines = [g] if g.geom_type == 'LineString' else [x for x in getattr(g, 'geoms', []) if x.geom_type == 'LineString']
    return [[(y, x) for x, y in l.coords] for l in lines if not l.is_empty and len(l.coords) >= 2]


# 해안선은 선이라 바다를 칠할 수 없다. 범위 테두리와 이어 면으로 만든 뒤,
# 해안선 오른쪽(오픈스트리트맵 규칙: 바다가 오른쪽)에 있는 면을 바다로 고른다
def sea_polys(coast, bb):
    lines = []
    for _, pts in coast:
        lines += [LineString([(lo, la) for la, lo in l]) for l in clip_line(pts, bb)]
    if not lines:
        return []
    def merge(ls):
        m = linemerge(ls)
        return [m] if m.geom_type == 'LineString' else list(m.geoms)

    merged = merge(lines)
    # 해안선이 군데군데 끊겨 있으면(강화 북쪽 등) 바다가 닫히지 않는다. 범위 테두리에 닿지 않은 끝을 가까운 시작과 잇는다
    edge = bb.exterior
    ends = [l for l in merged if not l.is_closed and edge.distance(Point(l.coords[-1])) > 1e-6]
    starts = [l for l in merged if not l.is_closed and edge.distance(Point(l.coords[0])) > 1e-6]
    gaps = []
    for l in ends:
        e = Point(l.coords[-1])
        s = min(starts, key=lambda x: e.distance(Point(x.coords[0])), default=None)
        if s is not None and e.distance(Point(s.coords[0])) < 0.06:
            gaps.append(LineString([l.coords[-1], s.coords[0]]))
    if gaps:
        merged = merge(merged + gaps)
    tree = STRtree(merged)
    sea = []
    for poly in polygonize(unary_union(merged + [bb.exterior])):
        rp = poly.representative_point()
        line = merged[tree.nearest(rp)]
        d = line.project(rp)
        a = line.interpolate(max(0.0, d - 1e-5))
        b = line.interpolate(min(line.length, d + 1e-5))
        cross = (b.x - a.x) * (rp.y - a.y) - (b.y - a.y) * (rp.x - a.x)
        if cross < 0:
            sea.append(poly)
    return [([(y, x) for x, y in p.exterior.coords], [[(y, x) for x, y in i.coords] for i in p.interiors]) for p in sea]


def build(rid, b, d):
    bb = box(b[1], b[0], b[3], b[2])
    xy = make_xy((b[0] + b[2]) / 2)

    def poly_out(items, kind, min_area):
        res = []
        for ring, holes in items:
            if area_m2(ring, xy) < min_area:
                continue
            for r, hs in clip_poly(ring, holes, bb):
                r = simplify(r, TOL[kind], xy)
                if len(r) < 4:
                    continue
                res.append([encode(r)] + [encode(simplify(h, TOL[kind], xy)) for h in hs if area_m2(h, xy) >= min_area])
        return res

    roads = []
    for cls, pts in sorted(d['roads'], key=lambda r: -r[0]):
        for l in clip_line(pts, bb):
            roads.append([cls, encode(simplify(l, TOL['road'], xy))])
    rivers = [encode(simplify(l, TOL['river'], xy)) for _, pts in d['river'] for l in clip_line(pts, bb)]
    out = dict(
        bbox=list(b),
        sea=poly_out(sea_polys(d['coast'], bb), 'sea', 0.0),
        water=poly_out(d['water'], 'water', MIN_AREA['water']),
        green=poly_out(d['green'], 'green', MIN_AREA['green']),
        river=rivers,
        roads=roads,
    )
    path = os.path.join(OUT, rid + '.map.json')
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(out, f, separators=(',', ':'))
    print(f'{rid}: 바다 {len(out["sea"])}, 물 {len(out["water"])}, 녹지 {len(out["green"])}, 강 {len(rivers)}, 도로 {len(roads)} → {os.path.getsize(path) // 1024}KB')


def main():
    regions = specs()
    only = set(sys.argv[1:])
    if only:
        regions = {k: v for k, v in regions.items() if k in only}
    data = collect(regions)
    for rid, b in regions.items():
        build(rid, b, data[rid])


if __name__ == '__main__':
    main()
