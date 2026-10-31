"""Deploys the built plugin JARs to the Minecraft server. Runs in the deploy job of .github/workflows/deploy.yml.

    warn players -> stop the server -> replace tf-*.jar over SFTP -> start the server

The server is stopped first because overwriting a JAR that a running server has open can crash it.

Settings come from environment variables (GitHub environment secrets):

  SFTP_HOST, SFTP_PORT (default 22), SFTP_USER       where to upload
  SFTP_PASSWORD or SFTP_PRIVATE_KEY                  how to log in
  SFTP_HOST_KEY                                      `ssh-keyscan -p PORT HOST` output, so we know it's really your server
  SFTP_PLUGINS_DIR (default "plugins")               the server's plugins folder, relative to the SFTP login folder

  Then ONE way to stop and start the server:
  PTERO_PANEL_URL, PTERO_SERVER_ID, PTERO_API_KEY    game hosts with a Pterodactyl-style panel (client API key)
  SSH_STOP_COMMAND, SSH_START_COMMAND                your own VPS, e.g. "sudo systemctl stop minecraft"

  RESTART_WARNING_SECONDS (default 30)               chat warning before a Pterodactyl restart
  DRY_RUN=1                                          connect and show what would change, without changing anything

Usage: python deploy.py DIST_DIR
"""

import json
import os
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

import paramiko

PREFIX = "tf-"


def env(name, default=None, required=False):
    value = os.environ.get(name, "").strip() or default
    if required and not value:
        fail(f"Missing setting {name}. Add it to the 'production' environment secrets (see docs/officer-setup.md).")
    return value


def fail(message):
    print(f"::error::{message}")
    sys.exit(1)


def log(message):
    print(message, flush=True)


