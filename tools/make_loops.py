# 드라이브 OST 음원 생성
# 사용법: python tools/make_loops.py [출력 폴더]
# numpy만 있으면 된다. 기본 출력은 assets_src/music
#
# 곡을 파트로 나눠 한 줄로 이어 붙인다. 스템 6개가 모두 같은 길이, 같은 마디 위치.
# 앱은 마디가 끝날 때마다 다음에 틀 마디를 골라서 파트를 재배열한다.
# 파트 위치는 앱의 Arranger.PARTS와 같아야 한다.
import os
import sys
import wave

import numpy as np

SR = 22050
BPM = 96
BEAT = 60.0 / BPM
BAR = int(round(BEAT * 4 * SR))  # 55125 샘플 = 2.5초
rng = np.random.default_rng(7)

# (이름, 마디 수, 반복 가능)
PARTS = [
    ('idle', 4, True),
    ('cruise_a', 8, True),
    ('cruise_b', 8, True),
    ('build_1', 2, True),
    ('build_2', 2, True),
    ('pre', 2, False),
    ('drop', 8, False),
    ('after', 4, False),
]
STEMS = ['pad', 'bass', 'arp', 'lead', 'drums', 'fx']

AM, F, C, G = [57, 60, 64], [53, 57, 60], [60, 64, 67], [55, 59, 62]
ROOT = {id(AM): 45, id(F): 41, id(C): 48, id(G): 43}
PROG = [AM, F, C, G]


def hz(m):
    return 440.0 * 2 ** ((m - 69) / 12)


def adsr(n, a, d, s, r):
    na, nd, nr = int(a * SR), int(d * SR), int(r * SR)
    ns = max(n - na - nd - nr, 0)
    e = np.concatenate([
        np.linspace(0, 1, na, endpoint=False),
        np.linspace(1, s, nd, endpoint=False),
        np.full(ns, s),
        np.linspace(s, 0, nr),
    ])
    if len(e) < n:
        e = np.pad(e, (0, n - len(e)))
    return e[:n]


def tone(f, sec, harm=(1.0, 0.4, 0.2), detune=0.0, vib=0.0):
    t = np.arange(int(sec * SR)) / SR
    wob = vib * np.sin(2 * np.pi * 5.5 * t) if vib else 0.0
    y = np.zeros_like(t)
    for k, g in enumerate(harm, start=1):
        y += g * np.sin(2 * np.pi * f * k * t + wob)
    if detune:
        y2 = np.zeros_like(t)
        for k, g in enumerate(harm, start=1):
            y2 += g * np.sin(2 * np.pi * f * (1 + detune) * k * t + 0.5)
        y = (y + y2) / 2
    return y


def pluck(f, sec, decay=7):
    t = np.arange(int(sec * SR)) / SR
    y = np.sin(2 * np.pi * f * t) + 0.3 * np.sin(2 * np.pi * f * 2 * t)
    return y * np.exp(-t * decay) * np.minimum(t / 0.004, 1)


def kick():
    t = np.arange(int(0.3 * SR)) / SR
    f = 45 + 90 * np.exp(-t * 30)
    return np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 12)


def snare():
    t = np.arange(int(0.2 * SR)) / SR
    noise = np.diff(rng.standard_normal(len(t) + 1))
    return (noise * 0.5 + 0.4 * np.sin(2 * np.pi * 190 * t)) * np.exp(-t * 25)


def hat(level):
    t = np.arange(int(0.06 * SR)) / SR
    return np.diff(rng.standard_normal(len(t) + 1)) * np.exp(-t * 60) * level


def crash():
    t = np.arange(int(2.0 * SR)) / SR
    return np.diff(rng.standard_normal(len(t) + 1)) * np.exp(-t * 2.5) * 0.35


def lowpass(x, cutoff):
    X = np.fft.rfft(x)
    f = np.fft.rfftfreq(len(x), 1 / SR)
    return np.fft.irfft(X / (1 + (f / cutoff) ** 4), len(x))


def reverb(x, mix=0.3, decay=1.6):
    n = len(x)
    t = np.arange(int(1.8 * SR)) / SR
    imp = rng.standard_normal(len(t)) * np.exp(-t * 6.9 / decay)
    imp /= np.sqrt(np.sum(imp ** 2))
    wet = np.fft.irfft(np.fft.rfft(x) * np.fft.rfft(imp, n), n)
    return (1 - mix) * x + mix * wet


