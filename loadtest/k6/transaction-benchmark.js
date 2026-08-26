import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://host.docker.internal:8081';
const username = __ENV.USERNAME || 'benchmark';
const password = __ENV.PASSWORD || '1234';
const mode = __ENV.MODE || 'sync';
const scenario = __ENV.TEST_SCENARIO || 'normal';
const runId = __ENV.RUN_ID || `${mode}-${scenario}-${Date.now()}`;
const accountPairs = Number(__ENV.ACCOUNT_PAIRS || 20);
const targetRps = Number(__ENV.TARGET_RPS || 100);
const preAllocatedVUs = Number(__ENV.PRE_ALLOCATED_VUS || Math.max(20, targetRps));
const maxVUs = Number(__ENV.MAX_VUS || Math.max(100, targetRps * 4));
const allowFailures = (__ENV.ALLOW_FAILURES || 'false') === 'true';

const transferLatency = new Trend('transfer_latency', true);
const transferFailures = new Rate('transfer_failures');
const successfulTransfers = new Counter('successful_transfers');

function scenarioOptions() {
  if (scenario === 'ramp') {
    return {
      executor: 'ramping-arrival-rate',
      startRate: Number(__ENV.START_RPS || 25),
      timeUnit: '1s',
      preAllocatedVUs,
      maxVUs,
      stages: [
        { target: Number(__ENV.RAMP_RPS_1 || 50), duration: __ENV.RAMP_DURATION_1 || '1m' },
        { target: Number(__ENV.RAMP_RPS_2 || 100), duration: __ENV.RAMP_DURATION_2 || '1m' },
        { target: Number(__ENV.RAMP_RPS_3 || 200), duration: __ENV.RAMP_DURATION_3 || '1m' },
        { target: Number(__ENV.RAMP_RPS_4 || 400), duration: __ENV.RAMP_DURATION_4 || '1m' },
      ],
    };
  }
  if (scenario === 'spike') {
    const steadyRps = Number(__ENV.STEADY_RPS || 50);
    const spikeRps = Number(__ENV.SPIKE_RPS || 500);
    return {
      executor: 'ramping-arrival-rate',
      startRate: steadyRps,
      timeUnit: '1s',
      preAllocatedVUs,
      maxVUs,
      stages: [
        { target: steadyRps, duration: __ENV.STEADY_DURATION || '1m' },
        { target: spikeRps, duration: __ENV.SPIKE_RAMP || '10s' },
        { target: spikeRps, duration: __ENV.SPIKE_DURATION || '30s' },
        { target: steadyRps, duration: __ENV.RECOVERY_RAMP || '10s' },
        { target: steadyRps, duration: __ENV.RECOVERY_DURATION || '1m' },
      ],
    };
  }
  return {
    executor: 'constant-arrival-rate',
    rate: targetRps,
    timeUnit: '1s',
    duration: __ENV.DURATION || '2m',
    preAllocatedVUs,
    maxVUs,
  };
}

export const options = {
  scenarios: { transfers: scenarioOptions() },
  thresholds: allowFailures ? {} : {
    http_req_failed: ['rate<0.01'],
    transfer_failures: ['rate<0.01'],
    transfer_latency: [`p(95)<${Number(__ENV.P95_LIMIT_MS || 500)}`],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  tags: { benchmark_mode: mode, benchmark_scenario: scenario, run_id: runId },
};

export function setup() {
  const response = http.post(`${baseUrl}/api/login`, JSON.stringify({ username, password }), {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'POST /api/login' },
  });
  const succeeded = check(response, {
    'login returns 200': (result) => result.status === 200,
    'login returns token': (result) => Boolean(result.headers.Authorization),
  });
  if (!succeeded) {
    fail(`Login failed: status=${response.status}, body=${response.body}`);
  }
  return { token: response.headers.Authorization };
}

export default function (data) {
  const pair = ((__VU - 1) + __ITER) % accountPairs;
  const response = http.post(`${baseUrl}/api/s/account/transfer`, JSON.stringify({
    withdrawNumber: String(7100000000 + pair),
    depositNumber: String(7200000000 + pair),
    withdrawPassword: 1234,
    amount: 1,
    transactionType: 'TRANSFER',
  }), {
    headers: {
      Authorization: data.token,
      'Content-Type': 'application/json',
      'Idempotency-Key': `${runId}-${__VU}-${__ITER}`,
    },
    tags: { name: 'POST /api/s/account/transfer', mode, scenario },
  });

  transferLatency.add(response.timings.duration, { mode, scenario });
  const succeeded = check(response, {
    'transfer returns 201': (result) => result.status === 201,
    'transaction id exists': (result) => result.status === 201
        && result.json('data.transaction.id') != null,
  });
  transferFailures.add(!succeeded, { mode, scenario });
  if (succeeded) {
    successfulTransfers.add(1, { mode, scenario });
  }
}

export function handleSummary(data) {
  return {
    [`/results/${runId}-summary.json`]: JSON.stringify(data, null, 2),
    stdout: summary(data),
  };
}

function summary(data) {
  const latency = data.metrics.transfer_latency?.values || {};
  const requests = data.metrics.http_reqs?.values || {};
  const failures = data.metrics.transfer_failures?.values || {};
  return [
    '',
    `Transaction benchmark (${mode}/${scenario})`,
    `  run id:       ${runId}`,
    `  requests:     ${requests.count || 0}`,
    `  throughput:   ${format(requests.rate)} req/s`,
    `  avg:          ${format(latency.avg)} ms`,
    `  p50:          ${format(latency.med)} ms`,
    `  p95:          ${format(latency['p(95)'])} ms`,
    `  p99:          ${format(latency['p(99)'])} ms`,
    `  failure rate: ${format((failures.rate || 0) * 100)}%`,
    '',
  ].join('\n');
}

function format(value) {
  return value == null ? '-' : Number(value).toFixed(3);
}