class Pterodactyl:
    """Pterodactyl client API (also used by Pelican and most Pterodactyl-based host panels)."""

    def __init__(self, panel_url, server_id, api_key):
        self.base = f"{panel_url.rstrip('/')}/api/client/servers/{server_id}"
        self.api_key = api_key

    def _call(self, method, path, body=None):
        request = urllib.request.Request(
            self.base + path,
            method=method,
            data=None if body is None else json.dumps(body).encode(),
            headers={"Authorization": f"Bearer {self.api_key}", "Accept": "application/json",
                     "Content-Type": "application/json"},
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                raw = response.read()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as e:
            raise RuntimeError(f"Panel API {method} {path} returned HTTP {e.code}") from None

    def state(self):
        return self._call("GET", "/resources")["attributes"]["current_state"]

    def say(self, message):
        try:
            self._call("POST", "/command", {"command": f"say {message}"})
        except RuntimeError as e:
            log(f"Couldn't send the chat warning ({e}); continuing.")

    def stop(self, warning_seconds):
        if self.state() == "offline":
            log("Server is already offline.")
            return
        if warning_seconds > 0:
            self.say(f"[TechForge] Restarting in {warning_seconds}s to install new plugins. Back in about a minute!")
            time.sleep(warning_seconds)
        log("Stopping server...")
        self._call("POST", "/power", {"signal": "stop"})
        if not self._wait_for("offline", 120):
            log("Server didn't stop within 120s; killing it.")
            self._call("POST", "/power", {"signal": "kill"})
            if not self._wait_for("offline", 30):
                fail("Server still isn't offline. Nothing was uploaded. Check the panel.")

    def start(self):
        log("Starting server...")
        self._call("POST", "/power", {"signal": "start"})
        if self._wait_for("running", 180):
            log("Server is running.")
        else:
            log("::warning::Server didn't report 'running' within 180s. Check the panel console.")

    def _wait_for(self, wanted, timeout):
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.state() == wanted:
                return True
            time.sleep(3)
        return False


class SshCommands:
    def __init__(self, client, stop_command, start_command):
        self.client, self.stop_command, self.start_command = client, stop_command, start_command

    def _run(self, command):
        log(f"$ {command}")
        _, stdout, stderr = self.client.exec_command(command, timeout=180)
        code = stdout.channel.recv_exit_status()
        output = (stdout.read() + stderr.read()).decode(errors="replace").strip()
        if output:
            log(output)
        if code != 0:
            fail(f"'{command}' exited with {code}.")

    def stop(self, warning_seconds):
        self._run(self.stop_command)

    def start(self):
        self._run(self.start_command)


def connect():
    host = env("SFTP_HOST", required=True)
    port = int(env("SFTP_PORT", "22"))
    user = env("SFTP_USER", required=True)
    host_key = env("SFTP_HOST_KEY", required=True)
    password = env("SFTP_PASSWORD")
    private_key = env("SFTP_PRIVATE_KEY")
    if not password and not private_key:
        fail("Set SFTP_PASSWORD or SFTP_PRIVATE_KEY.")

    client = paramiko.SSHClient()
    with tempfile.NamedTemporaryFile("w", delete=False, suffix=".known_hosts") as f:
        f.write(host_key.replace("\\n", "\n") + "\n")
        known_hosts = f.name
    client.load_host_keys(known_hosts)
    client.set_missing_host_key_policy(paramiko.RejectPolicy())

    pkey = None
    if private_key:
        with tempfile.NamedTemporaryFile("w", delete=False, suffix=".key") as f:
            f.write(private_key.replace("\\n", "\n").strip() + "\n")
            key_path = f.name
        os.chmod(key_path, 0o600)
        for key_type in (paramiko.Ed25519Key, paramiko.ECDSAKey, paramiko.RSAKey):
            try:
                pkey = key_type.from_private_key_file(key_path)
                break
            except paramiko.SSHException:
                continue
        if pkey is None:
            fail("SFTP_PRIVATE_KEY isn't a key paramiko can read (use an unencrypted ed25519, ECDSA or RSA key).")

    try:
        client.connect(host, port=port, username=user, password=password, pkey=pkey,
                       look_for_keys=False, allow_agent=False, timeout=30)
    except paramiko.BadHostKeyException:
        fail("The server's SSH host key doesn't match SFTP_HOST_KEY. If you didn't change servers, stop and investigate.")
    except paramiko.AuthenticationException:
        fail(f"SFTP login as '{user}' was rejected. Check SFTP_USER and SFTP_PASSWORD / SFTP_PRIVATE_KEY.")
    except paramiko.SSHException as e:
        if "not found in known_hosts" in str(e):
            fail(f"SFTP_HOST_KEY has no entry for {host}:{port}. Paste the output of: ssh-keyscan -p {port} {host}")
        fail(f"Couldn't connect to {host}:{port}: {e}")
    except OSError as e:
        fail(f"Couldn't reach {host}:{port} ({e}). Check SFTP_HOST and SFTP_PORT, and that the server allows SFTP.")
    return client


def main():
    if len(sys.argv) != 2:
        fail("Usage: deploy.py DIST_DIR")
    jars = sorted(Path(sys.argv[1]).glob(f"{PREFIX}*.jar"))
    if not jars:
        fail(f"No {PREFIX}*.jar files in {sys.argv[1]}.")
    dry_run = env("DRY_RUN") == "1"
    plugins_dir = env("SFTP_PLUGINS_DIR", "plugins").rstrip("/")
    warning = int(env("RESTART_WARNING_SECONDS", "30"))

    client = connect()
    sftp = client.open_sftp()

    if env("PTERO_PANEL_URL"):
        restarter = Pterodactyl(env("PTERO_PANEL_URL"), env("PTERO_SERVER_ID", required=True), env("PTERO_API_KEY", required=True))
    elif env("SSH_STOP_COMMAND"):
        restarter = SshCommands(client, env("SSH_STOP_COMMAND"), env("SSH_START_COMMAND", required=True))
    else:
        fail("Set PTERO_PANEL_URL/PTERO_SERVER_ID/PTERO_API_KEY or SSH_STOP_COMMAND/SSH_START_COMMAND so the server can be "
             "stopped before upload.")

    try:
        remote = set(sftp.listdir(plugins_dir))
    except FileNotFoundError:
        fail(f"'{plugins_dir}' doesn't exist on the server. Check SFTP_PLUGINS_DIR.")
    new = {jar.name for jar in jars}
    stale = sorted(name for name in remote if name.startswith(PREFIX) and name.endswith(".jar") and name not in new)

    log("Plan:")
    for jar in jars:
        log(f"  {'replace' if jar.name in remote else 'add':<8} {plugins_dir}/{jar.name}")
    for name in stale:
        log(f"  {'remove':<8} {plugins_dir}/{name}  (no longer in the repo)")
    if dry_run:
        log("DRY_RUN=1, so nothing was changed.")
        return

    restarter.stop(warning)
    try:
        for name in stale:
            sftp.remove(f"{plugins_dir}/{name}")
        for jar in jars:
            sftp.put(str(jar), f"{plugins_dir}/{jar.name}")
            log(f"Uploaded {jar.name} ({jar.stat().st_size // 1024} KB)")
    finally:
        # Start the server again even if an upload failed, so a bad deploy doesn't leave it down.
        restarter.start()
        sftp.close()
        client.close()
    log(f"Deployed {len(jars)} plugin(s).")


if __name__ == "__main__":
    main()
