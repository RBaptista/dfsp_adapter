package cv.sitech.adapter.out.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.ManyToOne;

@Entity
@Table(name = "accounts")
public class AccountEntity {
  private Long id;
  private CustomerEntity customer;
  private BigDecimal balance = BigDecimal.ZERO;
  private Float reservedBalance;
  private DfspEntity dfsp;

  @Id
  @GeneratedValue
  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  @ManyToOne
  @JoinColumn(name = "customer_id")
  public CustomerEntity getCustomer() {
    return customer;
  }

  public void setCustomer(CustomerEntity customer) {
    this.customer = customer;
  }

  public BigDecimal getBalance() {
    return balance;
  }

  public void setBalance(BigDecimal balance) {
    this.balance = balance;
  }

  @Column(name = "reserved_balance")
  public Float getReservedBalance() {
    return reservedBalance;
  }

  public void setReservedBalance(Float reservedBalance) {
    this.reservedBalance = reservedBalance;
  }

  @ManyToOne
  @JoinColumn(name = "dfsp_id")
  public DfspEntity getDfsp() {
    return dfsp;
  }

  public void setDfsp(DfspEntity dfsp) {
    this.dfsp = dfsp;
  }

}
