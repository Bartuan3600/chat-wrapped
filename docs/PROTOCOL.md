# Chat Wrapped 1.1 – "Ganzen Chat analysieren" protocol (v1)

App: **Chat Wrapped 1.1** (`de.bartuan.chatwrapped`, versionCode 2). Same design as Ausgaben "Belege scannen":
transport **ntfy.sh**, result store **textdb.dev**, encryption **AES-256-GCM** with a key baked into the APK.
Own key + topic: `analysis-config.json` (project root, git-ignored; see `analysis-config.example.json`) (`aes_key_b64`, `req_topic`, `server`) → baked into
`BuildConfig.CW_KEY_B64 / CW_REQ_TOPIC / CW_SERVER` at build time. **The APK is therefore private – don't share it.**

```
App: gzip(whole export .txt) → split into ≤8 MB parts → each part AES-GCM blob → PUT ntfy.sh/<req_topic> (attachments, no tag)
App: request JSON → envelope → POST ntfy.sh/<req_topic>  Tags: chat-request   (> 4096 B: PUT as attachment <id>-request.json)
Worker: fetch_requests.py → pending/<id>/{request.json, chat.txt, stats.json, stats.md, result_template.json}
Analyst writes result.json → post_result.py → POST encrypted envelope to result_url (textdb.dev)
App: GET result_url every 15 s while the chat is open (also on resume / reopen)
```

## Crypto (identical to Ausgaben)
* AES-256-GCM, 12-byte random IV per blob/envelope, 128-bit tag appended, no AAD.
* Blob = `IV || ciphertext || tag` (raw bytes). Envelope = `{"id"?, "enc":"aes-256-gcm", "iv":b64, "data":b64(ct||tag)}`.

## Upload
* Plaintext = the exported chat text exactly as imported (UTF-8; ALL messages; media are not included – only the
  `<Medien ausgeschlossen>` markers that are in the text). gzip'd (typ. 4–6× smaller).
* Parts of ≤ 8 MB (ntfy.sh: 15 MB per attachment); total gzip ≤ 90 MB (ntfy.sh visitor quota 100 MB) else the app
  refuses with a message. Each part: `PUT https://ntfy.sh/<topic>`, `Filename: <id>-<n>.bin`, no tags.
* `parts[].sha256` = SHA-256 of the **encrypted** blob. `chat.sha256`/`bytes` = of the **plain** text (after gunzip).
* Resume: uploaded parts are persisted (`chats/<chatId>/analysis.json`); after a failure "Erneut versuchen" only uploads
  missing parts, as long as they are < 2.5 h old (ntfy attachments expire ~3 h) and come from the same bytes.

## Request (encrypted)
```json
{"v":1,"app":"chatwrapped","id":"<uuid>","t":"2026-10-19T20:01:35+02:00","title":"Lena & Jonas",
 "people":["Lena","Jonas"],"messages":6472,"first":"2025-11-02T00:17","last":"2026-10-15T22:10",
 "chat":{"format":"whatsapp-txt","compression":"gzip","bytes":312345,"sha256":"<hex>","gzip_bytes":35913,
         "parts":[{"n":1,"url":"https://ntfy.sh/file/….bin","sha256":"<hex of blob>","size":35941}]},
 "sections":["overview", …],"self_name":"Alex","lang":"de","result_url":"https://textdb.dev/api/data/<uuid>","enc":"aes-256-gcm"}
```
`self_name` (since 1.2, may be null): WhatsApp sometimes exports one side with an EMPTY sender (`30/05/2024, 12:36 -  : text`).
The user names it once in the app; `people` already contains that name, and the worker maps blank senders in chat.txt to it
(`stats.py --self-name`).

Published as `POST` with `Tags: chat-request`, `Title: chat-request`, body = envelope with plaintext `id`.
If the envelope > 4096 bytes (≈ 18+ parts) → `PUT` with the same tags, `Filename: <id>-request.json`,
`Message: {"id":…,"enc":"aes-256-gcm","envelope":"attachment"}`; the envelope is the attachment.
**Ignore messages without the `chat-request` tag** (part uploads). Dedupe by `id` (a "Neu analysieren" = new id).

## Result (write once; plaintext or – used by post_result.py – encrypted envelope; textdb limit ≈ 1–2 MB, keep < 900 KB)
```json
{"v":1,"id":"<same id>","status":"done|partial|error","t":"…","error":null,"score":86,"summary":"one line",
 "sections":[{"key":"who_writes_more","title":"optional (default per key)","emoji":"optional","headline":"Fast ausgeglichen",
   "body":"Fließtext", "score":null,
   "people":[{"name":"Lena","value":"50,5 %","text":"…"}],
   "bullets":["…"],
   "quotes":[{"sender":"Lena","date":"15.08.2026","text":"…","note":"…"}],
   "timeline":[{"period":"Nov–Jan","title":"…","text":"…"}]}]}
```
Every field in a section is optional; the app renders each section as a card in the order given. Person names in
`people[].name` / `quotes[].sender` should be exactly the names from `request.people` – the app shows the **name** with
the person's chart colour. Unknown keys are rendered too (give `title` + `emoji`). `score` on top (or in the
`compatibility` section) is shown as a 0–100 bar.

Section keys (all 21 in the template): overview, who_writes_more, response_times, starters_enders, activity_times,
streaks_pauses, mood_timeline, topics, running_gags, emojis, writing_style, questions_answers, conflicts, investment,
red_flags, green_flags, highlights, phases, compatibility, tips, verdict.

## Worker commands
| step | command |
|---|---|
| fetch new (resumes after last seen ntfy msg, exit 0 = new, 3 = none) | `python3 worker/fetch_requests.py` |
| re-scan ntfy cache | `python3 worker/fetch_requests.py --since 12h` |
| list | `python3 worker/list_pending.py` |
| stats for any export | `python3 worker/stats.py chat.txt --out DIR` |
| publish | `python3 worker/post_result.py <id> pending/<id>/result.json` (refuses TODO, wrong id; `--dry-run`) |
| routine poll | `./watch.sh 300` (or cron `*/5 * * * * python3 …/fetch_requests.py`) |

**Expiry:** ntfy.sh keeps attachments ~3 h and messages 12 h → fetch within 3 h. After fetching, the chat lives only in
`pending/<id>/chat.txt` (later `done/<id>/`); delete those folders when no longer needed.

## Tests
`AnalysisTest` (fake server: all bytes round-trip, multi-part, resume, attachment fallback, id check) and two live E2E runs
on fresh random topics/keys (`e2e/run-*`, never the production topic): single part and 22 parts + attachment envelope →
fetch_requests.py → byte-identical chat → post_result.py → Kotlin poll received 21 sections, score 86.

## Web/iPhone client (Chat Wrapped Web, PWA)
Same protocol. Extra optional request fields: `platform:"web-ios"`, `client:"pwa-1.0"`, `chat.export_format:"ios"|"android"`.
iOS exports (`[19.10.26, 12:36:01] Name: text`, `_chat.txt` inside the zip) are already parsed by stats.py.
Key + topic are NOT in the hosted files: they come from the private link fragment `#k=<b64 key>&t=<topic>` (stored in localStorage).
