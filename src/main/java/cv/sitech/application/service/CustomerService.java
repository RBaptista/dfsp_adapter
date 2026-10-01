package cv.sitech.application.service;

import java.util.List;

import cv.sitech.application.port.in.CustomerUseCase;
import cv.sitech.application.port.out.CustomerRepository;
import cv.sitech.domain.Contact;
import cv.sitech.domain.Customer;
import cv.sitech.domain.exception.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class CustomerService implements CustomerUseCase {

  private final CustomerRepository customerRepository;

  public CustomerService(CustomerRepository customerRepository) {
    this.customerRepository = customerRepository;
  }

  @Override
  @Transactional
  public Customer create(String name, Contact contact, String address, Long nif) {
    return customerRepository.save(new Customer(name, contact, address, nif));
  }

  @Override
  public Customer findById(Long id) {
    return customerRepository.findById(id).orElseThrow(() -> new NotFoundException("Customer " + id + " not Found"));
  }

  @Override
  public List<Customer> findAll() {
    return customerRepository.findAll();
  }

  @Override
  @Transactional
  public Customer update(Long id, String name, Contact contact, String address, Long nif) {
    Customer customer = findById(id);
    customer.setName(name);
    customer.setContact(contact);
    customer.setAddress(address);
    customer.setNif(nif);
    return customerRepository.save(customer);
  }

}
