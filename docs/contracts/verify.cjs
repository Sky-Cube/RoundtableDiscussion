const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const api = require('./public-api.schema.json');
const fixture = require('./api.fixtures.json');
const runtime = require('../runtime-contract.schema.json');

const ajvRoot = process.argv[2];
const formatsRoot = process.argv[3];
const Ajv2020 = require(ajvRoot ? join(ajvRoot, 'dist', '2020') : 'ajv/dist/2020');
const addFormats = require(formatsRoot || 'ajv-formats');
const ajv = new Ajv2020({ allErrors: true, strictTypes: false, strictTuples: false });
addFormats(ajv);
ajv.addSchema(api, 'public');
ajv.addSchema(runtime, 'model');

function check(schema, definition, value, expected = true) {
    const validate = ajv.getSchema(schema + '#/$defs/' + definition);
    assert.ok(validate, definition);
    assert.equal(validate(value), expected, definition + ' ' + ajv.errorsText(validate.errors));
}

const validateFrame = ajv.getSchema('public#/$defs/sseFrame');

const operations = {
    listDiscussions: [200, 400, 500],
    createDiscussion: [201, 400, 403, 500, 502, 503, 504],
    listPresets: [200, 400, 500],
    copyPreset: [201, 400, 403, 404, 500],
    startDiscussion: [202, 400, 403, 404, 409, 429, 500, 503],
    getDiscussion: [200, 400, 404, 500],
    deleteDiscussion: [204, 400, 403, 404, 409, 500],
    streamDiscussion: [200, 204, 400, 404, 429, 500],
};

const terminal = (snapshot) => ['COMPLETED', 'FAILED'].includes(snapshot.status);

function verifyRoster(room) {
    assert.equal(room.participants.length, room.expertCount + 1);
    for (const key of ['id', 'displayName', 'colorKey']) {
        assert.equal(new Set(room.participants.map((person) => person[key])).size, room.participants.length);
    }
    const experts = room.participants.filter((person) => person.role === 'EXPERT');
    assert.equal(new Set(experts.map((person) => person.perspective)).size, room.expertCount);
    assert.equal(room.participants[0].stance, null);
    assert.equal(room.participants[0].perspective, null);
    assert.ok(experts.every((person) => typeof person.stance === 'string'));
}

for (const { operation, request, response, reject, origin } of fixture.http) {
    check('public', operation + 'Request', request, reject !== 'request');
    assert.ok(response.status === 403 || operations[operation].includes(response.status));
    if (response.status >= 400) {
        assert.equal(response.headers, undefined);
        check('public', 'httpError', response, reject !== 'response');
        if (response.status === 403) assert.ok(origin);
        continue;
    }
    if (operation === 'streamDiscussion' || operation === 'deleteDiscussion') {
        assert.equal(response.body, undefined);
        if (response.status === 200) {
            assert.deepEqual(response.headers, {
                contentType: 'text/event-stream; charset=utf-8', cacheControl: 'no-cache, no-transform',
            });
        } else {
            assert.equal(response.headers, undefined);
        }
        continue;
    }
    check('public', operation + 'Response', response.body, reject !== 'response');
    if (reject) continue;
    const data = response.body.data;
    if (response.status === 201) {
        assert.equal(response.headers.location, '/api/v1/discussions/' + data.id);
        verifyRoster(data);
    }
    if (operation === 'createDiscussion') {
        assert.equal(data.expertCount, request.body.expertCount ?? 4);
    }
    if (operation === 'getDiscussion' || operation === 'startDiscussion') {
        assert.equal(data.id, request.params.id);
    }
    if (operation === 'listPresets') {
        data.forEach(verifyRoster);
        const slugs = data.map((preset) => preset.slug);
        assert.deepEqual(slugs, [...new Set(slugs)].sort());
    }
}
console.log('PASS HTTP ' + fixture.http.length + ' cases across ' + Object.keys(operations).length + ' operations');

const empty = fixture.snapshots.A0;
const presetRoster = fixture.http.find(({ operation, response }) =>
    operation === 'listPresets' && response.status === 200).response.body.data[0].participants;
