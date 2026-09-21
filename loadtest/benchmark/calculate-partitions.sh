#!/usr/bin/env bash
set -euo pipefail

peak_rps="${PEAK_RPS:-1000}"
measured_partition_rps="${MEASURED_PARTITION_RPS:-250}"
consumer_processing_ms="${CONSUMER_PROCESSING_MS:-10}"
target_utilization="${TARGET_UTILIZATION:-0.7}"
headroom="${PARTITION_HEADROOM:-1.3}"

awk -v peak="$peak_rps" \
    -v partition_rps="$measured_partition_rps" \
    -v processing_ms="$consumer_processing_ms" \
    -v utilization="$target_utilization" \
    -v headroom="$headroom" '
function ceil(value) { return int(value) == value ? value : int(value) + 1 }
BEGIN {
  if (peak <= 0 || partition_rps <= 0 || processing_ms <= 0 || utilization <= 0 || utilization > 1 || headroom < 1) {
    print "All rates and processing time must be positive; utilization=(0,1], headroom>=1" > "/dev/stderr"
    exit 2
  }
  required = peak * headroom
  producer_partitions = ceil(required / (partition_rps * utilization))
  consumer_capacity = 1000 / processing_ms
  consumer_partitions = ceil(required / (consumer_capacity * utilization))
  partitions = producer_partitions > consumer_partitions ? producer_partitions : consumer_partitions
  printf "peak_rps=%g\n", peak
  printf "planned_rps_with_headroom=%.2f\n", required
  printf "producer_based_partitions=%d\n", producer_partitions
  printf "consumer_based_partitions=%d\n", consumer_partitions
  printf "recommended_partitions=%d\n", partitions
  printf "max_useful_consumer_instances=%d\n", partitions
}'
