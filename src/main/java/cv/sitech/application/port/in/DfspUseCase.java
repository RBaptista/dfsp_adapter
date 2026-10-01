package cv.sitech.application.port.in;

import java.util.List;

import cv.sitech.domain.Dfsp;
import cv.sitech.domain.CurrencyEnum;

public interface DfspUseCase {
  Dfsp create(String fspId, String displayName, CurrencyEnum currency, String callbackBaseUrl, Long netDebitCap,
      Long initialPosition, Long initialFunds);

  Dfsp findById(String fspId);

  List<Dfsp> findAll();

  Dfsp update(String fspId, String displayName, CurrencyEnum currency, Long initialPosition);

}
