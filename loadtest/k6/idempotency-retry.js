import http from 'k6/http';
import { check, fail } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://host.docker.internal:8081';
const username = __ENV.USERNAME || 'test';
const password = __ENV.PASSWORD || '1234';
const mode = __ENV.MODE || 'redis';
const idempotencyKey = __ENV.IDEMPOTENCY_KEY || 'k6-idempotency-retry';

const transferLatency = new Trend('idempotent_transfer_latency', true);
const transferFailures = new Rate('idempotent_transfer_failures');

export const options = {
  scenarios: {
    duplicate_retries: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 20),
      duration: __ENV.DURATION || '30s',
      gracefulStop: '5s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    idempotent_transfer_failures: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  tags: {
    test: 'idempotency-retry',
    mode,
  },
};

export function setup() {
  const loginResponse = http.post(`${baseUrl}/api/login`, JSON.stringify({
    username,
    password,
  }), {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'POST /api/login' },
  });

  const loggedIn = check(loginResponse, {
    'login returns 200': (response) => response.status === 200,
    'login returns Authorization header': (response) => Boolean(response.headers.Authorization),
  });
  if (!loggedIn) {
    fail(`Login failed: status=${loginResponse.status}, body=${loginResponse.body}`);
  }

  return { token: loginResponse.headers.Authorization };
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
    tags: {
      name: 'POST /api/s/account/transfer (same key)',
      mode,
    },
  });

  transferLatency.add(response.timings.duration, { mode });
  const succeeded = check(response, {
    'transfer returns 201': (result) => result.status === 201,
    'transfer returns success code': (result) => result.json('code') === 1,
    'transfer returns transaction id': (result) => result.json('data.transaction.id') != null,
  });
  transferFailures.add(!succeeded, { mode });
}

export function handleSummary(data) {
  return {
    [`/results/${mode}-summary.json`]: JSON.stringify(data, null, 2),
    stdout: textSummary(data),
  };
}

function textSummary(data) {
  const metric = data.metrics.idempotent_transfer_latency;
  const values = metric ? metric.values : {};
  const checks = data.metrics.checks ? data.metrics.checks.values.rate * 100 : 0;
  return [
    '',
    `Idempotency load test (${mode})`,
    `  avg:   ${format(values.avg)} ms`,
    `  p50:   ${format(values.med)} ms`,
    `  p95:   ${format(values['p(95)'])} ms`,
    `  p99:   ${format(values['p(99)'])} ms`,
    `  max:   ${format(values.max)} ms`,
    `  checks: ${checks.toFixed(2)}%`,
    '',
  ].join('\n');
}

function format(value) {
  return value == null ? '-' : value.toFixed(3);
}
