package cv.sitech.application.port.in;

import java.util.List;

import cv.sitech.domain.Contact;
import cv.sitech.domain.Customer;

public interface CustomerUseCase {
  Customer create(String name, Contact contact, String address, Long nif);

  Customer findById(Long id);

  List<Customer> findAll();

  Customer update(Long id, String name, Contact contact, String address, Long nif);

}
