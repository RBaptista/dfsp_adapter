package cv.sitech.adapter.out.persistence;

import cv.sitech.adapter.out.entity.PartyEntity;
import cv.sitech.adapter.out.persistence.mapper.PartyMapper;
import cv.sitech.application.port.out.PartyRepository;
import cv.sitech.domain.Party;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class PartyPersistenceAdapter extends PanacheAdapter<Party, PartyEntity, Long> implements PartyRepository {

  protected PartyPersistenceAdapter() {
    super(null, null);
  }

  @Inject
  public PartyPersistenceAdapter(PartyPanacheRepository panache, PartyMapper mapper) {
    super(panache, mapper);
  }

}
