package cv.sitech.application.port.out;

import cv.sitech.domain.IdTypeEnum;
import cv.sitech.domain.transfer.FspiopError;
import cv.sitech.domain.transfer.PartyInfo;
import cv.sitech.domain.transfer.Transfer;

/**
 * FSPIOP v1.1 calls to the Mojaloop hub. Every call is asynchronous on the hub side: the hub
 * acknowledges with 202 and answers later through a callback to {@code /fspiop/{fspId}/...}.
 */
public interface FspiopGateway {

  // --- payer side ---
  void getParty(String sourceFspId, IdTypeEnum idType, String idValue);

  void postQuote(Transfer transfer);

  void postTransfer(Transfer transfer);

  // --- payee side ---
  void putParty(String sourceFspId, String destinationFspId, PartyInfo party);

  void putPartyError(String sourceFspId, String destinationFspId, IdTypeEnum idType, String idValue,
      FspiopError error);

  void putQuote(Transfer transfer);

  void putQuoteError(String sourceFspId, String destinationFspId, String quoteId, FspiopError error);

  void putTransfer(Transfer transfer, String transferState);

  void putTransferError(String sourceFspId, String destinationFspId, String transferId, FspiopError error);
}
