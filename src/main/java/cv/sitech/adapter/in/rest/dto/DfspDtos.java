package cv.sitech.adapter.in.rest.dto;

import java.math.BigDecimal;

import cv.sitech.domain.Account;
import cv.sitech.domain.CurrencyEnum;
import cv.sitech.domain.Dfsp;

public final class DfspDtos {

  private DfspDtos() {
  }

  public record CreateDfsp(String fspId, String displayName, CurrencyEnum currency, BigDecimal netDebitCap,
      BigDecimal initialPosition, BigDecimal initialFunds) {
  }

  public record UpdateDfsp(String displayName, BigDecimal netDebitCap) {
  }

  public record DfspView(String fspId, String displayName, CurrencyEnum currency, String callbackBaseUrl,
      BigDecimal netDebitCap, BigDecimal initialPosition, BigDecimal initialFunds) {
    public static DfspView from(Dfsp d) {
      return new DfspView(d.getFspId(), d.getDisplayName(), d.getCurrency(), d.getCallbackBaseUrl(),
          d.getNetDebitCap(), d.getInitialPosition(), d.getInitialFunds());
    }
  }

  /** the logged-in DFSP with its ledger position */
  public record DfspStatus(DfspView dfsp, AccountView position, AccountView settlement) {
  }

  public record AccountView(Long id, String type, Long customerId, BigDecimal balance, BigDecimal reservedBalance,
      BigDecimal pendingCredit, BigDecimal availableBalance, CurrencyEnum currency) {
    public static AccountView from(Account a) {
      return new AccountView(a.getId(), a.getType().name(), a.getCustomer() != null ? a.getCustomer().getId() : null,
          a.getBalance(), a.getReservedBalance(), a.getPendingCredit(), a.availableBalance(),
          a.getDfsp().getCurrency());
    }
  }

}
