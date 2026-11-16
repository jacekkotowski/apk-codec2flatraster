
### selftest: 60 pictures at 2 a second (30.0 s), 0.63% of pixels change per picture

| Coder | Quality step | Full pictures | Bytes per full picture | Bytes per delta picture (least to most) | Total bytes | Bytes per 30 s | PSNR dB | Pixels off |
|---|---|---|---|---|---|---|---|---|
| Prototype | exact | 3 | 290 | 145 (50 to 211) | 9108 | 9108 | exact | 0.00% |
| Index map packed with lzma (the earlier estimate in tools/bench.py) | exact | 3 | 697 | 558 (276 to 688) | 33912 | 33912 | exact | 0.00% |
| H.264 x264 veryslow | 30 | 3 | 1732 | 161 (87 to 335) | 14355 | 14355 | 53.9 | 0.04% |
| H.264 x264 veryslow (reference) | 40 | 3 | 907 | 91 (49 to 202) | 7894 | 7894 | 44.4 | 0.44% |
| H.264 x264 veryslow | 48 | 3 | 468 | 60 (25 to 162) | 4827 | 4827 | 38.7 | 1.64% |
| HEVC x265 slow | 30 | 3 | 1620 | 189 (58 to 533) | 15605 | 15605 | 52.5 | 0.04% |
| HEVC x265 slow (reference) | 40 | 3 | 806 | 95 (51 to 283) | 7828 | 7828 | 46.4 | 0.19% |
| HEVC x265 slow | 48 | 3 | 423 | 57 (37 to 119) | 4500 | 4500 | 41.9 | 0.72% |
| AV1 svt-av1 preset 4 | 35 | 3 | 778 | 112 (3 to 849) | 8711 | 8711 | 55.0 | 0.05% |
| AV1 svt-av1 preset 4 (reference) | 45 | 3 | 695 | 73 (3 to 615) | 6238 | 6238 | 48.9 | 0.15% |
| AV1 svt-av1 preset 4 | 55 | 3 | 610 | 46 (3 to 388) | 4445 | 4445 | 45.5 | 0.32% |
| AV1 libaom cpu 4 | 35 | 3 | 522 | 112 (3 to 885) | 7945 | 7945 | 55.9 | 0.03% |
| AV1 libaom cpu 4 (reference) | 45 | 3 | 493 | 76 (3 to 637) | 5811 | 5811 | 49.6 | 0.13% |
| AV1 libaom cpu 4 | 55 | 3 | 482 | 49 (3 to 412) | 4261 | 4261 | 45.8 | 0.33% |

Prototype: every picture decoded to exactly the input: yes. Python took 3 s to code and 3 s to decode.

### moving: 60 pictures at 3 a second (20.0 s), 3.61% of pixels change per picture

| Coder | Quality step | Full pictures | Bytes per full picture | Bytes per delta picture (least to most) | Total bytes | Bytes per 30 s | PSNR dB | Pixels off |
|---|---|---|---|---|---|---|---|---|
| Prototype | exact | 2 | 518 | 175 (122 to 227) | 11160 | 16740 | exact | 0.00% |
| Index map packed with lzma (the earlier estimate in tools/bench.py) | exact | 2 | 1178 | 564 (392 to 792) | 35072 | 52608 | exact | 0.00% |
| H.264 x264 veryslow | 30 | 2 | 1812 | 240 (106 to 680) | 17532 | 26298 | 52.7 | 0.24% |
| H.264 x264 veryslow (reference) | 40 | 2 | 696 | 141 (72 to 305) | 9553 | 14330 | 47.1 | 1.03% |
| H.264 x264 veryslow | 48 | 2 | 432 | 121 (59 to 241) | 7855 | 11782 | 43.0 | 2.00% |
| HEVC x265 slow | 30 | 2 | 1318 | 208 (75 to 664) | 14728 | 22092 | 57.7 | 0.10% |
| HEVC x265 slow (reference) | 40 | 2 | 419 | 103 (55 to 259) | 6811 | 10216 | 49.9 | 0.87% |
| HEVC x265 slow | 48 | 2 | 253 | 74 (40 to 187) | 4791 | 7186 | 45.2 | 2.46% |
| AV1 svt-av1 preset 4 | 35 | 2 | 1285 | 110 (3 to 1050) | 8972 | 13458 | 56.8 | 0.33% |
| AV1 svt-av1 preset 4 (reference) | 45 | 2 | 674 | 71 (3 to 654) | 5441 | 8162 | 53.3 | 0.72% |
| AV1 svt-av1 preset 4 | 55 | 2 | 400 | 54 (3 to 485) | 3929 | 5894 | 49.2 | 1.62% |
| AV1 libaom cpu 4 | 35 | 2 | 690 | 107 (3 to 952) | 7602 | 11403 | 55.4 | 0.30% |
| AV1 libaom cpu 4 (reference) | 45 | 2 | 540 | 74 (3 to 575) | 5345 | 8017 | 52.5 | 0.75% |
| AV1 libaom cpu 4 | 55 | 2 | 368 | 57 (3 to 392) | 4036 | 6054 | 48.8 | 1.33% |

