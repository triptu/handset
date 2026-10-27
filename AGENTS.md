# Handset

An Android app that lets AI agents operate the phone. README.md covers what it does, how to turn it on and how agents connect. docs/development.md covers trying tools over adb, how a call settles, the overlays and recording screens for the screen reader's tests.

## Layout

- `android/`: the Gradle project, one `:app` module, package `ai.tushar.handset`. Versions live in `android/gradle/libs.versions.toml`.
  - `device/`: the accessibility service, screen reader, overlays (touch ring, status pill), consent prompt, optional access.
  - `tools/`: every tool (`PhoneTool`), grouped in `Toolkit`. `Toolkit.INSTRUCTIONS` is the guidance an agent gets.
  - `runner/`: `ToolRunner`, the one queue every transport calls, with `ToolCall` and `ToolResult`.
  - `transport/`: the ways in. `ShellToolReceiver` (adb), `mcp/` and `relay/`.
  - `transport/mcp/`: the MCP server. `McpHandler.handle(body, headers): McpReply` checks the Origin and the agent's token and answers one JSON-RPC message, with no network. `McpHttpServer` serves it on port 8765 from `McpService`, a foreground service, on loopback and Tailscale, and on Wi-Fi only when the user allows it. The service listens again as networks change. The relay calls the same handler per frame, built by `Handset.mcpHandler()`. `Agents` keeps each agent's token hash or relay grant, its sources and when it was added. `MCP_TOOLS` gives each tool its hints and the source it needs.
  - `ui/ConnectAppActivity.kt`: another app on the phone connects itself as an agent, started for a result with `ai.tushar.handset.action.CONNECT_APP`. `Agents.connectApp` keys it by package and certificate (`PhoneApp`), so connecting again replaces its token. `AppRemovedReceiver`, and `Handset.install` for an uninstall it missed, remove it once the app is gone. README.md has the contract other apps build to; change both together.
  - `transport/relay/`: `RelayClient`, the phone side of the Cloudflare relay. It connects out over a WebSocket and hands each frame to the same `McpHandler`. `pair(url)` and `unpair()` start and end it; `unpair()` removes every relay agent.
  - `events/`: phone events. `PhoneEvents` holds the switches and hears the battery broadcast, `PlaceCollector`'s visits and the notification listener, and keeps each event through `ContextStore.claimEventSend`, once per occurrence. `Delivery` and `EventSendWorker` post it with WorkManager, at most 20 POSTs an hour through `ContextStore.reserveEventPost`, and `PhoneEvents.sync` queues again any send left unqueued. A retry can deliver an event twice, so every payload carries `event_id`. `Destination` is the one address, kept encrypted with a Keystore key, and knows a Claude routine's fire URL. `Payload` builds the body.
  - `context/`: the day record. `ContextDatabase` (Room on the bundled SQLite, with FTS5 tables made in a callback), `ContextStore` (every write and query the UI, tools and MCP read), a collector per source, `Recording` (the switches, Pause and how long text is kept), `ContextWorker` (sessions, location re-request, daily cleanup), `ExcludedApps`.
  - `ui/`: Compose screens. `theme/Theme.kt` holds every colour, size, spacing and duration; `Kit.kt` the shared rows, switches and buttons. `days/` reads the day record through `DayRepository`, which `StoreDayRepository` answers from `ContextStore`; a moment's id holds its kind, range and app, conversation or agent, so opening it queries again. `settings/` has the top level in `SettingsTab.kt`, a row per `SettingsSection` with its state from `SettingsState.summary`, and each section's page; on a wide screen the chosen page sits beside the list. Every row is a `ListRow`, with `SwitchRow` and `CheckRow` built on it. It reads agents through `AgentDirectory`, which `McpAgentDirectory` answers from `Agents`. `MainActivity.appData()` picks them, or the fakes when a debug build is started with `--ez sample_data true`.
- `relay/`: the Cloudflare relay, a Worker deployed with Alchemy. Its README covers deploying it and connecting Claude and ChatGPT.

## Commands

Run Gradle from `android/`, through `${TSTACK_HOME:-~/Documents/code/tstack}/scripts/heavy.sh` when other agents may be building.

