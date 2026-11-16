import {describe,it,expect} from "vitest";
import {CAPTURE_CATALOG} from "./catalog-schema";
import {inspectLocalEvidence,MAX_EVIDENCE_BYTES} from "./evidence-contract-r6";

const docCodes=CAPTURE_CATALOG.filter(f=>f.captureMode==="EVIDENCE_ONLY").map(f=>f.code);
const pdf=new TextEncoder().encode("%PDF-1.7\n1 0 obj\n%%EOF\n");
const zip=new Uint8Array([80,75,3,4,20,0,0,0,0,0,0,0,0,0,0,0]);
describe("R6.2 evidence intake preflight for all 12 non-numeric catalog forms",()=>{
  it("exactly 12 D/T documents; no other form becomes a document by file upload",()=>{
    expect(docCodes).toEqual(["D01","D02","D03","D04","D05","D06","D07","D08",
      "T01","T02","T03","T04"]);
  });
  it.each(docCodes)("%s creates ONLY a local unverified SHA256 manifest",async code=>{
    const result=await inspectLocalEvidence(code,"audit.pdf",pdf);
    expect(result.formCode).toBe(code);
    expect(result.sha256).toMatch(/^[a-f0-9]{64}$/);
    expect(result.byteLength).toBe(pdf.length);
    expect(result.sourceScan).toBe("NOT_PERFORMED");
    expect(result.valuesApproved).toBe(false);
    expect(result.originalStoredOnServer).toBe(false);
    expect(result.status).toBe("LOCAL_UNVERIFIED_EVIDENCE");
  });
  it("same raw bytes give same SHA; content change changes fingerprint",async()=>{
    const a=await inspectLocalEvidence("D01","statement.pdf",pdf);
    const b=await inspectLocalEvidence("D01","statement.pdf",pdf);
    const c=await inspectLocalEvidence("D01","statement.pdf",
      new TextEncoder().encode("%PDF-1.7\n2 0 obj\n%%EOF\n"));
    expect(a.sha256).toBe(b.sha256);
    expect(a.sha256).not.toBe(c.sha256);
  });
  it("cannot mislabel numbers, paths, empty or spoofed file extension as source evidence",async()=>{
    await expect(inspectLocalEvidence("F02","money.pdf",pdf)).rejects.toThrow("EVIDENCE_CODE_NOT_DOCUMENT");
    await expect(inspectLocalEvidence("D01","../statement.pdf",pdf)).rejects.toThrow("EVIDENCE_FILENAME_REJECTED");
    await expect(inspectLocalEvidence("D01","statement.pdf.exe",pdf)).rejects.toThrow("EVIDENCE_TYPE_NOT_ALLOWED");
    await expect(inspectLocalEvidence("D01","statement.pdf",new Uint8Array(0))).rejects.toThrow("EVIDENCE_SIZE_REJECTED");
    await expect(inspectLocalEvidence("D01","statement.pdf",new Uint8Array(MAX_EVIDENCE_BYTES+1)))
      .rejects.toThrow("EVIDENCE_SIZE_REJECTED");
    await expect(inspectLocalEvidence("D01","statement.pdf",zip))
      .rejects.toThrow("EVIDENCE_MAGIC_OR_ENCODING_MISMATCH");
    await expect(inspectLocalEvidence("D01","statement.xlsx",pdf))
      .rejects.toThrow("EVIDENCE_MAGIC_OR_ENCODING_MISMATCH");
  });
  it("accepts only compatible signatures; this is NOT malware or ZIP safety qualification",async()=>{
    const result=await inspectLocalEvidence("T01","tax.xlsx",zip);
    expect(result.contentType).toContain("spreadsheetml");
    expect(result.sourceScan).toBe("NOT_PERFORMED");
    expect(result.valuesApproved).toBe(false);
  });
});