const modelRoster = fixture.modelCases.find(({ definition, valid }) => definition === 'rosterOutput' && valid)
    .candidate.participants;
for (const [schema, definition, roster] of [
    ['public', 'participant', empty.participants],
    ['public', 'presetParticipant', presetRoster],
    ['model', 'participant', modelRoster],
]) {
    const [host, expert] = roster;
    check(schema, definition, host);
    check(schema, definition, expert);
    check(schema, definition, { ...host, stance: '主持建议应优先采购客服方案' }, false);
    check(schema, definition, { ...expert, stance: null }, false);
    const { stance, ...hostWithoutStance } = host;
    check(schema, definition, hostWithoutStance, false);
}
console.log('PASS role stance in runtime, discussion participant and preset participant schemas');

function makeDraft(room) {
    const { id, topic, expertCount, discussionGoal, participants } = fixture.snapshots[room + '2'];
    assert.equal(id, fixture.rooms[room]);
    return { ...structuredClone(empty), id, topic, expertCount, discussionGoal, participants: structuredClone(participants) };
}
const committedState = { A: makeDraft('A'), B: makeDraft('B') };
const events = { A: [], B: [] };
const legalStatus = { DRAFT: ['RUNNING'], RUNNING: ['SUMMARIZING', 'FAILED'], SUMMARIZING: ['COMPLETED', 'FAILED'] };
const legalStage = { IDLE: ['PREPARING'], PREPARING: ['SPEAKING', 'IDLE'], SPEAKING: ['IDLE'] };

function apply(snapshot, frame) {
    const { event, data: { discussionId, seq, payload } } = frame;
    assert.equal(discussionId, snapshot.id);
    assert.equal(frame.id, String(seq));
    assert.equal(seq, snapshot.lastEventSeq + 1);
    assert.equal(terminal(snapshot), false);
    if (event === 'discussion.status') {
        assert.ok(legalStatus[snapshot.status].includes(payload.status));
        if (payload.status === 'COMPLETED') assert.ok(snapshot.summary);
        if (payload.status === 'FAILED') assert.equal(snapshot.summary, null);
        Object.assign(snapshot, payload);
    }
    if (event === 'participant.status') {
        const participant = snapshot.participants.find((person) => person.id === payload.participantId);
        assert.equal(participant.role, 'EXPERT');
        const continuingSpeech = participant.stageStatus === 'SPEAKING' && payload.stageStatus === 'SPEAKING';
        assert.ok(continuingSpeech || legalStage[participant.stageStatus].includes(payload.stageStatus));
        if (payload.publicSpeech && participant.publicSpeech) {
            assert.ok(payload.publicSpeech.startsWith(participant.publicSpeech));
        }
        participant.stageStatus = payload.stageStatus;
        participant.publicFocus = payload.publicFocus;
        for (const key of ['publicSummary', 'publicSpeech']) {
            if (Object.hasOwn(payload, key)) participant[key] = payload[key];
            else delete participant[key];
        }
    }
    if (event === 'utterance.added') {
        const speaker = snapshot.participants.find((person) => person.id === payload.participantId);
        assert.equal(speaker.role, payload.kind === 'EXPERT' ? 'EXPERT' : 'HOST');
        assert.equal(payload.seq, snapshot.transcript.length + 1);
        assert.equal(payload.kind === 'SUMMARY' ? 'SUMMARIZING' : 'RUNNING', snapshot.status);
        if (payload.replyToUtteranceSeq !== null) {
            const original = snapshot.transcript.find((item) => item.seq === payload.replyToUtteranceSeq);
            assert.ok(original);
            assert.notEqual(original.participantId, speaker.id);
        }
        snapshot.transcript.push(payload);
        if (payload.kind === 'SUMMARY') snapshot.summary = payload.content;
    }
    if (event === 'insight.updated') {
        assert.equal(snapshot.status, 'RUNNING');
        if (payload.insightStatus === 'STALE') {
            assert.equal(payload.insightsThroughUtteranceSeq, snapshot.insightsThroughUtteranceSeq);
            assert.deepEqual(payload.insights, snapshot.insights);
        } else {
            const expertUtterances = snapshot.transcript.filter((item) => item.kind === 'EXPERT');
            assert.equal(payload.insightsThroughUtteranceSeq, expertUtterances.at(-1).seq);
            for (const finding of payload.insights) {
                const experts = finding.evidenceUtteranceSeqs.map((seq) => {
                    assert.ok(seq <= payload.insightsThroughUtteranceSeq);
                    return expertUtterances.find((item) => item.seq === seq).participantId;
                });
                assert.ok(new Set(experts).size >= 2);
            }
        }
        Object.assign(snapshot, payload);
    }
    snapshot.lastEventSeq = seq;
}

