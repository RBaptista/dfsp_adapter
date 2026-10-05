package cv.sitech.application.port.in;

import java.math.BigDecimal;
import java.time.Instant;

import cv.sitech.domain.CurrencyEnum;

/** An incoming FSPIOP POST /transfers, as seen by the payee. */
public record TransferPrepare(String transferId, String payerFspId, String payeeFspId, BigDecimal amount,
    CurrencyEnum currency, String ilpPacket, String condition, Instant expiration) {
}
