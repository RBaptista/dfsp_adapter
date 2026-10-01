package cv.sitech.adapter.out.persistence;

import cv.sitech.adapter.out.entity.AccountEntity;
import cv.sitech.adapter.out.persistence.mapper.AccountMapper;
import cv.sitech.application.port.out.AccountRepository;
import cv.sitech.domain.Account;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class AccountPersistenceAdapter extends PanacheAdapter<Account, AccountEntity, Long> implements AccountRepository {

  protected AccountPersistenceAdapter() {
    super(null, null);
  }

  @Inject
  public AccountPersistenceAdapter(AccountPanacheRepository panache, AccountMapper mapper) {
    super(panache, mapper);
  }

}
