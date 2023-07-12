.PHONY: check build test integration

default: build

check:
	cargo fmt --all -- --check
	cargo clippy --all-targets --all-features -- -D warnings
	cargo audit

build:
	cargo build

test:
	cargo test --all --all-features --lib -- --nocapture

integration:
	make -C tests

integration-down:
	make -C tests down

integration-core:
	make -C tests test-core

integration-driver:
	make -C tests test-driver

integration-bendsql:
	make -C tests test-bendsql

integration-binding-nodejs:
	make -C tests test-binding-nodejs
