package io.github.tmejs.reservation.inventory.product;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {

    private final ProductRepository products;

    ProductService(ProductRepository products) {
        this.products = products;
    }

    @Transactional
    public ProductEntity create(String name, int initialQuantity) {
        return products.save(new ProductEntity(UUID.randomUUID(), name, initialQuantity));
    }

    @Transactional(readOnly = true)
    public ProductEntity get(UUID id) {
        return products.findById(id).orElseThrow(() -> new ProductNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Page<ProductEntity> list(int page, int size) {
        return products.findAll(PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "id")));
    }

    @Transactional
    public ProductEntity addStock(UUID id, int quantity) {
        ProductEntity product = products.findByIdForUpdate(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
        long updatedQuantity = (long) product.getAvailableQuantity() + quantity;
        if (updatedQuantity > Integer.MAX_VALUE) {
            throw new StockOverflowException();
        }
        product.setAvailableQuantity((int) updatedQuantity);
        return product;
    }
}
