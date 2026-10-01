package cv.sitech.application.port.in;

import java.util.List;

import cv.sitech.domain.Account;
import cv.sitech.domain.IdTypeEnum;
import cv.sitech.domain.Party;

public interface PartyUseCase {
  Party create(IdTypeEnum idType, Account account, String msisdn);

  Party findById(Long id);

  List<Party> findAll();

  Party update(Long id, IdTypeEnum idType, String msisdn);

  void delete(Long id);
}
