# Download buttons (template)

`make_button.py` renders the big README / docs-site buttons from `button.html` with headless Chrome, as a light
(`<name>.png`) and a dark (`<name>-dark.png`) PNG, transparent around the button, 1800 px wide (shown at ~600–720 px).

| Button | Command | Status |
|---|---|---|
| DOWNLOAD PREMADE APK HERE | `--platform android --out ../repo_files/docs/images/buttons/download-apk` | in use |
| CHECK IT ON VIRUSTOTAL | `--platform verify --out ../repo_files/docs/images/buttons/verify-virustotal` | in use |
| DOWNLOAD PREMADE WINDOWS PROGRAM HERE | `--platform windows --out ../repo_files/docs/images/buttons/download-windows` | **not yet**: add when the Windows build ships |

Rules: all caps, short, no "exe" in the label. Each button links straight to a file under
`https://github.com/Milkymoomoo/Fully-Local-Agentic-Assistant/releases/latest/download/<stable name>`; the release
scripts upload every asset under a versioned name and a stable one (`MilkyMoosAssistant-<abi>.apk`, later
`MilkyMoosAssistant-windows-x64.zip`).

The Markdown snippet (README and docs pages; GitHub swaps the image with the reader's theme):

```html
<a href="https://github.com/Milkymoomoo/Fully-Local-Agentic-Assistant/releases/latest/download/MilkyMoosAssistant-arm64-v8a.apk">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/images/buttons/download-apk-dark.png">
    <img alt="DOWNLOAD PREMADE APK HERE" src="docs/images/buttons/download-apk.png" width="640">
  </picture>
</a>
```
