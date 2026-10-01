package cv.sitech.application.service;

import java.util.List;

import cv.sitech.application.port.in.AccountUseCase;
import cv.sitech.application.port.out.AccountRepository;
import cv.sitech.domain.Account;
import cv.sitech.domain.Customer;
import cv.sitech.domain.Dfsp;
import cv.sitech.domain.exception.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class AccountService implements AccountUseCase {

  private final AccountRepository accountRepository;

  public AccountService(AccountRepository accountRepository) {
    this.accountRepository = accountRepository;
  }

  @Override
  @Transactional
  public Account create(Customer customer, Double balance, Dfsp dfsp) {
    return accountRepository.save(new Account(customer, balance, dfsp));
  }

  @Override
  public Account findById(Long id) {
    return accountRepository.findById(id).orElseThrow(() -> new NotFoundException("Account " + id + " not Found"));
  }

  @Override
  public List<Account> findAll() {
    return accountRepository.findAll();
  }

  @Override
  @Transactional
  public Account update(Long id, Double balance, Dfsp dfsp) {
    Account account = findById(id);
    account.setBalance(balance);
    account.setDfsp(dfsp);
    return accountRepository.save(account);
  }

}
