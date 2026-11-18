import test from 'node:test';
import assert from 'node:assert/strict';
import {ApiHttpError,isDefiniteRejection} from './api.ts';

test('ambiguous concurrent conflicts preserve idempotency key',()=>{
  assert.equal(isDefiniteRejection(
    new ApiHttpError(409,'IDEMPOTENCY_WRITE_CONFLICT')),false);
  assert.equal(isDefiniteRejection(
    new ApiHttpError(409,'CONCURRENT_OR_DUPLICATE_WRITE')),false);
});
test('known stale and duplicate-slot conflicts are definite rejections',()=>{
  assert.equal(isDefiniteRejection(
    new ApiHttpError(409,'STALE_EXPECTED_REVISION')),true);
  assert.equal(isDefiniteRejection(
    new ApiHttpError(409,'REPORT_SLOT_ALREADY_EXISTS')),true);
  assert.equal(isDefiniteRejection(
    new ApiHttpError(422,'NO_FINANCIAL_CHANGES')),true);
  assert.equal(isDefiniteRejection(new Error('network unavailable')),false);
});
