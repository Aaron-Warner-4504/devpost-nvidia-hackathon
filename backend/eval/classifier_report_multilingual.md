### Laya `multilingual` (variant `choice`, threshold 0.9 chosen on tune)

| split | lang | scam events | scam timelines | benign events | gate: scam-ev / timeline / benign | classifier: scam-ev / timeline / benign | union: scam-ev / timeline / benign |
|---|---|---|---|---|---|---|---|
| tune | en | 36 | 17 | 128 | 69% / 100% / 12% | 17% / 29% / 6% | 69% / 100% / 18% |
| tune | hi | 14 | 7 | 120 | 57% / 100% / 2% | 36% / 43% / 13% | 71% / 100% / 16% |
| tune | hinglish | 13 | 5 | 116 | 54% / 100% / 10% | 38% / 60% / 17% | 69% / 100% / 22% |
| tune | all | 63 | 29 | 364 | 63% / 100% / 8% | 25% / 38% / 12% | 70% / 100% / 19% |
| holdout | en | 12 | 7 | 105 | 58% / 100% / 10% | 17% / 29% / 11% | 67% / 100% / 21% |
| holdout | hi | 3 | 3 | 59 | 67% / 67% / 20% | 33% / 33% / 7% | 67% / 67% / 25% |
| holdout | hinglish | 9 | 4 | 6 | 44% / 75% / 17% | 44% / 75% / 0% | 67% / 100% / 17% |
| holdout | all | 24 | 14 | 170 | 54% / 86% / 14% | 29% / 43% / 9% | 67% / 93% / 22% |

Variant comparison on tune (best threshold under the benign budget; '-' = none fits):

| variant | tune AUC | threshold | scam-ev recall | timeline recall | benign rate |
|---|---|---|---|---|---|
| noul | 0.57 | - | - | - | - |
| choice | 0.55 | 0.9 | 25% | 38% | 12% |

Event-level ROC AUC (0.5 = chance), noul / choice:

| split | en | hi | hinglish | all |
|---|---|---|---|---|
| tune | 0.58 / 0.56 | 0.44 / 0.53 | 0.76 / 0.64 | 0.57 / 0.55 |
| holdout | 0.55 / 0.45 | 0.66 / 0.77 | 0.72 / 0.85 | 0.63 / 0.62 |

Threshold sweep, tune+holdout all languages (classifier alone; event-level precision / scam-event recall / benign rate / timeline recall):

| threshold | split | precision | recall | benign rate | timeline recall |
|---|---|---|---|---|---|
| 0.05 | tune | 15% | 97% | 93% | 97% |
| 0.05 | holdout | 13% | 100% | 94% | 100% |
| 0.1 | tune | 15% | 90% | 86% | 97% |
| 0.1 | holdout | 14% | 96% | 86% | 100% |
| 0.2 | tune | 17% | 84% | 74% | 90% |
| 0.2 | holdout | 14% | 88% | 74% | 86% |
| 0.3 | tune | 16% | 71% | 66% | 86% |
| 0.3 | holdout | 15% | 79% | 65% | 79% |
| 0.4 | tune | 16% | 63% | 58% | 86% |
| 0.4 | holdout | 16% | 79% | 61% | 79% |
| 0.5 | tune | 16% | 56% | 51% | 72% |
| 0.5 | holdout | 13% | 58% | 56% | 71% |
| 0.6 | tune | 16% | 51% | 45% | 66% |
| 0.6 | holdout | 12% | 50% | 50% | 57% |
| 0.7 | tune | 13% | 37% | 41% | 52% |
| 0.7 | holdout | 12% | 46% | 46% | 57% |
| 0.8 | tune | 18% | 30% | 24% | 48% |
| 0.8 | holdout | 22% | 42% | 21% | 57% |
| 0.9 | tune | 27% | 25% | 12% | 38% |
| 0.9 | holdout | 30% | 29% | 9% | 43% |
| 0.95 | tune | 22% | 17% | 11% | 31% |
| 0.95 | holdout | 37% | 29% | 7% | 43% |
| 0.98 | tune | 24% | 14% | 8% | 28% |
| 0.98 | holdout | 37% | 29% | 7% | 43% |

Latency per message (production path: single question, CPU, after warm-up, n=620): p50 160 ms, p95 230 ms, max 277 ms.