Prototype: every picture decoded to exactly the input: yes. Python took 3 s to code and 4 s to decode.

### still: 20 pictures at 3 a second (6.7 s), 0.00% of pixels change per picture

| Coder | Quality step | Full pictures | Bytes per full picture | Bytes per delta picture (least to most) | Total bytes | Bytes per 30 s | PSNR dB | Pixels off |
|---|---|---|---|---|---|---|---|---|
| Prototype | exact | 1 | 611 | 1 (1 to 1) | 630 | 1920 | exact | 0.00% |
| Index map packed with lzma (the earlier estimate in tools/bench.py) | exact | 1 | 1320 | 144 (144 to 144) | 4056 | 16488 | exact | 0.00% |
| H.264 x264 veryslow | 30 | 1 | 2172 | 16 (15 to 24) | 2482 | 7935 | 57.1 | 0.00% |
| H.264 x264 veryslow (reference) | 40 | 1 | 828 | 22 (14 to 42) | 1244 | 4389 | 48.4 | 0.47% |
| H.264 x264 veryslow | 48 | 1 | 447 | 20 (14 to 45) | 823 | 3063 | 44.1 | 1.78% |
| HEVC x265 slow | 30 | 1 | 1579 | 33 (29 to 44) | 2209 | 7622 | 59.1 | 0.00% |
| HEVC x265 slow (reference) | 40 | 1 | 464 | 39 (33 to 43) | 1206 | 4790 | 50.0 | 0.39% |
| HEVC x265 slow | 48 | 1 | 236 | 36 (28 to 47) | 928 | 3877 | 46.7 | 1.51% |
| AV1 svt-av1 preset 4 | 35 | 1 | 1765 | 23 (3 to 113) | 2194 | 7259 | 63.1 | 0.00% |
| AV1 svt-av1 preset 4 (reference) | 45 | 1 | 990 | 23 (3 to 120) | 1435 | 5008 | 59.7 | 0.07% |
| AV1 svt-av1 preset 4 | 55 | 1 | 619 | 24 (3 to 120) | 1071 | 3927 | 57.3 | 0.20% |
| AV1 libaom cpu 4 | 35 | 1 | 950 | 20 (19 to 24) | 1328 | 4581 | 60.7 | 0.00% |
| AV1 libaom cpu 4 (reference) | 45 | 1 | 718 | 21 (20 to 25) | 1115 | 3972 | 59.2 | 0.03% |
| AV1 libaom cpu 4 | 55 | 1 | 515 | 21 (20 to 25) | 912 | 3363 | 58.0 | 0.14% |

Prototype: every picture decoded to exactly the input: yes. Python took 1 s to code and 1 s to decode.

### leaves: 110 pictures at 2 a second (55.0 s), 6.87% of pixels change per picture

