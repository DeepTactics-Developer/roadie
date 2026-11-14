# 좌표 줄이기와 압축. build_roads.py와 basemap.py가 같이 쓴다
import math


def simplify(pts, tol, xy):
    # 더글러스-포커. pts는 (lat, lon), tol은 m. xy는 (lat, lon) -> (x, y) m 변환
    if len(pts) < 3:
        return list(pts)
    q = [xy(*p) for p in pts]
    keep = [False] * len(pts)
    keep[0] = keep[-1] = True
    stack = [(0, len(pts) - 1)]
    while stack:
        i, j = stack.pop()
        (x1, y1), (x2, y2) = q[i], q[j]
        dx, dy = x2 - x1, y2 - y1
        L = math.hypot(dx, dy)
        best, at = 0.0, -1
        for k in range(i + 1, j):
            # 닫힌 고리는 시작과 끝이 같아서 선분이 아니라 점까지 거리로 잰다
            d = abs(dy * (q[k][0] - x1) - dx * (q[k][1] - y1)) / L if L > 1e-6 else math.hypot(q[k][0] - x1, q[k][1] - y1)
            if d > best:
                best, at = d, k
        if best > tol:
            keep[at] = True
            stack += [(i, at), (at, j)]
    return [p for p, k in zip(pts, keep) if k]


def despur(pts, xy, near=20.0, span=600.0):
    # 길 끝에서 골목으로 들어갔다 되돌아 나오는 것 같은 작은 고리를 잘라 낸다(지도에서 지그재그로 보임)
    q = [xy(*p) for p in pts]
    out_i = []
    i = 0
    n = len(pts)
    while i < n:
        out_i.append(i)
        cut = None
        run = 0.0
        for j in range(i + 1, n):
            run += math.hypot(q[j][0] - q[j - 1][0], q[j][1] - q[j - 1][1])
            if run > span:
                break
            if j - i >= 2 and math.hypot(q[j][0] - q[i][0], q[j][1] - q[i][1]) < near:
                cut = j
        i = cut if cut is not None else i + 1
    return [pts[k] for k in out_i]


def encode(pts):
    # 구글 폴리라인 형식(소수 5자리). 앱의 Polyline.decode가 푼다
    out, plat, plon = [], 0, 0
    for lat, lon in pts:
        a, b = int(round(lat * 1e5)), int(round(lon * 1e5))
        for v in (a - plat, b - plon):
            v = ~(v << 1) if v < 0 else v << 1
            while v >= 0x20:
                out.append(chr((0x20 | (v & 0x1f)) + 63))
                v >>= 5
            out.append(chr(v + 63))
        plat, plon = a, b
    return ''.join(out)
