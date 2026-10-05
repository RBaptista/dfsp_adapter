package cv.sitech.application.port.in;

import java.math.BigDecimal;

import cv.sitech.domain.CurrencyEnum;
import cv.sitech.domain.IdTypeEnum;

/** A P2P SEND transfer initiated by a customer of the logged-in (payer) DFSP. */
public record StartTransferCommand(IdTypeEnum payerIdType, String payerIdValue, IdTypeEnum payeeIdType,
    String payeeIdValue, BigDecimal amount, CurrencyEnum currency, String note, boolean autoAccept) {
}
