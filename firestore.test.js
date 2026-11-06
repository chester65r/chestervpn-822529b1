import { test, before, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const PROJECT_ID = 'demo-tunnel-console';
const HOST = process.env.FIRESTORE_EMULATOR_HOST || '127.0.0.1:8085';
const BASE_URL = `http://${HOST}/v1/projects/${PROJECT_ID}/databases/(default)/documents`;

function makeJwt(uid) {
  const header = Buffer.from(JSON.stringify({ alg: 'none', typ: 'JWT' })).toString('base64url');
  const payload = Buffer.from(
    JSON.stringify({
      iss: `https://securetoken.google.com/${PROJECT_ID}`,
      aud: PROJECT_ID,
      sub: uid,
      user_id: uid,
      iat: Math.floor(Date.now() / 1000) - 60,
      exp: Math.floor(Date.now() / 1000) + 3600,
    })
  ).toString('base64url');
  return `${header}.${payload}.`;
}

async function commitServerWrite(uid, docPath, fields) {
  const headers = { 'Content-Type': 'application/json' };
  if (uid) {
    headers['Authorization'] = `Bearer ${makeJwt(uid)}`;
  }
  const fullName = `projects/${PROJECT_ID}/databases/(default)/documents/${docPath}`;
  const body = {
    writes: [
      {
        update: {
          name: fullName,
          fields,
        },
        updateTransforms: [
          { fieldPath: 'createdAt', setToServerValue: 'REQUEST_TIME' },
          { fieldPath: 'updatedAt', setToServerValue: 'REQUEST_TIME' },
        ],
      },
    ],
  };
  return fetch(`http://${HOST}/v1/projects/${PROJECT_ID}/databases/(default)/documents:commit`, {
    method: 'POST',
    headers,
    body: JSON.stringify(body),
  });
}

async function getDocument(uid, docPath) {
  const headers = {};
  if (uid) {
    headers['Authorization'] = `Bearer ${makeJwt(uid)}`;
  }
  return fetch(`${BASE_URL}/${docPath}`, { method: 'GET', headers });
}

async function deleteDocument(uid, docPath) {
  const headers = {};
  if (uid) {
    headers['Authorization'] = `Bearer ${makeJwt(uid)}`;
  }
  return fetch(`${BASE_URL}/${docPath}`, { method: 'DELETE', headers });
}

before(async () => {
  const rulesContent = readFileSync('firestore.rules', 'utf8');
  const res = await fetch(`http://${HOST}/emulator/v1/projects/${PROJECT_ID}:securityRules`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      rules: {
        files: [{ name: 'firestore.rules', content: rulesContent }],
      },
    }),
  });
  assert.equal(res.status, 200, `Failed to load firestore.rules into emulator: ${await res.text()}`);
});

beforeEach(async () => {
  await fetch(
    `http://${HOST}/emulator/v1/projects/${PROJECT_ID}/databases/(default)/documents`,
    { method: 'DELETE' }
  );
});

test('owner can create, read, update, and delete valid relay server', async () => {
  const docPath = 'users/alice/relay_servers/srv_1';
  const createRes = await commitServerWrite('alice', docPath, {
    name: { stringValue: 'Frankfurt WG Node' },
    host: { stringValue: 'fsn1.hetzner.com' },
    port: { integerValue: '443' },
    transport: { stringValue: 'WebSocket' },
    notes: { stringValue: 'Primary EU relay' },
  });
  assert.equal(createRes.status, 200, `Expected owner create 200, got ${createRes.status}`);

  const readRes = await getDocument('alice', docPath);
  assert.equal(readRes.status, 200, `Expected owner read 200, got ${readRes.status}`);

  const updateRes = await commitServerWrite('alice', docPath, {
    name: { stringValue: 'Frankfurt WG Node v2' },
    host: { stringValue: 'fsn1.hetzner.com' },
    port: { integerValue: '8443' },
    transport: { stringValue: 'TLS' },
    notes: { stringValue: 'Updated port' },
  });
  assert.equal(updateRes.status, 200, `Expected owner update 200, got ${updateRes.status}`);

  const delRes = await deleteDocument('alice', docPath);
  assert.equal(delRes.status, 200, `Expected owner delete 200, got ${delRes.status}`);
});

test('unauthenticated user cannot read or write relay servers', async () => {
  const docPath = 'users/alice/relay_servers/srv_1';
  const readRes = await getDocument(null, docPath);
  assert.equal(readRes.status, 403);

  const writeRes = await commitServerWrite(null, docPath, {
    name: { stringValue: 'Node' },
    host: { stringValue: '1.2.3.4' },
    port: { integerValue: '443' },
    transport: { stringValue: 'TLS' },
    notes: { stringValue: '' },
  });
  assert.equal(writeRes.status, 403);
});

test('non-owner cannot read, update, or delete another user relay server', async () => {
  const docPath = 'users/alice/relay_servers/srv_1';
  const seedRes = await commitServerWrite('alice', docPath, {
    name: { stringValue: 'Alice Private Node' },
    host: { stringValue: '10.0.0.1' },
    port: { integerValue: '443' },
    transport: { stringValue: 'TLS' },
    notes: { stringValue: 'Private' },
  });
  assert.equal(seedRes.status, 200);

  const bobRead = await getDocument('bob', docPath);
  assert.equal(bobRead.status, 403);

  const bobDel = await deleteDocument('bob', docPath);
  assert.equal(bobDel.status, 403);
});

test('rejects invalid port or unexpected extra fields', async () => {
  const docPath = 'users/alice/relay_servers/srv_invalid';

  const badPortRes = await commitServerWrite('alice', docPath, {
    name: { stringValue: 'Bad Port Node' },
    host: { stringValue: '1.2.3.4' },
    port: { integerValue: '70000' },
    transport: { stringValue: 'TLS' },
    notes: { stringValue: '' },
  });
  assert.equal(badPortRes.status, 403);

  const extraFieldRes = await commitServerWrite('alice', docPath, {
    name: { stringValue: 'Extra Field Node' },
    host: { stringValue: '1.2.3.4' },
    port: { integerValue: '443' },
    transport: { stringValue: 'TLS' },
    notes: { stringValue: '' },
    role: { stringValue: 'admin' },
  });
  assert.equal(extraFieldRes.status, 403);
});

test('default deny blocks access to undeclared collections', async () => {
  const res = await getDocument('alice', 'some_random_collection/doc_1');
  assert.equal(res.status, 403);
});
