/* See motion.h. A line-by-line port of tools/edge/motion.py (_code, _res, _Models): keep the two in step. */
#include "motion.h"
#include "rc.h"
#include <math.h>

#define T 10
#define ESC (2 * T + 1)

struct MotionCodec {
    int nobj, n[MOTION_MAX_OBJECTS], alpha;
    Model res[2][6][3], pres[MOTION_MAX_OBJECTS][2], nbits, sign;
    int has[MOTION_MAX_OBJECTS], has2[MOTION_MAX_OBJECTS];
    int32_t last[MOTION_MAX_OBJECTS][MOTION_MAX_POINTS][2], last2[MOTION_MAX_OBJECTS][MOTION_MAX_POINTS][2];
};

MotionCodec *motion_create(int nobj, const int *n, int alpha) {
    if (nobj < 1 || nobj > MOTION_MAX_OBJECTS) return NULL;
    for (int o = 0; o < nobj; o++) if (n[o] < 1 || n[o] > MOTION_MAX_POINTS) return NULL;
    MotionCodec *m = calloc(1, sizeof *m);
    if (!m) return NULL;
    m->nobj = nobj; m->alpha = alpha;
    for (int o = 0; o < nobj; o++) m->n[o] = n[o];
    motion_reset(m);
    return m;
}
void motion_destroy(MotionCodec *m) { free(m); }
int motion_points(const MotionCodec *m) { int t = 0; for (int o = 0; o < m->nobj; o++) t += m->n[o]; return t; }

static void prior(Model *m) {
    model_init(m, 2 * T + 2);
    m->tot = 0;
    for (int s = 0; s < 2 * T + 2; s++) {
        m->f[s] = s < 2 * T + 1 ? 1 + (int)lrint(40.0 * exp(-fabs((double)(s - T)) / 1.2)) : 1;
        m->tot += m->f[s];
    }
}

void motion_reset(MotionCodec *m) {
    for (int a = 0; a < 2; a++) for (int k = 0; k < 6; k++) for (int c = 0; c < 3; c++) prior(&m->res[a][k][c]);
    for (int o = 0; o < MOTION_MAX_OBJECTS; o++) for (int w = 0; w < 2; w++) model_init(&m->pres[o][w], 2);
    model_init(&m->nbits, 16); model_init(&m->sign, 2);
    memset(m->has, 0, sizeof m->has); memset(m->has2, 0, sizeof m->has2);
}

/* motion.py _res: one residual (encoder: q given; decoder: q ignored). */
static int resid(Coder *c, MotionCodec *m, int axis, int kind, int ctx, int q) {
    Model *md = &m->res[axis][kind][ctx];
    int s = c->enc ? ((q >= -T && q <= T) ? q + T : ESC) : 0;
    s = symbol(c, md, s, NULL, 0);
    if (c->err) return 0;
    if (s < ESC) return s - T;
    if (c->enc) {
        int v = abs(q) - T - 1, b = 0;
        for (int t = v; t > 0; t >>= 1) b++;
        symbol(c, &m->nbits, b < 15 ? b : 15, NULL, 0);
        for (int i = b - 1; i >= 0; i--) uniform(c, 2, (v >> i) & 1);
        symbol(c, &m->sign, q < 0, NULL, 0);
        return q;
    }
    int b = symbol(c, &m->nbits, 0, NULL, 0), v = 0;
    for (int i = 0; i < b; i++) v = (v << 1) | uniform(c, 2, 0);
    int neg = symbol(c, &m->sign, 0, NULL, 0);
    return neg ? -(v + T + 1) : v + T + 1;
}

static int min2(int v) { v = abs(v); return v < 2 ? v : 2; }

