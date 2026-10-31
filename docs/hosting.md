# Hosting options

Researched October 31, 2026. Prices change, so check the host's own page before paying. **Deploy** means which [`deploy.py`](../.github/scripts/deploy.py) method works with that host (see [officer setup](officer-setup.md#3-automatic-deploys)).

## What the club needs

- **Custom plugin JARs.** This rules out Aternos and most free tiers.
- **A way for CI to upload them**: SFTP or a file API. A power API (stop/start) is nice to have.
- **4 GB RAM** for Paper plus light plugins and 10–20 players. Choose 6 GB if plugins get heavier.
- A location near Boise (Salt Lake City, Hillsboro, Seattle) for lower ping.
- An account owned by the club email, not one student.

## Our picks

| If the club... | Use | Cost | Deploy |
|---|---|---|---|
| Plays mostly on meeting nights | **exaroton** (pay per hour) | About €0.06/hr at 6 GB, so roughly €1–3/month | `exaroton` |
| Wants it always on, about $10–15/mo | **Sparked Host** Enterprise, Salt Lake City | $10.36/mo (4 GB), $15.54 (6 GB) | `next-restart` |
| Wants always on with Pterodactyl's documented API | **PebbleHost** Premium, Hillsboro OR | $9/mo (4 GB), $13.50 (6 GB) | `pterodactyl`, if its panel accepts the client API; otherwise `next-restart` |
| Has $0 and an officer willing to do sysadmin work | **Oracle Cloud** Always Free | $0 | `ssh` |

**exaroton** only bills while the server is online: 1 credit per GB-hour, and 1 credit = €0.01. Four 3-hour meetings at 6 GB come to about €0.72 a month. It stays cheaper than a $15.54 fixed plan until about 220 server-hours a month.
- Its API uploads files and starts and stops the server. It has no SFTP, but our deploy doesn't need it.
- Credit pools let members chip in. A Discord bot can start the server.
- The catch: someone has to start it before playing, auto-stop must be on, and the region is just "North America."
- Before relying on it, test that file uploads work while the server is offline. That behavior wasn't documented.

## Paid managed hosts

| Host | About 6 GB | Locations | SFTP | Power API | Backups | Deploy |
|---|---|---|---|---|---|---|
| exaroton | €0.06/hr | North America | No | Yes (documented) | One-click, to cloud | `exaroton` |
| Sparked Host (Enterprise, Ryzen 9 7900) | $15.54 | **Salt Lake City**, Dallas | Yes, with SSH keys | API page exists but is undocumented | 3 free slots | `next-restart` |
| PebbleHost Premium | $13.50 | **Hillsboro OR** | Yes (2022) | Said to be Pterodactyl-based; endpoints unverified | Daily + hourly | `pterodactyl`? / `next-restart` |
| PebbleHost Budget | $6 | North America (city not stated) | Yes | Same | None listed | `next-restart` |
| BisectHosting | $17.99 | **Hillsboro, Seattle**, LA, Dallas, Chicago | Yes (2022) | Not documented | Daily | `next-restart` |
| Bloom.host | $18 (8 GB Performance: $22) | LA, Dallas | Yes | None | Off-site incremental | `next-restart` |
| Modrinth Hosting | $18 ($15 quarterly) | North America | Yes | None public | 15 off-site | `next-restart`. It showed out of stock on Oct 31 |
| Apex Hosting | About $22.49 | Arizona, Las Vegas | **FTP only** | Unverified | Unverified | None: manual uploads only. It has a 25% `STUDENT` code (confirm it applies to CWI) |
| Shockbyte | Couldn't verify USD prices | North America | Yes | Unverified | Unverified | `next-restart` |

`next-restart` uploads without restarting. Changes go live at the host's next scheduled restart, or when an officer clicks Restart. On these hosts, set up a **daily scheduled restart** in the panel (for example, 5 a.m.).

## Free options

