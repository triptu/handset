# Developing Handset

What you need to try tools without an agent, drive the Android switches from a computer, and change the screen reader. The [README](../README.md) covers installing the app, and [setup.md](setup.md) covers connecting agents. [AGENTS.md](../AGENTS.md) has the code map and the rules.

## Turning the switches on over adb

The setup screen's three switches, from a computer:

```bash
adb shell settings put secure enabled_accessibility_services ai.tushar.handset/ai.tushar.handset.device.PhoneAccessibilityService
adb shell cmd notification allow_listener ai.tushar.handset/ai.tushar.handset.device.NotificationWatcher
adb shell dumpsys deviceidle whitelist +ai.tushar.handset
```

The first command replaces the list of enabled services. When the phone has other services on, append to the current value instead. `adb shell am force-stop ai.tushar.handset` makes Android drop the service from that list, so turn it on again after one.

An optional access needs both Android's permission and Handset's switch:

```bash
adb shell pm grant ai.tushar.handset android.permission.READ_CONTACTS
adb shell appops set ai.tushar.handset GET_USAGE_STATS allow
adb shell am broadcast -n ai.tushar.handset/.transport.ShellToolReceiver --es tool access --es args "'{\"access\":\"CONTACTS\",\"on\":true}'"
```

The service declares itself an accessibility tool (`isAccessibilityTool` in `android/app/src/main/res/xml/accessibility_service.xml`). On Android 14 and later, apps can mark a screen as sensitive, and Android hides those screens from services that aren't tools.

## Trying tools over adb

`ShellToolReceiver` takes any tool as a broadcast and writes the result to logcat under `Handset/Shell`. The manifest guards it with the `DUMP` permission, which only the shell and the system hold. Calls from adb show the pill and ask before Send and Pay, as any agent's calls do, and are recorded in Days as adb.

```bash
adb shell am broadcast -n ai.tushar.handset/.transport.ShellToolReceiver --es tool get_screen --es args "'{}'"
adb shell am broadcast -n ai.tushar.handset/.transport.ShellToolReceiver --es tool open_app --es args "'{\"app\":\"Clock\"}'"
adb shell am broadcast -n ai.tushar.handset/.transport.ShellToolReceiver --es tool tap --es args "'{\"target\":\"Timer\"}'"
adb logcat -s 'Handset/Shell:*'
```

To send several calls at once, as an agent does in one turn, send them as `calls`. They share one queue, so reads run beside actions, and each result is logged with when it finished:

```bash
adb shell am broadcast -n ai.tushar.handset/.transport.ShellToolReceiver --es tool calls \
  --es args "'{\"calls\":[{\"tool\":\"open_app\",\"params\":{\"app\":\"Clock\"}},{\"tool\":\"device_status\"}]}'"
```

The receiver also takes a few slugs that aren't tools:

- `stop` does what Stop in the pill does.
- `relay` with `{"url":"https://<your-relay>"}` connects the relay and logs the phone ID and public MCP URL. `{"off":true}` disconnects it and removes every relay agent.
- `access` with `{"access":"CONTACTS","on":true}` sets an access switch, as Settings does.
- `dump` with `{"name":"app-screen"}` records the screen in front for the screen reader's tests, below.

## Connecting an MCP client over adb

Forward the port and use the loopback address with the agent's token from Settings:

```bash
adb forward tcp:8765 tcp:8765
claude mcp add --transport http handset http://127.0.0.1:8765/mcp --header "Authorization: Bearer hs_..."
```

Claude Desktop connects through `mcp-remote`, with this in `claude_desktop_config.json`. Handset shows the same block, filled in, when you add an agent:

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

The server speaks streamable HTTP. `McpHandler` lists the protocol revisions it accepts, the current one that starts with `server/discover` and the earlier ones that start with `initialize`.

## How a call runs

Calls that use the screen run one at a time, in the order they arrive. Time a call spends waiting counts against its budget, which `ToolCall` sets to 2 minutes by default. Calls marked `concurrent`, such as `read_sms`, `find_contact` or `device_status`, run as soon as they arrive. Every way in uses the same `ToolRunner`, so these rules hold for adb, the MCP server and the relay alike.

An action settles on the screen's own accessibility events, not on a clock. It gets a short window to change anything, and a longer one when the screen still reads as before, since a page a tap opens can be slow to show. Then the screen must go quiet without a spinner and with its window inside the screen, or read the same twice while something keeps animating. A page sliding in sends no events and reports the positions it started from, so its window reaching past the screen's edge is what tells that it is still moving. Launches get longer than other actions. The timings live in `tools/PhoneActions.kt`.

`expect` is met only by text in a node that didn't hold it before the action, on a layer the agent can see. So a page that shows through while the next one loads doesn't count. A screen also counts as settled when only the same line or two keep changing, as a clock in a widget does. Only a progress bar without a value counts as a spinner.

A text target matches the element's whole text first, then one of the texts a row joins with ·, then a text that starts with it, then one that contains it. Among equal matches, the smallest wins. When the match is one text of a row, the tap lands on that text. `type_text` on a search bar that is a button taps it and types into the field it opens. `scroll`, and `tap` with `scroll_to_find`, try the lists that scroll the requested way, the one with the most items first, so a list inside a scrolling page comes before the page. A list counts as moved only when its own items change. When no list takes the scroll action, the first one is swiped.

The screen leaves out a page that a later one covers completely, such as the search page an app keeps under its results. It also leaves out labels that are only a media file's link, and shows an image's repeated label once with a count. Reads reuse the last screen when no event has arrived since, and prefetch the tree on Android 13 and later.

The action tools refuse element ids and points when another app has come to the front since the last read, as an incoming call does. An error about a missing element carries the screen as it is now. Results carry the phone's timings.

## The overlays

The touch ring, the swipe dot and the pill are accessibility overlays. The pill lets touches through while the agent's own touch is on its way, and screenshots hide all three. They are timed by the clock, because battery saver turns Android's animators off. The pill hides while Handset is in front, where the app's own line says the same.

The Send or Pay prompt is a heads-up notification with Allow and Deny. While it is open, other screen calls wait, so the agent can't open the notification shade and tap Allow itself. Concurrent calls still run.

## Testing the screen reader

`device/ScreenReader.kt` turns the accessibility tree into the screen the agent reads. Its rules, such as which layers count as hidden and how a card folds into one line, are checked against screens recorded from real apps, in `android/app/src/test/resources/screens`. To add one, open the screen on a phone and record it:

```bash
adb shell am broadcast -n ai.tushar.handset/.transport.ShellToolReceiver --es tool dump --es args "'{\"name\":\"app-screen\"}'"
adb pull /sdcard/Android/data/ai.tushar.handset/files/screens/app-screen.json
```

Replace names, numbers, addresses and account names in the file before you add it. Then add a case to `ScreenReaderTest` that states what the agent must and must not see, and run the tests from `android/`:

```bash
./gradlew :app:testDebugUnitTest
```

## Screenshots with sample data

A debug build started with `adb shell am start -n ai.tushar.handset/.ui.MainActivity --ez sample_data true` shows fake days and agents, for screenshots, until started with `--ez sample_data false`.
