package cv.sitech.application.port.in;

import java.util.List;

import cv.sitech.domain.IdTypeEnum;
import cv.sitech.domain.transfer.FspiopError;
import cv.sitech.domain.transfer.Transfer;

/** Hub requests addressed to a hosted DFSP acting as payee. */
public interface PayeeUseCase {
  void partyLookup(String fspId, String requesterFspId, IdTypeEnum idType, String idValue);

  void quoteRequested(String fspId, String requesterFspId, QuoteRequest request);

  void transferPrepared(String fspId, String requesterFspId, TransferPrepare prepare);

  /** hub PATCH notification after the payee answered with RESERVED */
  void transferNotified(String fspId, String transferId, String transferState);

  void transferFailed(String fspId, String transferId, FspiopError error);

  List<Transfer> findAll(String fspId);
}
