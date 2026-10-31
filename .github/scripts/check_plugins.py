"""Checks every plugin in plugins/ against the repo's rules. Runs in GitHub Actions (see .github/workflows/build.yml),
and you can run it yourself: python3 .github/scripts/check_plugins.py

This is a tripwire, not a sandbox. It catches honest mistakes and makes risky code obvious; an officer still reads
every pull request before it merges."""

import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PLUGINS = ROOT / "plugins"
CORE = "forge-core"
EXCEPTIONS_FILE = ROOT / ".github" / "policy-exceptions.txt"
PACKAGE_PREFIX = "io.github.techforgecwi."
PLACEHOLDER = "YOUR-GITHUB-USERNAME"

# rule id -> (why it's flagged, regex). Only applies to plugins other than ForgeCore.
RISKY = {
    "network": ("talks to the internet directly. Use ForgeAI from ForgeCore for AI calls.",
                r"\bjava\.net\b|\bHttpClient\b|\bHttpURLConnection\b|\bnew\s+(Server)?Socket\b|\bokhttp3?\b|\bURI\.create\b|\bnew\s+URL\b"),
    "env": ("reads environment variables, which is where secrets live.",
            r"\bSystem\s*\.\s*getenv\b|\bgetenv\s*\("),
    "process": ("runs programs on the server.",
                r"\bProcessBuilder\b|\bRuntime\s*\.\s*getRuntime\b|\.exec\s*\("),
    "reflection": ("uses reflection or class loading, which can get around these checks.",
                   r"\bsetAccessible\b|\bjava\.lang\.reflect\b|\bClass\s*\.\s*forName\b|\bMethodHandles\b|\bClassLoader\b|\bUnsafe\b|\bgetDeclared(Field|Method|Constructor)s?\b"),
    "secrets": ("reads ForgeCore's API key file or settings.",
                r"api-key\.txt|ForgeCore[/\\\"]|plugins/ForgeCore"),
    "native": ("loads native code or runs scripts.",
               r"\bSystem\s*\.\s*load(Library)?\b|\bScriptEngine\b|\bJNI\b|\bjava\.lang\.foreign\b"),
    "unicode-escape": ("uses \\uXXXX escapes in source code, which can hide what code really does. Type the character itself.",
                       r"\\u+[0-9a-fA-F]{4}"),
}

SECRET_PATTERNS = [
    r"\bsk-(proj-|ant-)?[A-Za-z0-9_\-]{20,}",
    r"\bAKIA[0-9A-Z]{16}\b",
    r"\bgh[pousr]_[A-Za-z0-9]{36,}\b",
    r"\bAIza[0-9A-Za-z_\-]{35}\b",
    r"-----BEGIN [A-Z ]*PRIVATE KEY-----",
]
BINARY_SUFFIXES = {".jar", ".class", ".so", ".dll", ".dylib", ".exe", ".zip"}
TEXT_SUFFIXES = {".java", ".yml", ".yaml", ".json", ".txt", ".md", ".kts", ".properties", ".toml", ".py", ".sh"}
SKIP_DIRS = {".git", ".gradle", "build", "run", ".idea", "out"}

problems, notes = [], []


def problem(path, message):
    rel = path.relative_to(ROOT).as_posix() if isinstance(path, Path) else path
    problems.append(f"`{rel}`: {message}")
    print(f"::error file={rel}::{message}")


def note(message):
    notes.append(message)
    print(f"::notice::{message}")


def read_plugin_yml(path):
    """Top-level keys only, which is all we need. Handles `key: value` and `key: [a, b]` or `- item` lists."""
    data, current = {}, None
    for raw in path.read_text(encoding="utf-8").splitlines():
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        if not raw.startswith((" ", "\t", "-")):
            key, _, value = raw.partition(":")
            current = key.strip()
            value = value.split(" #")[0].strip()
            if value.startswith("[") and value.endswith("]"):
                data[current] = [v.strip().strip("'\"") for v in value[1:-1].split(",") if v.strip()]
            elif value:
                data[current] = value.strip("'\"")
            else:
                data[current] = []
        elif raw.lstrip().startswith("- ") and isinstance(data.get(current), list):
            data[current].append(raw.lstrip()[2:].strip().strip("'\""))
    return data


def load_exceptions():
    allowed = set()
    if EXCEPTIONS_FILE.exists():
        for line in EXCEPTIONS_FILE.read_text(encoding="utf-8").splitlines():
            line = line.split("#")[0].strip()
            if ":" in line:
                plugin, _, rule = line.partition(":")
                allowed.add((plugin.strip(), rule.strip()))
    return allowed


def walk_files(base):
    for dirpath, dirnames, filenames in os.walk(base):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in filenames:
            yield Path(dirpath) / name