| Option | Verdict |
|---|---|
| **Oracle Cloud Always Free (Ampere A1)** | The only free option that fits.<br>**Gives you:** 2 OCPU / 12 GB, cut from 4/24 in June 2026 and enforced since about August. Give Paper a 6 GB heap.<br>**Catches:**<ul><li>Signup requires one officer's card and phone, so the account is tied to that person.</li><li>Capacity errors are common at signup.</li><li>Idle instances can be reclaimed (a 7-day window where CPU, network and memory all stay under 20%). A running Paper server with a 6 GB heap should stay above the memory threshold.</li><li>You open the firewall, do the backups, and keep it updated yourself.</li><li>ARM cores are slower per core than a Ryzen game host.</li></ul> |
| Self-host (club, campus, or member PC) + playit.gg tunnel | Works and needs no port forwarding. It depends on one person's machine staying on. |
| Aternos | **No.** It doesn't allow custom plugin uploads. |
| Minehut free | **No.** 1 GB, no SFTP, players join through the Minehut lobby, and there are no custom ports for Geyser. |
| FalixNodes free | **No.** Mandatory ads, idle kicks, and session timers. |
| GitHub Student Pack credits | DigitalOcean left the pack, and its credits expired July 31, 2026. Azure for Students ($100/yr) requires full-time enrollment, and $100 won't run an 8 GB VM for a year. |

## Unmanaged VPS (about 8 GB)

These cost more than game hosts in 2026, and their shared vCPUs usually give worse tick times. Paper's main loop runs on one core. Use one only if you want the sysadmin practice.

| Provider | 8 GB plan | Per month |
|---|---|---|
| Vultr | Regular 4 vCPU (High Frequency is better for Minecraft: $48) | $40 |
| Hetzner | CPX32 (Hillsboro), repriced in June 2026 | About €36 |
| DigitalOcean | Basic 4 vCPU | $48 |
| Akamai/Linode | Shared 4 vCPU | $48 |
| Contabo | Cloud VPS 10 (12-month term; heavily oversold) | About €4.50–5.50 |

## Gotchas

- **Never use `/reload`** to load new plugins. Paper needs a full restart, and the deploy handles that.
- **Pterodactyl-style hosts.** The SFTP password is usually the *panel* password. Make a dedicated deploy subuser with only file and power permissions.
- **Geyser (Bedrock players)** needs an extra UDP port. Confirm the host allows one before buying.
- **First-month promos.** Apex and others advertise discounted first months. Compare renewal prices.

## Sources

[Oracle Always Free resources](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm) · [InfoQ on the A1 cut](https://www.infoq.com/news/2026/07/oracle-cloud-free-tier-limits/) · [exaroton pricing](https://support.exaroton.com/hc/en-us/articles/360019687657-Pricing) · [exaroton API](https://developers.exaroton.com/) · [Sparked Host](https://sparkedhost.com/minecraft-server-hosting) · [PebbleHost](https://pebblehost.com/minecraft) · [BisectHosting](https://www.bisecthosting.com/minecraft-server-hosting) · [Bloom.host](https://bloom.host/minecraft) · [Modrinth Hosting](https://modrinth.com/hosting) · [Apex pricing](https://apexminecrafthosting.com/pricing/) · [Aternos: no plugin uploads](https://support.aternos.org/hc/en-us/articles/360027235831) · [Minehut plans](https://minehut.wiki.gg/wiki/Plan) · [Falix free plan limits](https://falixnodes.net/blog/why-is-free-plan-limited) · [GitHub Student Pack](https://education.github.com/pack) · [Azure for Students](https://learn.microsoft.com/en-us/azure/education-hub/about-azure-for-students) · [playit.gg](https://playit.gg/pricing) · [Vultr](https://www.vultr.com/pricing/) · [Hetzner](https://www.hetzner.com/cloud/regular-performance/) · [DigitalOcean](https://www.digitalocean.com/pricing/droplets) · [Akamai](https://www.akamai.com/cloud/pricing/cpu) · [Contabo](https://contabo.com/en-us/vps/)
