/** FIN V5 canonical reporting periods. Calendar years only; fiscal custom calendars fail closed. */
export type PeriodType = "WEEK" | "MONTH" | "QUARTER" | "HALF_YEAR" | "YEAR" | "AS_OF";
export type ValueCoverage = "PERIOD_ONLY" | "YEAR_TO_DATE" | "LIFE_TO_DATE" | "AS_OF";
export interface ReportingPeriod {
  type: PeriodType;
  code: string;
  calendarId: "ISO_CALENDAR";
  calendarVersion: "1";
  valueCoverage: ValueCoverage;
  startInclusive?: string;
  endExclusive?: string;
  asOfDate?: string;
}
function utc(y: number, m: number, d: number): Date {
  // Normalize the month before assigning the year: setUTCFullYear alone loses
  // the carry from month 12 (2026-12 + 1 must be 2027-01, not 2026-01).
  const yearCarry = Math.floor(m / 12);
  const normalizedMonth = ((m % 12) + 12) % 12;
  const date = new Date(Date.UTC(2000, normalizedMonth, d));
  date.setUTCFullYear(y + yearCarry);
  return date;
}
function iso(date: Date): string { return date.toISOString().slice(0,10); }
function addDays(date: Date, days: number): Date {
  const copy = new Date(date.getTime());
  copy.setUTCDate(copy.getUTCDate() + days);
  return copy;
}
function year(value: string): number {
  const y = Number(value);
  if (!/^\d{4}$/.test(value) || y < 1900 || y > 9998)
    throw new Error("INVALID_PERIOD_YEAR");
  return y;
}
function lastIsoWeek(y: number): number {
  const jan4 = utc(y + 1, 0, 4);
  const monday = addDays(jan4, -(jan4.getUTCDay() + 6) % 7);
  const currentJan4 = utc(y, 0, 4);
  const currentMonday = addDays(currentJan4, -(currentJan4.getUTCDay() + 6) % 7);
  return Math.round((monday.getTime() - currentMonday.getTime()) / (7 * 86400000));
}
export function resolvePeriod(type: PeriodType, code: string,
  valueCoverage: ValueCoverage = type === "AS_OF" ? "AS_OF" : "PERIOD_ONLY"): ReportingPeriod {
  if ((type === "AS_OF") !== (valueCoverage === "AS_OF"))
    throw new Error("PERIOD_COVERAGE_MISMATCH");
  let start: Date, end: Date;
  if (type === "WEEK") {
    const m = /^(\d{4})-W(\d{2})$/.exec(code);
    if (!m) throw new Error("INVALID_ISO_WEEK");
    const y = year(m[1]!);
    const w = Number(m[2]);
    if (w < 1 || w > lastIsoWeek(y)) throw new Error("INVALID_ISO_WEEK");
    const jan4 = utc(y, 0, 4);
    start = addDays(jan4, -(jan4.getUTCDay()+6)%7 + (w-1)*7);
    end = addDays(start, 7);
  } else if (type === "MONTH") {
    const m = /^(\d{4})\.(0[1-9]|1[0-2])$/.exec(code);
    if (!m) throw new Error("INVALID_MONTH");
    const y = year(m[1]!);
    const mon = Number(m[2]) - 1;
    start = utc(y, mon, 1); end = utc(y, mon+1, 1);
  } else if (type === "QUARTER") {
    const m = /^(\d{4})-Q([1-4])$/.exec(code);
    if (!m) throw new Error("INVALID_QUARTER");
    const y = year(m[1]!); const mon = (Number(m[2])-1)*3;
    start = utc(y, mon, 1); end = utc(y, mon+3, 1);
  } else if (type === "HALF_YEAR") {
    const m = /^(\d{4})-H([12])$/.exec(code);
    if (!m) throw new Error("INVALID_HALF_YEAR");
    const y = year(m[1]!); const mon = (Number(m[2])-1)*6;
    start = utc(y, mon, 1); end = utc(y, mon+6, 1);
  } else if (type === "YEAR") {
    const y = year(code);
    start = utc(y, 0, 1); end = utc(y+1, 0, 1);
  } else {
    const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(code);
    if (!m) throw new Error("INVALID_AS_OF");
    const y = year(m[1]!);
    start = utc(y, Number(m[2])-1, Number(m[3]));
    if (iso(start) !== code) throw new Error("INVALID_AS_OF");
    return {type, code, calendarId:"ISO_CALENDAR", calendarVersion:"1",
      valueCoverage:"AS_OF", asOfDate:code};
  }
  return {type, code, calendarId:"ISO_CALENDAR", calendarVersion:"1", valueCoverage,
    startInclusive:iso(start), endExclusive:iso(end)};
}
