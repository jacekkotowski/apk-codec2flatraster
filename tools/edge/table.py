#!/usr/bin/env python3
"""PLAN-edge.md task 1.9: every clip, every variant, the table and the gate G1 verdict. Writes results.md.
`python3 table.py` runs everything missing (jobs run 4 at a time, results cached in out/res/*.json: delete a file to redo it),
then prints the markdown. Needs baseline.py to have run (out/CLIP.base_*.npz).

How G1 is evaluated here (the plan leaves the details open; this is what the code does):
  tolerance   a measure is 'not worse' when it is within 0.05 of r, 5 points of a share (face found, hand found), 0.05 of an error
  reference   for a clip and an edge variant: among the fallbacks (4 sizes/boxes x 3 coders) whose face found, mouth r and hand found
              are each at least the edge variant's minus the tolerance, the one with the fewest bytes (none: criterion 2 passes
              and the clip is marked 'no fallback as legible')
  criterion 1 picture bytes (K+M+P payloads) <= 2000 per 30 s
  criterion 2 bytes <= 0.6 x the reference's
  criterion 3 edge legibility not worse than the reference's by the tolerance (face found, mouth r, hand found)
  A variant passes a clip when all three hold. G1 needs four of the five clips (three of four when a clip is dropped).
Legibility is measured at the shown instants: every 2nd camera frame (8 a second); the baseline's picture is the last one made (2 a second) held."""
import json, os, sys
from multiprocessing import Pool
import numpy as np
import cv2

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import clips, codec, marks, legible, baseline  # noqa: E402

RES = os.path.join(clips.OUT, "res")
CLIPS = [c for c in ["C1", "C2", "C3", "C4", "C5"]]
VARS = ["E1", "E2", "E3", "E4", "E5", "E6", "E7", "E8", "E9"]
CFGS = list(baseline.CFG)
CODERS = ["exact", "x264", "svt"]
TOL_R, TOL_P = 0.05, 5.0


def _orig_marks(name, n):
    m = dict(np.load(os.path.join(clips.OUT, name + ".marks.npz")))
    return {k: v[0::2][:n] for k, v in m.items()}


def edge_job(a):
    clip, var = a
    path = os.path.join(RES, f"{clip}.{var}.json")
    if os.path.exists(path):
        return json.load(open(path))
    p, recs, dec, flags, s, tm = codec.run(clip, var)
    n = len(dec)
    o = _orig_marks(clip, n)
    fr = clips.load(clip)
    r = legible.compare(o, marks.detect(dec, step=2), np.array([np.ascontiguousarray(fr[2 * i]) for i in range(n)]), dec)
    out = dict(clip=clip, var=var, K=s["by"]["K"], M=s["by"]["M"], P=s["by"]["P"], total=s["total"], with_headers=s["with_headers"],
               warped=100 * flags.count("W") / n, held=100 * flags.count("H") / n, enc_s=tm[0], legible=r, frames=n)
    json.dump(out, open(path, "w"))
    return out


