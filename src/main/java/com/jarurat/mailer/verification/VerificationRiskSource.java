package com.jarurat.mailer.verification;

import com.jarurat.mailer.campaignsplus.AudienceRiskSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Feeds the pre-send safety gate a forward looking bounce estimate.
 *
 * The gate declares AudienceRiskSource and picks up whatever single bean
 * implements it, so registering this is the whole integration. Nothing outside
 * this package changes.
 *
 * Two deliberate choices about what counts as a bounce:
 *
 * Only UNDELIVERABLE is counted. RISKY is a role account, a catch-all domain or
 * a domain with no MX, and those mostly do deliver. Folding them in would make
 * the estimate look responsible while actually blocking healthy campaigns to
 * lists that are full of info@ addresses, which is most B2B outreach.
 *
 * The estimate stays silent below a coverage floor. Extrapolating an 8% bounce
 * rate from the forty addresses somebody happened to verify would block a send
 * on essentially no evidence, and the gate's own comment is explicit that a
 * fabricated number is worse than no number.
 */
@Component
public class VerificationRiskSource implements AudienceRiskSource {

    private final VerificationService verification;
    private final double minCoverage;
    private final int minChecked;

    public VerificationRiskSource(VerificationService verification,
                                  @Value("${verification.risk.minCoverage:0.5}") double minCoverage,
                                  @Value("${verification.risk.minChecked:50}") int minChecked) {
        this.verification = verification;
        this.minCoverage = minCoverage;
        this.minChecked = minChecked;
    }

    @Override
    public Optional<Double> estimatedBouncePct(Long listId) {
        if (listId == null) return Optional.empty();

        VerificationService.Coverage coverage = verification.coverage(listId);
        if (coverage.checked() < minChecked) return Optional.empty();
        if (coverage.checkedFraction() < minCoverage) return Optional.empty();

        // Two decimals, because the gate formats to two and a raw double here
        // produces "4.999999999999999%" in the finding text.
        return Optional.of(Math.round(coverage.undeliverablePercent() * 100.0) / 100.0);
    }

    @Override
    public String describe() {
        return "address verification";
    }
}
