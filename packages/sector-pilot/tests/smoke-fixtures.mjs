// Synthetic golden inputs only. Expected values are independently stated.
export const SMOKE_CASES = Object.freeze([
  {form:'SF-G01', values:{netRevenue:'1000',cogs:'1100',soldQty:'50'},
    overrides:{cogs:'900'}, metric:'SKU_GROSS_PROFIT', before:'-100.00', after:'100.00',
    delta:'200.00', observation:'NEGATIVE_SKU_GROSS_PROFIT'},
  {form:'SF-G02', values:{periodCogs:'600',avgStockValue:'100'},
    overrides:{avgStockValue:'150'}, metric:'SKU_STOCK_TURN_PERIOD',
    before:'6.00',after:'4.00',delta:'-2.00'},
  {form:'SF-G03', values:{purchasePrice:'12',receivedQty:'10',freight:'20'},
    overrides:{freight:'10'},metric:'LANDED_UNIT_COST',before:'14.00',after:'13.00',delta:'-1.00'},
  {form:'SF-M01', values:{billableHours:'60',availableHours:'100'},
    overrides:{billableHours:'70'},metric:'BILLABLE_UTILIZATION_PCT',
    before:'60.00',after:'70.00',delta:'10.00'},
  {form:'SF-M02', values:{recognizedFee:'1000',directHours:'10',hourCost:'20',otherCost:'900'},
    overrides:{otherCost:'800'},metric:'ENGAGEMENT_CONTRIBUTION',
    before:'-100.00',after:'0.00',delta:'100.00',
    observation:'NEGATIVE_ENGAGEMENT_CONTRIBUTION'},
  {form:'SF-S01', values:{netRevenue:'1000',directLaborCost:'200',materialsCost:'900',completedJobs:'10'},
    overrides:{materialsCost:'800'},metric:'SERVICE_UNIT_MARGIN',
    before:'-10.00',after:'0.00',delta:'10.00',
    observation:'NEGATIVE_SERVICE_UNIT_MARGIN'},
  {form:'SF-C01',values:{unitsStarted:'100',goodUnits:'90',reworkedUnits:'5',wasteUnits:'5'},
    overrides:{goodUnits:'95',wasteUnits:'0'},metric:'GOOD_YIELD_PCT',
    before:'90.00',after:'95.00',delta:'5.00'},
  {form:'SF-C02',values:{materialUsed:'900',directLabor:'100',overhead:'100',goodUnits:'100'},
    overrides:{overhead:'0'},metric:'UNIT_MFG_COST',
    before:'11.00',after:'10.00',delta:'-1.00'},
  {form:'SF-C03',values:{runHours:'80',plannedHours:'100',downtimeHours:'20'},
    overrides:{runHours:'90',downtimeHours:'10'},metric:'EQUIPMENT_UTILIZATION_PCT',
    before:'80.00',after:'90.00',delta:'10.00'}
]);
export function syntheticRow(fixture) {
  return {formCode:fixture.form,businessId:'synthetic-company',dimensionId:'synthetic-item',
    currency:'KZT',period:'2026-01',sourceId:'src-'+fixture.form,
    revisionId:'revision-'+fixture.form,approved:true,values:fixture.values};
}
