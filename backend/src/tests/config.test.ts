import {test} from 'node:test';
import assert from 'node:assert/strict';
test('environment defaults are safe',()=>{assert.notEqual(process.env.WHATSAPP_ACCESS_TOKEN,'hardcoded-token');assert.notEqual(process.env.AI_API_KEY,'hardcoded-key')});
