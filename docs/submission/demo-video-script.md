# Demo video script (target 2:30, hard limit 3:00)

Check the Devpost Rules tab for the video length limit, hosting (YouTube/Vimeo/other) and whether a public or unlisted link is required.

## Decide the capture mode first (5 minutes)

The story needs three events on one phone. Pick the first row that works on the day, and rehearse it twice.

| Mode | How | Use when |
|---|---|---|
| A. Live | Release build, real notifications from a second phone | Always-on capture is merged, installed and you have seen a notification become an alert on this phone |
| B. Check this on the release build | Share each of the three texts to Sentinel with "Check this" against the live server | Capture is not ready. Works on `main` today (text check). The "why" timeline depends on the server linking the events: do a dry run and confirm it shows the earlier events before you rely on it |
| C. Demo mode (debug build) | `./gradlew assembleDebug -PsentinelApiBaseUrl=https://interns.enthral.ai/sentinel`, then Settings > Demo (or Home action) replays the 3-day story and opens the critical alert | The most reliable fallback. Say "demo timeline" in the voice-over; do not present it as live capture |

Whatever you use, do not claim live always-on capture unless mode A worked in rehearsal. In debug builds the demo posts real rows to your account; use a fresh account.

## Storyboard

| Time | Screen | Exact action | Voice-over |
|---|---|---|---|
| 0:00-0:12 | Title card, then phone home screen | Card: "Sentinel: a guardian that remembers". Cut to the phone locked. | V1 |
| 0:12-0:30 | Slide: problem (slides.md, slide 2) | None. Hold on the three-step scam strip. | V2 |
| 0:30-0:42 | Sentinel Home, "Protected" | Open the app. Show Home with sources on. | V3 |
| 0:42-1:00 | Day 1 | Mode A: second phone sends the courier SMS; notification appears; Sentinel stays quiet (no alert). Mode B/C: show event 1 being checked/replayed, tier info. | V4 |
| 1:00-1:15 | Day 2 | Mode A: place a call from the second phone and let it ring for a few seconds (the real story used a 4-minute call; say "a long call"). Show a caution in Alerts. Mode C: next demo step. | V5 |
| 1:15-1:40 | Day 3 | Send the WhatsApp UPI demand. Heads-up notification "Sentinel alert" appears. Tap it. Critical alert opens. | V6 |
| 1:40-2:00 | Why timeline | Slow scroll: headline, "Why we flagged this" with the SMS, the call, "This message", then "What to do now". Pause 3 s on the timeline. | V7 |
| 2:00-2:12 | Slide: architecture (slides.md, slide 4) | Show `architecture.svg`. Point at Nemotron, Token Factory, Tavily. | V8 |
| 2:12-2:24 | Slide: results with caveat (slide 7) | Show the memory ablation table. Leave the caveat visible. | V9 |
| 2:24-2:30 | End card | Repo URL, "Sentinel". | V10 |

## Voice-over (about 380 words, natural pace)

V1 (0:00): Every week, people in India lose money to scams that don't look like scams. This is Sentinel.

V2 (0:12): A courier SMS on Monday. A call from an unknown number on Tuesday. On Wednesday, a "customs officer" on WhatsApp demands a UPI payment. Each message, on its own, can look harmless. Together, they are a classic fraud. Most filters judge one message at a time, so they miss it.

V3 (0:30): Sentinel is an Android guardian with a memory. It keeps track of the numbers, links and claims that have contacted you, and it warns you before you pay, share an OTP or install something.

V4 (0:42): Day one. An unknown sender says a parcel is held and shares a link. Sentinel doesn't interrupt you. It quietly remembers the number, the link and the courier claim.

V5 (1:00): Day two. The same number calls. Memory connects the call to the courier story, and Sentinel raises a caution. Still no interruption.

V6 (1:15): Day three. A message demands twenty-five thousand rupees to a UPI id and says, don't tell anyone. Now Sentinel interrupts. Critical alert.

V7 (1:40): And it tells you why. Here is the timeline: the courier SMS, the call, and this message, all linked. The next step is plain: don't pay, don't share an OTP, and verify through the official website or helpline. The reasons are what let a worried person, or a parent, trust the warning.

V8 (2:00): Under the hood, the phone sends events to our API. Text is redacted before it is stored or sent to a model. Deterministic rules set a minimum risk tier. Then an NVIDIA Nemotron agent, running on Nebius Token Factory, investigates with tools: entity history, domain age, a sandboxed link inspector, and Tavily web lookup. It can raise the tier only with tool-confirmed facts, never lower it. Embeddings on Token Factory link messages even when the numbers differ.

V9 (2:12): On our synthetic evaluation, scams spread across different numbers were caught zero times of twelve without memory, and nine times of twelve with it. These are synthetic scenarios, so treat the numbers as a first signal, not a promise.

V10 (2:24): Sentinel: a guardian that remembers, and explains.

