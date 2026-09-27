#!/usr/bin/env bash
set -Eeuo pipefail

for topic in orders.v1 reservation-results.v1 orders.v1.DLT reservation-results.v1.DLT; do
  /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server kafka:9092 \
    --create \
    --if-not-exists \
    --topic "$topic" \
    --partitions 1 \
    --replication-factor 1
done

/opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --list
