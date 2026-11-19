package kz.fin.formlib.calc.fact;

import java.util.UUID;

/** FACT-1.B typed write contract, no HTTP approval path or production S2. */
public interface FactSetCommitter {
  FactSetCommitReceipt commitExact(CandidateFactSet candidate,
      F02MappingDescriptor descriptor,VerifiedQualification qualification,
      TrustedRequestPrincipal principal,UUID userId);
}
