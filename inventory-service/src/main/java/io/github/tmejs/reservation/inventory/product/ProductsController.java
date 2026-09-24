package io.github.tmejs.reservation.inventory.product;

import io.github.tmejs.reservation.api.inventory.ProductsApi;
import io.github.tmejs.reservation.api.inventory.model.AddStockRequest;
import io.github.tmejs.reservation.api.inventory.model.CreateProductRequest;
import io.github.tmejs.reservation.api.inventory.model.Product;
import io.github.tmejs.reservation.api.inventory.model.ProductPage;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProductsController implements ProductsApi {

    private final ProductService productService;

    ProductsController(ProductService productService) {
        this.productService = productService;
    }

    @Override
    public ResponseEntity<Product> createProduct(CreateProductRequest request) {
        Product created = toApi(productService.create(request.getName(), request.getInitialQuantity()));
        return ResponseEntity.created(URI.create("/products/" + created.getId())).body(created);
    }

    @Override
    public ResponseEntity<Product> getProduct(UUID id) {
        return ResponseEntity.ok(toApi(productService.get(id)));
    }

    @Override
    public ResponseEntity<ProductPage> listProducts(Integer page, Integer size) {
        var products = productService.list(page, size);
        var response = new ProductPage(
                products.getContent().stream().map(ProductsController::toApi).toList(),
                products.getNumber(),
                products.getSize(),
                products.getTotalElements(),
                products.getTotalPages());
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<Product> addStock(UUID id, AddStockRequest request) {
        return ResponseEntity.ok(toApi(productService.addStock(id, request.getQuantity())));
    }

    private static Product toApi(ProductEntity entity) {
        return new Product(entity.getId(), entity.getName(), entity.getAvailableQuantity());
    }
}
