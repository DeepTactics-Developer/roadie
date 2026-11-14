# 대한민국 전체 오픈스트리트맵 파일(Geofabrik)에서 도로 분석에 필요한 것만 지역별로 뽑는다
# 사용법: python tools/osm_extract.py   (tools/regions의 모든 지역을 한 번에 훑는다)
# pyosmium가 필요하다: pip install osmium
# 결과는 tools/.cache/osm_<지역>.json. build_roads.py가 이걸 읽는다.
#
# 공개 서버(Overpass)는 부하와 호출 제한이 있어서 전국으로 늘릴 수 없다.
# 파일을 한 번 받아 두면 지역을 늘려도 서버를 다시 부르지 않는다.
import glob
import json
import os
import re
import sys
import time
import urllib.request

import osmium

sys.stdout.reconfigure(encoding='utf-8')
HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, '.cache')
PBF = os.path.join(CACHE, 'south-korea.osm.pbf')
URL = 'https://download.geofabrik.de/asia/south-korea-latest.osm.pbf'
LAKE = re.compile('호$|호수$|저수지$|호지$')
MARGIN = 0.25  # 지점들 바깥으로 이만큼(도) 더 뽑는다


def download():
    if os.path.exists(PBF):
        return
    os.makedirs(CACHE, exist_ok=True)
    print('지도 파일 받는 중(약 300MB)')
    urllib.request.urlretrieve(URL, PBF)


def region_specs():
    out = {}
    for p in sorted(glob.glob(os.path.join(HERE, 'regions', '*.json'))):
        cfg = json.load(open(p, encoding='utf-8'))
        la = [n['lat'] for n in cfg['nodes']]
        lo = [n['lon'] for n in cfg['nodes']]
        out[cfg['id']] = dict(
            bbox=(min(la) - MARGIN, min(lo) - MARGIN, max(la) + MARGIN, max(lo) + MARGIN),
            names={n['find'] for n in cfg['nodes'] if n.get('find')},
        )
    return out


def inside(b, lat, lon):
    return b[0] <= lat <= b[2] and b[1] <= lon <= b[3]


def extract(specs):
    t0 = time.time()
    # 1) 호수가 여러 길로 둘러싸인 경우(경포호 같은)는 관계에 이름이 있고 길에는 이름이 없다
    rel_ways = {}
    for o in osmium.FileProcessor(PBF, osmium.osm.osm_entity_bits.RELATION):
        t = o.tags
        name = t.get('name')
        if name and t.get('natural') == 'water' and LAKE.search(name):
            for m in o.members:
                if m.type == 'w' and m.role == 'outer':
                    rel_ways[m.ref] = name
    print(f'  호수 관계 {len(rel_ways)}개 ({time.time() - t0:.0f}s)')

    out = {rid: dict(signals=[], tunnel=[], bridge=[], coast=[], lake=[], river=[], places=[], found={n: [] for n in s['names']}) for rid, s in specs.items()}
    n_nodes = n_ways = 0
    for o in osmium.FileProcessor(PBF, osmium.osm.osm_entity_bits.NODE | osmium.osm.osm_entity_bits.WAY).with_locations():
        if o.is_node():
            t = o.tags
            if len(t) == 0:
                continue
            n_nodes += 1
            loc = o.location
            if not loc.valid():
                continue
            lat, lon = loc.lat, loc.lon
            name = t.get('name')
            kind = None
            if t.get('highway') == 'traffic_signals':
                kind = 'signals'
            elif name and (t.get('mountain_pass') == 'yes' or t.get('natural') in ('saddle', 'peak')):
                kind = 'places'
            for rid, s in specs.items():
                if not inside(s['bbox'], lat, lon):
                    continue
                if kind == 'signals':
                    out[rid]['signals'].append([round(lat, 6), round(lon, 6)])
                elif kind == 'places':
                    pk = 'pass' if t.get('mountain_pass') == 'yes' or t.get('natural') == 'saddle' else 'peak'
                    out[rid]['places'].append([round(lat, 6), round(lon, 6), pk, name])
                if name in out[rid]['found']:
                    out[rid]['found'][name].append([round(lat, 6), round(lon, 6)])
            continue
        # 길
        wid = o.id
        t = o.tags
        lake_name = rel_ways.get(wid)
        if len(t) == 0 and lake_name is None:
            continue
        n_ways += 1
        hw, tun, br, nat, ww = t.get('highway'), t.get('tunnel'), t.get('bridge'), t.get('natural'), t.get('waterway')
        name = t.get('name') or lake_name or ''
        kind = None
        if hw and tun in ('yes', 'building_passage'):
            kind = 'tunnel'
        elif hw and br not in (None, 'no') and name:
            kind = 'bridge'
        elif nat == 'coastline':
            kind = 'coast'
        elif ww == 'river' and name.endswith('강'):
            kind = 'river'
        elif (nat == 'water' and LAKE.search(name)) or lake_name:
            kind = 'lake'
        want_name = [rid for rid, s in specs.items() if name in s['names']]
        if kind is None and not want_name:
            continue
        pts = [(n.lat, n.lon) for n in o.nodes if n.location.valid()]
        if not pts:
            continue
        la = [p[0] for p in pts]
        lo = [p[1] for p in pts]
        for rid, s in specs.items():
            b = s['bbox']
            if max(la) < b[0] or min(la) > b[2] or max(lo) < b[1] or min(lo) > b[3]:
                continue
            if kind:
                out[rid][kind].append([name, [[round(a, 6), round(c, 6)] for a, c in pts]])
            if name in out[rid]['found']:
                out[rid]['found'][name].append([round(sum(la) / len(la), 6), round(sum(lo) / len(lo), 6)])
    print(f'  이름 있는 점 {n_nodes}, 길 {n_ways} ({time.time() - t0:.0f}s)')
    return out


def main():
    download()
    specs = region_specs()
    only = set(sys.argv[1:])
    if only:
        specs = {k: v for k, v in specs.items() if k in only}
    res = extract(specs)
    for rid, d in res.items():
        path = os.path.join(CACHE, f'osm_{rid}.json')
        with open(path, 'w', encoding='utf-8') as f:
            json.dump(d, f, ensure_ascii=False, separators=(',', ':'))
        print(f'{rid}: 신호 {len(d["signals"])}, 터널 {len(d["tunnel"])}, 다리 {len(d["bridge"])}, 해안선 {len(d["coast"])}, 호수 {len(d["lake"])}, 강 {len(d["river"])}, 고개/봉우리 {len(d["places"])}')


if __name__ == '__main__':
    main()
