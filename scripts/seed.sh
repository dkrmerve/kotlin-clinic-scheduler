#!/usr/bin/env bash
# Seeds a running Clinic Scheduler (docker compose up --build) with demo data:
#   2 practitioners, 4 patients, appointments in Booked / CheckedIn / Cancelled states, a practitioner at daily
#   capacity, and a waitlist entry.
# Idempotent: ids of everything it created are kept in $STATE_FILE and re-checked through the API, so running it
# twice leaves one copy of everything. Requires bash, curl and jq; AUTH_DEV_ISSUER_ENABLED=true on the server.
#
#   ./scripts/seed.sh                          # against http://localhost:8080
#   BASE_URL=http://host:8080 ./scripts/seed.sh
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
STATE_FILE="${STATE_FILE:-.seed-state.json}"

for dep in curl jq; do command -v "$dep" >/dev/null 2>&1 || { echo "missing dependency: $dep" >&2; exit 1; }; done

token() { # token <subject> <role>
  curl -sf -X POST "$BASE_URL/auth/token" -H 'Content-Type: application/json' \
    -d "{\"subject\":\"$1\",\"role\":\"$2\"}" | jq -r .accessToken
}
api() { # api <method> <path> <token> [json-body]
  local method=$1 path=$2 tok=$3 body=${4:-}
  if [[ -n "$body" ]]; then
    curl -s -X "$method" "$BASE_URL$path" -H "Authorization: Bearer $tok" -H 'Content-Type: application/json' -d "$body"
  else
    curl -s -X "$method" "$BASE_URL$path" -H "Authorization: Bearer $tok"
  fi
}
# Outcome of a POST: the status label on success, the error code on a problem response.
outcome() { jq -r 'if .code then .code else (.appointment.status // .status) end'; }
# Next Monday-Friday at least N days ahead, as an ISO date (GNU date or BSD date).
weekday_ahead() {
  local d
  d=$(date -u -d "+$1 days" +%F 2>/dev/null || date -u -v+"$1"d +%F)
  while [[ $(date -u -d "$d" +%u 2>/dev/null || date -u -j -f %F "$d" +%u) -gt 5 ]]; do
    d=$(date -u -d "$d +1 day" +%F 2>/dev/null || date -u -j -v+1d -f %F "$d" +%F)
  done
  echo "$d"
}

echo "== waiting for $BASE_URL/health/ready"
for _ in $(seq 1 30); do curl -sf "$BASE_URL/health/ready" >/dev/null && break; sleep 2; done
curl -sf "$BASE_URL/health/ready" >/dev/null || { echo "service not ready" >&2; exit 1; }

ADMIN=$(token admin-seed admin)
STAFF=$(token staff-seed clinic_staff)

# ---- state: ids of what an earlier run created ------------------------------------------------------------
[[ -f "$STATE_FILE" ]] || echo '{}' > "$STATE_FILE"
state_get() { jq -r --arg k "$1" '.[$k] // empty' "$STATE_FILE"; }
state_set() { jq --arg k "$1" --arg v "$2" '.[$k]=$v' "$STATE_FILE" > "$STATE_FILE.tmp" && mv "$STATE_FILE.tmp" "$STATE_FILE"; }
existing() { # existing <key> <path-prefix>: prints the stored id if it still resolves through the API
  local id; id=$(state_get "$1")
  [[ -n "$id" ]] && api GET "$2/$id" "$STAFF" | jq -e .id >/dev/null 2>&1 && echo "$id"
}

ensure_practitioner() { # <key> <json>
  local id; id=$(existing "$1" /practitioners) || true
  if [[ -z "$id" ]]; then
    id=$(api POST /practitioners "$ADMIN" "$2" | jq -r .id); state_set "$1" "$id"; echo "created practitioner $1 -> $id" >&2
  else echo "practitioner $1 exists -> $id" >&2; fi
  echo "$id"
}
ensure_patient() { # <key> <name> <email>
  local id; id=$(existing "$1" /patients) || true
  if [[ -z "$id" ]]; then
    id=$(api POST /patients "$STAFF" "{\"name\":\"$2\",\"email\":\"$3\"}" | jq -r .id); state_set "$1" "$id"; echo "created patient $1 -> $id" >&2
  else echo "patient $1 exists -> $id" >&2; fi
  echo "$id"
}
# Books the Nth free slot of the day as reported by the availability endpoint (so offsets and DST are the server's job).
ensure_appointment() { # <key> <practitioner> <patient> <type> <date> <slot-index>
  local id; id=$(existing "$1" /appointments) || true
  if [[ -n "$id" ]]; then echo "appointment $1 exists -> $id" >&2; echo "$id"; return; fi
  local start; start=$(api GET "/practitioners/$2/availability?date=$5&type=$4" "$STAFF" | jq -r ".slots[$6] // empty")
  [[ -n "$start" ]] || { echo "no free slot #$6 for $1 on $5" >&2; return; }
  local resp; resp=$(api POST /appointments "$STAFF" "{\"practitionerId\":\"$2\",\"patientId\":\"$3\",\"type\":\"$4\",\"start\":\"$start\"}")
  id=$(echo "$resp" | jq -r '.id // empty')
  [[ -n "$id" ]] || { echo "could not book $1: $(echo "$resp" | jq -r .code)" >&2; return; }
  state_set "$1" "$id"; echo "booked $1 ($4 at $start) -> $id" >&2
  echo "$id"
}

