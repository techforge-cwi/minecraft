"""Deploys the built plugin JARs to the Minecraft server. Runs in the deploy job of .github/workflows/deploy.yml.

Only files named tf-*.jar are touched: changed ones are replaced, new ones added, and ones whose plugin was deleted from
the repo are removed. Plugins installed by hand (LuckPerms etc.) are left alone.

Pick a method with the DEPLOY_METHOD variable, or leave it empty to auto-detect from the secrets that are set:

  pterodactyl   SFTP + panel API. Warns players, stops the server, uploads, starts it again.
                SFTP_*  and  PTERO_PANEL_URL, PTERO_SERVER_ID, PTERO_API_KEY
  ssh           SFTP + SSH commands (your own VPS). Stops, uploads, starts.
                SFTP_*  and  SSH_STOP_COMMAND, SSH_START_COMMAND
  exaroton      exaroton's API only (no SFTP). If the server is online: warns, stops, uploads, starts.
                If it's offline, just uploads; the new plugins load next time someone starts it.
                EXAROTON_API_TOKEN, EXAROTON_SERVER_ID
  next-restart  SFTP only, for hosts with no power API. Doesn't restart anything: changed JARs go to plugins/update/
                (Paper swaps them in at the next restart) and new JARs go straight into plugins/.
                SFTP_*

  SFTP_* = SFTP_HOST, SFTP_PORT (default 22), SFTP_USER, SFTP_PASSWORD or SFTP_PRIVATE_KEY,
           SFTP_HOST_KEY (output of `ssh-keyscan -p PORT HOST`)
  SFTP_PLUGINS_DIR (default "plugins")    RESTART_WARNING_SECONDS (default 30)    DRY_RUN=1 (show the plan only)

The server is stopped before upload because overwriting a JAR that a running server has open can crash it.

Usage: python deploy.py DIST_DIR
"""

import json
import os
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

PREFIX = "tf-"


def env(name, default=None, required=False):
    value = os.environ.get(name, "").strip() or default
    if required and not value:
        fail(f"Missing setting {name}. Add it to the 'production' environment (see docs/officer-setup.md).")
    return value


def fail(message):
    print(f"::error::{message}", flush=True)
    sys.exit(1)


def log(message):
    print(message, flush=True)


def http(method, url, token, body=None, content_type="application/json"):
    data = body if isinstance(body, bytes) or body is None else json.dumps(body).encode()
    request = urllib.request.Request(url, method=method, data=data, headers={
        "Authorization": f"Bearer {token}", "Accept": "application/json", "Content-Type": content_type})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            raw = response.read()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        hint = " (check the API key/token and server ID)" if e.code in (401, 403) else ""
        raise RuntimeError(f"{method} {urllib.parse.urlsplit(url).path} returned HTTP {e.code}{hint}") from None
    except urllib.error.URLError as e:
        raise RuntimeError(f"Couldn't reach {urllib.parse.urlsplit(url).netloc}: {e.reason}") from None


# ---------- Files: where the JARs go ----------

class SftpFiles:
    def __init__(self):
        import paramiko  # Only needed for SFTP methods.
        self.paramiko = paramiko
        self.client = self._connect()
        self.sftp = self.client.open_sftp()

    def _connect(self):
        paramiko = self.paramiko
        host = env("SFTP_HOST", required=True)
        port = int(env("SFTP_PORT", "22"))
        user = env("SFTP_USER", required=True)
        host_key = env("SFTP_HOST_KEY", required=True)
        password, private_key = env("SFTP_PASSWORD"), env("SFTP_PRIVATE_KEY")
        if not password and not private_key:
            fail("Set SFTP_PASSWORD or SFTP_PRIVATE_KEY.")

        client = paramiko.SSHClient()
        with tempfile.NamedTemporaryFile("w", delete=False, suffix=".known_hosts") as f:
            f.write(host_key.replace("\\n", "\n") + "\n")
        client.load_host_keys(f.name)
        client.set_missing_host_key_policy(paramiko.RejectPolicy())

        pkey = None
        if private_key:
            with tempfile.NamedTemporaryFile("w", delete=False, suffix=".key") as f:
                f.write(private_key.replace("\\n", "\n").strip() + "\n")
            os.chmod(f.name, 0o600)
            for key_type in (paramiko.Ed25519Key, paramiko.ECDSAKey, paramiko.RSAKey):
                try:
                    pkey = key_type.from_private_key_file(f.name)
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

    def list(self, directory):
        try:
            return set(self.sftp.listdir(directory))
        except FileNotFoundError:
            return None

    def mkdir(self, directory):
        try:
            self.sftp.mkdir(directory)
        except OSError:
            pass  # Already exists.

    def put(self, local, remote):
        self.sftp.put(str(local), remote)

    def remove(self, remote):
        self.sftp.remove(remote)

    def close(self):
        self.sftp.close()
        self.client.close()