/* One picture (motion.py _code). cur: NULL when decoding. present/units as in motion.h. */
static int code(Coder *c, MotionCodec *m, const int32_t *present, const int32_t *cur, int32_t *out_present, int32_t *out_units) {
    int32_t pts[MOTION_MAX_OBJECTS][MOTION_MAX_POINTS][2];
    int have[MOTION_MAX_OBJECTS] = {0};
    const int32_t *cp = cur;
    for (int o = 0; o < m->nobj && !c->err; o++) {
        int n = m->n[o], kind = o == 0 ? 0 : 1, had = m->has[o];
        int pres = symbol(c, &m->pres[o][had], c->enc ? present[o] != 0 : 0, NULL, 0);
        if (c->err) break;
        if (!pres) continue;
        have[o] = 1;
        const int32_t *cu = NULL;                       /* this object's input points */
        if (c->enc) { cu = cp; cp += n * 2; }
        if (!had) {
            for (int a = 0; a < 2; a++) {
                int v = c->enc ? cu[a] : 0; if (v < 0) v = 0; if (v > 1023) v = 1023;
                pts[o][0][a] = uniform(c, 1024, v);
            }
            int pq[2] = {0, 0};
            for (int j = 1; j < n; j++) for (int a = 0; a < 2; a++) {
                int q = resid(c, m, a, 2 + kind, min2(pq[a]), c->enc ? cu[j * 2 + a] - pts[o][j - 1][a] : 0);
                pts[o][j][a] = pts[o][j - 1][a] + q; pq[a] = q;
            }
        } else {
            int32_t pred[MOTION_MAX_POINTS][2], r[MOTION_MAX_POINTS][2];
            for (int j = 0; j < n; j++) for (int a = 0; a < 2; a++) {
                int p1 = m->last[o][j][a];
                pred[j][a] = (m->alpha && m->has2[o]) ? p1 + (int)lrint(0.5 * (p1 - m->last2[o][j][a])) : p1;
                if (c->enc) r[j][a] = cu[j * 2 + a] - pred[j][a];
            }
            int S[2];
            for (int a = 0; a < 2; a++) {
                int mean = 0;
                if (c->enc) { double sum = 0; for (int j = 0; j < n; j++) sum += r[j][a]; mean = (int)lrint(sum / n); }
                S[a] = resid(c, m, a, 4 + kind, 0, mean);
            }
            int pq[2] = {0, 0};
            for (int j = 0; j < n; j++) for (int a = 0; a < 2; a++) {
                int q = resid(c, m, a, kind, min2(pq[a]), c->enc ? r[j][a] - S[a] : 0);
                pts[o][j][a] = pred[j][a] + S[a] + q; pq[a] = q;
            }
        }
    }
    if (c->err) return -1;
    int32_t *op = out_units;
    for (int o = 0; o < m->nobj; o++) {
        if (out_present) out_present[o] = have[o];
        if (have[o] && op) { for (int j = 0; j < m->n[o]; j++) { *op++ = pts[o][j][0]; *op++ = pts[o][j][1]; } }
    }
    /* state: last2 = last, last = this picture (absent objects: none) */
    memcpy(m->last2, m->last, sizeof m->last); memcpy(m->has2, m->has, sizeof m->has);
    for (int o = 0; o < m->nobj; o++) { m->has[o] = have[o]; if (have[o]) memcpy(m->last[o], pts[o], sizeof(int32_t) * 2 * (size_t)m->n[o]); }
    return 0;
}

int motion_encode(MotionCodec *m, const int32_t *present, const int32_t *units, int nunits, uint8_t *out, int cap) {
    if (!m || !present || !units || !out) return -1;
    int need = 0;
    for (int o = 0; o < m->nobj; o++) if (present[o]) need += m->n[o] * 2;
    if (nunits < need) return -1;
    Coder c; enc_start(&c, out, cap);
    int32_t pr[MOTION_MAX_OBJECTS];
    if (code(&c, m, present, units, pr, NULL) < 0) return -1;
    return enc_finish(&c);
}

int motion_decode(MotionCodec *m, const uint8_t *data, int len, int32_t *present, int32_t *units) {
    if (!m || !data || !present || !units) return -1;
    Coder c; dec_start(&c, data, len, 0);
    return code(&c, m, NULL, NULL, present, units);
}
