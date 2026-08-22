import http from 'k6/http';
import { check, fail } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://host.docker.internal:8081';
const username = __ENV.USERNAME || 'test';
const password = __ENV.PASSWORD || '1234';
const mode = __ENV.MODE || 'redis-setnx-race';
const idempotencyKey = __ENV.IDEMPOTENCY_KEY || 'k6-idempotency-setnx-race';

const raceLatency = new Trend('setnx_race_latency', true);
const raceFailures = new Rate('setnx_race_failures');

export const options = {
  scenarios: {
    first_request_race: {
      executor: 'per-vu-iterations',
      vus: Number(__ENV.VUS || 20),
      iterations: 1,
      maxDuration: __ENV.MAX_DURATION || '30s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    setnx_race_failures: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  tags: {
    test: 'idempotency-setnx-race',
    mode,
  },
};

export function setup() {
  const response = http.post(`${baseUrl}/api/login`, JSON.stringify({ username, password }), {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'POST /api/login' },
  });

  const succeeded = check(response, {
    'login returns 200': (result) => result.status === 200,
    'login returns Authorization header': (result) => Boolean(result.headers.Authorization),
  });
  if (!succeeded) {
    fail(`Login failed: status=${response.status}, body=${response.body}`);
  }
  return { token: response.headers.Authorization };
}

export default function (data) {
  const response = http.post(`${baseUrl}/api/s/account/transfer`, JSON.stringify({
    withdrawNumber: '1111111111',
    depositNumber: '2222222222',
    withdrawPassword: 1234,
    amount: 1,
    transactionType: 'TRANSFER',
  }), {
    headers: {
      Authorization: data.token,
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
    },
    tags: { name: 'POST /api/s/account/transfer (SET NX race)', mode },
  });

  raceLatency.add(response.timings.duration, { mode });
  const succeeded = check(response, {
    'race request returns 201': (result) => result.status === 201,
    'race request returns success code': (result) => result.json('code') === 1,
    'race request returns transaction id': (result) => result.json('data.transaction.id') != null,
  });
  raceFailures.add(!succeeded, { mode });
}

export function handleSummary(data) {
  return {
    [`/results/${mode}-summary.json`]: JSON.stringify(data, null, 2),
    stdout: textSummary(data),
  };
}

function textSummary(data) {
  const metric = data.metrics.setnx_race_latency;
  const values = metric ? metric.values : {};
  const iterations = data.metrics.iterations ? data.metrics.iterations.values.count : 0;
  const checks = data.metrics.checks ? data.metrics.checks.values.rate * 100 : 0;
  return [
    '',
    `Idempotency SET NX race test (${mode})`,
    `  requests: ${iterations}`,
    `  avg:      ${format(values.avg)} ms`,
    `  p50:      ${format(values.med)} ms`,
    `  p95:      ${format(values['p(95)'])} ms`,
    `  p99:      ${format(values['p(99)'])} ms`,
    `  max:      ${format(values.max)} ms`,
    `  checks:   ${checks.toFixed(2)}%`,
    '',
  ].join('\n');
}

function format(value) {
  return value == null ? '-' : value.toFixed(3);
}
