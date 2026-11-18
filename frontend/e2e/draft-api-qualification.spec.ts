import {randomUUID} from 'node:crypto';
import {expect,test} from '@playwright/test';

test('draft API rejects invalid writes and preserves an immutable three-revision history',async({request})=>{
  test.skip(process.env.FIN_QA_DATABASE!=='fin_qa','Requires an isolated QA deployment');
  const data = {
    organizationId:randomUUID(),economicEntityId:randomUUID(),businessScopeId:randomUUID(),projectId:randomUUID(),
    formCode:'F02',periodType:'M',periodCode:'2026-11',currency:'KZT',
    coverage:'PERIOD_ONLY',reportingBasis:'MANAGEMENT',dataSpace:'SYNTHETIC',expectedRevision:0,
    values:{netRevenue:'9007199254740993.47',cogs:'-11.01',operatingExpenses:'0'},
    rows:[],createdBy:'qa-r1',changeReason:'Synthetic API qualification'
  };
  const post = (payload:object,key=randomUUID())=>request.post('/api/v1/drafts',{
    data:payload,headers:{'Idempotency-Key':key,'X-FIN-UAT-Token':data.organizationId}
  });
  async function rejected(payload:object,status:number,code:string){
    const response = await post(payload);
    expect(response.status(),await response.text()).toBe(status);
    expect((await response.json()).code).toBe(code);
  }
  await rejected({...data,formCode:'QA-NOT-A-FORM'},422,'FORM_NOT_FOUND');
  await rejected({...data,formCode:'C01',values:{}},422,'INVALID_ROW_COUNT');
  await rejected({...data,formCode:'D01',values:{netRevenue:'1'}},422,'UNKNOWN_FIELD:netRevenue');
  await rejected({...data,values:{}},422,'REQUIRED_FIELD_MISSING:netRevenue');
  await rejected({...data,values:{...data.values,netRevenue:'0.001'}},422,'CURRENCY_MINOR_UNITS_EXCEEDED');
  await rejected({...data,values:{...data.values,netRevenue:'1,234.56'}},422,'INVALID_DECIMAL');

  // Reuse the same slot as the rejected create attempts. Any leaked instance
  // would cause a conflict here instead of a successful first revision.
  const key = randomUUID();
  const first = await post(data,key);
  expect(first.status(),await first.text()).toBe(200);
  const initial = await first.json();
  expect(initial.revisionNo).toBe(1);
  const replay = await post(data,key);
  expect(replay.status(),await replay.text()).toBe(200);
  expect(await replay.json()).toMatchObject({...initial,result:'IDEMPOTENT_REPLAY'});
  const update = {...data,instanceId:initial.instanceId,expectedRevision:1};
  await rejected({...update,projectId:randomUUID()},409,'SLOT_CONTEXT_CHANGED');
  await rejected({...update,organizationId:randomUUID()},404,'INSTANCE_NOT_FOUND');
  await rejected({...update,instanceId:randomUUID()},404,'INSTANCE_NOT_FOUND');
  const path = '/api/v1/drafts/'+initial.instanceId;
  const query = '?organizationId='+data.organizationId;
  expect((await request.get(path+'/revisions/999'+query)).status()).toBe(404);
  expect((await request.get(path+'?organizationId='+randomUUID())).status()).toBe(404);
  const submitted = ['201.12','301.13'].map(netRevenue=>({...update,values:{...data.values,netRevenue}}));
  const competing = await Promise.all(submitted.map(payload=>post(payload)));
  expect(competing.map(response=>response.status()).sort()).toEqual([200,409]);
  const winner = competing.findIndex(response=>response.ok());
  const loser = competing[1-winner];
  expect((await loser.json()).code).toBe('STALE_EXPECTED_REVISION');
  const rolledForward = await post({...submitted[1-winner],expectedRevision:2});
  expect(rolledForward.status(),await rolledForward.text()).toBe(200);
  expect((await rolledForward.json()).revisionNo).toBe(3);
  for(const [index,expected] of [data,submitted[winner],submitted[1-winner]].entries()){
    const response = await request.get(path+'/revisions/'+(index+1)+query);
    expect(response.status()).toBe(200);
    expect(await response.json()).toMatchObject({revisionNo:index+1,values:expected.values});
  }
});
