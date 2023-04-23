#!/bin/bash

set -e

CARGO_TARGET_DIR=${CARGO_TARGET_DIR:-./target}
DATABEND_USER=${DATABEND_USER:-root}
DATABEND_PASSWORD=${DATABEND_PASSWORD:-}
DATABEND_HOST=${DATABEND_HOST:-localhost}

TEST_HANDLER=$1

cargo build --bin bendsql

case $TEST_HANDLER in
"flight")
	echo "==> Testing Flight SQL handler"
	export BENDSQL_DSN="databend+flight://${DATABEND_USER}:${DATABEND_PASSWORD}@${DATABEND_HOST}:8900/?sslmode=disable"
	export BENDSQL="${CARGO_TARGET_DIR}/debug/bendsql"
	;;
"http")
	echo "==> Testing REST API handler"
	export BENDSQL_DSN="databend+http://${DATABEND_USER}:${DATABEND_PASSWORD}@${DATABEND_HOST}:8000/?sslmode=disable"
	export BENDSQL="${CARGO_TARGET_DIR}/debug/bendsql"
	;;
*)
	echo "Usage: $0 [flight|http]"
	exit 1
	;;
esac

for tf in cli/tests/*.{sql,sh}; do
	[[ -e "$tf" ]] || continue
	echo "    Running test -- ${tf}"
	if [[ $tf == *.sh ]]; then
		suite=$(basename "${tf}" | sed -e 's#.sh##')
		bash "${tf}" >"cli/tests/${suite}.output" 2>&1
	elif [[ $tf == *.sql ]]; then
		suite=$(basename "${tf}" | sed -e 's#.sql##')
		"${BENDSQL}" <"${tf}" >"cli/tests/${suite}.output" 2>&1
	fi
	diff "cli/tests/${suite}.output" "cli/tests/${suite}.result"
done
rm -f cli/tests/*.output

for tf in cli/tests/"$TEST_HANDLER"/*.{sql,sh}; do
	[[ -e "$tf" ]] || continue
	echo "    Running test -- ${tf}"
	if [[ $tf == *.sh ]]; then
		suite=$(basename "${tf}" | sed -e 's#.sh##')
		bash "${tf}" >"cli/tests/${TEST_HANDLER}/${suite}.output" 2>&1
	elif [[ $tf == *.sql ]]; then
		suite=$(basename "${tf}" | sed -e 's#.sql##')
		"${BENDSQL}" <"${tf}" >"cli/tests/${TEST_HANDLER}/${suite}.output" 2>&1
	fi
	diff "cli/tests/${TEST_HANDLER}/${suite}.output" "cli/tests/${TEST_HANDLER}/${suite}.result"
done
rm -f cli/tests/"$TEST_HANDLER"/*.output

echo "--> Tests $1 passed"
echo