| Coder | Quality step | Full pictures | Bytes per full picture | Bytes per delta picture (least to most) | Total bytes | Bytes per 30 s | PSNR dB | Pixels off |
|---|---|---|---|---|---|---|---|---|
| Prototype | exact | 6 | 1096 | 828 (718 to 905) | 92709 | 50495 | exact | 0.00% |
| Index map packed with lzma (the earlier estimate in tools/bench.py) | exact | 6 | 1641 | 1607 (1484 to 1740) | 176988 | 96530 | exact | 0.00% |
| H.264 x264 veryslow | 30 | 6 | 1338 | 531 (427 to 780) | 63252 | 34281 | 49.1 | 0.23% |
| H.264 x264 veryslow (reference) | 40 | 6 | 838 | 298 (242 to 448) | 36043 | 19512 | 40.6 | 1.82% |
| H.264 x264 veryslow | 48 | 6 | 537 | 145 (65 to 314) | 18332 | 9892 | 30.1 | 8.93% |
| HEVC x265 slow | 30 | 6 | 2386 | 671 (498 to 1033) | 84060 | 45383 | 51.5 | 0.06% |
| HEVC x265 slow (reference) | 40 | 6 | 1243 | 369 (316 to 536) | 45858 | 24775 | 42.5 | 0.89% |
| HEVC x265 slow | 48 | 6 | 578 | 287 (224 to 435) | 33348 | 18110 | 34.4 | 5.49% |
| AV1 svt-av1 preset 4 | 35 | 6 | 842 | 392 (3 to 2325) | 45849 | 24886 | 57.6 | 0.04% |
| AV1 svt-av1 preset 4 (reference) | 45 | 6 | 735 | 341 (3 to 2089) | 39858 | 21633 | 48.5 | 0.31% |
| AV1 svt-av1 preset 4 | 55 | 6 | 618 | 256 (3 to 1556) | 30378 | 16471 | 42.4 | 1.35% |
| AV1 libaom cpu 4 | 35 | 6 | 608 | 292 (3 to 1795) | 33987 | 18452 | 58.9 | 0.01% |
| AV1 libaom cpu 4 (reference) | 45 | 6 | 591 | 211 (3 to 1411) | 25472 | 13790 | 44.9 | 0.12% |
| AV1 libaom cpu 4 | 55 | 6 | 512 | 187 (3 to 1221) | 22568 | 12221 | 42.6 | 0.51% |

Prototype: every picture decoded to exactly the input: yes. Python took 7 s to code and 8 s to decode.

### leaves2: 110 pictures at 2 a second (55.0 s), 1.91% of pixels change per picture

| Coder | Quality step | Full pictures | Bytes per full picture | Bytes per delta picture (least to most) | Total bytes | Bytes per 30 s | PSNR dB | Pixels off |
|---|---|---|---|---|---|---|---|---|
| Prototype | exact | 6 | 1406 | 359 (63 to 1075) | 45743 | 24665 | exact | 0.00% |
| Index map packed with lzma (the earlier estimate in tools/bench.py) | exact | 6 | 2162 | 811 (284 to 1956) | 97348 | 52731 | exact | 0.00% |
| H.264 x264 veryslow | 30 | 6 | 4168 | 576 (56 to 2500) | 84914 | 45337 | 47.4 | 0.27% |
| H.264 x264 veryslow (reference) | 40 | 6 | 2129 | 196 (14 to 1005) | 33205 | 17585 | 37.4 | 2.47% |
| H.264 x264 veryslow | 48 | 6 | 873 | 73 (19 to 286) | 12789 | 6758 | 31.2 | 7.60% |
| HEVC x265 slow | 30 | 6 | 4931 | 765 (45 to 3623) | 109195 | 58425 | 49.0 | 0.11% |
| HEVC x265 slow (reference) | 40 | 6 | 2536 | 281 (32 to 1863) | 44443 | 23627 | 39.7 | 1.26% |
| HEVC x265 slow | 48 | 6 | 1108 | 96 (26 to 467) | 16640 | 8800 | 33.6 | 4.79% |
| AV1 svt-av1 preset 4 | 35 | 6 | 1300 | 349 (3 to 4785) | 44137 | 23815 | 54.6 | 0.12% |
| AV1 svt-av1 preset 4 (reference) | 45 | 6 | 1209 | 271 (3 to 4062) | 35464 | 19088 | 45.9 | 0.43% |
| AV1 svt-av1 preset 4 | 55 | 6 | 1185 | 149 (3 to 2640) | 22611 | 12051 | 39.6 | 1.13% |
| AV1 libaom cpu 4 | 35 | 6 | 1068 | 316 (3 to 4702) | 39304 | 21233 | 52.9 | 0.11% |
| AV1 libaom cpu 4 (reference) | 45 | 6 | 1042 | 244 (3 to 4060) | 31586 | 17011 | 44.8 | 0.36% |
| AV1 libaom cpu 4 | 55 | 6 | 949 | 142 (3 to 2834) | 20494 | 10959 | 39.3 | 1.04% |

Prototype: every picture decoded to exactly the input: yes. Python took 6 s to code and 8 s to decode.
