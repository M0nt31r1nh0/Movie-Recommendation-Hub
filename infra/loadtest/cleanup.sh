#!/usr/bin/env bash
# Deletes what the test users left in the application's databases: their profiles (with their watched
# movies and follows, by cascade) and their recommendation requests. Run it before users.py teardown,
# while the profiles still say which ids are the test users' (just teardown runs both, in that order).
set -euo pipefail

prefix=${USER_PREFIX:-loadtest-}

sql() {  # sql <pod> <user> <database> <psql arguments>
  local pod=$1 user=$2 db=$3
  shift 3
  kubectl -n movies exec "$pod" -c postgres -- psql -U "$user" -d "$db" "$@"
}

ids=$(sql profiles-db-0 profiles profiles -tAc "select profile_id from profiles.profile where username like '${prefix}%'")
if [ -z "$ids" ]; then
  echo "No test profiles in profiles-db"
  exit 0
fi
list=$(printf "'%s'," $ids)
list=${list%,}
echo "Deleting $(echo "$ids" | wc -l) test profiles and their recommendations"
sql recommendations-db-0 recommendations recommendations -c \
  "delete from recommendations.request where user_id in ($list); delete from recommendations.profile_state where user_id in ($list)"
sql profiles-db-0 profiles profiles -c "delete from profiles.profile where username like '${prefix}%'"
