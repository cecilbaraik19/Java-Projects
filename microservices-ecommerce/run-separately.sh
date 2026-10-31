#!/usr/bin/env bash
# Shortcut: starts every service as its OWN process.
exec "$(dirname "$0")/backend/run-separately.sh"