Word count of V1-V10 is roughly 380. Time it aloud; if over 2:30, cut V2's second sentence and V8's last sentence first.

## Slides to show (from slides.md)

- Architecture slide: use `docs/submission/architecture.svg`. Label which model does what: Nemotron chat (agent), Nemotron VL (screenshots), Qwen3-Embedding-8B (memory), Tavily (web lookup).
- Ablation slide, exact content (source `backend/eval/report.md`, "Memory upgrade"): "Distributed slow-burn scams (12, each message harmless alone, different number each time): baseline 0/12, with semantic memory + story progression 9/12. False interrupts on 80 original benign weeks: 0.062 per user-week, unchanged. Synthetic, template-generated scenarios; the agent in this eval is a scripted stand-in; first holdout look was contaminated for 1 scenario (see report)." Keep the last line readable for at least 4 seconds.

## What to show for the "why" timeline

Open the critical alert detail. Scroll slowly so the camera sees, in order: the Critical label and headline, the heading "Why we flagged this", the earlier SMS (with the BlueDart text), the call entry, the highlighted "This message", and "What to do now". Do not show the raw sender number if you do not want it in the public video; the demo numbers are fixtures (9876543210), so showing them is fine.

## Shot list

1. Title card (static, 4 s).
2. Problem slide (static).
3. Screen recording: Home, 6 s.
4. Screen recording: Day 1 event, 10 s.
5. Screen recording: Day 2 event / caution, 10 s.
6. Screen recording: Day 3 notification then tap, 15 s.
7. Screen recording: why timeline scroll, 20 s.
8. Architecture slide, 12 s.
9. Results slide, 12 s.
10. End card, 6 s.

Record the phone with a screen recorder (adb `screenrecord`, `scrcpy --record`, or the phone's built-in recorder) at 1080p portrait. Record voice-over separately in a quiet room and lay it over the capture; do not try to speak and operate the phone live.

## Fallback plan

| Failure | Response |
|---|---|
| Server or network down (`curl https://interns.enthral.ai/sentinel/health` fails) | Run the backend locally: `docker compose up -d db`, migrate, start uvicorn, `adb reverse tcp:8000 tcp:8000`, install a debug build with `-PsentinelApiBaseUrl=http://localhost:8000`, run demo mode. Needs a fresh DB and API keys in `backend/.env` only if you want live Nemotron; without a key the pipeline still runs on rules and shows the alert. |
| Live notifications do not arrive | Switch to mode B or C. |
| Alert shows but the "why" timeline is thin | Use mode C (the demo story links events by design) and call it the demo timeline. |
| Everything fails on the phone | Use the committed screenshots in `android/docs/screenshots/minimal/` as stills (home, critical alert) with the voice-over; say they are from the emulator build. |
| Record a safety take early | Record a complete offline take of mode C first (before any live attempt) so you always have a usable video. |

## Pre-recording checklist

- [ ] Phone charged above 80 percent, cable attached; battery saver off.
- [ ] Do Not Disturb off; Sentinel notification channels enabled (Critical, High-risk); lock-screen notifications allowed.
- [ ] Notification access granted to Sentinel (Settings > Notification access) if using mode A.
- [ ] Fresh account: Settings > Delete my data, then onboard again, so Home shows zero alerts. Check the language (English).
- [ ] Clear other noisy notifications; hide personal contacts; use a clean wallpaper.
- [ ] Screen recorder tested with a 10 s clip including notification sounds and the status bar.
- [ ] `curl -s https://interns.enthral.ai/sentinel/health` returns `{"status":"ok"}`.
- [ ] Second phone ready with these exact texts (mode A or B). Sender for 1: any unknown number. Sender for 3: a different unknown number, WhatsApp.
  1. `Your BlueDart parcel is held at the depot due to an incomplete address. Call 9876543210 or visit http://bluedart-redelivery.in to reschedule.`
  2. A call from a number not in the contacts (the demo fixture number is +91 98765 43210; use any unsaved number).
  3. `This is a customs officer. Your parcel is seized with illegal items. Pay Rs 25,000 penalty immediately to customs.clear@ybl or face arrest. Do not tell anyone.`
- [ ] Do not click the payment link or pay anything; the texts are fixtures.
- [ ] Rehearse the full 2:30 twice; keep the first full clean take.
- [ ] Slides open in a browser at full screen (architecture.svg, results slide).

## Post-recording checklist

- [ ] Total length under 3:00 (or the limit on the Rules tab); audio levels even; no personal data, tokens or keys visible (check the notification shade and any terminal).
- [ ] Captions or on-screen text for the three events; the ablation caveat is readable.
- [ ] Export 1080p MP4; upload to the platform the rules allow; set visibility as the rules require (public or unlisted); open the link in a logged-out window and play it end to end.
- [ ] Add the link to the Devpost draft and to `README.md` if you want.
- [ ] Keep the raw footage and the local file as backup.