class Part:
    # 파트 하나를 스템별로 그린다. 반복 파트는 꼬리를 앞으로 감고, 아니면 잘라낸다
    def __init__(self, bars, loop):
        self.n = bars * BAR
        self.loop = loop
        self.s = {k: np.zeros(self.n) for k in STEMS}

    def add(self, stem, at, sig):
        at = int(at)
        buf = self.s[stem]
        end = at + len(sig)
        if end <= self.n:
            buf[at:end] += sig
        elif self.loop:
            cut = self.n - at
            buf[at:] += sig[:cut]
            buf[:end - self.n] += sig[cut:]
        else:
            cut = self.n - at
            fade = np.linspace(1, 0, min(cut, 512))
            seg = sig[:cut].copy()
            seg[-len(fade):] *= fade
            buf[at:] += seg

    def finish(self):
        # 리버브는 파트 안에서만. 반복 파트는 fft라 이음새도 그대로 이어진다
        s = self.s
        s['pad'] = reverb(lowpass(s['pad'], 1500), 0.35, 2.0)
        s['bass'] = lowpass(s['bass'], 400)
        s['arp'] = reverb(lowpass(s['arp'], 3200), 0.3, 1.4)
        s['lead'] = reverb(lowpass(s['lead'], 3500), 0.4, 2.2)
        return s


def b(bar, beat=0.0):
    return int((bar * 4 + beat) * BEAT * SR)


def pad(p, bar, chord, bars, level=0.5, attack=1.0):
    sec = BEAT * 4 * bars + 1.0
    for m in chord:
        sig = tone(hz(m), sec, harm=(1, 0.5, 0.3, 0.15), detune=0.004)
        p.add('pad', b(bar), sig * adsr(len(sig), attack, 0.5, 0.8, 1.0) * level)


def bass_notes(p, bar, root, pattern, level=1.0):
    # pattern: (박, 길이 박)
    for beat, ln in pattern:
        sig = tone(hz(root), BEAT * ln, harm=(1, 0.35, 0.12))
        p.add('bass', b(bar, beat), sig * adsr(len(sig), 0.01, 0.1, 0.7, 0.08) * level)


def arp(p, bar, chord, steps, level=1.0, octave=12):
    order = [0, 1, 2, 1]
    per = 4 / steps
    for i in range(steps):
        m = chord[order[i % 4]] + octave
        p.add('arp', b(bar, i * per), pluck(hz(m), BEAT * 1.2) * level)


def groove(p, bar, hats=8, kicks=(0, 2), snares=(1, 3), level=1.0):
    for k in kicks:
        p.add('drums', b(bar, k), kick() * level)
    for s in snares:
        p.add('drums', b(bar, s), snare() * 0.8 * level)
    for e in range(hats):
        p.add('drums', b(bar, e * 4 / hats), hat(0.3 if e % 2 else 0.18) * level)


HALF = [(0, 1.8), (2, 1.8)]
GROOVE = [(0, 0.9), (1.5, 0.45), (2, 0.9), (3, 0.45)]
EIGHTHS = [(i * 0.5, 0.45) for i in range(8)]
SIXTEENTHS = [(i * 0.25, 0.22) for i in range(16)]


def make_idle():
    p = Part(4, True)
    pad(p, 0, AM, 2, 0.35, 1.5)
    pad(p, 2, F, 2, 0.35, 1.5)
    return p.finish()


def make_cruise(busy):
    p = Part(8, True)
    for i, ch in enumerate(PROG):
        bar = i * 2
        pad(p, bar, ch, 2)
        for k in range(2):
            bass_notes(p, bar + k, ROOT[id(ch)], GROOVE if busy else HALF)
            arp(p, bar + k, ch, 8, 1.0 if busy else 0.5)
            if busy:
                groove(p, bar + k, kicks=(0, 2, 3.5) if k else (0, 2))
            else:
                groove(p, bar + k, hats=4, kicks=(0,), snares=(), level=0.6)
    return p.finish()


def make_build(level):
    # 같은 2마디를 반복해도 어색하지 않게 F-G만 오간다
    p = Part(2, True)
    for k, ch in enumerate([F, G]):
        pad(p, k, ch, 1, 0.45, 0.3)
        bass_notes(p, k, ROOT[id(ch)], EIGHTHS if level == 1 else SIXTEENTHS, 0.9)
        arp(p, k, ch, 16, 0.8 if level == 1 else 1.0, 12 if level == 1 else 24)
        groove(p, k, hats=16, kicks=(0, 1, 2, 3), snares=() if level == 1 else tuple(i * 0.5 for i in range(8)))
    return p.finish()


