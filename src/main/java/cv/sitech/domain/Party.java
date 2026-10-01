package cv.sitech.domain;

public class Party {
  private IdTypeEnum idType;
  private Long id;
  private Account account;
  private String msisdn;

  public Party(IdTypeEnum idType, Account account, String msisdn) {
    this.idType = idType;
    this.account = account;
    this.msisdn = msisdn;
  }

  public IdTypeEnum getIdType() {
    return idType;
  }

  public void setIdType(IdTypeEnum idType) {
    this.idType = idType;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Account getAccount() {
    return account;
  }

  public void setAccount(Account account) {
    this.account = account;
  }

  public String getMsisdn() {
    return msisdn;
  }

  public void setMsisdn(String msisdn) {
    this.msisdn = msisdn;
  }

}
