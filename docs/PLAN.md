# Agentic Trust & Fraud Intelligence App: Final Plan v1.1

**Implementation plans (task-level):**
- Backend: `docs/superpowers/plans/2026-10-17-sentinel-backend.md`
- Android: `docs/superpowers/plans/2026-10-17-sentinel-android.md`

**Confirmed decisions (17 Oct):** team of 2 (backend owner + Android/frontend owner); India, English + Hindi; Android-first; Kotlin; both on-device and cloud intelligence; family alert included; entry may be commercialised; working name "Sentinel" (name not important); Token Factory key and credits obtained; execution method is **subagent-driven**.

Today: 17 Oct 2026. Hackathon deadline: **30 Oct 2026, 10:00am PT (about 12 working days).**
Hackathon: Nebius x NVIDIA Global AI Hackathon (Devpost). Facts below come from search snippets, because Devpost blocked direct fetch. **Re-read the Rules and Judging tabs yourself before building.**

Items tagged **[CALL]** are my decisions, open to change. Items tagged **[ASSUMPTION]** need your confirmation.

---
## 1. Two goals, one product
| Goal | What it needs | What it means for scope |
|---|---|---|
| Win or place in the hackathon | A working demo that visibly uses **Nebius Token Factory + an NVIDIA open model (Nemotron)**, a clear story, a 2-3 min video | Narrow, polished, demoable slice in 12 days |
| Become a real business | Real signal access, privacy, trust, distribution, revenue | Architecture that does not have to be rewritten, plus a post-hackathon path |

Rule: the hackathon build is **Phase 1 of the real product**, not a throwaway. The scope shrinks, the architecture does not.

## 2. Hackathon fit
- **Track [CALL]: Personal AI** ("private, always-on assistant with memory, reusable skills, user-controlled tools"). Best Apps and Agents is the fallback. The memory-first fraud agent matches Personal AI directly.
- **Mandatory:** runs on Token Factory or Nebius AI Cloud, and uses at least one NVIDIA open model. **[CALL]** Use Nemotron through Token Factory for the agent, and embeddings through Token Factory for memory. Confirm the exact model names in the Token Factory catalog on day 1.
- **Side prize:** Best Use of Tavily ($3k). Cheap to add: Tavily searches for a domain, brand or phone number during an investigation. **[CALL]** Include it.
- Prize pool is about $50k (grand $20k). Judging rubric not confirmed. Plan for the usual: technical depth, real usefulness, use of sponsor tech, demo quality.

## 3. Product definition
**Name (working):** Sentinel **[ASSUMPTION]**.
**One line:** A privacy-first Android guardian that remembers who and what has contacted you, connects events across SMS, email, calls and chat, and warns you before you pay, share an OTP or install something.

**Differentiator to show in the demo:** a scam that looks harmless message by message but is caught because memory links three events over three days. The alert says *why this event is different from the history*.

### Target user and market [ASSUMPTION]
- Launch: India. Individuals aged 25-60, then family protection of parents.
- Why India: UPI fraud, "digital arrest", fake KYC, courier and job scams. High volume, low-cost-per-user tolerance, and a clear unmet need. English + Hindi.
- **Money model [CALL]:**
  1. Free: manual "Check this" plus basic alerts.
  2. Paid: about ₹99-149/month individual or ₹299/month family: always-on monitoring, memory, family alerts for elders.
  3. Later: B2B2C via banks, telcos and insurers (white-label), which is likely the larger revenue.
  Revenue is not part of the hackathon build, but the **family alert** feature is built in as the paid hook.

## 4. Hackathon MVP scope (what ships by 30 Oct)
### In
1. **Android app (Kotlin, Jetpack Compose).**
2. **Signal capture:**
   - Notification listener (SMS, WhatsApp, bank and UPI app notification text). Avoids restricted SMS permissions.
   - Share-sheet "Check this" for any text, link or screenshot.
   - Call metadata via call-screening role or call log (number, time, duration, known contact or not). No audio.
   - Gmail read-only is **stretch**, behind a flag (backend Task 15).
   - Screenshot and image checks from the share sheet, read by a Nemotron vision model (backend Task 14).
3. **On-device layer (the "on-device" requirement) [CALL]:**
   - Local encrypted store (Room + SQLCipher) holding events and entity memory first.
   - Fast triage: regex and rules (OTP asks, urgency, UPI collect request, shortened or lookalike URLs, known scam phrases), phone number heuristics, a small TFLite/ONNX text classifier if time allows.
   - Redaction: names, account numbers, OTPs and card numbers are masked before anything leaves the phone.
   - Works offline in degraded mode: rules-only alerts.
