#!/usr/bin/env python3
"""Test players that play: they join the TV's room and vote, answer, draw and pick on their own.

Usage: python3 tools/test_players/bots.py <tv host:port> [number of bots, default 2]
Names: Robô Rui, Robô Eva and so on, or your own: BOT_NAMES="Sara Margarida,Pedro Henrique" python3 ...
Selfies: bot1.jpg and bot2.jpg, or the JPEGs in a folder of yours, in name order: BOT_SELFIES=<folder>.
Stop with Ctrl-C: their phones "go away" and they grey out on the TV.
Each bot acts 2-8 seconds into each stage, at random. They draw on the spotlight player's selfie with
doodle.swift (glasses, a crown, cat ears...), or, without Swift, hand in their own selfie.
"""
import glob
import json
import os
import random
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
NAMES = os.environ.get("BOT_NAMES", "").split(",") if os.environ.get("BOT_NAMES") else \
    ["Robô Rui", "Robô Eva", "Robô Zé", "Robô Ana", "Robô Tó", "Robô Lu", "Robô Gil", "Robô Bia"]


def post(url, data, content_type="application/x-www-form-urlencoded"):
    request = urllib.request.Request(url, data=data, headers={"Content-Type": content_type}, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as e:
        return e.code, b""


def compile_doodler():
    """Builds doodle.swift once, so each drawing takes a moment rather than a compile. None without Swift."""
    if not shutil.which("swiftc"):
        return None
    out = os.path.join(tempfile.mkdtemp(), "doodle")
    built = subprocess.run(["swiftc", "-O", "-o", out, os.path.join(HERE, "doodle.swift")], capture_output=True)
    return out if built.returncode == 0 else None


DOODLER = None


def fetch(url):
    with urllib.request.urlopen(url, timeout=10) as response:
        return response.read()


class Bot:
    def __init__(self, host, name, selfie, number):
        self.base = f"http://{host}"
        self.name = name
        self.selfie = selfie
        self.number = number
        self.drawings = 0
        status, body = post(f"{self.base}/api/join", urllib.parse.urlencode({"name": name}).encode())
        if status != 200:
            sys.exit(f"{name} couldn't join ({status}): is the TV in its room?")
        joined = json.loads(body)
        self.token, self.id = joined["token"], joined["id"]
        post(f"{self.base}/api/selfie?t={self.token}", selfie, "image/jpeg")
        self.acted = set()  # (serial, stage) already handled
        print(f"{name} joined")

    def act(self, path, data, content_type="application/x-www-form-urlencoded"):
        status, _ = post(f"{self.base}{path}?t={self.token}", data, content_type)
        print(f"{self.name}: {path} -> {status}")

    def react(self, room):
        game = room.get("game")
        if room.get("phase") != "GAME" or not game:
            return
        key = (game["serial"], game["stage"])
        if key in self.acted:
            return
        stage = game["stage"]
        others = [p["id"] for p in room["players"] if p["id"] != self.id]
        me_spotlight = game.get("spotlight") == self.id
        if stage == "VOTE" and game["voted"] is None:
            # Everyone can vote for anyone, themselves included.
            todo = lambda: self.act("/api/vote", f"target={random.choice(others + [self.id])}".encode())
        elif stage in ("ANSWER", "PHOTO") and game["answered"] is None:
            todo = lambda: self.act("/api/answer", f"option={random.randrange(4)}".encode())
        elif stage == "DRAW" and not me_spotlight and not game["drew"]:
            spotlight = game.get("spotlight")
            todo = lambda: self.act("/api/drawing", self.drawing(spotlight), "image/jpeg")
        # With only one drawing that isn't theirs, a player has no choice and doesn't pick.
        elif stage == "PICK" and game["picked"] is None and game.get("picks", True):
            choices = [d for d in game.get("drawers", []) if d != self.id]
            if not choices:
                return
            todo = lambda: self.act("/api/pick", f"drawer={random.choice(choices)}".encode())
        else:
            return
        self.acted.add(key)
        threading.Timer(random.uniform(2, 8), todo).start()

    def drawing(self, spotlight):
        """A doodle on the spotlight player's selfie, a different one each time; their own selfie if that fails."""
        if DOODLER is None or spotlight is None:
            return self.selfie
        try:
            folder = tempfile.mkdtemp()
            selfie, out = os.path.join(folder, "selfie.jpg"), os.path.join(folder, "drawing.jpg")
            with open(selfie, "wb") as f:
                f.write(fetch(f"{self.base}/selfie/{spotlight}?t={self.token}&v=1"))
            subprocess.run([DOODLER, selfie, out, str(self.number + self.drawings)], check=True)
            self.drawings += 1
            with open(out, "rb") as f:
                return f.read()
        except Exception as e:
            print(f"{self.name}: couldn't draw ({e}); handing in my selfie")
            return self.selfie

    def follow(self):
        """Reads the event stream for as long as the TV keeps it open, reconnecting like a browser would."""
        while True:
            try:
                with urllib.request.urlopen(f"{self.base}/api/events?t={self.token}", timeout=60) as stream:
                    for raw in stream:
                        line = raw.decode("utf-8").rstrip("\n")
                        if line.startswith("data: ") and line != "data: {}":
                            self.react(json.loads(line[6:]))
            except Exception as e:  # dropped connection or timeout: try again, as a browser does
                print(f"{self.name}: stream ended ({e}); reconnecting")
                time.sleep(2)


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    host = sys.argv[1]
    count = int(sys.argv[2]) if len(sys.argv) > 2 else 2
    global DOODLER
    DOODLER = compile_doodler()
    folder = os.environ.get("BOT_SELFIES")
    selfies = sorted(glob.glob(os.path.join(folder, "*.jp*g"))) if folder else \
        [os.path.join(HERE, "bot1.jpg"), os.path.join(HERE, "bot2.jpg")]
    if not selfies:
        sys.exit(f"No JPEGs in {folder}")
    bots = []
    for i in range(count):
        with open(selfies[i % len(selfies)], "rb") as f:
            bots.append(Bot(host, NAMES[i % len(NAMES)], f.read(), i))
    for bot in bots:
        threading.Thread(target=bot.follow, daemon=True).start()
    print("bots are playing; Ctrl-C to stop")
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
