#!/usr/bin/env python3
"""Test players that play: they join the TV's room and vote, answer, draw and pick on their own.

Usage: python3 tools/test_players/bots.py <tv host:port> [number of bots, default 2]
Names: Robô Rui, Robô Eva and so on, or your own: BOT_NAMES="Sara Margarida,Pedro Henrique" python3 ...
Stop with Ctrl-C: their phones "go away" and they grey out on the TV.
Each bot acts 2-8 seconds into each stage, at random. Their drawings are their own selfie.
"""
import json
import os
import random
import sys
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


class Bot:
    def __init__(self, host, name, selfie):
        self.base = f"http://{host}"
        self.name = name
        self.selfie = selfie
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
            todo = lambda: self.act("/api/drawing", self.selfie, "image/jpeg")
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
    bots = []
    for i in range(count):
        with open(os.path.join(HERE, f"bot{i % 2 + 1}.jpg"), "rb") as f:
            bots.append(Bot(host, NAMES[i % len(NAMES)], f.read()))
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
