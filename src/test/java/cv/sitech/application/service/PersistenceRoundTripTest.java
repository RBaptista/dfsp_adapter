package cv.sitech.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import cv.sitech.application.port.in.AccountUseCase;
import cv.sitech.application.port.in.CustomerUseCase;
import cv.sitech.application.port.in.DfspUseCase;
import cv.sitech.application.port.in.PartyUseCase;
import cv.sitech.domain.Account;
import cv.sitech.domain.Contact;
import cv.sitech.domain.CurrencyEnum;
import cv.sitech.domain.Customer;
import cv.sitech.domain.Dfsp;
import cv.sitech.domain.IdTypeEnum;
import cv.sitech.domain.Party;
import cv.sitech.domain.exception.NotFoundException;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
@TestTransaction
class PersistenceRoundTripTest {

  @Inject
  DfspUseCase dfspUseCase;

  @Inject
  CustomerUseCase customerUseCase;

  @Inject
  AccountUseCase accountUseCase;

  @Inject
  PartyUseCase partyUseCase;

  @Test
  void persistsAndReadsBackFullGraph() {
    Dfsp dfsp = dfspUseCase.create("test-fsp", "Test FSP", CurrencyEnum.CVE, "http://localhost/cb", 1000L, null, null);
    Customer customer = customerUseCase.create("Jane", new Contact("991", "222", "jane@test.cv"), "Praia", 123L);
    Account account = accountUseCase.create(customer, 50.0, dfsp);
    Party party = partyUseCase.create(IdTypeEnum.MSISDN, account, "2389910000");

    assertNotNull(customer.getId());
    assertNotNull(customer.getContact().getId());
    assertNotNull(account.getId());

    Party loaded = partyUseCase.findById(party.getId());
    assertEquals("2389910000", loaded.getMsisdn());
    assertEquals(IdTypeEnum.MSISDN, loaded.getIdType());
    assertEquals(50.0, loaded.getAccount().getBalance());
    assertEquals("jane@test.cv", loaded.getAccount().getCustomer().getContact().getEmail());
    assertEquals(CurrencyEnum.CVE, loaded.getAccount().getDfsp().getCurrency());
    assertEquals(0L, dfspUseCase.findById("test-fsp").getInitialPosition());
  }

  @Test
  void updateKeepsContactRow() {
    Customer customer = customerUseCase.create("Jane", new Contact("991", null, null), "Praia", 1L);
    Contact contact = customer.getContact();
    contact.setEmail("new@test.cv");

    Customer updated = customerUseCase.update(customer.getId(), "Jane D", contact, "Mindelo", 1L);

    assertEquals(customer.getContact().getId(), updated.getContact().getId());
    assertEquals("new@test.cv", updated.getContact().getEmail());
  }

  @Test
  void missingPartyThrows() {
    assertThrows(NotFoundException.class, () -> partyUseCase.findById(-1L));
    assertThrows(NotFoundException.class, () -> partyUseCase.delete(-1L));
  }

}
