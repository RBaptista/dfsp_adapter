package cv.sitech.application.port.in;

import java.time.Instant;

import cv.sitech.domain.IdTypeEnum;
import cv.sitech.domain.transfer.FspiopError;
import cv.sitech.domain.transfer.PartyInfo;

/** Hub callbacks addressed to a hosted DFSP acting as payer. Unknown ids are ignored. */
public interface PayerCallbackUseCase {
  void partyResolved(String fspId, PartyInfo party);

  void partyLookupFailed(String fspId, IdTypeEnum idType, String idValue, FspiopError error);

  void quoteReceived(String fspId, QuoteResponse response);

  void quoteFailed(String fspId, String quoteId, FspiopError error);

  void transferFulfilled(String fspId, String transferId, String fulfilment, String transferState,
      Instant completedAt);

  void transferFailed(String fspId, String transferId, FspiopError error);
}