function cursorFor(step) {
    const text = step.lastEventId ?? step.after ?? '0';
    return /^(0|[1-9][0-9]{0,15})$/.test(text) && Number.isSafeInteger(Number(text))
        ? Number(text)
        : null;
}

function subscription(room, step) {
    const cursor = cursorFor(step);
    const snapshot = committedState[room];
    if (cursor === null || cursor > snapshot.lastEventSeq) return { status: 400, frames: [] };
    if (terminal(snapshot) && cursor === snapshot.lastEventSeq) return { status: 204, frames: [] };
    const { activeTotal = 0, activeRoom = 0, limitTotal = 16, limitRoom = 8 } = step;
    if (activeTotal >= limitTotal || activeRoom >= limitRoom) return { status: 429, frames: [] };
    return { status: 200, frames: events[room].filter((frame) => frame.data.seq > cursor) };
}

for (const snapshot of Object.values(fixture.snapshots)) {
    check('public', 'discussionSnapshot', snapshot);
    verifyRoster(snapshot);
}
for (const roomsFrames of Object.values(fixture.frames)) {
    roomsFrames.forEach((frame) => check('public', 'sseFrame', frame));
}
for (const { frame, expected } of fixture.frameCases) {
    check('public', 'sseFrame', frame, expected !== 'invalid');
    if (expected === 'resnapshot') assert.notEqual(frame.id, String(frame.data.seq));
}

const publicSpeechFrame = structuredClone(fixture.frames.A[3]);
Object.assign(publicSpeechFrame.data.payload, {
    publicSummary: '公开说明实施条件。',
    publicSpeech: 'A 可先采购完整方案',
});
check('public', 'sseFrame', publicSpeechFrame);
for (const fields of [
    { stageStatus: 'PREPARING' },
    { stageStatus: 'IDLE', publicFocus: null, publicSpeech: null },
    { publicSummary: '字'.repeat(81) },
    { publicSpeech: '字'.repeat(121) },
    { hiddenReasoning: '合成测试内容' },
]) {
    const frame = structuredClone(publicSpeechFrame);
    Object.assign(frame.data.payload, fields);
    check('public', 'sseFrame', frame, false);
}
console.log('PASS public summary and speech: 1 accepted, 5 rejected');

const clients = {};
const counts = { C1: 0, C2: 0 };
const replayed = { A: [], B: [] };
function consume(client, frame, listenerToken) {
    if (listenerToken !== client.listenerToken) return 'discard';
    if (!validateFrame(frame)) return 'resnapshot';
    if (frame.data.discussionId !== client.snapshot.id) return 'discard';
    if (frame.id !== String(frame.data.seq)) return 'resnapshot';
    if (frame.data.seq <= client.snapshot.lastEventSeq) return 'discard';
    if (frame.data.seq !== client.snapshot.lastEventSeq + 1) return 'resnapshot';
    apply(client.snapshot, frame);
    client.serverCursor = frame.data.seq;
    if (terminal(client.snapshot)) client.connected = false;
    return 'applied';
}

function deliver(step, client, frames) {
    assert.deepEqual(frames.map(({ data }) => data.seq), step.delivered);
    for (const frame of frames) {
        assert.equal(consume(client, frame, client.listenerToken), 'applied');
        counts[step.client]++;
    }
}