class ExarotonFiles:
    def __init__(self, api):
        self.api = api

    def list(self, directory):
        try:
            info = self.api.call("GET", f"/files/info/{directory}/")
        except RuntimeError as e:
            if "HTTP 404" in str(e):
                return None
            raise
        return {child["name"] for child in (info.get("children") or [])}

    def mkdir(self, directory):
        pass  # Not needed: exaroton creates parent folders on upload.

    def put(self, local, remote):
        self.api.call("PUT", f"/files/data/{remote}/", Path(local).read_bytes(), "application/octet-stream")

    def remove(self, remote):
        self.api.call("DELETE", f"/files/data/{remote}/")

    def close(self):
        pass


# ---------- Power: stopping and starting the server ----------

class Pterodactyl:
    """Pterodactyl client API (also used by Pelican and most Pterodactyl-based host panels)."""

    def __init__(self):
        panel = env("PTERO_PANEL_URL", required=True).rstrip("/")
        self.base = f"{panel}/api/client/servers/{env('PTERO_SERVER_ID', required=True)}"
        self.token = env("PTERO_API_KEY", required=True)

    def _call(self, method, path, body=None):
        return http(method, self.base + path, self.token, body)

    def is_running(self):
        return self._call("GET", "/resources")["attributes"]["current_state"] != "offline"

    def stop(self, warning_seconds):
        """Returns True if the server was running (so it should be started again afterwards)."""
        if not self.is_running():
            log("Server is offline; it will stay offline.")
            return False
        if warning_seconds > 0:
            try:
                self._call("POST", "/command", {"command": warning_message(warning_seconds)})
            except RuntimeError as e:
                log(f"Couldn't send the chat warning ({e}); continuing.")
            time.sleep(warning_seconds)
        log("Stopping server...")
        self._call("POST", "/power", {"signal": "stop"})
        if not wait_for(lambda: not self.is_running(), 120):
            log("Server didn't stop within 120s; killing it.")
            self._call("POST", "/power", {"signal": "kill"})
            if not wait_for(lambda: not self.is_running(), 30):
                fail("Server still isn't offline. Nothing was uploaded. Check the panel.")
        return True

    def start(self):
        log("Starting server...")
        self._call("POST", "/power", {"signal": "start"})
        state = lambda: self._call("GET", "/resources")["attributes"]["current_state"]
        if wait_for(lambda: state() == "running", 180):
            log("Server is running.")
        else:
            log("::warning::Server didn't report 'running' within 180s. Check the panel console.")


class SshCommands:
    def __init__(self, files):
        self.client = files.client
        self.stop_command = env("SSH_STOP_COMMAND", required=True)
        self.start_command = env("SSH_START_COMMAND", required=True)

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
        return True

    def start(self):
        self._run(self.start_command)


class Exaroton:
    """exaroton API: https://developers.exaroton.com/"""

    OFFLINE, ONLINE, CRASHED = 0, 1, 7

    def __init__(self):
        api = env("EXAROTON_API_URL", "https://api.exaroton.com/v1").rstrip("/")  # Override only for testing.
        self.base = f"{api}/servers/{env('EXAROTON_SERVER_ID', required=True)}"
        self.token = env("EXAROTON_API_TOKEN", required=True)

    def call(self, method, path, body=None, content_type="application/json"):
        result = http(method, self.base + path, self.token, body, content_type)
        if isinstance(result, dict) and result.get("success") is False:
            raise RuntimeError(f"exaroton: {result.get('error')}")
        return result.get("data") if isinstance(result, dict) else result

    def status(self):
        return self.call("GET", "/")["status"]

    def stop(self, warning_seconds):
        status = self.status()
        if status in (self.OFFLINE, self.CRASHED):
            log("Server is offline; it will stay offline. New plugins load the next time it starts.")
            return False
        if status != self.ONLINE:
            fail(f"Server is busy (exaroton status {status}: starting, stopping, or saving). Try again in a minute.")
        if warning_seconds > 0:
            try:
                self.call("POST", "/command/", {"command": warning_message(warning_seconds)})
            except RuntimeError as e:
                log(f"Couldn't send the chat warning ({e}); continuing.")
            time.sleep(warning_seconds)
        log("Stopping server...")
        self.call("GET", "/stop/")
        if not wait_for(lambda: self.status() == self.OFFLINE, 180):
            fail("Server didn't stop within 180s. Nothing was uploaded. Check exaroton.")
        return True

    def start(self):
        log("Starting server...")
        self.call("GET", "/start/")
        if wait_for(lambda: self.status() == self.ONLINE, 240):
            log("Server is online.")
        else:
            log("::warning::Server isn't online yet after 240s. Check exaroton (it may be in a queue).")


