"""Docs generator (run from anywhere): see AGENTS.md §6 "Add a setting"."""
import os
_HERE = os.path.dirname(os.path.abspath(__file__))
# the docs live in repo_files/docs in the dev repo and in docs/ in the public copy
DOCS = next(p for p in (os.path.join(_HERE, "..", "repo_files", "docs"), os.path.join(_HERE, "..", "..", "..", "docs")) if os.path.isdir(p))
# (label, key, type, default, range, layman effect, tech note)
S = {}
S["Name, personality & wake words"] = ("wake", [
 ("Name","name","String","Bob From Accounting","free text","What the assistant is called; also used in default wake and sleep phrases.","Used by `PersonaPrompt` and the widget name art."),
 ("Wake words","wake_phrases","List","the name-based defaults","any number","The phrases that wake this assistant. All are active at once.","Compiled to sherpa-onnx KWS keywords (`keywords.txt`); phrases with unknown tokens are listed as *Couldn't use*."),
 ("Wake words on","wake_enabled","Boolean","on","","Turn hands-free waking off; you can still tap *Talk now*.","Off → no KWS stream; the service keeps running for taps/widget."),
 ("Sensitivity","sensitivity","Float","0.5","0.05–0.95","Higher wakes more easily but also by mistake.","threshold = 0.35 − 0.2·s, boost = 1 + s. Applied with *Apply sensitivity* (rebuilds the spotter)."),
 ("Personality / How you respond / How you address the user / Extra instructions","p_personality, p_style, p_address, p_extra","String","built-in text","","The character's system prompt, split into four editable parts.","`null` = default text; *Reset … to default* removes the key. Legacy key `personality` is read once for migration."),
 ("Listening switch (Home)","service_wanted","Boolean","on","","Whether background listening should run, including after a restart.","Read by `BootReceiver` on `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`."),
])
S["Continuous conversation & timeouts"] = ("conversation", [
 ("Unlimited listening","listen_timeout_on (inverted)","Boolean","timeout on","","Once awake, never stop listening on its own.","`unlimited = !listen_timeout_on || continuous` (`ListenPolicy`)."),
 ("Wait for","listen_timeout","Int seconds","8","2–3600","How long to wait for you to start talking before going back to the wake word.","Measured as audio fed to the recognizer; +30 s once speech was heard. **Not used while Continuous conversation is on (the default).**"),
 ("seconds / minutes","listen_timeout_unit","String","s","s, m","Only how the timeout is shown.","Display only."),
 ("Continuous conversation","continuous","Boolean","on","","Keep listening after every answer until a sleep word or *End*.","Counts as unlimited; watchdog restarts the recognizer after 10 min with no progress."),
 ("Ask before sending messages","confirm_sms","Boolean","on","","Read a text back and wait for *yes* before sending it.","Brain pending-confirmation state."),
 ("Chime when I start listening","chime","Boolean","on","","Play a sound when it starts listening.","Sound slot `start`/`follow`."),
 ("Chime when I stop listening","stop_chime","Boolean","on","","Play a sound when it stops.","Sound slot `stop`."),
])
S["Sleep words"] = ("sleep", [
 ("Sleep words","sleep_phrases","List","goodnight <name>, that's all, goodbye, stop listening, never mind …","any number","Phrases that end the conversation right away.","Matched as equal, suffix, or prefix + ≤ 2 extra words."),
 ("Confirm with","sleep_confirm","String","voice","voice, chime, both","How it acknowledges going to sleep.",""),
 ("Tap the avatar to end","avatar_tap_ends","Boolean","on","","Tapping the bubble during a conversation ends it.","Long-press menu always has *End conversation*."),
])
S["Text-to-speech (Voice)"] = ("voice", [
 ("Voice","voice","String","vits-piper-en_US-ryan-medium-int8","installed voices","Which voice speaks.","Directory name under `files/models/voices`."),
 ("Speed","voice_speed","Float","1.0","0.6–1.6","Speaking speed.","Piper `length_scale = 1/speed`."),
 ("Volume","voice_volume","Float","1.0","0–1","Loudness of the voice and chimes, separate from the phone volume.","`AudioOut.volumeProvider`: gain before the 0.97 peak limiter; also used for chimes (`Sounds.play`)."),
 ("Masculine ↔ Feminine","voice_shift_<voice>","Float","0","−1…1","Shifts the voice lower or higher, saved per voice.","`VoiceShift`: pitch ±4 semitones (WSOLA + resample) and formants ∓/±10 % (cepstral envelope), applied separately so it does not sound chipmunk-like."),
 ("Expressiveness","tts_noise","Float","0.667","0.1–1.0 (stored 0–1.2)","How lively the intonation is.","Piper/VITS `noise_scale`."),
 ("Rhythm variation","tts_noise_w","Float","0.8","0.1–1.2","How much timing varies.","VITS `noise_scale_w`."),
 ("Preview phrase","preview_phrase","String","\"Hey there, sugar. How does this sound?\"","","What *▶ Preview* says.",""),
 ("Speaker","voice_speaker_<voice>","Int","0","Kokoro 0–10, LibriTTS 0–903","Who talks, for voices with several speakers (Kokoro: 11 named, LibriTTS: 904 numbered). *Speaker* card at the top of the page; saved per voice.","Clamped by sherpa-onnx to the model's speaker count. Falls back to the older single `voice_speaker` for the voice in use."),
])
S["2D avatar"] = ("avatar", [
 ("Choose image","avatar_file","String","\"\" (built-in)","file in filesDir","Your own avatar picture (PNG/JPG/WebP/GIF).",""),
 ("Talking mouth","avatar_mouth","Boolean","on","","Mouth moves with speech.","Driven by TTS amplitude."),
 ("Body animation","avatar_animate","Boolean","on","","Breathing, sway and head motion.","Skin2D 20×20 mesh warp."),
 ("Animated GIF","avatar_gif_mode","String","play","play, still","Play the GIF, or use its first frame fully rigged.",""),
 ("Mouth & joints","rig_<avatarKey>","String (JSON)","auto","","Where the mouth and joints are.","Set by *Place mouth & joints* / *Auto-place again*."),
 ("Framing","framing_same, framing_<avatar>_<surface|all>","Boolean / String","same for all","home, bubble, widget","Zoom and position of the picture per place.","Set in the framing editor."),
])
S["Bubble & widget"] = ("bubble", [
 ("Show avatar bubble","avatar_on","Boolean","on","","Floating bubble over other apps.","Needs *Display over other apps*."),
 ("Show","avatar_mode","String","always","always, active","Always, or only while listening/thinking/speaking.",""),
 ("Size","avatar_size","Int dp","72","40–160 (stored ≤ 200)","Bubble size.",""),
 ("Opacity","avatar_opacity","Float","0.95","0.2–1","Bubble transparency.",""),
 ("Tuck away after","avatar_autohide","Int seconds","8","0–120 (0 = never)","Idle bubble slides half off the edge.",""),
 ("Bubble position","avatar_x, avatar_y","Int px","−1 (right edge), 400","","Where you dragged it. *Reset position* clears it.",""),
 ("Glow around the screen edges","edge_glow","Boolean","on","","Coloured light at the screen edge while active.",""),
 ("Glow colour","glow_color","Int ARGB","#4FC3F7","palette","Colour of that light.",""),
])
S["3D avatar & photo → 3D"] = ("avatar3d", [
 ("Workflow & run settings (ComfyUI .json)","files/workflow3d.json, workflow3d_source.txt","File","phone defaults (official TRELLIS.2 workflow, fitted)","","Import a ComfyUI workflow; its values are fitted to this phone (step counts, seed, crop, triangles). The table shows workflow value → value used → why.","`Workflow3DSettings.parse` → `Run3DPlan.plan(wf, overrides, Device)`; unknown nodes are listed, not guessed."),
 ("Your changes to the workflow values","files/workflow3d_overrides.json","JSON","none","steps, cfg, scheduler, random seed, seed, crop, triangles, background","Per-value overrides with a reset each, and *Reset all my changes*.","`Workflow3DOverrides`; *your value* wins over the fitted value, clamped to safe ranges."),
 ("Show 3D on Home and the bubble","show3d","Boolean","on","","Use the 3D model when this assistant has one.",""),
 ("Rig","rig_style3d","String","skinned","skinned, rigid","Smooth bending, or N64-style rigid parts, for models without a skeleton.","AutoRig3D v6."),
 ("Motion","anim_strength","Float","1","Full 1, Balanced 0.5, No stretching 0","How much the body stretches when it moves.",""),
 ("Look (shader)","shader3d","String","none","none, retrostation, retro64","Modern, PS1-style or N64-style look. One at a time.",""),
 ("Intensity","shader3d_intensity","Float","0.8","0–1","Strength of the retro effect.",""),
 ("No depth buffer","psx_no_depth","Boolean","off","","Retrostation: authentic PS1 polygon pop.","Painter's-order CPU sort."),
 ("Fog","n64_fog","Boolean","on","","Retro64 distance fog.",""),
 ("Automatic for this phone / Detail","detail3d","Float","−1 (automatic)","0–1","Fewer triangles and pixels for speed.","Detail3D tiers by RAM/cores."),
 ("<Surface> own setting / detail","detail3d_home, detail3d_bubble, detail3d_widget","Float","−1 (same as global)","0–1","Different detail for Home, bubble or widget.",""),
])
S["Background"] = ("background", [
 ("Background","bg_mode","String","own","own, custom","The picture's own background, or one you choose.",""),
 ("Background file","bg_file","String","\"\"","files/backgrounds","Still image, GIF/animated WebP/AVIF, or a video clip.","Video: 12 s window, ≤ 96 frames at ≤ 320 px."),
 ("Background sound","bg_sound","Boolean","off","","Play a video background's sound.",""),
 ("Background volume","bg_volume","Float","0.5","0–1","Its loudness.",""),
])
S["Widget name"] = ("look", [
 ("Name style","name_style","String","plain","plain, gold, silver, rgb","How the name is drawn on the widget.","Per assistant."),
 ("Name font","name_font","String","sans","sans, serif, mono, casual, cursive, condensed, black, file:<font>","Font of the name; you can import .ttf/.otf.",""),
])
S["Chimes & sounds"] = ("chimes", [
 ("Start / Follow-up / Stop / Sleep sound","sound_start, sound_follow, sound_stop, sound_sleep","String","\"\" (built-in)","\"\", ring:<uri>, file:<name>","Replace a chime with a phone sound or your own file.","Clips over 8 s are cut."),
])
S["Thinking model"] = ("model", [
 ("Model for this assistant","llm_file","String","\"\" (none)","installed .gguf","Which brain this assistant uses.","Empty → rule commands only."),
 ("Load assistant on demand","on_demand","Boolean","on","","Load the big parts only when woken, to save memory and battery.",""),
 ("Unload after idle","unload_after","Int seconds","60","10–600 (stored ≤ 3600)","How soon to free memory after a conversation.",""),
 ("Processor threads","llm_threads","Int","0 (auto)","0–8","CPU cores used for thinking.","Auto: 4 if ≥ 8 cores, 2 if ≥ 4, else 1."),
])
S["Memory"] = ("memory", [
 ("Remember our conversations","memory_on","Boolean","on","","Long-term memory for this assistant, kept on the phone only.","See [Memory](features/memory.md)."),
])
S["Internet & search"] = ("internet", [
 ("Allow internet access","internet","Boolean","**off**","","Lets it look things up on the web.","`Net` refuses every call while off."),
 ("Mode","browsing_mode","String","headless","headless, frontend","Read results invisibly, or also open them in your browser.",""),
 ("Also read the browser tab","frontend_a11y","Boolean","off","","Frontend mode: read the open tab with Accessibility.",""),
 ("Search providers order","search_order","List","Brave, DuckDuckGo, SearXNG, Wikipedia","drag order","Which search engine is tried first.",""),
 ("Provider switched off","search_disabled","Set","none","","Skip a provider.",""),
 ("SearXNG address","searx_url","String","\"\"","URL","Your own SearXNG server.","http allowed for local addresses."),
 ("Pages to read per question","pages_to_read","Int","3","1–5","How many result pages are read before answering.",""),
])
S["News feeds"] = ("news", [
 ("Feeds","news_feeds","List \"Name|url\"","BBC World, NPR, PBS NewsHour, Guardian World, Al Jazeera","any RSS/Atom","Where *what's the news* reads from.",""),
])
S["Permissions & background listening"] = ("setup", [
 ("Keep the processor awake while listening","keep_cpu_awake","Boolean","on","","Keeps the wake word working with the screen off.","Partial wake lock."),
])
S["Appearance"] = ("look", [
 ("Theme","theme","String","dark","system, light, dark","App colours.",""),
 ("Wallpaper colours","wallpaper_colors","Boolean","off","","Android 12+: colours from your wallpaper.",""),
 ("Large text","large_text","Boolean","off","","Bigger text in the app.",""),
 ("Widget hint","widget_hint_done","Boolean","off","","Hides the *Add widget* hint after *Got it*.",""),
])
G = [
 ("Settings view","belle_global › simple_settings","Boolean","Simple","Simple shows a short page of the most-used settings; Advanced shows every page."),
 ("Assistant list","belle_global › ids","String","default","All assistants (profiles)."),
 ("Active assistant","belle_global › active","String","default","The one Home and the widget show."),
 ("Wake priority","belle_global › priority","String","list order","Which assistant answers when phrases overlap."),
 ("Let each assistant answer its own wake words","belle_global › multi_wake","Boolean","off","All assistants listen at once."),
 ("Last model","belle_global › last_llm","String","\"\"","Suggested for new assistants."),
 ("Book speed","book_reader › speed","Float","1.0","Reading speed 0.6–2.0 (± 0.15 per *faster/slower*)."),
 ("Last book","book_reader › last","String","none","Resumes *continue the book*."),
 ("Widget look","belle_widgets › mode_<widgetId>","String","2d","2D or 3D per widget, toggled on the widget."),
 ("Widget moving picture","belle_widgets › animate","Boolean","on","Avatar › Home-screen widget › *Moving picture*. Off = a still picture, also while talking (saves battery)."),
 ("Reminders to come","files/reminders.json","JSON list","empty","Reminders (and the app's own alarm/timer fallback) not yet fired; re-armed after a restart, an update or a force stop."),
 ("Avatar Studio progress","files/avatar_studio/studio.{0,1}.ck, picture.png","Checkpoint (sealed JSON)","not started","Which assistant, picture and 2D/3D you chose and how far you got; the Studio reopens there."),
 ("Photo → 3D job","files/pixal3d_job/job.json + ck/","Sealed JSON + checkpoints","none","An unfinished photo → 3D run (settings with fixed seeds, the photo, and stage checkpoints). It continues after a kill or restart; up to 3 tries."),
 ("3D speed on this phone","files/pixal3d_times.json","JSON","first guess: the plan's desktop times × 10","Measured seconds per step and per stage on this phone, saved after every timed step (so an interrupted build counts too); used for the time-left countdown. Ignored if restored on another phone."),
 ("Asked about battery limits","belle_global › battery_prompted","Boolean","off","Set after the one-time *run without battery limits* request before the first 3D build, so it is never asked again."),
 ("3D build history","files/pixal3d_history.{0,1}.ck + pixal3d_history_events.log","Sealed JSON + text","empty","The last 30 photo → 3D runs (who for, start/end, stage, result or error, duration, peak memory) and their redacted event lines; shown in the 3D build log and in *Export log*."),
 ("Last generated model","files/models/3d/last_generated.glb + last_generated.attached","glTF + JSON","none","The newest photo → 3D result and which assistant got it (hash + profile). A model with no matching marker is offered under *Attach last generated model to…*."),
 ("Download speed","downloads_eta › bytes_per_sec","Float","none","Last smoothed download speed, so a resumed queue starts with a sensible time left."),
 ("3D licence accepted","pixal3d_install › accepted_<licence>","Boolean","off","DINOv3 licence accepted."),
 ("Unfinished downloads","downloads_wanted › ids; files/models/3d/.install_wanted","Set / marker","none","Downloads you started that resume after an app kill or restart."),
]
out=[]
w=out.append
w("# Settings reference\n\n> *Every setting, what it does, its default, and where it is stored.*\n\n<!-- nav -->\n")
w('<p align="center"><img src="images/screens/settings_simple_1.png" width="280" alt="Simple settings"> &nbsp; <img src="images/screens/page_conversation_1.png" width="280" alt="Conversation settings page"><br><sub>Emulator renders · Simple view (left) and an Advanced page (right)</sub></p>\n')
w("## How settings work\n\n- **Each assistant has its own settings.** Name, voice, wake words, avatar, model and so on belong to the assistant you are editing; switch at the top of Settings. A few settings are shared by all assistants; they are listed under [App-wide](#app-wide).\n- **Simple or Advanced.** *Simple* shows the handful you'll actually change. *Advanced* shows every page below.\n- **Changes apply straight away.** The two exceptions are *Apply sensitivity* and the bubble *Apply* button.\n- **Export / import an assistant** (Assistants page) copies all of that assistant's settings as a small JSON file. It does not include memory, pictures or model files.\n")
w("<details><summary>Technical details</summary>\n\n- **Storage:** each assistant is an Android `SharedPreferences` file named after its id, wrapped by `Prefs.kt`. Global keys live in `belle_global` (`ProfileStore`). Other files: `book_reader`, `belle_widgets`, `pixal3d_install`.\n- **Live updates:** `Prefs` getters read `SharedPreferences` on every access, so most keys take effect on the next use. Wake-phrase and sensitivity changes call `AssistantRuntime.reloadWakeWords`. A new speech pack sends `ACTION_RELOAD` to `AssistantService`. Voice and model choices are read when those engines load, which happens at the next wake when *load on demand* is on (the default).\n- **Export format:** `{\"belle_profile\":1,\"settings\":{…}}`. Import gives the assistant a new 8-character id.\n- **Clamping:** setters clamp values, so a hand-edited export can't set out-of-range values. The *Range* column shows the slider range, and the stored range where it is wider.\n\n</details>\n")
import re
def slug(t): return re.sub(r'[^\w\- ]','',t.lower()).replace(' ','-')
w("## Contents\n\n"+"\n".join(f"- [{t}](#{slug(t)})" for t in S)+"\n- [App-wide](#app-wide)\n")
for title,(pid,rows) in S.items():
    w(f"## {title}\n")
    w("| Setting | Default | What it does |\n|---|---|---|")
    for r in rows: w(f"| **{r[0]}** | {r[3]} | {r[5]} |")
    w("\n<details><summary>Technical details</summary>\n")
    w("| Key | Type | Range | Notes |\n|---|---|---|---|")
    for r in rows: w(f"| `{r[1]}` | {r[2]} | {r[4] or '—'} | {r[6] or '—'} |")
    w("\n</details>\n")
w("## App-wide\n\nThese are shared by every assistant.\n\n| Setting | Default | What it does |\n|---|---|---|")
for g in G: w(f"| **{g[0]}** | {g[3]} | {g[4]} |")
w("\n<details><summary>Technical details</summary>\n\n| File › key | Type |\n|---|---|")
for g in G: w(f"| `{g[1]}` | {g[2]} |")
w("\n</details>\n")
w("## Settings that don't do anything\n\nNone, as of 0.1.3. Earlier versions had three: `mem_budget_mb` (stored but never read) was **removed**; the speaker (`voice_speaker`) and the widget's `animate` flag now have controls (*Speaker* card, *Moving picture*).\n\n<!-- nav -->\n")
open(os.path.join(DOCS,'settings-reference.md'),'w').write("\n".join(out))
