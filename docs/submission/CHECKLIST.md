# Final submission checklist

Deadline: **30 Oct 2026, 10:00 AM PT = 22:30 IST** (PDT is UTC-7 on that date; confirm the stated deadline and time zone on the Devpost page). The Devpost site could not be read from here, so every "confirm" below is a manual check.

## Re-read before anything else
- [ ] Rules tab: eligibility, team size, required tech (Token Factory or Nebius AI Cloud, NVIDIA open model), video length and hosting rules, repo and licence requirements, whether judges need repo access, whether a working demo or test build is required, IP and commercialisation terms.
- [ ] Judging tab: criteria and weights; update `docs/submission/judging-notes.md` if they differ.
- [ ] Track names: select the track (planned: Personal AI; fallback Best Apps and Agents). Copy the exact names shown on the form.
- [ ] Side prize: Best Use of Tavily. Check whether it needs a separate opt-in, an extra field or a written note on how Tavily is used; the text is ready: "Tavily powers the `web_lookup` tool, which the agent uses to check a brand, domain or number during an investigation" (`backend/app/pipeline/tools.py`). Confirm `TAVILY_API_KEY` is set on the VM, or do not claim live Tavily use.
- [ ] Sponsor tech statement: "inference on Nebius Token Factory; NVIDIA Nemotron models". Do not say the API runs on Nebius AI Cloud.

## Devpost form
- [ ] Project name, tagline (use the elevator pitch from `docs/submission/devpost.md`, shortened if the field is short).
- [ ] Description sections pasted from `devpost.md` (includes the Honest limitations paragraph).
- [ ] Built with tags from `devpost.md`.
- [ ] Team members all added and accepted the invitation (names to be filled by you; the repo does not list the team).
- [ ] Screenshots uploaded (3-5): `android/docs/screenshots/minimal/alert-detail-critical-light.png`, `home-light.png`, `family-invite-light.png`, plus the architecture image (export `docs/submission/architecture.svg` to PNG) and the results slide.
- [ ] Video link added and tested logged out.
- [ ] "Try it" links: repo URL; live API health URL `https://interns.enthral.ai/sentinel/health` (not a web UI); APK link only if you host one.
- [ ] Submit, then confirm the status shows Submitted (not Draft). Take a screenshot.

## Repository
- [ ] Repo: `github.com/Aaron-Warner-4504/devpost-nvidia-hackathon`. Check whether it is public or private. If private, check whether the rules require judges to access it and how (usually adding a judge account or making it public); do that before submitting.
- [ ] Merge the docs branch (`docs/submission`) into `main` (or whichever branch the Devpost link points to); verify the README renders on GitHub (mermaid, images).
- [ ] Licence: no licence file exists. Decide: open-source (MIT/Apache-2.0) or all rights reserved. Check the Rules for any requirement. The Inter font in `android/third_party/inter/` is OFL; Laya is Apache-2.0 (optional extra). Add `LICENSE` and update the last section of `README.md`.
- [ ] No secrets committed: `git grep -n -i "api_key=.\+" -- . ':!*.example'` and check `backend/.env`, `deploy/.env.prod`, keystore files are not tracked (they are in `.gitignore`).
- [ ] Commit history has no tool-attribution trailers (project preference: remote is public and must not show Co-Authored-By lines).
- [ ] `backend/.env.example` and `deploy/.env.prod.example` still show earlier placeholder model IDs. Either update them to the deployed IDs (`nvidia/nemotron-3-super-120b-a12b`, `nvidia/Nemotron-3.5-Super-VL`) in a separate code-change commit, or leave as is; the README states this.

## Facts to confirm (marked unverified in the pack)
- [ ] Deployed model IDs match the README table (check the VM's `.env.prod`).
- [ ] Always-on capture: merged, or not? Update the status table in `README.md`, `devpost.md` limitations and `judging-notes.md` to match.
- [ ] Redaction location: if the Kotlin port is merged and verified, update the privacy wording; otherwise keep "redacted server-side".
- [ ] Release APK runs on the phone against the live server; semantic memory enabled and working on the live server (embedding key set, `SEMANTIC_MEMORY` not forced off).
- [ ] The Tavily and vision calls work on the live server, or remove the claim from the video.

## Timing plan (IST, assuming it is the night of 29 Oct)
| When (IST) | Task |
|---|---|
| 29 Oct night | Read Rules and Judging tabs. Decide the capture mode for the video. Sleep. |
| 30 Oct 07:00 | **Code freeze** (no feature work; only fixes that block the demo). Tag the commit. Build the release/debug APK to be used for the video. |
| 07:00-09:00 | Rehearse the capture on the phone twice; record the safety take of mode C; check `/health`. |
| 09:00-12:00 | Record the final screen capture and voice-over (see `demo-video-script.md`). |
| 12:00-14:00 | Edit, add slides, export, upload, test the link logged out. |
| 14:00-16:00 | Fill the Devpost form; update README status table; merge the docs; check repo access. **Save the draft.** |
| 16:00-19:30 | Final read-through of the Devpost page and video; **submit by 19:30 IST** (3 hours before the deadline). |
| 19:30-22:30 | Buffer only: fix a broken link, re-upload. Do not change the product. |

Starting later than planned: keep the 3-hour buffer and cut in this order: edit polish, slides beyond the two needed (architecture, results), extra screenshots. Do not cut the honest limitations.

## Do not
- Do not state numbers that are not in `backend/eval/report.md`; do not claim users, partnerships or pricing validation.
- Do not claim family push, always-on capture or on-device redaction unless verified on the day.
