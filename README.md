<img src="assets/icon.svg" width="64" height="64" alt="">

# Handset

Handset is an Android app that lets an AI agent operate your phone. Claude, ChatGPT, Claude Code, Codex or any other MCP client can read the screen, open apps, tap, type and scroll, the way you would. Handset can also keep a record of what you did on the phone each day, which you browse in the app and your agents can search.

The daily record stays on the phone. The app uses the network only for agent connections, which are off until you turn them on and answer only the agents you approve, and for the phone events you turn on, which go only to the address you give. The text it reads goes only to those agents, and the daily record is never uploaded.

The app is installed by hand, not from Play. It drives the phone through an accessibility service, which Play only allows for apps that serve people with disabilities.

## What works today

- **Phone control.** About 30 tools that read the screen and act on it, plus tools for alarms, timers, volume, notifications, messages, contacts, location, photos and screen time. The [tools table](#tools) lists them.
- **Two ways for agents to connect, both off by default.** The relay gives the phone a public address for claude.ai, ChatGPT and other web apps. The direct connection lets Claude Code, Codex, Claude Desktop and other MCP clients reach the phone over Tailscale, or over your Wi-Fi when you allow it, with nothing in between. Each agent has its own switches for notifications, text messages, contacts, location, and photos and videos. [Connecting an agent](#connecting-an-agent) shows how.
- **adb.** A computer connected over adb can call any tool.
- **Safety.** A tap on Send, Pay, Buy and similar buttons waits for you to tap Allow. A pill at the top of the screen says what the agent is doing, with Stop. Inside Handset, a line at the top says it instead. Every step an agent takes is kept in the day record, so it shows in Days.
- **Phone events, off by default.** When the battery runs low, charging starts or stops, you arrive at or leave a named place, or a person or app you pick sends a notification, Handset can tell a Claude routine or any web address. [Phone events](#phone-events) shows how.
- **Days, off by default.** Four sources, each with its own switch in Settings under What Handset records: the text of the screens you open, notifications as they arrive, which app was open and when, and places where you stayed 10 minutes or more. They are kept in a database on the phone for 7, 30 or 90 days, as you choose, with every step an agent takes. The Days tab shows each day by place and time. Open a moment to read the screens you saw, a conversation, or an agent's steps, and search everything kept. Agents can search it, catch up on what came in, and read a day's timeline with the tools below. Pause for an hour and Delete today sit beside the switches, and the Pause tile does the same from quick settings.

## Planned

- Chat in the app, with your own API key, or on the phone with Gemini Nano or Gemma 4 E4B.

## Tools

| Tool | What it does |
|---|---|
| `get_screen` | The app in front and its elements, each with an id and its centre as `@x,y`. It can filter by text, cap the list, wait for an `expect` text, and adds a screenshot when the screen has almost no elements. |
| `tap`, `type_text`, `scroll`, `swipe`, `set_value`, `press` | Act on an element, named by id or by its text as `target`, or a point. Each takes `expect`, and returns a status line (`[ok]`, whether the elements changed, the app, the settle time) and the settled screen. `tap` can scroll to find its target. |
| `run_steps` | Up to 20 taps, typing, scrolls, presses, opens, searches and waits in one call. It stops at the first failure, missed `expect`, app that doesn't open, tap that changes nothing, or tap that needs approval. |
| `read_text`, `wait_for` | The full text of an element; wait for text or an app to appear. |
| `open_app`, `list_apps`, `open_url`, `web_search` | Open apps, links such as `wa.me` and `intent:` links, and searches. Web links reuse one Chrome tab. |
| `screenshot` | A JPEG of the screen, with the scale to screen pixels. |
| `list_notifications`, `reply_notification` | Read notifications, ongoing ones included, and reply through a notification's Reply action. |
| `set_alarm`, `set_timer`, `device_status`, `set_do_not_disturb`, `set_volume` | System APIs, so no screen is needed. |
| `read_sms`, `find_contact`, `get_location`, `list_media`, `view_media`, `share_media`, `screen_time` | Your own things, each behind an access you turn on. `view_media` returns one photo or video frame as an image. `share_media` returns up to four photos as sharper images, each up to 1,600 px on its long side. |
| `now_playing` | What's playing in any app, and play, pause or skip, through notification access. |
| `search_context`, `inbox_since`, `day_timeline` | Read the [day record](#the-day-record): search the screens and notifications kept, everything that came in since a time grouped by conversation, and one day's apps, places, notifications and agent steps in order. They don't use the screen, so they run beside other calls. |

### How a call runs

Calls that use the screen run one at a time, in the order they arrive, so actions an agent sends in one turn don't race. Time a call spends waiting counts against its budget, 2 minutes by default. Calls that don't use the screen, such as `read_sms`, `find_contact` or `device_status`, run as soon as they arrive. Every way in uses the same runner, so these rules hold for adb and for the MCP server alike.

An action settles on the screen's own accessibility events, not on a clock. It gets 120 ms to change anything, and 600 ms when the screen still reads as before, since a page a tap opens can be slow to show. Then the screen must go 180 ms without a change, without a spinner and with its window inside the screen, or read the same twice while something keeps animating. A page sliding in sends no events and reports the positions it started from, so its window reaching past the screen's edge is what tells that it is still moving. A launch gets 600 ms to change and 6 s in all. Anything else gets 3 s.

`expect` is met only by text in a node that didn't hold it before the action, on a layer the agent can see. So a page that shows through while the next one loads doesn't count. A screen also counts as settled when only the same line or two keep changing, as a clock in a widget does. Only a progress bar without a value counts as a spinner.

A text target matches the element's whole text first, then one of the texts a row joins with ·, then a text that starts with it, then one that contains it. Among equal matches, the smallest wins. When the match is one text of a row, the tap lands on that text. `type_text` on a search bar that is a button taps it and types into the field it opens. `scroll` and `scroll_to_find` try the lists that scroll the requested way, the one with the most items first, so a list inside a scrolling page comes before the page. A list counts as moved only when its own items change. When no list takes the scroll action, the first one is swiped.

The screen leaves out a page that a later one covers completely, such as the search page an app keeps under its results. It also leaves out labels that are only a media file's link, and shows an image's repeated label once with a count. Reads reuse the last screen when no event has arrived since, and prefetch the tree on Android 13 and later.

The action tools refuse element ids and points when another app has come to the front since the last read, as an incoming call does. An error about a missing element carries the screen as it is now. Results carry the phone's timings.

### What you see

Each tap shows as a ring that fades in 300 ms, and each swipe as a moving dot. While a call runs, a pill at the top of the screen says which agent is doing what, such as "Claude Code is opening Clock", step by step in `run_steps`, with Stop. Stop turns Handset off at once. Both are accessibility overlays. The pill lets touches through while the agent's own touch is on its way, and screenshots hide both. The ring and the pill are timed by the clock, because battery saver turns Android's animators off.

When "Ask me before tapping Send or Pay" is on, a tap on an element labelled like Send, Pay, Buy, Order, Transfer, Post or Publish waits for you to tap Allow in a notification. Nothing else asks. While the prompt is open, other screen calls wait, so the agent can't open the notification shade and tap Allow itself.

Turning Handset off ends the call that is running and the ones waiting, and refuses new ones until you turn it on again.

## Connecting an agent

For claude.ai and ChatGPT, use the relay below. For an agent on your own computer, use the direct connection:

1. In Handset, open Settings and tap **Add agent** under Connected agents. Give it a name, such as Claude Code. Adding an agent turns on **Direct connection** under Connections.
2. Handset shows the agent's token once, inside the setup for Claude Code, Codex, Claude Desktop or any other MCP client. Pick yours, tap **Copy**, and tap Done. The page is kept out of screenshots while the token shows.
3. Paste the setup where the agent runs.

The phone listens on port 8765. Connections shows its Tailscale address (100.x.y.z) when Tailscale is on, and its Wi-Fi address too when you turn on **Also on Wi-Fi**. A notification, "Handset is ready for agents", shows the same addresses and stays while the server runs.

The setups look like this, with your phone's address and the agent's token:

```bash
# Claude Code
claude mcp add --transport http handset http://100.101.102.103:8765/mcp --header "Authorization: Bearer hs_..."

# Codex
export HANDSET_TOKEN=hs_...
codex mcp add handset --url http://100.101.102.103:8765/mcp --bearer-token-env-var HANDSET_TOKEN
```

Claude Desktop connects through `mcp-remote`. Add this to `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "handset": {
      "command": "npx",
      "args": ["mcp-remote", "http://100.101.102.103:8765/mcp", "--allow-http", "--header", "Authorization:${HANDSET_AUTH}"],
      "env": { "HANDSET_AUTH": "Bearer hs_..." }
    }
  }
}
```

Any other MCP client needs the URL and the header `Authorization: Bearer <token>`. The server speaks streamable HTTP, both the 2026-07-28 revision and the earlier ones that start with `initialize`.

An agent can always read the screen, the phone's status, the app list and screen time, and act on the screen. Notifications, text messages, contacts, location, and photos and videos are off for each agent until you turn them on on its page in Settings. An agent without Notifications gets search and a day's timeline without the notifications in them. The optional access in Settings must be on too. A call that needs one that is off tells the agent how you can turn it on. Agents can't open, read or touch Handset's own screens, which hold the tokens and these switches. A refused call is kept with the agent's steps in Days. **New token** on the agent's page replaces its token and **Remove** revokes it at once. The phone keeps only a hash of each token, so it can't show a token again.

Traffic is plain HTTP, which Tailscale encrypts. So the server listens only on Tailscale and on the phone itself, until you turn on **Also on Wi-Fi**. Anyone on the same Wi-Fi could see the agent's traffic, so turn it on only on a Wi-Fi you trust. The server turns back on after a restart when you left it on.

To try it from a computer over adb, forward the port and use `127.0.0.1`:

```bash
adb forward tcp:8765 tcp:8765
claude mcp add --transport http handset http://127.0.0.1:8765/mcp --header "Authorization: Bearer hs_..."
```

## Connect claude.ai and ChatGPT through a relay

The relay gives your phone a public HTTPS MCP URL. The phone connects out, so it needs no public IP or open inbound port. Deploy a relay with the [relay setup guide](relay/README.md#deploy-your-own).

In Handset, open Settings, find **Relay** under Connections, paste your relay's address and tap **Connect**. The relay row then shows whether it is connected, and the phone's public MCP address with Copy. Over adb, the same is:

```bash
adb shell am broadcast -n ai.tushar.handset/.transport.ShellToolReceiver --es tool relay --es args "'{\"url\":\"https://<your-relay>\"}'"
adb logcat -s 'Handset/Shell:I'
```

The log shows the phone ID and public MCP URL. Add that URL as a custom connector in claude.ai, or as a custom MCP app with OAuth in ChatGPT. Follow the [Claude](relay/README.md#connect-claude) or [ChatGPT](relay/README.md#connect-chatgpt) setup steps. Enter your phone ID on the relay's authorization page.

The phone shows a notification with the callback host, client name, and a six-digit code. Allow only if you started the connection on that host. Compare the code with the browser, then tap **Allow** on the phone. A matching code binds the session but does not prove the client is trustworthy.

The approved client appears under Connected agents, as via its site, with all source switches off. It has no token, so it has no New token. Turn on only the sources it needs. Removing it there revokes its relay grant. The same approval, Stop, activity history, and private Handset screens apply to relay calls.

Claude's connector is available in its apps under the same account, and in routines that support connectors. The phone must be online when a client calls it. The relay reconnects after network changes and does not replay calls.

TLS ends at Cloudflare. Cloudflare and the relay can read requests and responses in transit, and your AI provider receives the tool results. The relay stores authorization records but never stores MCP request or response content. Read the [relay privacy details](relay/README.md#privacy).

To disconnect the phone, tap **Disconnect the relay** in Settings. It removes every agent that signed in through the relay. Over adb:

```bash
adb shell am broadcast -n ai.tushar.handset/.transport.ShellToolReceiver --es tool relay --es args "'{\"off\":true}'"
```

## Phone events

Handset can tell a routine when something happens on the phone. The routine lives in Claude, or behind any address that takes a POST, and decides what to do. It can call the phone back through the Handset connector to read more or act.

Under Settings, Phone events, each event has its own switch, all off until you turn them on:

- **Battery below a level**, 20% unless you pick another. It is sent once each time the battery runs down, and not again until the phone has charged.
- **Charging started or stopped.**
- **Arrived at or left a place** you named in Days. Places must be recording. Arriving is sent once you have stayed 10 minutes.
- **A notification from someone**, from the people and apps you pick among those in your notifications.

An event carries only an id, its type, the time, and the battery level, place name, or the person's or app's name. It never carries what a message says. Handset posts at most 20 times an hour, tries included. An event past that waits, and goes once the hour allows it. A 5xx answer or a timeout is tried again later, up to 5 times, and any other answer isn't. The address may have taken an event that timed out, so a routine can get one twice. Every try carries the same `event_id`, so the routine can skip one it has seen. Every event, sent or not, shows in Days, and opening it shows exactly what was sent. The address and its token are kept encrypted with a key in Android's keystore.

### Start a Claude routine from the phone

1. At [claude.ai/code/routines](https://claude.ai/code/routines), make a new routine. Under its connectors keep Handset, which you added as a connector through the [relay](#connect-claudeai-and-chatgpt-through-a-relay), and any others it needs, such as your calendar.
2. Write a prompt that acts on the event. Claude passes the event inside a `<routine-fire-payload>` block and treats it as data unless the prompt says to use it. For example:

   > Handset sent the event in the routine-fire-payload block. Read its JSON. If you have handled an event with the same event_id before, do nothing. When the type is battery_low and my calendar has an event away from home in the next 3 hours, use the Handset connector to check that Battery Saver is on and tell me with a notification on the phone how long the battery should last. When the type is arrived and the place is Office, read my calendar and list today's meetings. Otherwise do nothing.

3. Add an **API** trigger. Copy the **Fire URL**, then tap **Generate token** and copy the token, which Claude shows only once.
4. In Handset, open Settings, tap **Send events to a routine** under Phone events, paste the URL as the address and the token as the token, and tap **Save**. Handset sees that it is a Claude routine and adds the `anthropic-version` header it needs.
5. Tap **Send a test event**. The line under it says Sent, and the routine's history shows a new run.

A Claude routine gets the event as `text`: a sentence and the fields as JSON.

````text
Handset phone event. The phone's battery is at 15%, below the level set in Handset, and it isn't charging.

```json
{
    "event_id": "evt_3f9c2a71d04be85c16a9e042",
    "type": "battery_low",
    "time": "2026-10-27T18:56:51+05:30",
    "battery": 15
}
```
````

Any other address gets the fields alone, as JSON, with `Authorization: Bearer <token>` when you gave a token. Plain http is allowed only to the phone itself, so the token never crosses a network unencrypted:

```json
{"event_id":"evt_8b21e0c5f7a34d9612ce57b0","type":"arrived","time":"2026-10-27T09:12:40+05:30","place":"Office"}
```

The types are `battery_low`, `charging_started`, `charging_stopped`, `arrived`, `left`, `notification` and `test`. Claude allows 30 fires an hour for each routine, and Handset's own cap of 20 keeps under it.

Battery and charging events are heard while Handset's process runs, which the Accessibility service keeps it doing. Handset never polls the battery.

## Build and install

You need JDK 17 or newer and the Android SDK. From `android/`:

```bash
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

A release build is signed with the key in `~/.config/handset/` (`release.jks` and `release.password`) when that folder exists, and with the debug key otherwise. A build machine can pass the key as the `HANDSET_KEYSTORE` and `HANDSET_KEYSTORE_PASSWORD` environment variables instead. Android refuses an update signed with a different key, so uninstall first when you switch keys. Every build's version code is the minutes since 2026-01-01, so the newer build always installs over the older one.

## Turning it on

The app's Setup screen walks through three things:

- **Accessibility.** This lets the app read the screen and act on it.
- **Notification access.** This lets it read and reply to notifications and control what's playing.
- **Unrestricted battery.** This keeps it reachable while the screen is off.

Android blocks Accessibility for an app installed from a file at first. Try to turn it on once. Then open the app's App info, tap ⋮ and choose Allow restricted settings. Then turn on Accessibility again.

The service declares itself an accessibility tool (`isAccessibilityTool`). On Android 14 and later, apps such as Swiggy mark their screens as sensitive, and Android hides those screens from services that aren't tools.

Setup also offers optional access, all at once with Turn all on: contacts, text messages, location, photos and videos, and screen time. Each has a switch in Settings. A tool that needs an access that is off says so, and the agent passes it on.

Over adb, the same switches are:

```bash
adb shell settings put secure enabled_accessibility_services ai.tushar.handset/ai.tushar.handset.device.PhoneAccessibilityService
adb shell cmd notification allow_listener ai.tushar.handset/ai.tushar.handset.device.NotificationWatcher
adb shell dumpsys deviceidle whitelist +ai.tushar.handset
```

The first command replaces the list of enabled services. When the phone has other services on, append to the current value instead.

An optional access needs both its switch and Android's permission:

```bash
adb shell pm grant ai.tushar.handset android.permission.READ_CONTACTS
adb shell appops set ai.tushar.handset GET_USAGE_STATS allow
adb shell am broadcast -n ai.tushar.handset/.transport.ShellToolReceiver --es tool access --es args "'{\"access\":\"CONTACTS\",\"on\":true}'"
```

## Trying tools over adb

```bash
adb shell am broadcast -n ai.tushar.handset/.transport.ShellToolReceiver --es tool get_screen --es args "'{}'"
adb shell am broadcast -n ai.tushar.handset/.transport.ShellToolReceiver --es tool open_app --es args "'{\"app\":\"Clock\"}'"
adb shell am broadcast -n ai.tushar.handset/.transport.ShellToolReceiver --es tool tap --es args "'{\"target\":\"Timer\"}'"
adb logcat -s 'Handset/Shell:*'
```

Each call's output goes to logcat under `Handset/Shell`. To send several calls at once, as an agent does in one turn, send them as `calls`. They share one queue, so reads run beside actions, and each result is logged with when it finished:

```bash
adb shell am broadcast -n ai.tushar.handset/.transport.ShellToolReceiver --es tool calls \
  --es args "'{\"calls\":[{\"tool\":\"open_app\",\"params\":{\"app\":\"Clock\"}},{\"tool\":\"device_status\"}]}'"
```

`--es tool stop` does what Stop in the pill does. The receiver needs the `DUMP` permission, which only the shell and the system hold, so other apps can't call it. Calls from adb show the pill and ask before Send and Pay, as any agent's calls do.

## Testing the screen reader

`device/ScreenReader.kt` turns the accessibility tree into the screen the agent reads. Its rules, such as which layers count as hidden and how a card folds into one line, are checked against screens recorded from real apps, in `android/app/src/test/resources/screens`. To add one, open the screen on a phone and record it:

```bash
adb shell am broadcast -n ai.tushar.handset/.transport.ShellToolReceiver --es tool dump --es args "'{\"name\":\"app-screen\"}'"
adb pull /sdcard/Android/data/ai.tushar.handset/files/screens/app-screen.json
```

Replace personal details in the file, such as names, addresses and account names, before you add it. Then add a test to `ScreenReaderTest` that states what the agent must and must not see. Run the tests from `android/`:

```bash
./gradlew :app:testDebugUnitTest
```

## License

MIT, see [LICENSE](LICENSE). The bundled Google Sans Flex font and Phosphor icons keep their own licences, in `android/app/src/main/assets/licenses`.
