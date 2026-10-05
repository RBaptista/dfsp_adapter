package cv.sitech.domain;

import java.math.BigDecimal;

public class Dfsp {
  private String fspId;
  private String displayName;
  private CurrencyEnum currency;
  private String callbackBaseUrl;
  private BigDecimal netDebitCap;
  private BigDecimal initialPosition;
  private BigDecimal initialFunds;

  public Dfsp(String fspId, String displayName, CurrencyEnum currency, String callbackBaseUrl,
      BigDecimal netDebitCap, BigDecimal initialPosition, BigDecimal initialFunds) {
    this.fspId = fspId;
    this.displayName = displayName;
    this.currency = currency;
    this.callbackBaseUrl = callbackBaseUrl;
    this.netDebitCap = netDebitCap != null ? netDebitCap : BigDecimal.ZERO;
    this.initialPosition = initialPosition != null ? initialPosition : BigDecimal.ZERO;
    this.initialFunds = initialFunds != null ? initialFunds : BigDecimal.ZERO;
  }

  public String getFspId() {
    return fspId;
  }

  public void setFspId(String fspId) {
    this.fspId = fspId;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public CurrencyEnum getCurrency() {
    return currency;
  }

  public void setCurrency(CurrencyEnum currency) {
    this.currency = currency;
  }

  public String getCallbackBaseUrl() {
    return callbackBaseUrl;
  }

  public void setCallbackBaseUrl(String callbackBaseUrl) {
    this.callbackBaseUrl = callbackBaseUrl;
  }

  public BigDecimal getNetDebitCap() {
    return netDebitCap;
  }

  public void setNetDebitCap(BigDecimal netDebitCap) {
    this.netDebitCap = netDebitCap;
  }

  public BigDecimal getInitialPosition() {
    return initialPosition;
  }

  public void setInitialPosition(BigDecimal initialPosition) {
    this.initialPosition = initialPosition;
  }

  public BigDecimal getInitialFunds() {
    return initialFunds;
  }

  public void setInitialFunds(BigDecimal initialFunds) {
    this.initialFunds = initialFunds;
  }

}
