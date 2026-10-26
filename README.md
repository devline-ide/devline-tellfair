# Tellfair for DevLine

Host extension for text to speech, independently packaged as a .dlextension. It is included in the desktop bundled-extension catalog and stays outside application dependencies and exported runtimes.

Tellfair registers its own Settings > Extensions > Tellfair page with webview location: "settings". Add, replace or remove the API key there, then create or open a .tfspeech document. The right Tellfair dock and speech editor link to those settings; neither contains the key form. The key stays in the IDE profile at tellfair/api-key and is never written to the project, document, logs, remote account URL or webview state.

The speech editor supports presets, built-in/custom voice IDs, seed, expression, adherence, temperature, speed and MP3/WAV/Opus. Ctrl+S saves through the IDE document buffer and Ctrl+Z/Ctrl+Y use its shared history. Generation starts only on the Generate action. It runs in a bounded background job; Cancel interrupts it. Audio is transferred in bounded local chunks for playback and can be exported beside the document with a unique filename. Existing audio is never overwritten. Closing the document or project releases its job.

The single Tellfair panel lives in the right dock. Its Create/Open launcher stays above a separate top-level native browser surface, never a nested SDK iframe. In Settings > Extensions > Tellfair, paste the private Mini cabinet link copied from https://tellfair.com/account below API keys. It is stored in the local profile (tellfair/mini-cabinet), is not returned to webviews or MCP, and can be replaced/removed. Without a link the native view opens the account page. A hidden panel closes its remote page; overlays temporarily hide it. Browser-only hosts retain a compact API balance/usage view and external account action. The .tfspeech editor always uses its own local SDK view.

API contract: [Tellfair documentation](https://tellfair.com/docs), POST /api/v1/tts with Bearer authentication, GET /api/v1/presets without a key (presets, voices, piperVoices). Version 0.1.6 adds lazy authenticated balance, voices, usage, remoteJobs and remoteJob queries through the existing DevLine MCP extension bridge. Read requests run in a bounded worker, return state=loading until ready, cache account data for 30 seconds (remote job status for one second), time out and never start paid synthesis. No separate Tellfair MCP package/server configuration is required. Custom voices are selectable by discovered IDs; Piper uses voiceId and is Russian-only, with no emotion parameters. Maximum 5000 characters per request. No automatic retries for paid synthesis. Responses distinguish invalid keys, balance/plan access, rate limits and server/network failures.

Example:

~~~json
{
  "schemaVersion": 1,
  "kind": "tellfairSpeech",
  "text": "Hello, DevLine!",
  "presetId": "news-anchor",
  "format": "mp3"
}
~~~

Extensions can declare a webview with location: "sidebar" and a shared icon name. Other webviews default to location: "dock". Activity ordering affects contributed sidebar icons only; Explorer, Search and Git remain first. Drag icons, use Alt+Up/Down, or choose Move up/down from their context menu. Visibility and order are stored with extension preferences in the IDE profile, with version checks on writes.

Build: ./gradlew :devline-tellfair:extensionArchive. Focused checks: :devline-tellfair:tellfairCheck and :devline-ide-extensions:extensionActivityCheck. Both use local fixtures; they never spend Tellfair credits. The installable archive declares API 6, DevLine >= 0.1.0 and its required document, media, settings, ui.inline-launcher and ui.native-browser-settings capabilities. Older hosts reject the archive before activation.

Validation on 2026-10-23: Kotlin provider/API/desktop dispatcher compilation, strict Vue checks and isolated production UI builds passed. A local HTTP fixture verified request bodies, chunked audio, no-overwrite export, document ownership and HTTP errors. Real service-only Main in headless Chromium verified lazy activation, right-click without loading, fixed core order, drag/keyboard sorting, file creation, Ctrl+S/disk bytes, undo, light/dark rendering, hidden panel unload and reload persistence. A second sidebar contribution was included in the QA package to exercise sorting. Paid production synthesis was not run without a user key. Full Gradle assembly was unavailable due to the shared wrapper cache lock permissions; the archive was assembled with the cached compiler and bundled JDK and validated through the real extension loader. No user IDE was restarted.

UX 0.1.1: compact voice inspector with search, inline key setup, collapsible numeric parameters with draft validation, Ctrl+Enter, stale-audio feedback, long-text warning without truncation, and a responsive text-first layout. All default bundling and lazy-sidebar behavior remain in place. Focused UI evidence: build/qa-tellfair-ux-20261023/acceptance-r3/proof.json; paid TTS used local transport fixtures only.

UX 0.1.2: explicit host requirements and a Check again action after interrupted result/status transfer. Checking the existing job never submits another paid synthesis.

Settings 0.1.4: the extension registers its own Settings > Extensions page using location: "settings" and requires ui.settings. Source QA covers invalid-key validation, private save/removal with cleared fields, RU/light and EN/dark, a credential-free account surface, editor settings links and draft/save behavior without paid synthesis. Evidence: build/qa-settings-cabinet-20261024/proof.json and site-embedding-policy.json.

Player 0.1.5: shared waveform transport with seek, volume/mute, playback speed and repeat; controls survive new clips. Charged counts use Russian/English cardinal forms. Export refreshes its directory and offers Show in project while retaining unsaved text. Requires editor.reveal. Actual runtime/export and headless UI evidence: build/qa-tellfair-player-20261024/acceptance-r3/proof.json; synthesis transport was a loopback fixture only.
