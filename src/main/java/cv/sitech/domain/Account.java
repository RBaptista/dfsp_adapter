package cv.sitech.domain;

public class Account {
  private Long id;
  private Customer customer;
  private Double balance;
  private Float reservedBalance;
  private Dfsp dfsp;

  public Account(Customer customer, Double balance, Dfsp dfsp) {
    this.customer = customer;
    this.balance = balance != null ? balance : 0.0;
    this.dfsp = dfsp;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Customer getCustomer() {
    return customer;
  }

  public void setCustomer(Customer customer) {
    this.customer = customer;
  }

  public Double getBalance() {
    return balance;
  }

  public void setBalance(Double balance) {
    this.balance = balance;
  }

  public Float getReservedBalance() {
    return reservedBalance;
  }

  public void setReservedBalance(Float reservedBalance) {
    this.reservedBalance = reservedBalance;
  }

  public Dfsp getDfsp() {
    return dfsp;
  }

  public void setDfsp(Dfsp dfsp) {
    this.dfsp = dfsp;
  }

}
