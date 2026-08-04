#!/bin/sh
# Runs as an nginx docker-entrypoint.d/ script: sourced before nginx starts,
# not a replacement entrypoint, so it must not exec anything itself.
set -eu

cat > /usr/share/nginx/html/config.js <<EOF
window.__ENV__ = {
  API_BASE_URL: "${API_BASE_URL:-http://localhost:8080/api}"
};
EOF
