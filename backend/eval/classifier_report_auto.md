### Laya `auto` (variant `choice`, threshold 0.9 chosen on tune)

| split | lang | scam events | scam timelines | benign events | gate: scam-ev / timeline / benign | classifier: scam-ev / timeline / benign | union: scam-ev / timeline / benign |
|---|---|---|---|---|---|---|---|
| tune | en | 36 | 17 | 128 | 69% / 100% / 12% | 8% / 18% / 8% | 69% / 100% / 20% |
| tune | hi | 14 | 7 | 120 | 57% / 100% / 2% | 36% / 43% / 12% | 71% / 100% / 14% |
| tune | hinglish | 13 | 5 | 116 | 54% / 100% / 10% | 31% / 40% / 10% | 69% / 100% / 16% |
| tune | all | 63 | 29 | 364 | 63% / 100% / 8% | 19% / 28% / 10% | 70% / 100% / 17% |
| holdout | en | 12 | 7 | 105 | 58% / 100% / 10% | 17% / 29% / 6% | 58% / 100% / 16% |
| holdout | hi | 3 | 3 | 59 | 67% / 67% / 20% | 33% / 33% / 8% | 67% / 67% / 27% |
| holdout | hinglish | 9 | 4 | 6 | 44% / 75% / 17% | 33% / 75% / 0% | 56% / 100% / 17% |
| holdout | all | 24 | 14 | 170 | 54% / 86% / 14% | 25% / 43% / 6% | 58% / 93% / 20% |

Variant comparison on tune (best threshold under the benign budget; '-' = none fits):

| variant | tune AUC | threshold | scam-ev recall | timeline recall | benign rate |
|---|---|---|---|---|---|
| noul | 0.60 | - | - | - | - |
| choice | 0.63 | 0.9 | 19% | 28% | 10% |

Event-level ROC AUC (0.5 = chance), noul / choice:

| split | en | hi | hinglish | all |
|---|---|---|---|---|
| tune | 0.67 / 0.66 | 0.51 / 0.58 | 0.79 / 0.77 | 0.60 / 0.63 |
| holdout | 0.66 / 0.61 | 0.70 / 0.76 | 0.81 / 0.94 | 0.68 / 0.68 |

Threshold sweep, tune+holdout all languages (classifier alone; event-level precision / scam-event recall / benign rate / timeline recall):

| threshold | split | precision | recall | benign rate | timeline recall |
|---|---|---|---|---|---|
| 0.05 | tune | 15% | 100% | 95% | 100% |
| 0.05 | holdout | 13% | 100% | 95% | 100% |
| 0.1 | tune | 16% | 94% | 87% | 100% |
| 0.1 | holdout | 14% | 100% | 89% | 100% |
| 0.2 | tune | 17% | 86% | 73% | 93% |
| 0.2 | holdout | 14% | 88% | 76% | 100% |
| 0.3 | tune | 20% | 78% | 53% | 90% |
| 0.3 | holdout | 17% | 79% | 56% | 100% |
| 0.4 | tune | 23% | 70% | 41% | 90% |
| 0.4 | holdout | 18% | 71% | 47% | 86% |
| 0.5 | tune | 21% | 57% | 37% | 72% |
| 0.5 | holdout | 19% | 67% | 41% | 86% |
| 0.6 | tune | 21% | 48% | 31% | 66% |
| 0.6 | holdout | 20% | 54% | 31% | 71% |
| 0.7 | tune | 21% | 37% | 24% | 55% |
| 0.7 | holdout | 20% | 42% | 24% | 57% |
| 0.8 | tune | 19% | 25% | 19% | 41% |
| 0.8 | holdout | 29% | 38% | 13% | 50% |
| 0.9 | tune | 25% | 19% | 10% | 28% |
| 0.9 | holdout | 35% | 25% | 6% | 43% |
| 0.95 | tune | 21% | 11% | 7% | 17% |
| 0.95 | holdout | 44% | 17% | 3% | 29% |
| 0.98 | tune | 25% | 6% | 3% | 10% |
| 0.98 | holdout | 67% | 17% | 1% | 29% |

Latency per message (production path: single question, CPU, after warm-up, n=620): p50 388 ms, p95 1012 ms, max 2239 ms.

