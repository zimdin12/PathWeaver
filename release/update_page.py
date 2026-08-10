"""Update only the Modrinth page body and summary. Uploads nothing, touches no version."""
import json, os, sys, requests

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
TOK = os.environ["MODRINTH_PAT"]
H = {"Authorization": TOK, "User-Agent": "CasualZ/pathweaver-publish (steven.zimdin@gmail.com)"}
PID = "ZQJOU3vB"

body = open("build/publish/body.md", encoding="utf-8").read()
summary = (
    "Mob pathfinding runs on the server thread and causes tick spikes. PathWeaver moves it to "
    "spare CPU cores: mean tick roughly halved with 1024 mobs, worst-1% ticks down 55-61%. "
    "Server-side, clients need nothing. Read the warning before installing."
)
assert len(summary) <= 256, len(summary)

r = requests.patch(f"https://api.modrinth.com/v2/project/{PID}",
                   headers={**H, "Content-Type": "application/json"},
                   data=json.dumps({"body": body, "description": summary}))
print("page update:", r.status_code, r.text[:300] if r.status_code >= 300 else "")

check = requests.get(f"https://api.modrinth.com/v2/project/{PID}", headers=H).json()
print("served summary:", check["description"])
print("served body length:", len(check["body"]), "chars")
print("BODY MATCHES" if check["body"] == body else "BODY MISMATCH")
