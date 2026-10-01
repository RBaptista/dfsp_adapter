package cv.sitech.domain;

public class Dfsp {
  private String fspId;
  private String displayName;
  private CurrencyEnum currency;
  private String callbackBaseUrl;
  private Long netDebitCap;
  private Long initialPosition;
  private Long initialFunds;

  public Dfsp(String fspId, String displayName, CurrencyEnum currency, String callbackBaseUrl, Long netDebitCap,
      Long initialPosition, Long initialFunds) {
    this.fspId = fspId;
    this.displayName = displayName;
    this.currency = currency;
    this.callbackBaseUrl = callbackBaseUrl;
    this.netDebitCap = netDebitCap != null ? netDebitCap : 0L;
    this.initialPosition = initialPosition != null ? initialPosition : 0L;
    this.initialFunds = initialFunds != null ? initialFunds : 0L;
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

  public Long getNetDebitCap() {
    return netDebitCap;
  }

  public void setNetDebitCap(Long netDebitCap) {
    this.netDebitCap = netDebitCap;
  }

  public Long getInitialPosition() {
    return initialPosition;
  }

  public void setInitialPosition(Long initialPosition) {
    this.initialPosition = initialPosition;
  }

  public Long getInitialFunds() {
    return initialFunds;
  }

  public void setInitialFunds(Long initialFunds) {
    this.initialFunds = initialFunds;
  }

}
