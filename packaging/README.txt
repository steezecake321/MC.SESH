MC.SESH - Minecraft Server Easy Self Hosting
============================================

Manage Minecraft servers from a web page on this PC, your phone, or any device on your network.

Quick start
-----------
1. Unzip the whole download, then double-click Install.bat. A setup wizard opens in your web browser: pick folders,
   create the owner account, choose your defaults and whether it starts with Windows. When it finishes, MC.SESH opens
   in the same tab. (No browser? Install-Text.bat does the same in a plain text window.
   Or skip installing: just double-click Start.bat to run it straight from this folder.)
2. Your browser opens http://localhost:8585 . Create the owner account (username + password) the first time.
   This account is the manager administrator (OP5).
3. Add a server: create a new one (Vanilla, Fabric, Forge, NeoForge), add an existing
   folder, or let the manager search this PC (CurseForge, ATLauncher, FTB, plain jars...).

New servers, mods and Bedrock players
-------------------------------------
Add server asks for the world name, seed, world type, difficulty, game mode, max players, message of the day and
spawn protection, and writes them before the first start. For Fabric, Forge and NeoForge, "I plan to add mods"
opens a Modrinth mod picker as soon as the server exists (Fabric API is pre-selected when it is not already
installed); you can pick several mods at once and required helper mods are added for you.
World type "Single biome" (Minecraft 1.20.2 and newer) makes the whole overworld one biome of your choice (every vanilla biome is listed, including
caves, Nether, End and Void ones, which make strange worlds), for example Cherry Grove only. Terrain still has hills, rivers and caves; the biome sets trees, colours, weather and mobs. The
server cannot be told the biome through server.properties, so the manager puts a small data pack
(<world>\datapacks\manager-single-biome) in the new world's folder before the first start. On a modded server you can
also type a biome id from a mod. It only affects the world the server generates the first time; delete that data pack
only if you also delete the world.
"Let Bedrock players join" installs Geyser and Floodgate (Fabric and NeoForge only, when a build exists for that
Minecraft version). Servers that have Geyser get a Bedrock tab: status, the Windows Firewall rule for the UDP port,
a gamertag whitelist helper, an approval list for Bedrock players the whitelist turns away (or timed auto-approve),
and the main Geyser settings.
Delete (next to Remove, administrators only) deletes a stopped server's folder: Recycle Bin by default, optional
world backup zip first, and you must type the server's name. Folders that contain other servers, your user folder
or the manager itself are never deleted.

Backup, Restore and adding a mod loader
---------------------------------------
Every server has Backup and Restore buttons (level 4 and OP5). Backup saves the whole server (world, mods, configs,
settings, whitelist, ops; logs are left out) or just the world as a zip. A running server is backed up without
stopping it: the manager pauses world saving through RCON while the files are copied. Backups are kept in
<manager folder>\backups\<server> unless an administrator picks another folder, with an optional "keep only the
newest N" limit. Restore needs the server stopped, saves a safety copy of the current state first, and puts the old
state back automatically if the restore fails, so any restore can itself be undone. Administrators can also restore
a zip from elsewhere on the PC and delete backup files.
A vanilla server's Mods tab has "Add a mod loader": Fabric, Forge or NeoForge is installed into the same folder
(world, settings, whitelist and ops untouched), memory and Java flags are carried over to a new manager-start.bat
(your own start.bat is left alone), and a whole-server backup is taken first, so Restore can return it to vanilla.

