package cv.sitech.application.port.out;

import java.util.List;
import java.util.Optional;

import cv.sitech.domain.Account;

public interface AccountRepository {
  Account save(Account account);

  Optional<Account> findById(Long id);

  List<Account> findAll();

  Boolean deleteById(Long id);
}
