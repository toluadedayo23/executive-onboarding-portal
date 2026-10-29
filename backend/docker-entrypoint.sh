#!/bin/sh
set -eu
mkdir -p /var/lib/exec-platform/uploads
chown -R app:app /var/lib/exec-platform/uploads
exec su-exec app java -jar /app/app.jar
