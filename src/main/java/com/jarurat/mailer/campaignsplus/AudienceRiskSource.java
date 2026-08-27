package com.jarurat.mailer.campaignsplus;

import java.util.Optional;

/**
 * Optional supplier of an up-front bounce estimate for a list.
 *
 * The pre-send gate can only measure what already happened: hard bounces
 * recorded against recent sends. That says nothing about a list nobody has
 * mailed yet. Address verification can answer that, but only by probing, which
 * is far too slow to run over 12,000 rows inside a send gate.
 *
 * So the gate asks for an estimate rather than producing one. Implement this
 * with whatever the verifier has already stored or sampled, register it as a
 * bean, and the estimate is folded into the same thresholds. With no
 * implementation present the gate simply skips the check and says so.
 */
public interface AudienceRiskSource {

    /**
     * Expected bounce percentage for the mailable members of a list, or empty
     * when there is not enough verification data to answer. Never guess: an
     * invented number here blocks real campaigns.
     */
    Optional<Double> estimatedBouncePct(Long listId);

    /** How the estimate was arrived at, shown next to the number. */
    default String describe() { return "address verification"; }
}
