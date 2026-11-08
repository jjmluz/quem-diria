#!/bin/sh
# Joins test players to the TV's room and keeps their event streams open so they show as connected.
# Usage: tools/test_players/join.sh <tv host:port> <room code> tools/test_players
# Stop with Ctrl-C (or kill it): their phones "go away" and they grey out on the TV.
HOST=$1; CODE=$2; DIR=$3
i=1
for NAME in "Robô Rui" "Robô Eva"; do
  TOKEN=$(curl -s --data-urlencode "c=$CODE" --data-urlencode "name=$NAME" "http://$HOST/api/join" | sed -n 's/.*"token":"\([0-9a-f]*\)".*/\1/p')
  [ -z "$TOKEN" ] && { echo "join failed for $NAME"; exit 1; }
  curl -s -o /dev/null -w "$NAME selfie %{http_code}\n" -H "Content-Type: image/jpeg" --data-binary "@$DIR/bot$i.jpg" "http://$HOST/api/selfie?t=$TOKEN"
  curl -s -N "http://$HOST/api/events?t=$TOKEN" > /dev/null &
  i=$((i + 1))
done
echo "bots joined; holding their connections"
wait