4. **Cloud layer (the "cloud" requirement) [CALL]:**
   - FastAPI backend on Nebius (VM or managed Kubernetes if easy, otherwise a single VM with Docker).
   - Escalation only: events with triage score above threshold, or user "Check this", go to the agent.
   - **Agent with Nemotron on Token Factory:** tool-calling loop with `get_entity_history`, `check_url` (domain age + lookalike), `check_number`, `inspect_link`, `web_lookup` (Tavily).
   - **Link inspector:** a separate sandboxed container that follows redirects, checks threat feeds (Safe Browsing, URLhaus), and loads the page in a headless browser to detect credential/OTP/UPI forms and APK downloads. It blocks internal addresses (SSRF), has timeouts and size caps, and a clean result never lowers a tier (scammers cloak).
   - **Agent authority:** rules set a minimum tier (floor). The agent leads the investigation and may raise the tier up to `critical` only by citing tool-confirmed facts; it can never go below the floor. `AGENT_MODE=rules|agent|hybrid` enables a three-way ablation.
   - Embeddings on Token Factory for semantic recall of past events.
   - Temporal memory in Postgres + pgvector: entities, edges with valid_from/valid_to, evidence event IDs.
5. **Alert experience:** tiered (Info, Caution, High, Critical), only High/Critical interrupt. Each alert: what happened, why now (history links), one next step, one-tap "safe / scam" feedback.
6. **Family alert (paid hook):** a trusted contact gets a push when a Critical alert fires (redacted headline only). Pairing by a 6-character invite code (10 min expiry); the protected user gives explicit consent; either side can unlink. FCM push to a second app install.
7. **Evaluation harness:** about 40 scripted multi-step scam timelines and 80 benign look-alikes (Indian context), with a script that outputs recall, false-alert rate and time-to-warning, run in three modes (rules / agent / hybrid). The comparison table is a demo slide.
8. **Demo kit:** a backend simulator and an in-app demo mode that inject a 3-day scam timeline so the demo is reliable.
9. **Web dashboard:** memory timeline, linked entities and the "why" list for the demo user (backend Task 16).
10. **Push to the user's own device** for high/critical alerts (backend Task 17).

### Out (and why)
Call audio and live transcription (consent law, time), accessibility-service scraping (Play policy risk), auto-block or auto-reply, iOS, B2B portal, payments/billing, multi-language beyond English/Hindi.

## 5. Architecture
```
 ANDROID (Kotlin)                                   NEBIUS CLOUD
 ┌──────────────────────────────────────┐          ┌─────────────────────────────────┐
 │ Capture: NotificationListener,       │          │ FastAPI gateway (auth, consent) │
 │   Share target, CallScreening/log    │          │  ├ Entity linker + temporal     │
 │ On-device: normalize → redact →      │ redacted │  │   memory (Postgres+pgvector) │
 │   rules/classifier triage → local    │  events  │  ├ Risk agent                   │
 │   encrypted memory (Room+SQLCipher)  ├─────────►│  │   Nemotron @ Token Factory   │
 │ UI: alerts, timeline, "why", feedback│◄─────────┤  │   tools: history, url, number│
 │ Family contact (FCM)                 │  alerts  │  │   Tavily web lookup          │
 └──────────────────────────────────────┘          │  ├ Rules engine → final tier    │
                                                   │  ├ Alert policy (dedupe, cap)   │
                                                   │  └ Audit + eval harness         │
                                                   └─────────────────────────────────┘
```
Design rules:
- **Deterministic rules set the tier; the LLM proposes signals and writes the explanation.** Cuts false alarms and makes alerts auditable.
- **Scam text is untrusted.** It goes to the model as quoted data only, with tool permissions that cannot take external action, to resist prompt injection.
- The phone is the source of truth for raw content. Cloud stores redacted events, derived entities and signals only. Raw text never leaves except by explicit "Check this".
- Cloud memory is per-user, encrypted, with a one-call "forget everything".

## 6. Data model (core)
`users`, `consents(source, granted_at, revoked_at)`, `events(source, ts, redacted_text, hash)`, `entities(type: person|org|phone|domain|upi_id|amount|claim)`, `edges(a, b, relation, valid_from, valid_to, evidence_event_ids)`, `assessments(event_id, tier, signals[], explanation, model, latency)`, `alerts(assessment_id, shown_at, action, user_label)`, `trusted_contacts`.