# ---------- The deploy ----------

def warning_message(seconds):
    return f"say [TechForge] Restarting in {seconds}s to install new plugins. Back in about a minute!"


def wait_for(check, timeout):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if check():
            return True
        time.sleep(3)
    return False


def pick_method():
    method = env("DEPLOY_METHOD", "").lower()
    if not method:
        if env("EXAROTON_API_TOKEN"):
            method = "exaroton"
        elif env("PTERO_PANEL_URL"):
            method = "pterodactyl"
        elif env("SSH_STOP_COMMAND"):
            method = "ssh"
        else:
            fail("No way to stop the server is configured. Set the PTERO_*, SSH_*_COMMAND, or EXAROTON_* secrets, or set "
                 "the DEPLOY_METHOD variable to next-restart to upload without restarting.")
    if method not in ("pterodactyl", "ssh", "exaroton", "next-restart"):
        fail(f"Unknown DEPLOY_METHOD '{method}'. Use pterodactyl, ssh, exaroton, or next-restart.")
    return method


def main():
    if len(sys.argv) != 2:
        fail("Usage: deploy.py DIST_DIR")
    jars = sorted(Path(sys.argv[1]).glob(f"{PREFIX}*.jar"))
    if not jars:
        fail(f"No {PREFIX}*.jar files in {sys.argv[1]}.")
    method = pick_method()
    dry_run = env("DRY_RUN") == "1"
    warning = int(env("RESTART_WARNING_SECONDS", "30"))

    if method == "exaroton":
        power = Exaroton()
        files = ExarotonFiles(power)
        plugins_dir = "plugins"
    else:
        files = SftpFiles()
        plugins_dir = env("SFTP_PLUGINS_DIR", "plugins").rstrip("/")
        power = {"pterodactyl": Pterodactyl, "ssh": lambda: SshCommands(files), "next-restart": lambda: None}[method]()

    try:
        remote = files.list(plugins_dir)
        if remote is None:
            fail(f"'{plugins_dir}' doesn't exist on the server. Check SFTP_PLUGINS_DIR, and start the server once first.")
        new = {jar.name for jar in jars}
        stale = sorted(n for n in remote if n.startswith(PREFIX) and n.endswith(".jar") and n not in new)
        staged = method == "next-restart"

        log(f"Method: {method}. Plan:")
        for jar in jars:
            if jar.name in remote:
                log(f"  {'replace':<8} {plugins_dir}/{jar.name}" + (f"  (via {plugins_dir}/update/)" if staged else ""))
            else:
                log(f"  {'add':<8} {plugins_dir}/{jar.name}")
        for name in stale:
            log(f"  {'remove':<8} {plugins_dir}/{name}  (no longer in the repo)")
        if dry_run:
            log("DRY_RUN=1, so nothing was changed.")
            return

        if staged:
            files.mkdir(f"{plugins_dir}/update")
            for name in stale:
                files.remove(f"{plugins_dir}/{name}")
            for jar in jars:
                # A running server has its current JARs open, so changed ones wait in update/ for the next restart.
                target = f"{plugins_dir}/update/{jar.name}" if jar.name in remote else f"{plugins_dir}/{jar.name}"
                files.put(jar, target)
                log(f"Uploaded {target}")
            log(f"Uploaded {len(jars)} plugin(s). They take effect the next time the server restarts.")
            return

        was_running = power.stop(warning)
        try:
            for name in stale:
                files.remove(f"{plugins_dir}/{name}")
            for jar in jars:
                files.put(jar, f"{plugins_dir}/{jar.name}")
                log(f"Uploaded {jar.name} ({jar.stat().st_size // 1024} KB)")
        finally:
            # Start the server again even if an upload failed, so a bad deploy doesn't leave it down.
            if was_running:
                power.start()
        log(f"Deployed {len(jars)} plugin(s).")
    except RuntimeError as e:
        fail(str(e))
    finally:
        files.close()


if __name__ == "__main__":
    main()
