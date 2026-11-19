package kz.fin.formlib.calc.fact;

import kz.fin.formlib.calc.ingest.FormFactCandidateExtractor;

/** Pure mapping of an INGEST-2 reverified exact revision; never writes or qualifies. */
public interface F02SourceFactAssembler {
  CandidateFactSet assemble(
      FormFactCandidateExtractor.Projection reverifiedSource,
      F02MappingDescriptor descriptor);
}