## 7. Work plan (13 days, 2 tracks in parallel)
Owners: backend and agent = you (with Claude, subagent-driven); Android app = your teammate. The API contract (`docs/api-contract.md`, backend Task 1) goes to the teammate on day 1. Shared redaction/triage test vectors live in `shared/vectors/`. Task-level schedules are in the two implementation plans; the table below is the summary.

| Day | Backend + agent | Android | Shared |
|---|---|---|---|
| 17-18 | Repo, FastAPI skeleton, Token Factory + Nemotron call working, schema | Compose app shell, Room/SQLCipher, permission and consent flow | **Read Devpost rules; fix model names; write scenario spec** |
| 19-21 | Entity extraction, temporal memory, linker, rules engine | Notification listener + share target + call metadata, on-device redaction | Scenario dataset v1 (40 + 80) |
| 22-24 | Agent loop with tools, Tavily, embeddings recall, alert policy | Triage rules, alert UI with "why now" timeline, feedback | Eval harness running, first metrics |
| 25-26 | Hardening, prompt-injection tests, latency, deploy on Nebius | Family alert (FCM), offline degraded mode, polish | Demo simulator |
| 27-28 | Bug bash, tuning on eval set | Demo build (APK) | Video (2-3 min), README, architecture slide, Devpost write-up |
| 29 | **Buffer + submit by end of day (deadline 30 Oct 10:00 PT)** | | |

Cut order if behind: Gmail (already stretch) → Tavily → family alert → TFLite classifier → timeline screen → image sharing → Hindi UI. Link inspector, the agent and the eval harness are not cuttable.

## 8. Demo script (the story the video tells)
1. Day 1: unknown "courier" SMS with a link. Info only; the app remembers it quietly.
2. Day 2: a call from an unknown number, 4 minutes. Memory links number to the courier claim; Caution.
3. Day 3: WhatsApp message from the "customs officer" asks for a UPI payment. **Critical alert**: "This is the third contact in 3 days, same claimed organisation, link domain registered 2 days ago, payment request. Don't pay. Call the courier on the number on your official order." Family member gets pinged.
4. Show the "why" timeline, the metrics slide, and the Nebius/Nemotron architecture.

## 8b. Judging story
Technical depth (temporal memory + tool agent + hybrid on-device/cloud), real usefulness (a real problem with numbers), sponsor tech (Token Factory, Nemotron, Tavily), polish (reliable demo, eval metrics), and a believable business path.

## 9. Path to real revenue (after 30 Oct)
- **Weeks 1-4:** private beta with 50 users in India (friends, family, communities), measure false alerts per user-week and alerts acted on.
- **Platform gates:** Google Play declaration for notification access, restricted scope assessment if Gmail ships, DPDP Act notice, consent and grievance process, data-retention policy.
- **Month 2-3:** paid plan, family plan, referral loop through elders' families; pilot conversation with one bank or telco.
- **Moat:** the memory graph, a labeled Indian scam-timeline dataset, and user feedback loop.
- **Open risks:** Play Store rejection of notification access (mitigation: share-sheet and on-device-only mode), trust and privacy backlash, scammers adapting, LLM cost per user (mitigation: on-device triage gates around 95% of events).

## 10. Risks for the 13 days
Android notification access and call screening are fiddly on real devices (test early, day 19). Token Factory model names or limits may differ from assumptions (verify day 1). Scope is large for 13 days: the cut order above is binding. Single-person or team size changes everything (question 1).

## 11. Answered and still open
Answered: team of 2 (you backend, teammate Android); real Android phone available; commercial entry allowed (per you; verify on the Devpost Rules tab); India + English/Hindi; name not important; Token Factory key and credits obtained; family alert included; subagent-driven execution.

Still open:
1. One repo (`backend/`, `android/`, `shared/`, `docs/`) or separate repos? Recommended: one repo.
2. Git remote: GitHub repo or local only? (The GitHub connector failed to connect in this session.)
3. Does the teammate have Android Studio, a real phone and a Firebase project (needed for push)? If no Firebase, push and family alert drop to stretch.
4. Safe Browsing and Tavily API keys: will you create them (free)?
5. Does Claude also implement the Android plan with subagents, or only the backend? Default: backend only.
