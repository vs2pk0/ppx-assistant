#!/usr/bin/env python3
"""Local debug APK helper; requires authorized adb root and private test flags."""
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import time
import uuid

adb = str(Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk")) / "platform-tools/adb")
runner = "io.github.vs2pk0.piyou.test/com.akari.ppx.test.PreferenceInstrumentation"
mode = sys.argv[1]
value = (json.loads(Path(sys.argv[2]).read_text()) if mode == "restore" else json.loads(sys.argv[2])) if len(sys.argv) > 2 else {}

def shell(command, **kwargs):
    return subprocess.check_output([adb, "shell", command], text=True, **kwargs)

if mode in {"profile", "restore"}:
    command = ["am", "instrument", "-w", "-e", "values", json.dumps(value, ensure_ascii=False), runner]
    if mode == "restore":
        command[-1:-1] = ["-e", "replace", "true"]
    output = shell(shlex.join(command))
    if "INSTRUMENTATION_CODE: -1" not in output or "INSTRUMENTATION_RESULT: error=" in output:
        raise RuntimeError(output)
    print("Profile applied:", ", ".join(value))
elif mode == "ui":
    value["request"] = str(uuid.uuid4())
    cache = "/data/user/0/com.sup.android.superb/cache/"
    subprocess.run([adb, "shell", "su -c " + shlex.quote("cat > " + cache + "ppx-ui-command.json")],
                   input=json.dumps(value, ensure_ascii=False), text=True, check=True)
    for _ in range(40):
        try:
            result = json.loads(shell("su -c " + shlex.quote("cat " + cache + "ppx-ui-result.json"), stderr=subprocess.DEVNULL))
            if result.get("request") == value["request"]:
                if "error" in result:
                    raise RuntimeError(result["error"])
                Path("build/device/ui-latest.json").write_text(json.dumps(result, ensure_ascii=False, indent=2))
                for key in ("action", "clipboard", "media", "self", "soundStream", "senderRoute", "commentColor"):
                    if key in result:
                        print(key + ":", json.dumps(result[key], ensure_ascii=False))
                for view in result.get("views", []):
                    if view["text"] or view["desc"] or (view["id"] and view["clickable"]):
                        print(json.dumps(view, ensure_ascii=False))
                break
        except (json.JSONDecodeError, subprocess.CalledProcessError):
            pass
        time.sleep(0.25)
    else:
        raise RuntimeError("Debug host UI probe did not respond")
else:
    raise ValueError("mode must be profile, restore or ui")