def check_plugin(folder, exceptions, seen_names):
    name = folder.name
    if not re.fullmatch(r"[a-z][a-z0-9]*(-[a-z0-9]+)*", name):
        problem(folder, "Folder names use lowercase letters, numbers and dashes, like `trivia-bot`.")
    if not (folder / "README.md").is_file():
        problem(folder, "Add a README.md that says what the plugin does and lists its commands.")

    yml_path = folder / "src" / "main" / "resources" / "plugin.yml"
    if not yml_path.is_file():
        problem(folder, "Missing `src/main/resources/plugin.yml`. Start new plugins with `./gradlew newPlugin -Pplugin=" + name + "`.")
        return
    yml = read_plugin_yml(yml_path)
    for key in ("name", "version", "main", "api-version", "authors"):
        if not yml.get(key):
            problem(yml_path, f"`{key}:` is missing or empty.")
    if yml.get("libraries") and (name, "libraries") not in exceptions:
        problem(yml_path, "`libraries:` makes the server download extra code at startup. Ask an officer first "
                          f"(they can add `{name}: libraries` to .github/policy-exceptions.txt).")
    if (folder / "src" / "main" / "resources" / "paper-plugin.yml").exists():
        problem(folder, "Use plugin.yml, not paper-plugin.yml. Paper plugin loaders can download code at startup.")
    if PLACEHOLDER in yml_path.read_text(encoding="utf-8"):
        problem(yml_path, f"Replace `{PLACEHOLDER}` in `authors:` with your GitHub username.")

    plugin_name = str(yml.get("name", ""))
    if plugin_name:
        if plugin_name.lower() in seen_names:
            problem(yml_path, f"Another plugin is already named `{plugin_name}`. Pick a different `name:`.")
        seen_names.add(plugin_name.lower())

    main = str(yml.get("main", ""))
    if main:
        if not main.startswith(PACKAGE_PREFIX):
            problem(yml_path, f"Put your code in a package that starts with `{PACKAGE_PREFIX}`, like `{PACKAGE_PREFIX}{name.replace('-', '')}`.")
        main_file = folder / "src" / "main" / "java" / (main.replace(".", "/") + ".java")
        if not main_file.is_file():
            problem(yml_path, f"`main: {main}` points to `{main_file.relative_to(ROOT).as_posix()}`, which doesn't exist.")

    if (folder / "build.gradle.kts").exists() or (folder / "build.gradle").exists():
        note(f"`{name}` has its own Gradle build file. An officer needs to look at build changes closely.")

    sources = []
    for path in walk_files(folder):
        if path.suffix in BINARY_SUFFIXES:
            problem(path, "Don't commit compiled or binary files. CI builds the plugin from source.")
        elif path.suffix in {".kt", ".kts", ".groovy", ".scala"} and "src" in path.relative_to(folder).parts:
            problem(path, "Plugins in this repo are written in Java (.java files).")
        elif path.suffix == ".java":
            sources.append(path)

    uses_core = False
    for path in sources:
        text = path.read_text(encoding="utf-8", errors="replace")
        if "io.github.techforgecwi.core" in text:
            uses_core = True
        if name == CORE:
            continue
        for rule, (why, pattern) in RISKY.items():
            if (name, rule) in exceptions:
                continue
            for number, line in enumerate(text.splitlines(), 1):
                if re.search(pattern, line):
                    rel = path.relative_to(ROOT).as_posix()
                    problems.append(f"`{rel}` line {number}: this code {why} (rule `{rule}`)")
                    print(f"::error file={rel},line={number}::This code {why} If you really need it, ask an officer "
                          f"to add `{name}: {rule}` to .github/policy-exceptions.txt.")
                    break

    if uses_core and name != CORE and "ForgeCore" not in yml.get("depend", []):
        problem(yml_path, "This plugin uses ForgeAI, so add `depend: [ForgeCore]`.")


def check_secrets():
    for path in walk_files(ROOT):
        if path.name == "api-key.txt":
            problem(path, "Never commit API keys. Delete this file and rotate the key.")
            continue
        if path.suffix not in TEXT_SUFFIXES or path == Path(__file__).resolve():
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        for pattern in SECRET_PATTERNS:
            if re.search(pattern, text):
                problem(path, "This looks like an API key or private key. Remove it, and rotate the key: it's public now.")
                break


def note_files_outside_plugins():
    if not os.environ.get("GITHUB_BASE_REF"):
        return
    # On pull requests the checkout is GitHub's test merge of the PR into main, so its first parent is main.
    diff = subprocess.run(["git", "diff", "--name-only", "HEAD^1", "HEAD"], capture_output=True, text=True, cwd=ROOT)
    outside = [p for p in diff.stdout.splitlines() if p and not p.startswith("plugins/")]
    if outside:
        note("This pull request changes files outside `plugins/` (" + ", ".join(f"`{p}`" for p in outside[:8])
             + (", ..." if len(outside) > 8 else "") + "). An officer will look at those closely.")


def main():
    exceptions = load_exceptions()
    seen = set()
    folders = sorted(p for p in PLUGINS.iterdir() if p.is_dir()) if PLUGINS.is_dir() else []
    for folder in folders:
        check_plugin(folder, exceptions, seen)
    if not any(f.name == CORE for f in folders):
        problem("plugins", f"`plugins/{CORE}` is missing. Every other plugin depends on it.")
    check_secrets()
    note_files_outside_plugins()

    summary = [f"## Plugin rules: {'❌ ' + str(len(problems)) + ' problem(s)' if problems else '✅ all good'}", ""]
    summary.append(f"Checked {len(folders)} plugin(s): " + ", ".join(f"`{f.name}`" for f in folders))
    if problems:
        summary += ["", "### Fix these", ""] + [f"- {p}" for p in problems]
    if notes:
        summary += ["", "### For the reviewer", ""] + [f"- {n}" for n in notes]
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as f:
            f.write("\n".join(summary) + "\n")
    print("\n".join(summary))
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
