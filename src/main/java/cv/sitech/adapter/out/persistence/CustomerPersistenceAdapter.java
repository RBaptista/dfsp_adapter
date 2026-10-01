package cv.sitech.adapter.out.persistence;

import cv.sitech.adapter.out.entity.CustomerEntity;
import cv.sitech.adapter.out.persistence.mapper.CustomerMapper;
import cv.sitech.application.port.out.CustomerRepository;
import cv.sitech.domain.Customer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class CustomerPersistenceAdapter extends PanacheAdapter<Customer, CustomerEntity, Long> implements CustomerRepository {

  protected CustomerPersistenceAdapter() {
    super(null, null);
  }

  @Inject
  public CustomerPersistenceAdapter(CustomerPanacheRepository panache, CustomerMapper mapper) {
    super(panache, mapper);
  }

}
