package cv.sitech.application.port.out;

import java.math.BigDecimal;

import cv.sitech.domain.Dfsp;
import cv.sitech.domain.IdTypeEnum;

/** Hub administration: participant onboarding (central-ledger) and party registration (ALS). */
public interface HubAdminGateway {

  /** creates the participant, its callback endpoints, NDC limit and initial position; idempotent */
  void onboard(Dfsp dfsp);

  void recordFundsIn(String fspId, BigDecimal amount, String currency);

  void registerParty(String fspId, IdTypeEnum idType, String idValue, String currency);

  void deregisterParty(String fspId, IdTypeEnum idType, String idValue);
}
