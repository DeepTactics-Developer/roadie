# 드라이브 OST용 루프 음원 생성
# 사용법: python tools/make_loops.py [출력 폴더]
# numpy만 있으면 된다. 기본 출력은 assets_src/music
import os
import sys
import wave

import numpy as np

SR = 22050
BPM = 96
BEAT = 60.0 / BPM
BARS = 8
BAR = int(round(BEAT * 4 * SR))
N = BAR * BARS  # 루프 길이(샘플). 96bpm 8마디면 정확히 20초
rng = np.random.default_rng(7)


def hz(m):
    return 440.0 * 2 ** ((m - 69) / 12)


def peak(x, level):
    return x / np.max(np.abs(x)) * level


def mix_in(buf, start, sig):
    # 루프 끝을 넘는 꼬리는 앞쪽으로 되감아서 이음새가 안 튀게 한다
    start %= len(buf)
    end = start + len(sig)
    if end <= len(buf):
        buf[start:end] += sig
    else:
        cut = len(buf) - start
        buf[start:] += sig[:cut]
        buf[:end - len(buf)] += sig[cut:]


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


def pluck(f, sec):
    t = np.arange(int(sec * SR)) / SR
    y = np.sin(2 * np.pi * f * t) + 0.3 * np.sin(2 * np.pi * f * 2 * t)
    return y * np.exp(-t * 7) * np.minimum(t / 0.004, 1)


def lowpass(x, cutoff):
    # fft로 걸면 루프가 원형이라 이음새가 그대로 이어진다
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


def make_pad():
    buf = np.zeros(N)
    chords = [[57, 60, 64], [53, 57, 60], [60, 64, 67], [55, 59, 62]]
    for i, ch in enumerate(chords):
        sec = BEAT * 8 + 1.2
        for m in ch:
            sig = tone(hz(m), sec, harm=(1, 0.5, 0.3, 0.15), detune=0.004)
            sig *= adsr(len(sig), 1.0, 0.5, 0.8, 1.2)
            mix_in(buf, i * 2 * BAR, sig * 0.5)
    buf = reverb(lowpass(buf, 1500), 0.35, 2.0)
    return peak(buf, 0.30)


def make_bass():
    buf = np.zeros(N)
    roots = [45, 41, 48, 43]
    for i, r in enumerate(roots):
        for b in range(2):
            bar_start = (i * 2 + b) * BAR
            for pos in (0, 3, 4, 6):
                dur = BEAT * 0.9 if pos in (0, 4) else BEAT * 0.45
                sig = tone(hz(r), dur, harm=(1, 0.35, 0.12))
                sig *= adsr(len(sig), 0.01, 0.1, 0.7, 0.12)
                mix_in(buf, bar_start + int(pos * BEAT / 2 * SR), sig)
    return peak(lowpass(buf, 400), 0.30)


def make_arp():
    buf = np.zeros(N)
    chords = [[57, 60, 64], [53, 57, 60], [60, 64, 67], [55, 59, 62]]
    order = [0, 1, 2, 1, 0, 1, 2, 1]
    for i, ch in enumerate(chords):
        for step in range(16):
            m = ch[order[step % 8]] + 12
            st = i * 2 * BAR + int(step * BEAT / 2 * SR)
            mix_in(buf, st, pluck(hz(m), BEAT * 1.2))
    buf = reverb(lowpass(buf, 3000), 0.3, 1.4)
    return peak(buf, 0.20)


def make_lead():
    buf = np.zeros(N)
    # (시작 박, 음, 박 길이) 마이너 펜타토닉
    notes = [
        (0, 76, 3), (3, 74, 1), (4, 72, 2), (6, 69, 2),
        (8, 72, 3), (11, 69, 1), (12, 74, 3), (15, 72, 1),
        (16, 76, 3), (19, 79, 1), (20, 76, 2), (22, 74, 2),
        (24, 74, 3), (27, 72, 1), (28, 69, 3), (31, 67, 1),
    ]
    for b, m, ln in notes:
        sec = BEAT * ln + 0.3
        sig = tone(hz(m), sec, harm=(1, 0.3, 0.1), vib=0.35)
        sig *= adsr(len(sig), 0.03, 0.15, 0.7, 0.3)
        mix_in(buf, int(b * BEAT * SR), sig)
    buf = reverb(lowpass(buf, 3500), 0.4, 2.2)
    return peak(buf, 0.25)


def kick():
    t = np.arange(int(0.3 * SR)) / SR
    f = 45 + 90 * np.exp(-t * 30)
    ph = 2 * np.pi * np.cumsum(f) / SR
    return np.sin(ph) * np.exp(-t * 12)


def snare():
    t = np.arange(int(0.2 * SR)) / SR
    noise = np.diff(rng.standard_normal(len(t) + 1))
    return (noise * 0.5 + 0.4 * np.sin(2 * np.pi * 190 * t)) * np.exp(-t * 25)


def hat(level):
    t = np.arange(int(0.06 * SR)) / SR
    noise = np.diff(rng.standard_normal(len(t) + 1))
    return noise * np.exp(-t * 60) * level


def make_drums():
    buf = np.zeros(N)
    for bar in range(BARS):
        b0 = bar * BAR
        for beat in (0, 2):
            mix_in(buf, b0 + int(beat * BEAT * SR), kick())
        if bar % 2 == 1:
            mix_in(buf, b0 + int(3.5 * BEAT * SR), kick() * 0.7)
        for beat in (1, 3):
            mix_in(buf, b0 + int(beat * BEAT * SR), snare() * 0.8)
        for e in range(8):
            mix_in(buf, b0 + int(e * BEAT / 2 * SR), hat(0.35 if e % 2 else 0.2))
    return peak(buf, 0.35)


def make_riser(sec=4.0):
    # 장면 포인트 직전에 깔리는 상승음
    n = int(sec * SR)
    t = np.arange(n) / SR
    noise = rng.standard_normal(n)
    X = np.fft.rfft(noise)
    f = np.fft.rfftfreq(n, 1 / SR)
    X *= ((f > 800) & (f < 7000)).astype(float)
    noise = np.fft.irfft(X, n)
    sweep = np.sin(2 * np.pi * np.cumsum(200 + 1400 * (t / sec) ** 2) / SR)
    env = (t / sec) ** 2
    return peak((noise / np.max(np.abs(noise)) * 0.7 + sweep * 0.5) * env, 0.40)


def make_impact(sec=2.5):
    # 클라이맥스 진입 타격음
    n = int(sec * SR)
    t = np.arange(n) / SR
    boom = np.sin(2 * np.pi * np.cumsum(38 + 90 * np.exp(-t * 14)) / SR) * np.exp(-t * 2.2)
    crash = np.diff(rng.standard_normal(n + 1)) * np.exp(-t * 2.8) * 0.5
    return peak(reverb(boom + crash, 0.3, 1.2), 0.60)


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
    parts = {
        'pad': make_pad(),
        'bass': make_bass(),
        'arp': make_arp(),
        'lead': make_lead(),
        'drums': make_drums(),
        'riser': make_riser(),
        'impact': make_impact(),
    }
    for name, x in parts.items():
        save(os.path.join(out, name + '.wav'), x)
        print(f'{name}.wav  {len(x) / SR:.2f}초')


if __name__ == '__main__':
    main()
