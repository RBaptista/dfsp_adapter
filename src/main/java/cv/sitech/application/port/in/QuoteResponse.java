package cv.sitech.application.port.in;

import java.math.BigDecimal;
import java.time.Instant;

/** An FSPIOP PUT /quotes/{id} callback, as seen by the payer. */
public record QuoteResponse(String quoteId, BigDecimal transferAmount, BigDecimal payeeReceiveAmount,
    BigDecimal payeeFee, Instant expiration, String ilpPacket, String condition) {
}
