# Security & verification

> *How to check that the app you downloaded is the real one, what is scanned automatically, and how to report a problem.*

<!-- nav -->
<p align="center"><sub><a href="glossary.md">← Glossary</a> &nbsp;·&nbsp; <a href="README.md">Docs home</a></sub></p>
<!-- /nav -->

**Live results:** the docs site's **[Security & verification](https://milkymoomoo.github.io/Fully-Local-Agentic-Assistant/security.html)** page shows the newest scan of every release first, with the history of older versions below.

## Check your download in one minute

Every release lists the **SHA-256** of each file (a fingerprint: change one byte and it no longer matches) in `SHA256SUMS.txt` and on the security page. Compute it for the file you downloaded and compare.

| Your computer | Command |
|---|---|
| **Windows** (PowerShell) | `Get-FileHash .\MilkyMoosAssistant-arm64-v8a.apk -Algorithm SHA256` |
| **Windows** (Command Prompt) | `certutil -hashfile MilkyMoosAssistant-arm64-v8a.apk SHA256` |
| **macOS** | `shasum -a 256 -c SHA256SUMS.txt --ignore-missing` (prints *OK*) |
| **Linux** | `sha256sum -c SHA256SUMS.txt --ignore-missing` (prints *OK*) |
| **Android** (Termux) | `sha256sum /sdcard/Download/MilkyMoosAssistant-arm64-v8a.apk` |

The APK is also **signed**. Its signing certificate's SHA-256 is:

```
0e:5a:b9:71:bb:55:b0:ff:14:0d:ac:bc:cb:75:04:3e:ae:1f:88:3c:4e:ec:4c:2b:66:4f:fa:71:ea:6f:ed:8b
```

Check it with `apksigner verify --print-certs MilkyMoosAssistant-arm64-v8a.apk` (Android SDK build-tools). Android only installs an update over the app if it carries the same certificate.

## What is scanned automatically

| Scan | What it looks for | When |
|---|---|---|
| **VirusTotal** | Each release file checked by about 70 antivirus engines | On every release, re-checked weekly. Files are looked up by fingerprint first and only uploaded if VirusTotal has never seen them |
| **CodeQL** | Security bugs in the Kotlin/Java and C/C++ code | Every push and weekly |
| **gitleaks** | Passwords, keys or tokens committed by mistake | Every push and weekly |
| **OSV-Scanner** | Libraries with known vulnerabilities | Every push and weekly |
| **OpenSSF Scorecard** | How the project is run (pinned actions, branch protection, reviews…) | Every push to main and weekly |

**A scan that hasn't finished yet** shows *pending, last result from <date>* instead of an empty card. VirusTotal's free tier allows 4 requests a minute and 500 a day; when that runs out, the scan waits in a queue and finishes in a later hourly run.

**About antivirus warnings:** an app that listens for a wake word, reads the screen through Accessibility (only if you turn it on) and can call or text is the kind of app some engines flag by its permissions alone. One or two generic detections on a new file are common; the security page links every result so you can read what was found.

<details><summary>Technical details</summary>

- **Workflows** (`.github/workflows/`), all actions pinned to a commit SHA:
  - `virustotal.yml`: on `release: published`, hourly (finishes queued scans) and by hand. `tools/security/vt.py` uses VirusTotal API v3: `GET /files/{sha256}` first; upload (`/files`, or `/files/upload_url` above 32 MB) only on 404; then polls `/analyses/{id}`. Rate limiting: ≥ 15.5 s between requests, a daily budget of 480 counted in `security/quota.json`; on HTTP 429 / `QuotaExceededError` the file goes to `security/queue.json`. Without the `VT_API_KEY` secret the job skips cleanly.
  - `security.yml`: CodeQL (`java-kotlin` built by hand; `c-cpp` with build-mode none; `security-extended` queries; `third_party/` ignored), gitleaks 8.30.1, OSV-Scanner 2.7.1, and on schedule a VirusTotal recheck. A final job records the results.
  - `scorecard.yml`: OpenSSF Scorecard with `publish_results: true`.
- **History** (the `gh-pages` branch, append-only): `security/history/<version>/<scan>-<date>.json` (created with exclusive create, never overwritten), `security/index.json` (entries, versions, pending), `security/badges/<scan>.json` (shields.io endpoint badges). All writers share the concurrency group `gh-pages` and rebase-and-retry on push.
- **Release notes** get a link to their version's entry: `security.html#v<version>`.
- **Code:** `tools/security/` (`vt.py`, `summarize.py`, `record.py`, `pages.sh`, `merge_state.py`); tests: `python3 tools/security/test_security.py`.

</details>

## Reporting a vulnerability

Please use GitHub's **private vulnerability reporting** (*Security › Report a vulnerability* on the repository) rather than a public issue. Include the version (Settings › Privacy › About this build) and steps to reproduce. Never include personal data, recordings or your memory database.

## What the app does and doesn't send

The app has no account, no analytics and no server. It goes online only for downloads you start, optional web answers (off by default) and voice samples. Details: [Privacy, permissions and storage](features/privacy-permissions-storage.md).

<!-- nav -->
<p align="center"><sub><a href="glossary.md">← Glossary</a> &nbsp;·&nbsp; <a href="README.md">Docs home</a></sub></p>
<!-- /nav -->
