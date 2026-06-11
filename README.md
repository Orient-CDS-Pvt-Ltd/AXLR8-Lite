# Orient AXLR8 Lite

An open-source Eclipse plugin that brings AI chat to ABAP development. Free, lightweight, focused.

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Eclipse Marketplace](https://img.shields.io/badge/Eclipse-Marketplace-orange)](https://marketplace.eclipse.org/)

---

## What it does

AXLR8 Lite adds an **AI chat panel** to your Eclipse IDE, tuned for SAP ABAP development:

- **Chat with 2 LLM providers** — Claude Code (via your Claude Pro/Max subscription) or GitHub Models (via your GitHub PAT, free for Copilot subscribers)
- **Read the active editor** — chat sees the ABAP file you're working on
- **EHP8 / S/4HANA mode toggle** — AI respects your SAP target version (no RAP suggestions on EHP8, etc.)
- **Apply suggestions to editor** — write AI-generated code back to the active file with one click
- **Expand window & Copy Code** — pop the conversation into a large markdown-rendered window; one-click copy of any code block

That's it. No SAP credentials needed. No deploy. Just chat that's smarter about ABAP than generic AI tools.

---

## Install

### From Eclipse Marketplace
1. Eclipse → **Help → Eclipse Marketplace**
2. Search **"AXLR8 Lite"**
3. Click Install

### From update site
1. Eclipse → **Help → Install New Software → Add…**
2. Location: `https://orient-cds-pvt-ltd.github.io/AXLR8-Lite/`
3. Select **AXLR8 Lite** → Next → Finish

### Manual dropin
1. Download the latest JAR from [Releases](https://github.com/Orient-CDS-Pvt-Ltd/AXLR8-Lite/releases)
2. Drop into `<eclipse-install>/dropins/`
3. Restart Eclipse with `-clean`

---

## First-run setup

1. **Window → Show View → Other → Orient AXLR8 Lite → AXLR8 Lite Chat**
2. Click **⚙ Settings** in the chat toolbar
3. Pick your provider:
   - **Claude Code** — requires the [Claude CLI](https://docs.anthropic.com/claude/docs/claude-code) installed locally. No API key needed if you have a Claude Pro/Max subscription.
   - **GitHub Models** — paste a GitHub PAT with `models:read` scope. [Create one here](https://github.com/settings/personal-access-tokens). Free for Copilot subscribers.
4. Pick your SAP target (EHP8 or S/4HANA) — affects what features the AI suggests
5. Open an ABAP file, type a question in the chat input, press `Enter` to send (`Shift+Enter` for a newline)

---

## What's in this repo

```
.
├── src/                        Java source (~6,500 LOC)
│   ├── com/abapai/plugin/      Reused infrastructure (AI backends, token budgeting)
│   └── com/orient/axlr8lite/   Lite-specific UI + orchestrator
├── META-INF/MANIFEST.MF        OSGi bundle metadata
├── plugin.xml                  Eclipse extension points
├── feature.xml                 p2 feature descriptor
├── build.properties            PDE build config
├── .project / .classpath       Eclipse PDE project files (import-ready)
├── LICENSE                     Apache 2.0
└── README.md                   You are here
```

---

## Build from source

This is a standard Eclipse PDE plugin — the easiest build is right inside Eclipse.

### Prerequisites
- JDK 17+
- Eclipse for RCP/Plug-in Developers (2024-12 or later), with the PDE tooling

### Build in Eclipse (recommended)
1. **File → Import → General → Existing Projects into Workspace** → select this folder.
   The bundled `.project` / `.classpath` make it import as the `com.orient.axlr8lite` plugin.
2. Eclipse compiles automatically (JDT). Fix nothing — all dependencies are
   standard Eclipse runtime bundles declared in `META-INF/MANIFEST.MF`.
3. **File → Export → Plug-in Development → Deployable plug-ins and fragments**
   → select `com.orient.axlr8lite` → choose a destination → Finish.
   Eclipse produces `plugins/com.orient.axlr8lite_<version>.jar`.

### Build from the command line (optional)
```bash
# Compile against the Eclipse plugin set (adjust the path to your install's
# plugins pool, e.g. ~/.p2/pool/plugins or <eclipse>/plugins):
javac -source 17 -target 17 -encoding UTF-8 \
      -cp "<eclipse-plugins>/*" \
      -d bin $(find src -name '*.java')

# Package the bundle JAR:
jar cfm com.orient.axlr8lite_1.0.0.jar META-INF/MANIFEST.MF \
    -C bin . plugin.xml
```

### Run it
Drop the exported JAR into `<eclipse-install>/dropins/` and relaunch Eclipse
with `-clean`, or install from the p2 update site (see Install section above).

---

## What's NOT in AXLR8 Lite

Lite is the **free chat tool**. For the full ABAP-development experience, see **AXLR8 + ACTV8** (paid):

| Feature | Lite | Full (AXLR8 + ACTV8) |
|---|---|---|
| Chat with multiple LLMs | 2 providers | 6 providers (+ OpenAI, Claude API, Gemini, Codex CLI) |
| Read active editor as context | ✓ | ✓ |
| RAG / workspace context | ✗ (Full only) | ✓ |
| EHP8 / S/4 mode toggle | ✓ (basic) | ✓ (deep 42-rule compat engine) |
| **Live SAP schemas fetched into prompts** | ✗ | ✓ |
| **Compat auto-rewriter on AI output** | ✗ | ✓ |
| **ACTV8: FSD → ABAP project generator** | ✗ | ✓ |
| **Safe SAP deploy (EHP8 + S/4) with AI Fix loop** | ✗ | ✓ |
| **TSD document generation** | ✗ | ✓ |
| **ATC + ST22 + Transport features** | ✗ | ✓ |

→ Learn more: **[https://aiplugin.genesispro.ai/](https://aiplugin.genesispro.ai/)**

---

## Contributing

Contributions welcome. Open an issue first for anything beyond a small fix.

- **Bug reports**: GitHub Issues
- **Feature requests**: please prefix `[Lite scope]` so we don't drift into Full-only territory
- **Pull requests**: keep them focused; include a smoke-test note

---

## License

Apache License 2.0. See [LICENSE](LICENSE).

Copyright 2026 OrientCDS Private Limited.

---

## Acknowledgements

Built on Eclipse PDE, SWT, JFace. Uses [json-java](https://github.com/stleary/JSON-java) (forked, Apache 2.0).
