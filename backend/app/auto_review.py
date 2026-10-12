"""Persistent Redis scheduling for mixed automatic/manual submission review."""
import json
import secrets
import time
from itertools import groupby
from .affiliate import AffiliateService, AffiliateError

# Allocation and queue insertion are atomic. Retried submissions do not consume slots.
ALLOCATE = """
local prior=redis.call('GET',KEYS[1]); if prior then return prior end
local ordinal=redis.call('INCR',KEYS[2])-1
local block=math.floor(ordinal/100)
local maskkey=KEYS[3]..':'..block
local mask=redis.call('GET',maskkey)
if not mask then mask=ARGV[1]; redis.call('SET',maskkey,mask) end
local selected=string.sub(mask,(ordinal%100)+1,(ordinal%100)+1)=='1'
local record=cjson.encode({selected=selected,ordinal=ordinal,due_at=tonumber(ARGV[2])})
redis.call('SET',KEYS[1],record)
if selected then redis.call('ZADD',KEYS[4],ARGV[2],ARGV[3]) end
return record
"""


def schedule(service: AffiliateService, submission: dict) -> None:
    if not service.redis or submission['status'] != 'PENDING_REVIEW':
        return
    task = service.task(submission['task_id'])
    # Proof/form tasks retain manual review. This policy covers simple video/link tasks.
    if not task or task.get('task_type') != 'URL' or task.get('field_schema'):
        return
    mask = ['1'] * 65 + ['0'] * 35
    # Keep outcomes mixed within each block; never queue all 65 approvals consecutively.
    rng = secrets.SystemRandom()
    while True:
        rng.shuffle(mask)
        if all(len(list(run)) <= (8 if value == '1' else 5) for value, run in groupby(mask)):
            break
    due = time.time() + secrets.randbelow(901) + 900
    service.db._cmd('EVAL', ALLOCATE, '4',
        service._key('auto_review:assignment', submission['submission_id']),
        service._key('auto_review:count', submission['user_id']),
        service._key('auto_review:mask', submission['user_id']),
        service._key('auto_review:due'), ''.join(mask), str(due), submission['submission_id'])


def process_due(db_factory, repair=False):
    events = []
    with db_factory() as db:
        service = AffiliateService(db)
        if not service.redis:
            return events
        if repair:
            # Repair the gap if a crash occurred after saving a submission but before scheduling.
            pending = sorted((s for s in service._rlist('submission') if s['status'] == 'PENDING_REVIEW'),
                             key=lambda s: (s['created_at'], s['submission_id']))
            for submission in pending:
                schedule(service, submission)
        queue = service._key('auto_review:due')
        ids = db._cmd('ZRANGEBYSCORE', queue, '-inf', str(time.time()), 'LIMIT', '0', '50') or []
        for submission_id in ids:
            submission = service._submission(str(submission_id))
            if not submission or submission['status'] != 'PENDING_REVIEW':
                db._cmd('ZREM', queue, submission_id)
                continue
            try:
                submission, ledger = service.review(str(submission_id), 'APPROVED',
                    'Automatically approved under the mixed review policy', 'automatic-review')
            except AffiliateError as exc:
                if exc.code in ('INVALID_STATE', 'NOT_FOUND'):
                    db._cmd('ZREM', queue, submission_id)
                continue
            events.append((submission, ledger))
            db._cmd('ZREM', queue, submission_id)
    return events