def make_pre():
    # 드롭 직전 숨 고르기. 드럼은 롤로 몰아가다 마지막 반 박은 비운다
    p = Part(2, False)
    pad(p, 0, G, 2, 0.6, 2.0)
    for i in range(16):
        p.add('drums', b(0, i * 0.25), snare() * (0.2 + 0.4 * i / 16))
    for i in range(28):
        p.add('drums', b(1, i * 0.125), snare() * (0.6 + 0.4 * i / 28))
    n = 2 * BAR
    t = np.arange(n) / SR
    sec = n / SR
    noise = rng.standard_normal(n)
    X = np.fft.rfft(noise)
    f = np.fft.rfftfreq(n, 1 / SR)
    X *= ((f > 800) & (f < 7000)).astype(float)
    noise = np.fft.irfft(X, n)
    noise /= np.max(np.abs(noise))
    sweep = np.sin(2 * np.pi * np.cumsum(200 + 1400 * (t / sec) ** 2) / SR)
    riser = (noise * 0.7 + sweep * 0.5) * (t / sec) ** 2
    riser[-int(0.1 * SR):] *= np.linspace(1, 0, int(0.1 * SR))
    p.add('fx', 0, riser)
    return p.finish()


def make_drop():
    p = Part(8, False)
    lead = [
        (0, 76, 3), (3, 74, 1), (4, 72, 2), (6, 69, 2),
        (8, 72, 3), (11, 69, 1), (12, 74, 3), (15, 72, 1),
        (16, 76, 3), (19, 79, 1), (20, 76, 2), (22, 74, 2),
        (24, 74, 3), (27, 72, 1), (28, 69, 3), (31, 67, 1),
    ]
    for i, ch in enumerate(PROG):
        bar = i * 2
        pad(p, bar, ch, 2, 0.55, 0.05)
        for k in range(2):
            bass_notes(p, bar + k, ROOT[id(ch)], GROOVE)
            bass_notes(p, bar + k, ROOT[id(ch)] + 12, [(0.5, 0.4), (2.5, 0.4)], 0.5)
            arp(p, bar + k, ch, 8)
            groove(p, bar + k, kicks=(0, 1, 2, 3), snares=(1, 3), hats=8)
    for bar in (0, 4):
        p.add('drums', b(bar), crash())
    for beat, m, ln in lead:
        sig = tone(hz(m), BEAT * ln + 0.3, harm=(1, 0.3, 0.1), vib=0.35)
        p.add('lead', b(0, beat), sig * adsr(len(sig), 0.03, 0.15, 0.7, 0.3))
    # 진입 타격음
    t = np.arange(int(2.5 * SR)) / SR
    boom = np.sin(2 * np.pi * np.cumsum(38 + 90 * np.exp(-t * 14)) / SR) * np.exp(-t * 2.2)
    hit = np.diff(rng.standard_normal(len(t) + 1)) * np.exp(-t * 2.8) * 0.5
    p.add('fx', 0, reverb(boom + hit, 0.3, 1.2))
    return p.finish()


def make_after():
    # 여운. 점점 비워서 순항으로 돌아간다
    p = Part(4, False)
    pad(p, 0, AM, 2, 0.5, 0.2)
    pad(p, 2, F, 2, 0.4, 0.5)
    bass_notes(p, 0, 45, [(0, 7.5)], 0.8)
    bass_notes(p, 2, 41, [(0, 7.5)], 0.6)
    sig = tone(hz(76), BEAT * 6, harm=(1, 0.3, 0.1), vib=0.35)
    p.add('lead', 0, sig * adsr(len(sig), 0.05, 0.3, 0.5, 2.0) * 0.8)
    for bar in range(4):
        arp(p, bar, AM if bar < 2 else F, 4, 0.6 - bar * 0.12)
        groove(p, bar, hats=8, kicks=(0,) if bar < 2 else (), snares=(), level=1 - bar * 0.22)
    return p.finish()


MAKERS = {
    'idle': make_idle,
    'cruise_a': lambda: make_cruise(False),
    'cruise_b': lambda: make_cruise(True),
    'build_1': lambda: make_build(1),
    'build_2': lambda: make_build(2),
    'pre': make_pre,
    'drop': make_drop,
    'after': make_after,
}
LEVEL = {'pad': 0.30, 'bass': 0.30, 'arp': 0.20, 'lead': 0.25, 'drums': 0.35, 'fx': 0.55}


def save(path, x):
    pcm = (np.clip(x, -1, 1) * 32767).astype('<i2')
    with wave.open(path, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def main():
    base = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(base, 'assets_src', 'music')
    os.makedirs(out, exist_ok=True)
    rendered = [MAKERS[name]() for name, _, _ in PARTS]
    bar = 0
    for name, bars, loop in PARTS:
        print(f'{name:9s} {bar:3d}마디부터 {bars}마디{" 반복" if loop else ""}')
        bar += bars
    for stem in STEMS:
        x = np.concatenate([r[stem] for r in rendered])
        x = x / np.max(np.abs(x)) * LEVEL[stem]
        save(os.path.join(out, stem + '.wav'), x)
    print(f'스템 {len(STEMS)}개, {bar}마디 {bar * BAR / SR:.1f}초')


if __name__ == '__main__':
    main()
