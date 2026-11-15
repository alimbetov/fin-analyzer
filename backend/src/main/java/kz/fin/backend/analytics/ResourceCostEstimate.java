package kz.fin.backend.analytics;

import java.math.BigDecimal;
import java.util.*;

/**
 * Resource-based BoQ cost estimate, not an official country-specific estimate.
 * Direct cost = work quantity * resource usage per work unit * price per resource unit.
 * Overheads applied to direct cost; contingency to direct + overhead.
 * VAT, indexation, local norms and contractual adjustments are NOT silently added.
 */
public final class ResourceCostEstimate {
    private ResourceCostEstimate() {}

    public record ResourceRate(String resourceCode, String resourceUnit,
                               FinancialAmount pricePerUnit, String rateVersion,
                               boolean approved) {
        public ResourceRate {
            if (resourceCode == null || resourceCode.isBlank()
                    || resourceUnit == null || resourceUnit.isBlank()
                    || rateVersion == null || rateVersion.isBlank())
                throw new IllegalArgumentException("INVALID_RESOURCE_RATE");
            Objects.requireNonNull(pricePerUnit);
            pricePerUnit.requireNonNegative("RESOURCE_PRICE");
        }
    }

    public record ResourceUsage(ResourceRate rate, BigDecimal resourceUnitsPerWorkUnit) {
        public ResourceUsage {
            Objects.requireNonNull(rate);
            Objects.requireNonNull(resourceUnitsPerWorkUnit);
            if (resourceUnitsPerWorkUnit.signum() < 0)
                throw new IllegalArgumentException("NEGATIVE_RESOURCE_CONSUMPTION");
        }
    }

    public record BoqLine(String lineCode, String wbsCode, int displayOrder,
                          String workUnit, BigDecimal workQuantity,
                          List<ResourceUsage> resources) {
        public BoqLine {
            if (lineCode == null || lineCode.isBlank() || wbsCode == null || wbsCode.isBlank()
                    || workUnit == null || workUnit.isBlank() || displayOrder < 0)
                throw new IllegalArgumentException("INVALID_BOQ_LINE");
            Objects.requireNonNull(workQuantity);
            if (workQuantity.signum() < 0) throw new IllegalArgumentException("NEGATIVE_WORK_QUANTITY");
            resources = List.copyOf(resources);
            if (resources.isEmpty()) throw new IllegalArgumentException("EMPTY_RESOURCE_BILL");
            Set<String> resourceCodes = new HashSet<>();
            for (ResourceUsage usage : resources) {
                if (!resourceCodes.add(usage.rate().resourceCode()))
                    throw new IllegalArgumentException("DUPLICATE_RESOURCE_CODE");
            }
        }
    }

    public record Input(AnalysisContext context, List<BoqLine> lines,
                        BigDecimal overheadFraction, BigDecimal contingencyFraction) {
        public Input {
            Objects.requireNonNull(context);
            context.requireFlowPeriod();
            lines = List.copyOf(lines);
            if (lines.isEmpty()) throw new IllegalArgumentException("EMPTY_BOQ");
            Objects.requireNonNull(overheadFraction);
            Objects.requireNonNull(contingencyFraction);
            if (overheadFraction.signum() < 0 || contingencyFraction.signum() < 0
                    || overheadFraction.compareTo(BigDecimal.ONE) > 0
                    || contingencyFraction.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("INVALID_MARKUP_FRACTION");
            }
            Set<String> identifiers = new HashSet<>();
            for (BoqLine line : lines) {
                if (!identifiers.add(line.lineCode())) throw new IllegalArgumentException("DUPLICATE_BOQ_LINE");
                for (ResourceUsage resource : line.resources()) {
                    context.requireCurrency(resource.rate().pricePerUnit());
                    if (!resource.rate().approved()) throw new IllegalArgumentException("UNAPPROVED_RESOURCE_RATE");
                }
            }
        }
    }

    public record LineResult(String lineCode, String wbsCode, int displayOrder,
                             BigDecimal quantity, String workUnit, FinancialAmount directCost) {}

    public record Result(AnalysisContext context, List<LineResult> lines,
                         FinancialAmount directCost, FinancialAmount overhead,
                         FinancialAmount contingency, FinancialAmount grandTotal,
                         String methodVersion) {
        public Result { lines = List.copyOf(lines); }
    }

    public static Result calculate(Input input) {
        Objects.requireNonNull(input);
        List<BoqLine> sorted = new ArrayList<>(input.lines());
        sorted.sort(Comparator.comparingInt(BoqLine::displayOrder).thenComparing(BoqLine::lineCode));
        FinancialAmount totalDirect = FinancialAmount.zero(input.context().currency());
        List<LineResult> calculated = new ArrayList<>();
        for (BoqLine line : sorted) {
            FinancialAmount lineDirect = FinancialAmount.zero(input.context().currency());
            for (ResourceUsage usage : line.resources()) {
                FinancialAmount component = usage.rate().pricePerUnit()
                        .times(usage.resourceUnitsPerWorkUnit()).times(line.workQuantity());
                lineDirect = lineDirect.plus(component);
            }
            calculated.add(new LineResult(line.lineCode(),line.wbsCode(),line.displayOrder(),
                    line.workQuantity(),line.workUnit(),lineDirect));
            totalDirect = totalDirect.plus(lineDirect);
        }
        FinancialAmount overhead = totalDirect.times(input.overheadFraction());
        FinancialAmount subtotal = totalDirect.plus(overhead);
        FinancialAmount contingency = subtotal.times(input.contingencyFraction());
        return new Result(input.context(),calculated,totalDirect,overhead,contingency,
                subtotal.plus(contingency),"RESOURCE_BOQ_V1");
    }
}