Crashes, automatic backups and notifications
--------------------------------------------
Crash restart (on for every server; Properties > Manager settings): when a server goes down without anyone asking it to
(its process ended, or it wrote a crash report), the manager starts it again after 10 seconds. After 3 crashes in 15
minutes it stops trying and says so. Stopping it from the manager, or typing /stop in the game, is never a crash.
Automatic backups (Restore dialog > Automatic backups; off by default): every few hours, daily at a time, or on chosen
weekdays; whole server or world only; optionally skipped when nobody played since the last one. A schedule that was
missed while the PC or manager was off runs at the next check. Only the newest N backups are kept if you set that
limit (administrators), otherwise they pile up.
The bell at the top right lists what the manager noticed. Administrators see everything, operators (level 4) see their
own servers. A pop-up appears when something new happens while the page is open. It reports: crashes, automatic
restarts and servers that keep crashing; a server that did not auto-start; a failed backup; a changed Bedrock port; a
Bedrock player waiting for approval; drives that are nearly full; sign-ins blocked after wrong passwords; a new
Minecraft release; mod and loader updates; and "Geyser (or any mod) now supports Minecraft 26.3 - update now?" for
Fabric servers, with an Update now button (administrators) that stops the server, backs it up, updates it and starts
it again. About every 6 hours (and when you press "Check for updates now" in the notification settings) the manager runs
the updater's dry run on each Fabric server, which changes nothing.
Notification settings (gear in the bell): choose which kinds of events pop up on this device; turn on desktop
notifications (the browser asks permission; browsers only allow this when the manager is opened as
http://localhost:8585 on the server PC or over HTTPS, not at the PC's network address); and, for administrators, a
webhook (Discord or plain JSON) that receives the same events, with a choice of kinds and a minimum severity.

CurseForge and modpacks
-----------------------
Settings > CurseForge takes your own free CurseForge API key (console.curseforge.com > API keys). It is stored only in
this manager's config on this PC. With it, a server's Mods tab can search and install mods from CurseForge as well as
Modrinth (required helper mods are added; each file is checked against CurseForge's checksum; a mod whose author
forbids downloads through the API is listed so you can download it by hand). CurseForge does not say whether a mod
runs on a server, so the manager warns when Modrinth knows it is client-only.
Add server > "Start from a modpack" builds a server from a modpack: search CurseForge or Modrinth, or open a pack file
(a CurseForge .zip or a Modrinth .mrpack). The manager reads the pack, installs the Minecraft version and loader it
asks for (Fabric, Forge or NeoForge; Quilt packs cannot run here), downloads every mod that runs on a server (mods
Modrinth knows to be client-only, resource packs and shader packs are skipped), copies the pack's config files, and
turns off automatic updates so the pack stays as its author tested it. Plan on several GB of memory and a few minutes
for the first start of a big pack. "What players need to join" can also export the client list as a CurseForge
modpack (.zip), listing the mods CurseForge recognises by file fingerprint.

LAN worlds
----------
Minecraft's Multiplayer screen only lists a server under "LAN worlds" if something announces it. Dedicated servers do
not, so the manager does it for every running server (Properties > Manager settings, on by default): a small multicast
message (224.0.2.60, port 4445) about every second and a half. Java Edition players on the same network see the server
and can join without typing an address. Some Wi-Fi networks block this between devices, and it never leaves your own
network. Bedrock does not use it.

Archives and Bedrock
--------------------
Create a server from a world also takes .7z, .rar, .tar, .tar.gz/.tgz and .tar.xz files besides .zip. They are unpacked
with the tar.exe that comes with Windows 10 and 11 (older Windows 10 builds may not read RAR or 7-Zip) into data\tmp,
and removed again afterwards.
Bedrock 26.60 (planned for 27 October 2026) stops using Bedrock's old connection method (RakNet) and uses a new one,
NetherNet. The manager moves every Geyser server to it (Geyser build 1237 or newer; an older one raises a notification
asking you to update it): Geyser accepts both methods, and each server gets its own Bedrock port, separate from its Java
port, because NetherNet's signaling needs that port over TCP as well as UDP, plus one more UDP port for NetherNet
itself. This happens the next time the server starts (or when the manager starts, for stopped servers), a notification
tells you the new port, and Bedrock players must then connect to the new port (the address stays the same). On the
Bedrock tab, "Allow through Windows Firewall" opens TCP and UDP on the Bedrock port and UDP on the NetherNet port.
Over the internet, forward those same ports on your router. Geyser also shows the Java server's name and message of the day
(first line = server list title, second line = server list text) to Bedrock players; both follow later changes (Bedrock
tab: "Keep the Bedrock server name and server list lines the same as the Java server"; existing servers get it too unless
switched off). Before a start the manager checks that the Bedrock ports are free; if not it picks free ones.
Worlds from singleplayer, and what players need to join
--------------------------------------------------------
Add server > "Create a server from a world" (administrators) turns a singleplayer world into a server. It lists the
worlds it finds in the standard Minecraft launcher and the CurseForge, Prism/MultiMC, Modrinth App, ATLauncher, FTB
and GDLauncher folders, and "Bring a world from anywhere" lets you pick a world folder, a launcher or instance
folder, or a .zip (for example from a friend). The world is copied, never moved, so it still works in singleplayer.
The wizard is pre-filled from the world (Minecraft version, game mode, difficulty, hardcore, loader). "Bring my
character over" saves your singleplayer inventory, position and experience under your Minecraft account (the
manager looks the account up at Mojang), and can make you an operator. Mods from the game's mods folder are looked
up on Modrinth: the ones that run on a server can be installed with the server; client-only ones (minimaps,
shaders) stay on your own game and can be remembered for the client list and modpack below.
JourneyMap waypoints: JourneyMap normally keeps waypoints on the player's own PC (journeymap\data\sp\<world>\waypoints,
.json files). When the wizard finds them next to the world (in the launcher's journeymap folder, in a folder you got
the world in, or inside a zip), "Import the JourneyMap waypoints" turns them into JourneyMap global waypoints of the new
server, so every player who has the JourneyMap mod sees them without importing anything. "Choose a waypoints folder"
lets you point at any folder that holds them. JourneyMap is installed on the server if it is missing; this needs a
Fabric, Forge or NeoForge server. Death markers are not imported. Worlds saved by newer JourneyMap versions already
carry each player's own waypoints inside the world and need nothing.
A server cannot send mods to players. On a modded server's Mods tab, "What players need to join" shows what each
player must install. "Copy client mods list" copies a plain-text message (Minecraft and loader versions plus each
mod with its Modrinth link) to paste into a chat. "Download client modpack" saves one .mrpack file that Modrinth App
(Add instance > Import from file) or Prism Launcher (Add instance > Import) turns into a ready-to-play profile; the
launcher downloads and verifies every mod itself, nothing is bundled. Mods not on Modrinth are listed so you can
send those files by hand.

