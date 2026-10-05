package cv.sitech.application.port.in;

import java.math.BigDecimal;
import java.util.List;

import cv.sitech.domain.CurrencyEnum;
import cv.sitech.domain.Dfsp;

public interface DfspUseCase {
  /** creates the DFSP with its POSITION and SETTLEMENT ledger accounts */
  Dfsp create(String fspId, String displayName, CurrencyEnum currency, BigDecimal netDebitCap,
      BigDecimal initialPosition, BigDecimal initialFunds);

  Dfsp findById(String fspId);

  List<Dfsp> findAll();

  Dfsp update(String fspId, String displayName, BigDecimal netDebitCap);

  /** registers the DFSP in the hub (participant, endpoints, limits, initial funds) */
  void onboard(String fspId);

}
