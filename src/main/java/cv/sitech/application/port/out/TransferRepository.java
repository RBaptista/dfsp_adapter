package cv.sitech.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import cv.sitech.domain.IdTypeEnum;
import cv.sitech.domain.transfer.Transfer;
import cv.sitech.domain.transfer.TransferDirection;

public interface TransferRepository {
  Transfer save(Transfer transfer);

  Optional<Transfer> findByTransferId(TransferDirection direction, String transferId);

  Optional<Transfer> findByQuoteId(TransferDirection direction, String quoteId);

  Optional<Transfer> findInboundByCondition(String payeeFspId, String ilpCondition);

  List<Transfer> findOutboundByPayer(String payerFspId);

  List<Transfer> findInboundByPayee(String payeeFspId);

  /** outbound transfers of {@code payerFspId} still waiting for discovery of this party */
  List<Transfer> findAwaitingParty(String payerFspId, IdTypeEnum idType, String idValue);

  /** non-final transfers whose expiration is before {@code now} */
  List<Transfer> findExpired(Instant now);

  /** COMMITTED transfers where {@code fspId} is payer (OUTBOUND) or payee (INBOUND), completed in [from, to) */
  List<Transfer> findCommitted(String fspId, Instant from, Instant to);
}
