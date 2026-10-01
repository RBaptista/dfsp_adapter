package cv.sitech.application.port.out;

import java.util.List;
import java.util.Optional;

import cv.sitech.domain.Party;

public interface PartyRepository {
  Party save(Party party);

  Optional<Party> findById(Long id);

  List<Party> findAll();

  Boolean deleteById(Long id);

}