def base_job(a):
    clip, cfg, coder = a
    path = os.path.join(RES, f"{clip}.base_{cfg}_{coder}.json")
    if os.path.exists(path):
        return json.load(open(path))
    W, H, _ = baseline.CFG[cfg]
    z = np.load(os.path.join(clips.OUT, f"{clip}.base_{cfg}.npz"))
    win = z["window"]
    pics = z[coder]
    info = json.load(open(os.path.join(clips.OUT, f"{clip}.base_{cfg}.json")))
    fr = clips.load(clip)
    idx = [i for i in range(240) if 2 * i >= 7 and (2 * i - 7) // 8 < len(pics) and 2 * i < 480]
    orig, dec = [], []
    for i in idx:
        f, p = 2 * i, (2 * i - 7) // 8
        L, T, Wd, Hd = [v / 1000.0 for v in win[p]]
        x0, y0 = int(round(L * 640)), int(round(T * 480))
        x1, y1 = x0 + max(8, int(round(Wd * 640))), y0 + max(8, int(round(Hd * 480)))
        cr = np.ascontiguousarray(fr[f])[max(0, y0):min(480, y1), max(0, x0):min(640, x1)]
        orig.append(cv2.resize(cr, (640, 480), interpolation=cv2.INTER_AREA))
        dec.append(cv2.resize(np.ascontiguousarray(pics[p]), (640, 480), interpolation=cv2.INTER_CUBIC))
    orig, dec = np.array(orig), np.array(dec)
    o = marks.detect(orig, step=2)
    r = legible.compare(o, marks.detect(dec, step=2), orig, dec)
    out = dict(clip=clip, cfg=cfg, coder=coder, bytes=info[coder], legible=r, frames=len(idx))
    json.dump(out, open(path, "w"))
    return out


def tag(v, s):
    return "n/a" if v != v else s.format(v)


def leg_str(r):
    return (f"{tag(r['face_found'], '{:.0f}')}% / {tag(r['face_err'], '{:.2f}')} / {tag(r['mouth_r'], '{:.2f}')} / "
            f"{tag(r['hand_found'], '{:.0f}')}% / {tag(r['object_f1'], '{:.0f}')}%")


def not_worse(a, b):
    """True when legibility a is not worse than b (face found, mouth r, hand found, within the tolerance); NaN measures are skipped."""
    ok = True
    for k, tol in (("face_found", TOL_P), ("mouth_r", TOL_R), ("hand_found", TOL_P)):
        if a[k] == a[k] and b[k] == b[k] and a[k] < b[k] - tol:
            ok = False
    return ok


def main():
    os.makedirs(RES, exist_ok=True)
    clipinfo = {c: clips.info(c) for c in CLIPS}
    mk = {c: dict(np.load(os.path.join(clips.OUT, c + ".marks.npz"))) for c in CLIPS}
    with Pool(4) as pool:
        E = pool.map(edge_job, [(c, v) for c in CLIPS for v in VARS], chunksize=1)
        B = pool.map(base_job, [(c, g, k) for c in CLIPS for g in CFGS for k in CODERS], chunksize=1)
    Ed = {(e["clip"], e["var"]): e for e in E}
    Bd = {(b["clip"], b["cfg"], b["coder"]): b for b in B}
    L = []
    P = L.append
    P("# Edge codec, phase 1 results (generated by `table.py`, do not edit)\n")
    P("Bytes are per 30 s, picture only (K+M+P payloads; `+hdr` adds 2 bytes a record). Legibility: face found / face error (of eye distance) / mouth r / hand found / object F1 (see `legible.py`).\n")
    P("## Clips\n")
    P("| clip | source file | source s | played forward and back | face frames | hand frames |\n|---|---|---|---|---|---|")
    for c in CLIPS:
        i = clipinfo[c]
        P(f"| {c} | {i['note']} | {i['source_seconds']} | {'yes' if i['pingpong'] else 'no'} | {100 * mk[c]['face'].mean():.1f}% | {100 * mk[c]['hand'].any(1).mean():.1f}% |")
    P("\n## Edge codec, every variant\n")
    P("| clip | var | K n / bytes | M n / bytes | P n / bytes | total B | +hdr | warped % | legibility |\n|---|---|---|---|---|---|---|---|---|")
    for c in CLIPS:
        for v in VARS:
            e = Ed[(c, v)]
            P(f"| {c} | {v} | {e['K'][0]} / {e['K'][1]} | {e['M'][0]} / {e['M'][1]} | {e['P'][0]} / {e['P'][1]} | {e['total']} | {e['with_headers']} | {e['warped']:.0f} | {leg_str(e['legible'])} |")
    P("\n## Fallback (0.17 path), every size, box and coder\n")
    P("cfg = picture size and face box (h head, f face only). Bytes per 30 s.\n")
    P("| clip | cfg | exact | x264 | svt-av1 | legibility (svt-av1) |\n|---|---|---|---|---|---|")
    for c in CLIPS:
        for g in CFGS:
            b = [Bd[(c, g, k)] for k in CODERS]
            P(f"| {c} | {g} | {b[0]['bytes']} | {b[1]['bytes']} | {b[2]['bytes']} | {leg_str(b[2]['legible'])} |")
    P("\n## Gate G1\n")
    P("| var | " + " | ".join(CLIPS) + " | clips passing | clips <= 2000 B | mean bytes |\n|---|" + "---|" * (len(CLIPS) + 3))
    verdict = {}
    for v in VARS:
        cells, npass, n1 = [], 0, 0
        for c in CLIPS:
            e = Ed[(c, v)]
            ref = None
            cand = [b for b in B if b["clip"] == c and not_worse(b["legible"], e["legible"])]
            if cand:
                ref = min(cand, key=lambda b: b["bytes"])
            c1 = e["total"] <= 2000
            c2 = ref is None or e["total"] <= 0.6 * ref["bytes"]
            c3 = ref is None or not_worse(e["legible"], ref["legible"])
            n1 += c1
            ok = c1 and c2 and c3
            npass += ok
            cells.append(f"{e['total']} B {'1' if c1 else '-'}{'2' if c2 else '-'}{'3' if c3 else '-'}" + (f" (ref {ref['cfg']} {ref['coder']} {ref['bytes']})" if ref else " (no fallback as legible)"))
        verdict[v] = (npass, n1)
        P(f"| {v} | " + " | ".join(cells) + f" | {npass} | {n1} | {np.mean([Ed[(c, v)]['total'] for c in CLIPS]):.0f} |")
    P("\nCells: edge bytes, then which criteria hold (1: at most 2000 B, 2: at most 0.6 x the reference, 3: legibility not worse), then the reference fallback.\n")
    best = max(VARS, key=lambda v: (verdict[v][0], -np.mean([Ed[(c, v)]["total"] for c in CLIPS])))
    passes = verdict[best][0] >= 4
    P(f"**Variant chosen once (most clips passing, then fewest mean bytes): {best}, passing {verdict[best][0]} of {len(CLIPS)} clips. G1 {'PASSED' if passes else 'NOT passed'}.**\n")
    P("Closest to 2000 B per clip, over all variants: " + ", ".join(f"{c} {min(Ed[(c, v)]['total'] for v in VARS)} B ({min(VARS, key=lambda v: Ed[(c, v)]['total'])})" for c in CLIPS) + ".\n")
    P("Best fallback bytes per clip (any size/box/coder): " + ", ".join(f"{c} {min(b['bytes'] for b in B if b['clip'] == c)} B" for c in CLIPS) + ".\n")
    txt = "\n".join(L)
    open(os.path.join(HERE, "results.md"), "w").write(txt)
    print(txt)


if __name__ == "__main__":
    main()