SCHEDULE='{"MONDAY":{"start":"09:00","end":"17:00"},"TUESDAY":{"start":"09:00","end":"17:00"},"WEDNESDAY":{"start":"09:00","end":"17:00"},"THURSDAY":{"start":"09:00","end":"17:00"},"FRIDAY":{"start":"09:00","end":"13:00"}}'
DR_ADA=$(ensure_practitioner dr_ada "{\"name\":\"Dr. Ada Lovelace\",\"specialty\":\"General practice\",\"slotMinutes\":15,\"bufferMinutes\":5,\"maxAppointmentsPerDay\":12,\"schedule\":$SCHEDULE}")
DR_ALAN=$(ensure_practitioner dr_alan "{\"name\":\"Dr. Alan Turing\",\"specialty\":\"Physiotherapy\",\"slotMinutes\":15,\"bufferMinutes\":0,\"maxAppointmentsPerDay\":3,\"schedule\":$SCHEDULE}")

GRACE=$(ensure_patient grace "Grace Hopper" "grace.hopper@example.test")
LINUS=$(ensure_patient linus "Linus Torvalds" "linus@example.test")
MARGARET=$(ensure_patient margaret "Margaret Hamilton" "margaret@example.test")
KATHERINE=$(ensure_patient katherine "Katherine Johnson" "katherine@example.test")

DAY=$(weekday_ahead 3)
DAY2=$(weekday_ahead 8)

A_BOOKED=$(ensure_appointment a_booked         "$DR_ADA"  "$GRACE"    Consultation "$DAY"  0)
A_CHECKED=$(ensure_appointment a_checked_in    "$DR_ADA"  "$LINUS"    FollowUp     "$DAY"  4)
A_CANCEL=$(ensure_appointment a_cancelled      "$DR_ADA"  "$MARGARET" Procedure    "$DAY"  8)
# Dr. Alan takes 3 per day: fill DAY2 so the 4th booking fails and a patient lands on the waitlist.
ensure_appointment a_full_1 "$DR_ALAN" "$GRACE"    Consultation "$DAY2" 0 >/dev/null
ensure_appointment a_full_2 "$DR_ALAN" "$LINUS"    Consultation "$DAY2" 3 >/dev/null
ensure_appointment a_full_3 "$DR_ALAN" "$MARGARET" Consultation "$DAY2" 6 >/dev/null

# Transitions are idempotent through their 409s on a second run.
[[ -n "$A_CHECKED" ]] && echo "check-in a_checked_in -> $(api POST "/appointments/$A_CHECKED/check-in" "$STAFF" | outcome)"
[[ -n "$A_CANCEL" ]] && echo "cancel a_cancelled -> $(api POST "/appointments/$A_CANCEL/cancel" "$STAFF" | outcome)"

W=$(existing waitlist /waitlist) || true
if [[ -z "$W" ]]; then
  FIRST=$(api GET "/practitioners/$DR_ALAN/availability?date=$DAY2&type=Consultation" "$STAFF" | jq -r '.slots[0] // empty')
  if [[ -n "$FIRST" ]]; then
    echo "4th booking on a full day -> $(api POST /appointments "$STAFF" "{\"practitionerId\":\"$DR_ALAN\",\"patientId\":\"$KATHERINE\",\"type\":\"Consultation\",\"start\":\"$FIRST\"}" | outcome)"
  else
    echo "4th booking on a full day -> availability is already empty (daily capacity reached)"
  fi
  W=$(api POST /waitlist "$STAFF" "{\"practitionerId\":\"$DR_ALAN\",\"patientId\":\"$KATHERINE\",\"date\":\"$DAY2\",\"type\":\"Consultation\"}" | jq -r '.id // empty')
  [[ -n "$W" ]] && state_set waitlist "$W" && echo "waitlist entry -> $W"
else
  echo "waitlist entry exists -> $W"
fi

echo
echo "== summary"
echo "practitioners: Dr. Ada=$DR_ADA  Dr. Alan=$DR_ALAN"
echo "patients:      Grace=$GRACE  Linus=$LINUS  Margaret=$MARGARET  Katherine=$KATHERINE"
echo "booked:        $A_BOOKED (Booked)  $A_CHECKED (CheckedIn)  $A_CANCEL (Cancelled)"
echo "availability:  curl -H \"Authorization: Bearer \$TOKEN\" \"$BASE_URL/practitioners/$DR_ADA/availability?date=$DAY&type=Consultation\""
echo "waitlist:      curl -H \"Authorization: Bearer \$TOKEN\" \"$BASE_URL/waitlist?practitionerId=$DR_ALAN&date=$DAY2\""
echo "state saved in $STATE_FILE (delete it to start over after 'docker compose down -v')"
