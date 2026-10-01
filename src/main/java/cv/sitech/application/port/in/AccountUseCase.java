package cv.sitech.application.port.in;

import java.util.List;

import cv.sitech.domain.Account;
import cv.sitech.domain.Customer;
import cv.sitech.domain.Dfsp;

public interface AccountUseCase {
  Account create(Customer customer, Double balance, Dfsp dfsp);

  Account findById(Long id);

  List<Account> findAll();

  Account update(Long id, Double balance, Dfsp dfsp);

}