for (const step of fixture.steps) {
    const client = clients[step.client];
    const room = step.room;
    switch (step.action) {
        case 'commit': {
            const before = committedState[room];
            const pending = structuredClone(before);
            const transaction = step.seqs.map((seq) => fixture.frames[room][seq - 1]);
            for (const frame of transaction) apply(pending, frame);
            if (step.firstRollBack) {
                // 模拟总结事务首次回滚。订阅仍只看得到已提交的序号。
                assert.deepEqual(subscription(room, { after: String(before.lastEventSeq) }).frames, []);
                assert.equal(before.summary, null);
                assert.notEqual(before.status, pending.status);
            }
            check('public', 'discussionSnapshot', pending);
            committedState[room] = pending;
            events[room].push(...transaction);
            break;
        }
        case 'snapshot': {
            const expected = fixture.snapshots[step.name];
            assert.deepEqual(committedState[room], expected);
            clients[step.client] = { room, snapshot: structuredClone(expected), listenerToken: Symbol(step.name), connected: false };
            break;
        }
        case 'subscribe': {
            assert.equal(client.room, room);
            if (step.lastEventId) assert.equal(Number(step.lastEventId), client.snapshot.lastEventSeq);
            const result = subscription(room, step);
            assert.equal(result.status, step.status);
            client.connected = result.status === 200;
            client.serverCursor = cursorFor(step);
            deliver(step, client, result.frames);
            break;
        }
        case 'poll': {
            assert.equal(client.connected, true);
            const frames = events[client.room].filter(({ data }) => data.seq > client.serverCursor);
            deliver(step, client, frames);
            break;
        }
        case 'disconnect':
            client.connected = false;
            break;
        case 'duplicate':
            assert.equal(consume(client, fixture.frames[room][step.seq - 1], client.listenerToken), 'discard');
            break;
        case 'late':
            assert.equal(consume(client, fixture.frames[room][step.seq - 1], Symbol('旧监听器')), 'discard');
            break;
        case 'foreign':
            assert.equal(consume(client, fixture.frames[room][step.seq - 1], client.listenerToken), 'discard');
            break;
        case 'gap':
            assert.equal(consume(client, fixture.frames[room][step.seq - 1], client.listenerToken), 'resnapshot');
            client.connected = false;
            break;
        case 'probeSubscribe': {
            const result = subscription(room, step);
            assert.equal(result.status, step.status);
            assert.deepEqual(result.frames.map(({ data }) => data.seq), step.delivered ?? []);
            if (step.code !== undefined) assert.equal(step.code, result.status);
            if (step.delivered) replayed[room] = result.frames.map(({ data }) => data.seq);
            break;
        }
        default:
            assert.fail(step.action);
    }
}
assert.deepEqual(clients.C1.snapshot, fixture.snapshots.A20);
assert.deepEqual(clients.C2.snapshot, fixture.snapshots.A20);
assert.equal(clients.C1.connected, false);
assert.equal(clients.C2.connected, false);
const exampleClient = { snapshot: structuredClone(fixture.snapshots.A2), listenerToken: Symbol('反例') };
for (const { frame } of fixture.frameCases) {
    assert.equal(consume(exampleClient, frame, exampleClient.listenerToken), 'resnapshot');
    assert.deepEqual(exampleClient.snapshot, fixture.snapshots.A2);
}
const document = readFileSync(join(__dirname, '../api.md'), 'utf8').replaceAll('\r\n', '\n');
const rawFrames = document.match(/^```text\n(.*?)^```/ms)[1].trim().split('\n\n');
const exampleEvents = rawFrames.map((lines) => {
    const [id, event, data] = lines.split('\n');
    const frame = { id: id.slice(4), event: event.slice(7), data: JSON.parse(data.slice(6)) };
    check('public', 'sseFrame', frame);
    assert.deepEqual(frame, fixture.frames.A[frame.data.seq - 1]);
    return frame.event;
});
assert.equal(new Set(exampleEvents).size, 4);
console.log('PASS SSE ' + (events.A.length + events.B.length) + ' frames, ' + fixture.steps.length +
    ' steps, C1/C2 applied ' + counts.C1 + '/' + counts.C2 + ', terminal replay ' + JSON.stringify(replayed));

let accepted = 0;
for (const { definition, candidate, valid } of fixture.modelCases) {
    check('model', definition, candidate, valid);
    if (valid) accepted++;
}
console.log('PASS model schema ' + accepted + ' accepted and ' + (fixture.modelCases.length - accepted) + ' rejected');