```bash
./gradlew :app:assembleRelease      # app/build/outputs/apk/release/app-release.apk
./gradlew :app:testDebugUnitTest    # AGP 9 runs unit tests on the debug variant only
```

Release builds sign with `~/.config/handset/release.jks` (password in `release.password` beside it) or the `HANDSET_KEYSTORE` and `HANDSET_KEYSTORE_PASSWORD` variables, else the debug key. Never put a key in the repo.

## Rules

- A new transport calls `Handset.instance.runner`, never a tool's `run` directly. The runner owns ordering, budgets, the consent prompt, the pill, the record of agent steps and Stop. Its one hook, `Host.record(call, detail, ok)`, writes each step, refused and stopped ones included, to the store through `Handset.recordAgentAction`; the MCP handler's refusals go there too.
- `submit` is synchronous on purpose: submit calls in the order they arrived, then await. Cancelling the returned `Deferred` cancels the call.
- Mark a tool `concurrent` only when it never touches the screen. Concurrent tools skip the screen queue, so they also run while the consent prompt is open.
- `device/` and `tools/` are a port and their behaviour is tuned against real apps. Change them only with a reason, and add a recorded screen and a `ScreenReaderTest` case for any screen reader change. Scrub names, numbers and addresses from recorded screens.
- Read and write the day record through `ContextStore`. A query on an FTS5 table goes through `@RawQuery`, since Room doesn't know those tables. A new table needs a Room migration; a change to the search tables also needs their triggers changed in `SearchTables`.
- `adb shell am force-stop ai.tushar.handset` makes Android drop the app from the enabled accessibility services. Turn it on again after one.
- The app uses `INTERNET` only for the MCP server and the relay, which are off until the user turns them on, and for phone events, which go only to the address the user gave. Keep the README's privacy lines true.
- A phone event carries only the fields `PhoneEvent` has: its id, type, time, battery level, place name, and the person's or app's name. A person's name goes only when `NotificationRow.fromPerson` says the sender is one. Never add message text to it. Sends stay out of `ContextStore.dayTimeline`, which agents read.
- Phone events have two known gaps. The battery receiver is registered in `HandsetApplication.onCreate`, so battery changes while Android has killed the process, before it rebinds the accessibility service, are lost. The first reading after the restart is compared with the stored one, so a low battery or a plug change still sends one event. A notification posted while the listener was disconnected is kept in the day record when `onListenerConnected` reads the active ones, but sends no event.
- A tool missing from `MCP_TOOLS` is hidden from MCP agents. Give a new tool a line there, with the `Source` it needs when it reads the user's notifications, messages, contacts, location or media.
- The MCP server must stay a foreground service with its wake lock and Wi-Fi lock. Without them Doze cuts its network with the screen off, even on localhost.
- The screen tools refuse Handset's own windows, and `launch` refuses to open Handset, in `PhoneAccessibilityService.requireOtherApp`. Its screens hold agents' tokens and sources. A new screen tool goes through the service's methods, not around them.
- An MCP call carries the agent's name as `ToolCall.agent`, which Days, the pill and the in-app line show, and its id as `ToolCall.agentId`, which each step keeps. Two agents can share a name, so own steps are matched by id. A transport that knows who is calling sets both; adb leaves them null and is recorded as adb.
- A tool whose answer mixes sources is `scoped` in `MCP_TOOLS`. The handler sets `without_notifications`, `without_location` and `caller` (the agent's id) after the agent's own arguments, from the agent's switches, so search and the timeline leave out what the agent can't read. Another agent's steps show only the tool and whether it worked.
- The pill hides while Handset is in front (`Handset.inFront`), since the app's own line says the same. Keep the two from stacking.
- Settings names its pages Agents, Recording, Connections, Phone events, Safety, Android permissions and About, in `SettingsSection`. Only a permission that needs the user may colour its summary. Error messages for agents and the README point to these names; change them together.
- No licensed fonts or other brands' assets. The app bundles Google Sans Flex (OFL) and Phosphor Icons (MIT), built by `android/tools/ui_resources.py`, with their licences in `assets/licenses`. App glyphs are generic Phosphor shapes, never brand logos.
