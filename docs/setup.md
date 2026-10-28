# Setting up Handset with an agent

This guide is for the agent a person hands the [README](../README.md)'s prompt to. It covers connecting an MCP client to the phone, what the tools do, sending phone events to a routine, and what the person should know about privacy. [AGENTS.md](../AGENTS.md) has the code map, and [development.md](development.md) covers adb and the screen reader.

Before any of this, the person has installed Handset on a phone running Android 11 or newer and turned on its three Android settings, as the README says. Accessibility lets agents read the screen and act on it. Notification access lets them read and reply to notifications and control what's playing. Battery unrestricted keeps the phone reachable while the screen is off, so check it when the phone stops answering. If a tool answers that a setting or an access is off, tell the person which one and where it is in Handset.

## Pick the way in

There are three ways in, and all are off until the person turns them on. Ask which client they use, and follow one section.

- An agent on their own computer (Claude Code, Codex, Claude Desktop, or any MCP client) connects [directly](#directly-from-a-computer), over Tailscale or Wi-Fi.
- claude.ai and ChatGPT connect [through a relay](#through-a-relay-for-claudeai-and-chatgpt), a Cloudflare Worker the person deploys.
- An app on the same phone connects [itself](#from-another-app-on-the-phone), with no setup by the person.

## Directly, from a computer

Claude Code, Codex, Claude Desktop and other MCP clients connect over Tailscale, or over the person's Wi-Fi when they allow it, with nothing in between.

1. In Handset, the person opens Settings, Agents, and taps **Add agent**. They name it after the app it runs in.
2. Handset shows the agent's token once, inside a ready setup for Claude Code, Codex, Claude Desktop or any MCP client. They copy it to you.

The phone listens on port 8765. Settings, Connections shows its Tailscale address when Tailscale is on, and its Wi-Fi address when **Also on Wi-Fi** is on. The setups look like this, with the phone's address in place of the example:

```bash
# Claude Code
claude mcp add --transport http handset http://100.101.102.103:8765/mcp --header "Authorization: Bearer hs_..."

# Codex
export HANDSET_TOKEN=hs_...
codex mcp add handset --url http://100.101.102.103:8765/mcp --bearer-token-env-var HANDSET_TOKEN
```

Claude Desktop connects through `mcp-remote`, with this in `claude_desktop_config.json`:

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

Any MCP client needs that URL and the header `Authorization: Bearer <token>`. The server speaks streamable HTTP.

The traffic is plain HTTP. Tailscale encrypts it, but on Wi-Fi anyone on the network could read it, so the person should turn on **Also on Wi-Fi** only on a network they trust. **New token** on the agent's page replaces its token and **Remove** revokes it. The phone keeps only a hash of each token.

To try it from a computer over USB, forward the port with `adb forward tcp:8765 tcp:8765` and use `127.0.0.1` as the address.

## Through a relay, for claude.ai and ChatGPT

The relay is a small Cloudflare Worker the person deploys to their own account. It gives the phone a public HTTPS address. The phone connects out to it, so it needs no public IP or open port. The [relay guide](../relay/README.md) covers deploying it in three commands and adding it to Claude or ChatGPT. Deploy it, then:

1. In Handset, the person opens Settings, Connections, pastes the relay's address under **Relay** and taps **Connect**. The row shows whether it is connected and the phone's public MCP address, which is the relay's address with `/mcp` on the end.
2. They add that address as a custom connector in claude.ai or as a custom MCP app in ChatGPT. On the relay's sign-in page, they enter the phone ID Handset shows.
3. The phone shows a notification with the site that asked, the client's name and a six-digit code. They should allow only if they started the sign-in on that site and the code matches the browser.

The agent then appears under Settings, Agents, with every switch off. Removing it there revokes its grant, and **Disconnect the relay** removes every agent that signed in through it.

A Claude connector works in the Claude apps under the same account, and in Claude routines. The phone must be online when the agent calls it. When the phone is offline a call fails at once, and nothing is queued.

## From another app on the phone

An app on the same phone can connect as an agent with one tap, and nobody copies a token. [Nano Chat](https://github.com/triptu/nano-chat) connects this way. This is the contract an app builds to.

1. The app starts the action `ai.tushar.handset.action.CONNECT_APP` for a result, with `ActivityResultContracts.StartActivityForResult` or `startActivityForResult`. A plain `startActivity` is refused, since Handset must know which app is asking. On Android 11 and later, add `<queries><package android:name="ai.tushar.handset" /></queries>` to the app's manifest.
2. Handset shows "<App> wants to use this phone" with the app's name, icon and package, and the same switches as any agent's page, all off. The user taps **Allow** or **Don't allow**.
3. On Allow, Handset turns on its MCP server on the phone itself (not on Wi-Fi), waits until its own socket holds `127.0.0.1:8765`, and returns `RESULT_OK` with these String extras:
   - `mcp_url`: `http://127.0.0.1:8765/mcp`
   - `token`: the app's bearer token, sent as `Authorization: Bearer <token>`
   - `agent_id`: the agent's id in Handset
4. Otherwise it returns `RESULT_CANCELED` with `reason`: `denied` (the user said no or went back), `not_for_result`, `handset_off` (agents are switched off in Handset), or `port_in_use` (another app holds port 8765 on the phone, so Handset gave no token).

Handset knows the app by its package name and the SHA-256 of its signing certificate. Connecting again from the same app gives it a new token, keeps its switches and turns it on if it was paused, so it can ask again whenever it lost its token. The same package signed with another key is a different agent. Uninstalling the app removes its agent, and its token stops working. The token works only at `127.0.0.1` on the phone itself, never over Wi-Fi, Tailscale or a relay.

The app shows under Settings, Agents as "On this phone". Its calls go through every check other agents' calls do: its switches, Send and Pay approval, Stop, the activity record, and Handset's private screens.

## What the person controls

Every agent can read the screen, the phone's status, the app list and screen time, search what Handset records apart from notifications and places, and act on the screen. Notifications, text messages, contacts, location, and photos and videos are off for each agent until the person turns them on, on its page under Settings, Agents. When a tool needs one that is off, it says which. Contacts, text messages, location, photos and videos, and screen time also need their Android permission, each with a switch under Settings, Android permissions. Agents can't open or read Handset's own screens.

To pause an agent without removing it, the person turns off the switch at the top of its page. Its calls get a tool error until it is turned on again, and it keeps its token or sign-in and its switches. Connecting an app again, or signing in again from the same relay client, turns a paused agent back on.

With **Ask me before tapping Send or Pay** on, under Settings, Safety, a tap on an element whose label has the English word Send, Pay, Buy, Order, Transfer, Post or Publish waits for the person to tap Allow in a notification. Other screen calls wait with it, so an agent can't tap Allow itself. Run such a tap as its own call, not inside `run_steps`. A button in another language or with no label isn't caught, and `reply_notification` and `type_text` with `submit` send without asking.

While an agent works, a pill at the top of the screen says who is doing what, with Stop. Stop ends the running call and the waiting ones, and refuses new ones until the person turns agents on again. Every step an agent takes, refused and stopped ones included, is kept in the day record and shows in Days.

## The tools

| Tools | What they do |
|---|---|
| `get_screen`, `read_text`, `wait_for`, `screenshot` | Read the app in front and its elements, each with an id and a position. Wait for text or an app to appear. |
| `tap`, `type_text`, `scroll`, `swipe`, `set_value`, `press` | Act on an element by id or text, or on a point, and return the settled screen. |
| `run_steps` | Up to 20 actions in one call. It stops at the first one that fails. |
| `open_app`, `list_apps`, `open_url`, `web_search` | Open apps, links and searches. |
| `set_alarm`, `set_timer`, `set_volume`, `set_do_not_disturb`, `device_status`, `screen_time` | System settings and status, with no screen needed. |
| `list_notifications`, `reply_notification`, `now_playing`, `inbox_since` | Read and reply to notifications, control what's playing, and catch up on what came in since a time. |
| `read_sms`, `find_contact`, `get_location`, `list_media`, `view_media`, `share_media` | The person's own things, each behind its switch. |
| `search_context`, `day_timeline` | Search the day record, and read one day's apps, places, notifications and agent steps in order. |

Screen actions run one at a time, in the order they arrive, so the actions sent in one turn don't race. Each waits for the screen to settle before it answers, and says whether anything changed. The other tools run as soon as they arrive. A call has 2 minutes by default. If a call disconnects or times out, read the screen before retrying, since the action may have completed. [development.md](development.md) describes how a call settles.

## The day record

Under Settings, Recording, four sources each have a switch, all off at first. Screen text keeps the text of the screens the person opens, for search. Notifications are kept as they arrive, for catching up. App use is which app was open and when. Places are where they stayed 10 minutes or more, named by tapping the place in Days.

**Record my day**, at the top of Settings, stops all recording until it is turned on again, and keeps the choice of sources. **Pause for an hour** stops it for an hour, from Settings or a quick settings tile. **Delete today** removes everything since midnight. Screen text and notifications from password managers, payment apps, keyboards and the apps added under **Apps never read** are never kept. App use still lists those apps.

Screen text and notifications are kept 7, 30 or 90 days, as the person chooses. App use, places, agent steps and phone events are kept a year. An agent sees only the sources turned on for it. Search and the day's timeline leave out what it can't read, and another agent's steps show only the tool and whether it worked.

## Phone events

Handset can tell a Claude routine, or any address that takes a POST, when something happens on the phone. The routine decides what to do, and can call the phone back through the Handset connector. Under Settings, Phone events, each event has its own switch:

- **Battery below a level**, 20% unless the person picks another, once each time it runs down.
- **Charging started or stopped.**
- **Arrived at or left a place** named in Days, while Places is recording.
- **A notification from someone**, from the people and apps the person picks.

An event carries only an id, its type, the time, and the battery level, place name, or the person's or app's name. It never carries what a message says. Every event, sent or not, shows in Days with exactly what was sent. The address and its token are kept encrypted with a key in Android's keystore. Handset posts at most 20 times an hour, tries included. A failed post is tried again, up to 5 times, so a routine can get an event twice. Every try carries the same `event_id`.

To start a Claude routine from the phone:

1. At [claude.ai/code/routines](https://claude.ai/code/routines), make a routine with the Handset connector and any others it needs. Write a prompt that reads the event, skips an `event_id` it has seen, and acts on the type.
2. Add an **API** trigger. Copy its fire URL and generate a token.
3. In Handset, the person taps **Send events to a routine**, pastes both, and taps **Save**. Handset recognises a Claude routine and adds the header it needs.
4. They tap **Send a test event**. The routine's history shows a new run.

A Claude routine gets the event as text, a sentence and the fields as JSON. Any other address gets the fields alone, with `Authorization: Bearer <token>` when one was given:

```json
{"event_id":"evt_8b21e0c5f7a34d9612ce57b0","type":"arrived","time":"2026-10-27T09:12:40+05:30","place":"Office"}
```

The types are `battery_low`, `charging_started`, `charging_stopped`, `arrived`, `left`, `notification` and `test`. Battery and charging changes are heard only while Handset's process runs. After Android restarts it, the first reading is compared with the last one kept, so a change in between still sends one event.

## Privacy

Tell the person these when they ask what leaves the phone.

The day record and every agent's steps live in a database in the app's private storage on the phone, and are never uploaded. A tool's result goes to the agent that called it, and `search_context` and `day_timeline` return recorded screen text, notifications and places to the agent that asks, within its switches. Phone events go to the one address they gave, whether or not an agent is connected. Nothing else is sent.

A direct connection has nobody in between. A relay connection passes through Cloudflare and their relay, which can read calls in transit, and the AI provider receives the results. The relay stores sign-in records, never call content. The [relay guide](../relay/README.md#privacy) has the detail.

The app uses the internet only for the MCP server and the relay, which are off until the person turns them on, and for phone events, which go only to the address they gave.

## Build from source

The person can build the APK instead of downloading a release. [AGENTS.md](../AGENTS.md#commands) has the commands and the signing rules.