Folders
-------
  app\       program files            (replace these to upgrade)
  runtime\   bundled Java              (used for the manager and as a default for servers)
  tools\     helper scripts
  data\      settings, caches, logs   (back this up; contains your server list)
  servers\   default place for new servers (you choose; each new server can go anywhere)

Other devices
-------------
Open  http://<this-PC's-address>:8585  from a phone or another computer on the same network and
sign in. The manager shows the address at the top of the page.
If it does not load, open Settings > Network access and click "Allow other devices" (Windows asks you
to approve it), or answer yes to the firewall question in Install.bat.

People and access
-----------------
Everyone gets their own login. The first account is the administrator (OP5): only OP5 creates or removes
servers and changes manager settings, updates or startup. Other people get a Minecraft-style level per server:
  0 Viewer       watch the console, no commands
  1 Moderator    basic commands        2 Gamemaster  gameplay commands (gamemode, give, tp...)
  3 Admin        kick, ban, op, whitelist and read settings
  4 Operator     start/stop/restart and edit settings; can invite people up to level 3 on their servers
Levels follow Minecraft's own command permissions, so a level 2 account cannot run /op or /stop.
Accounts that have a Minecraft username also become in-game operators of that level, kept in step automatically
(ops.json; only entries the manager created are changed, and you can switch it off per server on the Properties tab).
On a phone the server picker and the Stop/Restart/Backup/Restore actions are two compact dropdown buttons at the top.
Add people with the people icon (top right) > Invites: you get a one-time link that expires; the person
chooses their own username and password. Every action is recorded in a tamper-evident activity log
(People icon > Activity). Accounts are plain files in data\accounts; delete one to remove that person.

Start with Windows
------------------
Choose it in Install.bat, or later in the app: Settings > Start with Windows. "When the PC starts" runs
before anyone signs in (Windows asks for your password once). Servers marked "start automatically"
start with the manager. The same page can move old per-server Windows tasks over to the manager.

Stop / remove
-------------
Stop.bat stops the manager (servers keep running). Uninstall.ps1 removes shortcuts, the firewall
rule and the boot task. Your servers and worlds are never deleted by these scripts.

Forgot a password, or locked out?  Run Owner.bat (it needs no password, only access to this PC):
  Owner.bat list                      show accounts
  Owner.bat reset-password <user>     set a new password
  Owner.bat create-admin <user>       make a new administrator
  Owner.bat wipe-all                  delete every account and start over (then re-create the owner)

Notes
-----
- Windows 10/11, 64-bit. Current Minecraft versions need Java 25 (bundled). Older modpacks may
  need Java 8/17/21: the manager finds installed Java versions and picks a matching one.
- The page is plain HTTP; use it on a network you trust.
